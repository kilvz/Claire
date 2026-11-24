package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.chat.ui.AttachmentMessage
import com.wangzan101.mika.chat.ui.ChatTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * One hit. [inTitle] means a term is in the title; the snippet is taken from the body and is null when no suitable body snippet exists.
 * [target] is the chat message where the body hit is; null when only the title matched.
 */
data class ConversationHit(
    val id: String,
    val inTitle: Boolean,
    val snippet: String?,
    val score: Int,
    val target: ChatTarget? = null,
)

/** Result of one body search; [truncated] true means more hits were not returned. */
data class SearchResult(val hits: List<ConversationHit>, val truncated: Boolean = false)

/**
 * Sidebar search: searches conversation titles and the visible text inside conversations (the user's messages and steers are taken as shown on the bubbles, see [shown]; the assistant's replies).
 *
 * Bodies are cached by the conversation file's modification time: an unchanged file is not parsed again. Files are read through [turnsOf] (the same parsing as [ConversationStore]),
 * without taking the store's lock, so a background task writing into the conversation while winding down is not blocked by a search.
 */
class ConversationSearch(
    private val fileOf: (String) -> File,
    private val turnsOf: (File) -> List<Turn>?,
) {
    /** One cached body. [updatedAt] is [ConversationSummary.updatedAt]; when it changes the body is re-read. */
    private data class Entry(val updatedAt: Long, val body: Body)

    /** One piece of visible text and the message it corresponds to on the chat page. */
    private data class Segment(val text: String, val target: ChatTarget)

    /** The visible text in one conversation, in order of appearance; the snippet is taken from one of the segments. */
    private data class Body(val segments: List<Segment>, val firstUser: String?)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()

    /**
     * Title-only search (purely in memory), shown immediately while typing. A hit requires every term to be in the title;
     * when a term is only in the body, the hit is not shown yet and waits for [search] to finish scanning bodies.
     */
    fun titleMatches(query: String, candidates: List<ConversationSummary>): List<ConversationHit> {
        val terms = termsOf(query)
        if (terms.isEmpty()) return emptyList()
        val hits = candidates.mapNotNull { c ->
            val inTitle = terms.map { c.title.contains(it, ignoreCase = true) }
            if (inTitle.any { !it }) return@mapNotNull null
            ConversationHit(
                c.id, inTitle = true, snippet = null,
                score = score(titleAll = true, bodyAll = false, titleCount = terms.size, titlePrefix = prefix(c.title, terms.first())),
            )
        }
        return ranked(hits, candidates).take(MAX_HITS)
    }

    /** Searches titles and bodies. Call on an IO thread; cancellation is checked after each conversation so a changed query stops early. */
    suspend fun search(query: String, candidates: List<ConversationSummary>): SearchResult {
        val terms = termsOf(query)
        if (terms.isEmpty()) return SearchResult(emptyList())
        val hits = ArrayList<ConversationHit>(candidates.size)
        val alive = HashSet<String>(candidates.size)
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            alive += c.id
            val body = if (c.updatedAt == 0L) Body(emptyList(), null) else body(c)
            val inTitle = terms.map { c.title.contains(it, ignoreCase = true) }
            val inBody = terms.map { t -> body.segments.any { it.text.contains(t, ignoreCase = true) } }
            // Every term must appear in the title or the body at least once
            if (inTitle.indices.any { !inTitle[it] && !inBody[it] }) continue
            val titleAll = inTitle.all { it }
            val best = bestSegment(body.segments, terms)
            // When the title is the first message (or the file name when only a file was sent) and the title already matched, the same text is not repeated as the snippet
            val snippet = best?.takeIf { !(titleAll && (it.text == body.firstUser || it.text == c.title)) }?.let(::window)
            hits += ConversationHit(
                c.id, inTitle = inTitle.any { it }, snippet = snippet,
                score = score(titleAll, inBody.all { it }, inTitle.count { it }, prefix(c.title, terms.first())),
                target = best?.target,
            )
        }
        synchronized(lock) { cache.keys.retainAll(alive) }
        val sorted = ranked(hits, candidates)
        return SearchResult(sorted.take(MAX_HITS), truncated = sorted.size > MAX_HITS)
    }

    /** Clears the body cache; called when the sidebar closes, so the bodies of all conversations are not held on to after a single search. */
    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private fun body(c: ConversationSummary): Body {
        val cached = synchronized(lock) { cache[c.id] }
        if (cached != null && cached.updatedAt == c.updatedAt) return cached.body
        val turns = runCatching { turnsOf(fileOf(c.id)) }.getOrNull().orEmpty()
        val segments = ArrayList<Segment>()
        turns.forEachIndexed { index, turn -> segments += visibleText(turn, index) }
        // Only look at the first turn: when the first message carried only attachments or a screen, its typed text is empty, and a later turn's words are not substituted for it
        val firstUser = turns.firstOrNull()?.let { shown(it.userText).first() }?.takeIf(String::isNotBlank)
        val body = Body(segments, firstUser)
        synchronized(lock) { cache[c.id] = Entry(c.updatedAt, body) }
        return body
    }

    /**
     * The text the user and the assistant show in one turn; duplicates within the same turn are kept once. The user's message and steers are taken through [shown];
     * [Turn.result] is the decided outcome code (done, partial...), which the chat page does not display, so it is not searched.
     */
    private fun visibleText(t: Turn, turnIndex: Int): List<Segment> = buildList {
        fun put(s: String?, target: ChatTarget) {
            s?.takeIf(String::isNotBlank)?.let { add(Segment(it, target)) }
        }
        shown(t.userText).forEach { put(it, ChatTarget.Message(turnIndex, null)) }
        t.steers.forEachIndexed { index, steer ->
            shown(steer).forEach { put(it, ChatTarget.Message(turnIndex, index)) }
        }
        t.finishedReplies.forEachIndexed { index, reply ->
            put(reply, ChatTarget.Reply(turnIndex, index, interrupted = false))
        }
        t.interruptedReplies.forEachIndexed { index, reply ->
            put(reply, ChatTarget.Reply(turnIndex, index, interrupted = true))
        }
        put(t.outcome, ChatTarget.Status(turnIndex))
        put(t.note, ChatTarget.Status(turnIndex))
    }.distinctBy { it.text }

    /**
     * The text a user message (the opening message, a steer) shows on its bubble, split the same way the bubble does: a page shows the site and the selected text, a quote shows the
     * quoted passage, a file card shows the file name (a photo shows only the image), and a screen shows the screenshot. The page description lines, the quote header, the paths in
     * attachment lines, and the screen line are not displayed and are not searched (the sidebar title is compared after removing them too); the file size appears on the card and is
     * also not searched, so that searching "KB" does not hit every conversation with an attachment. The first item is what the user typed this time and may be an empty string.
     */
    private fun shown(text: String): List<String> {
        val paged = AttachedPage.parse(text)
        val quoted = QuoteMessage.parse(paged.words)
        val attached = AttachmentLines.parse(quoted.words)
        val words = UserScreenMessage.parse(attached.words).words
        val page = paged.page
        val files = attached.files.map { (path, _) -> path.substringAfterLast('/') }.filterNot(AttachmentMessage::isPhoto)
        return listOf(words) + listOfNotNull(page?.let { WebUrlPolicy.host(it.url) ?: it.url }, page?.selection, quoted.quote?.text) + files
    }

    /** The segment with the most matching terms; on a tie, the one whose match comes first wins. */
    private data class Best(val text: String, val target: ChatTarget, val index: Int, val length: Int)

    private fun bestSegment(segments: List<Segment>, terms: List<String>): Best? {
        var best: Best? = null
        var bestCount = 0
        for (seg in segments) {
            var count = 0
            var first = -1
            var firstLength = 0
            for (term in terms) {
                val i = seg.text.indexOf(term, ignoreCase = true)
                if (i < 0) continue
                count++
                if (first < 0 || i < first) {
                    first = i
                    firstLength = term.length
                }
            }
            if (count == 0) continue
            if (count > bestCount || (count == bestCount && (best == null || first < best.index))) {
                best = Best(seg.text, seg.target, first, firstLength)
                bestCount = count
            }
        }
        return best
    }

    /** Keeps a stretch before and after the match, squeezes newlines into spaces, and adds an ellipsis where truncated. */
    private fun window(best: Best): String {
        val text = best.text
        val start = (best.index - SNIPPET_BEFORE).coerceAtLeast(0)
        val end = (best.index + best.length + SNIPPET_AFTER).coerceAtMost(text.length)
        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        val piece = text.substring(safeStart(text, start), safeEnd(text, end)).replace(SPACE, " ").trim()
        return head + piece + tail
    }

    /** Never cut an emoji surrogate pair in half: when the start lands on a low surrogate, move it back one place. */
    private fun safeStart(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
        return i
    }

    /** When the end lands on a low surrogate, move it forward one place so the pair stays whole. */
    private fun safeEnd(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i++
        return i
    }

    private fun score(titleAll: Boolean, bodyAll: Boolean, titleCount: Int, titlePrefix: Boolean): Int =
        (if (titleAll) 100 else if (titleCount > 0) 40 else 0) +
            (if (bodyAll) 30 else 0) +
            titleCount * 5 +
            (if (titlePrefix) 15 else 0)

    private fun prefix(title: String, term: String): Boolean = title.startsWith(term, ignoreCase = true)

    /** Higher scores first; on a tie, pinned conversations first, then the most recently changed. */
    private fun ranked(hits: List<ConversationHit>, candidates: List<ConversationSummary>): List<ConversationHit> {
        val byId = candidates.associateBy { it.id }
        return hits.sortedWith(
            compareByDescending<ConversationHit> { it.score }
                .thenByDescending { byId[it.id]?.pinnedAt != null }
                .thenByDescending { byId[it.id]?.updatedAt ?: 0L }
                .thenBy { it.id },
        )
    }

    companion object {
        /** How many hits are returned at most; beyond that only the first ones are shown. */
        const val MAX_HITS = 100

        private const val MAX_TERMS = 8
        private const val SNIPPET_BEFORE = 24
        private const val SNIPPET_AFTER = 48
        // Java's \s only recognizes ASCII whitespace, so the full-width space from Chinese IMEs and pasted NBSP must also count as separators
        private val SPACE = Regex("[\\s\\u3000\\u00A0]+")

        /** Splits a query into terms: whitespace-separated, empty and duplicate ones dropped, at most 8. */
        fun termsOf(query: String): List<String> =
            query.trim().split(SPACE).filter(String::isNotEmpty).distinct().take(MAX_TERMS)
    }
}
