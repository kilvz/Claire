package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The model's verdict for a notification. */
enum class TriageDecision(val wire: String) {
    Ignore("ignore"), Remember("remember"), Surface("surface");

    companion object {
        fun ofWire(s: String?): TriageDecision? = entries.firstOrNull { it.wire == s }
    }
}

/** Built-in explanations (the UI supplies the words per language), used in place of a reason written by the model. */
enum class TriageNote {
    /** The reply had no entry for it, or the verdict given was unrecognised: treated as ignore. */
    NoVerdict,
    /** This app has hit today's limit on surfaced hints, so it is recorded as ignore. */
    SurfaceCapped,
}

/**
 * One verdict. [reason] is written by the model (drawn from the notification content and treated as untrusted plain text: cleaned, shortened, displayed as plain text only);
 * when [note] is not null the UI shows the built-in explanation instead of [reason].
 */
data class TriageVerdict(val decision: TriageDecision, val reason: String = "", val note: TriageNote? = null)

/** A notification to judge: the record itself, plus the texts the same key has shown over time (see [NoticeInbox.Snapshot.history]; the last piece is the current text). */
data class TriageItem(val record: NoticeRecord, val history: List<NoticeLine> = emptyList())

sealed interface TriageResult {
    /** The verdicts by notification key; every item in this batch has one. */
    data class Judged(val verdicts: Map<String, TriageVerdict>) : TriageResult
    /**
     * The model errored, timed out, or the reply could not be read: the whole batch counts as a failed judgement and is not retried automatically. [unreachable]: the model was never reached (no network, no proxy);
     * [unanswered]: the connection was made, but no reply arrived before the timeout here (the model is busy or very slow; unreachable cases surface earlier as connection errors and count as [unreachable]);
     * [unreadable]: the model replied, but the reply could not be read into verdicts (it was not JSON only, or the structure was wrong). These are used by the caller for logging and for choosing a backoff.
     */
    data class Failed(val unreachable: Boolean = false, val unreadable: Boolean = false, val unanswered: Boolean = false) : TriageResult
    /** The proactive budget for today is used up, so no request was sent: this batch stays pending. */
    data object BudgetExhausted : TriageResult
}

/**
 * Asks the model once to judge a batch of notifications from the same app (at most [MAX_BATCH]): ignore, remember, worth surfacing. Pure logic, with the model supplied by the caller.
 *
 * Injection defence:
 * - One request only, an empty tool list; no retry (an unreadable reply is a failure, so a broken configuration does not burn through the daily request budget).
 * - All text from notifications is wrapped field by field in [Untrusted] (closing markers escaped, case and whitespace variants blocked beforehand), the system prompt states that it is data, not instructions,
 *   and that one notification cannot change another's verdict.
 * - The reply is strictly validated ([parse]): unknown ids are dropped, duplicate ids keep only the first, unknown verdicts become ignore, missing ids become ignore with a built-in explanation,
 *   and only the trailing `{"items":[…]}` or an array of items is accepted (notification originals quoted in prose do not count); if it cannot be read, the whole batch fails.
 * - Each batch holds one app's notifications only (the caller batches by app, and this is checked here too). Known limitation: a few notifications from the same app (different people in WeChat, say)
 *   are still in one request, so one message can still influence another's verdict; at this stage the consequence is only a label.
 *
 * [language] is the language for the reasons (the English name of the UI language), written on the first line of the input.
 */
class NoticeTriage(
    private val model: ChatModel,
    private val language: String,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    suspend fun triage(items: List<TriageItem>): TriageResult {
        require(items.isNotEmpty() && items.size <= MAX_BATCH) { "batch size ${items.size}" }
        require(items.map { it.record.packageName }.distinct().size == 1) { "one app per batch" }
        val ids = items.indices.map(::idOf)
        val input = buildInput(items, language)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return TriageResult.Failed(unanswered = true)
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return TriageResult.BudgetExhausted
        } catch (e: CancellationException) {
            // only throw when the caller itself was cancelled (screen locked, feature turned off); timeouts and the like inside the model count as failure
            currentCoroutineContext().ensureActive()
            return TriageResult.Failed()
        } catch (e: Exception) {
            // when the caller is cancelled, the cancellation can surface as another exception (connection interrupted, for instance): that is not a failure of this batch
            currentCoroutineContext().ensureActive()
            // connection-layer errors (the model adapter wraps them as Network / Proxy; a bare IOException counts the same) all mean the model was never reached
            return TriageResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy || e is java.io.IOException)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        val byId = parse(text, ids) ?: return TriageResult.Failed(unreadable = true)
        return TriageResult.Judged(items.indices.associate { i -> items[i].record.key to byId.getValue(ids[i]) })
    }

    companion object {
        const val PROMPT = "notice_triage"
        const val TIMEOUT_MS = 30_000L
        const val MAX_BATCH = 8
        const val TITLE_MAX = 120
        const val TEXT_MAX = 300
        const val LINES_MAX = 5
        const val LINE_MAX = 200
        const val REASON_MAX = 120

        /** The id of the [index]th item in a batch (0-based): n1…n8. Meaningful only within this one request, and not a notification key. */
        fun idOf(index: Int): String = "n${index + 1}"

        // the various spellings of the closing marker (case, whitespace) are rewritten first, then Untrusted escapes the canonical form; opening markers are rewritten too, so they do not look like a new block
        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /** Input sent to the model: the first line is the language for reasons, then the package name, then each notification listed; all text from notifications is inside untrusted. */
        fun buildInput(items: List<TriageItem>, language: String): String = buildString {
            appendLine("Language for reasons: $language")
            appendLine("App package: ${items.first().record.packageName}")
            appendLine("Notifications (oldest first):")
            items.forEachIndexed { i, item ->
                val r = item.record
                appendLine()
                appendLine("[${idOf(i)}]")
                // group or private chat is split out by us from how the notification is written (omitted when it cannot be told), written outside the untrusted block
                r.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(r.title, TITLE_MAX)
                // for groups it carries who said it (see [NoticeRecord.said])
                val text = NoticeFilter.clean(r.said, TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (text.isNotEmpty()) appendLine("text: ${untrusted(text)}")
                // texts shown by the same notification before it was reposted (the last piece is the current one and is not repeated)
                val earlier = item.history.dropLast(1).takeLast(LINES_MAX)
                    .map { line -> NoticeFilter.clean(listOf(line.title, line.text).filter { it.isNotBlank() }.joinToString(": "), LINE_MAX) }
                    .filter { it.isNotEmpty() }
                if (earlier.isNotEmpty()) appendLine("earlier: ${untrusted(earlier.joinToString("\n") { "- $it" })}")
                val messages = r.messages.takeLast(LINES_MAX)
                    .map { m -> NoticeFilter.clean(listOfNotNull(m.sender, m.text).joinToString(": "), LINE_MAX) }
                    .filter { it.isNotEmpty() }
                if (messages.isNotEmpty()) appendLine("messages: ${untrusted(messages.joinToString("\n") { "- $it" })}")
            }
        }.trimEnd()

        /**
         * Reads the reply strictly: the last value the model gave (see [itemsOf]: a local model may think for a while first, and while thinking it may restate a notification containing
         * an object that looks like an answer; the conclusion is last). Unreadable means null (the whole batch fails).
         * Returns a verdict for each id in [ids]: unknown ids are dropped; duplicates keep only the first; unrecognised verdicts become ignore without the model's reason; missing ones become ignore.
         * Reasons are cleaned the same way as notification text and cut to [REASON_MAX].
         */
        fun parse(raw: String, ids: List<String>): Map<String, TriageVerdict>? {
            val items = itemsOf(raw) ?: return null
            // something was given, yet not one item looks like an item (such as [1,2,3]): the reply's structure is wrong and the whole batch is unreadable —
            // it must not be accepted as "no verdict was given for any item" (that would turn the whole batch into ignores). Truly giving none ([]) is fine as before
            if (items.isNotEmpty() && items.none { it is JsonObject }) return null
            val out = LinkedHashMap<String, TriageVerdict>()
            for (e in items) {
                val o = e as? JsonObject ?: continue
                val idValue = o.field("id") as? JsonPrimitive ?: continue
                if (!idValue.isString) continue
                val id = idValue.content.trim()
                if (id !in ids || id in out) continue
                val decision = TriageDecision.ofWire((o.field("decision") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                out[id] = if (decision == null) {
                    TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict)
                } else {
                    TriageVerdict(decision, NoticeFilter.clean((o.field("reason") as? JsonPrimitive)?.contentOrNull.orEmpty(), REASON_MAX))
                }
            }
            return ids.associateWith { id -> out[id] ?: TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict) }
        }

        /**
         * The items in the reply: only the last value the model gave counts ([ReplyJson.ending]). Ending with an object means it must carry an `items` array; ending with an array means that array is the items
         * (on a device the same model omits the outer layer when there are many items and gives `[{…},{…}]` directly). Notification originals quoted or restated in prose do not count.
         * A reply not ending in JSON is unreadable (the whole batch fails): if we fell back to looking for "the last object with `items`", then the model merely restating
         * a chunk of JSON written in the notification and following it with "don't trust that" would make the restated chunk the verdict. The prompt asks for JSON only anyway.
         * Anything over [ReplyJson.MAX_CHARS] is not read.
         */
        private fun itemsOf(raw: String): JsonArray? {
            if (raw.length > ReplyJson.MAX_CHARS) return null
            return when (val end = ReplyJson.ending(ReplyJson.normalise(raw))) {
                is ReplyJson.Ending.Value -> when (val v = end.element) {
                    is JsonArray -> v
                    is JsonObject -> v.field("items") as? JsonArray
                    else -> null
                }
                ReplyJson.Ending.GaveUp, ReplyJson.Ending.None -> null
            }
        }
    }
}
