package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a signal in the reply was dropped (for counting only, no content). */
enum class LearnDrop {
    /** Beyond [LearnTuning.SIGNALS_MAX]. */
    OverLimit,
    /** Not an object, or it has both topic and new, or neither. */
    Malformed,
    /** A cited notification id is not in this batch: the whole item is dropped (counted once per item). */
    UnknownId,
    /** No valid notification ids at all. */
    NoIds,
    /** The cited topic id is not one given this time. */
    UnknownTopic,
    /** The new topic's kind is not recognised. */
    BadKind,
    /** The new topic's label is empty. */
    BadLabel,
    /** The label looks like it contains a URL. */
    UrlLike,
    /** The label contains a long run of digits. */
    LongDigits,
    /** The label fails the sensitive-information check. */
    Sensitive,
    /** "Sensitive inference" is off, yet the model supplied a new topic marked sensitive. */
    SensitiveOff,
    /** It validated, but applying it to the topic pool did not take it: the cited topic was deleted in the meantime, or sensitive inference was off when applied (counted by the caller). */
    NotApplied,
    /** The newly created topic's name is one the user deleted before (counted by the caller). */
    Blocked,
}

sealed interface LearnResult {
    /**
     * The reply was understood; [signals] are the validated ones (possibly empty) and [dropped] counts what was dropped (by reason).
     * [contacts] and [contactDropped] are the contact classifications from the same reply (counted separately from topics, so one side breaking does not affect the other).
     */
    data class Learned(
        val signals: List<LearnSignal>,
        val dropped: Map<LearnDrop, Int>,
        val contacts: List<ContactSignal> = emptyList(),
        val contactDropped: Map<ContactDrop, Int> = emptyMap(),
    ) : LearnResult
    /**
     * Nothing was learned. [unreachable] true means the model was never reached at all (no network, no proxy), unrelated to this batch's content, and the batch is kept to be sent later.
     * Otherwise the model returned an error, timed out, or the reply could not be read: the batch is dropped and not retried. Either way the caller waits a while before sending the next batch.
     * [unreadable]: the model replied but the reply could not be read into a result (it was not JSON only, or the structure was wrong); used by the caller for logging only.
     */
    data class Failed(val unreachable: Boolean = false, val unreadable: Boolean = false) : LearnResult
    /** The proactive budget for today is used up, so no request was sent: this batch stays pending. */
    data object BudgetExhausted : LearnResult
}

/** An existing topic shown to the model; [id] is the pool id (replaced by t1…t30 in the request). */
data class ExistingTopic(val id: String, val kind: TopicKind, val label: String)

/**
 * Learns short topics from a batch of notifications from the same app (at most [LearnTuning.BATCH_MAX]): the user's interests, things they follow, things they are shopping for and the like.
 * What an app pushes is its recommendation model's judgement about this user: ads and recommendations are signal here, not noise. Pure logic, with the model supplied by the caller.
 *
 * Injection defence, same as [NoticeTriage]:
 * - Only one request, an empty tool list, and a timeout of [LearnTuning.TIMEOUT_MS]; no retry here (a batch that never reached the model is kept by the caller, see [LearnResult.Failed]).
 * - Notification text, and the names of existing topics (also learned earlier by the model from untrusted text), are wrapped in [Untrusted];
 *   the user's actions (opened, dismissed) are what we saw ourselves and go outside the untrusted block.
 * - The reply is strictly validated ([parse]): only this batch's notification ids, the topic ids given this time and a fixed set of kinds are accepted; new topic labels are cleaned and shortened,
 *   must not look like URLs, must not contain long digit runs and must pass [SensitiveFilter]; at most [LearnTuning.SIGNALS_MAX] are accepted.
 * - The model can only cite or create topics; it cannot rename, merge or delete, and the reply has no app field either — the caller decides which app the evidence is attributed to.
 * - Chat apps (WeChat and the like) are learned from too (the user excludes them by setting the source tier to "low"): the prompt only allows taking "what is being talked about" from the conversation,
 *   and what other people say to the user, ask for or instruct is never treated as fact about the user; private or group names must not be used as topic labels either.
 *   Those two cannot be checked by machine (a name is indistinguishable from an ordinary noun) and rely on the prompt alone; only what is checkable literally (URLs, long digit runs, sensitive information) is blocked in [checkLabel].
 * - Contacts and relationships come in the same request ([contactsAllowed]): the model may only say "these notifications came from one person / one group, and this is the relationship to the user",
 *   never names; who counts as one contact and what they are called is derived from the notifications themselves by [ContactIdentity]. Kinds and relations are limited to a fixed set, at most [LearnTuning.CONTACTS_MAX] entries.
 *   Existing contacts and earlier classifications are not put into the request: each round's vote stands on its own.
 */
class NoticeLearn(
    private val model: ChatModel,
    private val language: String,
    /** "Sensitive inference" is on: the model may produce health, finance and similar topics, but new topics must be marked sensitive. When off, anything marked is dropped. */
    private val sensitiveAllowed: Boolean = false,
    /** "Learn contacts and relationships" is on: the model may classify contacts; when off the request says not to, and anything supplied in the reply is dropped. */
    private val contactsAllowed: Boolean = false,
    private val timeoutMs: Long = LearnTuning.TIMEOUT_MS,
) {
    suspend fun learn(packageName: String, events: List<LearnEvent>, existing: List<ExistingTopic>): LearnResult {
        require(events.isNotEmpty() && events.size <= LearnTuning.BATCH_MAX) { "batch size ${events.size}" }
        require(events.all { it.packageName == packageName }) { "one app per batch" }
        val shown = existing.take(LearnTuning.EXISTING_MAX)
        val input = buildInput(packageName, events, shown, language, sensitiveAllowed, contactsAllowed)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return LearnResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return LearnResult.BudgetExhausted
        } catch (e: CancellationException) {
            // only throw when the caller itself was cancelled (screen locked, feature turned off); timeouts and the like inside the model count as failure
            currentCoroutineContext().ensureActive()
            return LearnResult.Failed()
        } catch (e: Exception) {
            // when the caller is cancelled, the cancellation can surface as another exception (connection interrupted, for instance): that is not a failure of this batch
            currentCoroutineContext().ensureActive()
            // the connection was never established (the model is only reachable on the home network, and the user is out, for example): the request never reached the model, so this batch is fine
            return LearnResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, events.size, shown.map { it.id }, sensitiveAllowed, contactsAllowed) ?: LearnResult.Failed(unreadable = true)
    }

    companion object {
        const val PROMPT = "notice_learn"

        fun eventId(index: Int): String = "n${index + 1}"
        fun topicRef(index: Int): String = "t${index + 1}"

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        private fun outcomeWire(o: LearnOutcome) = when (o) {
            LearnOutcome.Opened -> "opened"
            LearnOutcome.Dismissed -> "dismissed"
            LearnOutcome.None -> "none"
        }

        /** Input sent to the model: the first line is the language for topic labels, then the package name, existing topics and the notifications; text from elsewhere all sits in untrusted. */
        fun buildInput(
            packageName: String,
            events: List<LearnEvent>,
            existing: List<ExistingTopic>,
            language: String,
            sensitiveAllowed: Boolean = false,
            contactsAllowed: Boolean = false,
        ): String = buildString {
            appendLine("Language for labels: $language")
            appendLine("App package: $packageName")
            // the mode is written by us, outside the untrusted block
            appendLine("Sensitive topics: ${if (sensitiveAllowed) "allowed" else "not allowed"}")
            appendLine("Contacts: ${if (contactsAllowed) "on" else "off"}")
            appendLine()
            if (existing.isEmpty()) {
                appendLine("Existing topics: none")
            } else {
                appendLine("Existing topics (cite one of these ids when it is about the same subject):")
                existing.forEachIndexed { i, t ->
                    appendLine("[${topicRef(i)}] kind: ${t.kind.wire}, label: ${untrusted(NoticeFilter.clean(t.label, LearnTuning.LABEL_MAX))}")
                }
            }
            appendLine()
            appendLine("Notifications (oldest first):")
            events.forEachIndexed { i, e ->
                appendLine()
                appendLine("[${eventId(i)}] user action: ${outcomeWire(e.outcome)}")
                // group or private chat is split out by us from how the notification is written (omitted when it cannot be told), written outside the untrusted block
                e.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(e.title, LearnTuning.TITLE_MAX)
                val text = NoticeFilter.clean(e.text, LearnTuning.TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (text.isNotEmpty()) appendLine("text: ${untrusted(text)}")
            }
        }.trimEnd()

        /**
         * Reads the reply strictly: the last value the model gave (see [itemsOf]; the model may think for a while first).
         * Unreadable means null (the whole batch fails). [eventCount] is how many notifications this batch has (n1…); [topicIds] are the topics shown to the model this time (t1… mapping in order to pool ids).
         * Each item is sorted by the fields it carries: ones with `topic` / `new` are topics, ones with `type` / `relation` (and neither of the first two) are contacts, regardless of which array they were in;
         * each side's limit is counted separately. If the contact part is broken the topics are still used, and if the topic part is broken the contacts are still used.
         */
        fun parse(
            raw: String,
            eventCount: Int,
            topicIds: List<String>,
            sensitiveAllowed: Boolean = false,
            contactsAllowed: Boolean = false,
        ): LearnResult.Learned? {
            val (signalItems, contactItems) = itemsOf(raw) ?: return null
            // something was given, yet not one item is an object (such as [1,2,3]): the reply's structure is wrong and the whole batch is unreadable — it must not be accepted as "nothing was learned"
            if ((signalItems.isNotEmpty() || contactItems.isNotEmpty()) && (signalItems + contactItems).none { it is JsonObject }) return null
            val dropped = LinkedHashMap<LearnDrop, Int>()
            fun drop(d: LearnDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
            val out = ArrayList<LearnSignal>()
            signalItems.forEachIndexed { i, e ->
                if (i >= LearnTuning.SIGNALS_MAX) return@forEachIndexed drop(LearnDrop.OverLimit)
                val o = e as? JsonObject ?: return@forEachIndexed drop(LearnDrop.Malformed)
                // ones carrying both the topic and the contact fields count for neither side
                if (present(o, "type") || present(o, "relation")) return@forEachIndexed drop(LearnDrop.Malformed)
                val valid = idsOf(o, eventCount) ?: return@forEachIndexed drop(LearnDrop.UnknownId)
                if (valid.isEmpty()) return@forEachIndexed drop(LearnDrop.NoIds)
                val topic = o.field("topic")
                val new = o.field("new")
                val hasTopic = topic != null && topic !is JsonNull
                val hasNew = new != null && new !is JsonNull
                if (hasTopic == hasNew) return@forEachIndexed drop(LearnDrop.Malformed)
                if (hasTopic) {
                    val ref = (topic as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    val index = topicIds.indices.firstOrNull { topicRef(it) == ref } ?: return@forEachIndexed drop(LearnDrop.UnknownTopic)
                    out += LearnSignal(valid, topicId = topicIds[index])
                } else {
                    val n = new as? JsonObject ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val kind = TopicKind.ofWire((n.field("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase())
                        ?: return@forEachIndexed drop(LearnDrop.BadKind)
                    // an unrecognisable mark means dropping the whole item: an incomprehensible "sensitive" mark must not be accepted as unmarked.
                    // it belongs inside new; the outer level (next to ids) is accepted too, and a mark in either place counts as marked
                    val inner = flag(n.field("sensitive")) ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val outer = flag(o.field("sensitive")) ?: return@forEachIndexed drop(LearnDrop.Malformed)
                    val sensitive = inner || outer
                    if (sensitive && !sensitiveAllowed) return@forEachIndexed drop(LearnDrop.SensitiveOff)
                    val rawLabel = (n.field("label") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    when (val r = checkLabel(rawLabel)) {
                        is LabelCheck.Ok -> out += LearnSignal(valid, newKind = kind, newLabel = r.label, sensitive = sensitive)
                        is LabelCheck.Bad -> drop(r.why)
                    }
                }
            }
            val (contacts, contactDropped) = parseContacts(contactItems, eventCount, sensitiveAllowed, contactsAllowed)
            return LearnResult.Learned(out, dropped, contacts, contactDropped)
        }

        /** The contact part: only this batch's notification ids, a fixed set of types (unknown means the whole item is dropped) and relations (unknown becomes unsure); names are never read at all. */
        private fun parseContacts(
            items: List<JsonElement>,
            eventCount: Int,
            sensitiveAllowed: Boolean,
            contactsAllowed: Boolean,
        ): Pair<List<ContactSignal>, Map<ContactDrop, Int>> {
            val dropped = LinkedHashMap<ContactDrop, Int>()
            fun drop(d: ContactDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
            if (!contactsAllowed) {
                items.forEach { _ -> drop(ContactDrop.Off) }
                return emptyList<ContactSignal>() to dropped
            }
            val out = ArrayList<ContactSignal>()
            items.forEachIndexed { i, e ->
                if (i >= LearnTuning.CONTACTS_MAX) return@forEachIndexed drop(ContactDrop.OverLimit)
                val o = e as? JsonObject ?: return@forEachIndexed drop(ContactDrop.Malformed)
                val valid = idsOf(o, eventCount) ?: return@forEachIndexed drop(ContactDrop.UnknownId)
                if (valid.isEmpty()) return@forEachIndexed drop(ContactDrop.NoIds)
                val type = ContactType.ofWire(wireOf(o, "type")) ?: return@forEachIndexed drop(ContactDrop.Malformed)
                var relation = ContactRelation.ofWire(wireOf(o, "relation")) ?: ContactRelation.Unknown
                // with "sensitive inference" off, "partner" becomes unsure
                if (relation == ContactRelation.Partner && !sensitiveAllowed) relation = ContactRelation.Unknown
                out += ContactSignal(valid, type, relation)
            }
            return out to dropped
        }

        private fun wireOf(o: JsonObject, name: String): String? =
            (o.field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()

        /**
         * The indices of the notifications this item cites that are in this batch (deduplicated). If any of them is not a string, or not an id from this batch, the whole item is dropped (null is returned):
         * when one piece of evidence does not line up, the conclusion cannot be trusted — the mismatched id is not simply dropped while the conclusion is accepted. Segmented note lines are dropped whole the same way.
         */
        private fun idsOf(o: JsonObject, eventCount: Int): List<Int>? {
            val indices = (o.field("ids") as? JsonArray).orEmpty()
                .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.let { id -> eventIndex(id, eventCount) } }
            if (indices.any { it == null }) return null
            return indices.filterNotNull().distinct()
        }

        sealed interface LabelCheck {
            data class Ok(val label: String) : LabelCheck
            data class Bad(val why: LearnDrop) : LabelCheck
        }

        /**
         * A new topic label: cleaned the same way as notification text; ones that look like URLs, contain a long digit run or fail the sensitive-information check are rejected (these are checked against the untruncated full text,
         * so truncation cannot hide them); then cut to [LearnTuning.LABEL_MAX]; empty ones are rejected.
         */
        fun checkLabel(raw: String): LabelCheck {
            val full = NoticeFilter.clean(raw, 1_000)
            if (full.isEmpty()) return LabelCheck.Bad(LearnDrop.BadLabel)
            val normalized = LearnText.nfkc(full)
            if (LearnText.urlLike(normalized)) return LabelCheck.Bad(LearnDrop.UrlLike)
            if (LearnText.longDigits(normalized)) return LabelCheck.Bad(LearnDrop.LongDigits)
            if (LearnText.sensitive(full)) return LabelCheck.Bad(LearnDrop.Sensitive) // the main app's check, plus other names for things like login codes
            val label = NoticeFilter.cap(full, LearnTuning.LABEL_MAX).trim()
            return if (label.isEmpty()) LabelCheck.Bad(LearnDrop.BadLabel) else LabelCheck.Ok(label)
        }

        private fun eventIndex(id: String, count: Int): Int? {
            if (!id.startsWith("n")) return null
            val n = id.substring(1).toIntOrNull() ?: return null
            // only the canonical n1…nN form is accepted (n01 does not count)
            return (n - 1).takeIf { it in 0 until count && eventId(it) == id }
        }

        private fun present(o: JsonObject, name: String): Boolean = o.field(name).let { it != null && it !is JsonNull }

        /**
         * The "sensitive" mark: absent or null means unmarked; JSON true / false are taken as is, and "true" / "false" written as strings are accepted too (some models write it that way,
         * and the meaning is clear). Other forms (other strings, numbers, arrays, objects) are unrecognisable and return null.
         * Err on the strict side: if a string "true" were treated as unmarked, it would be accepted even with "sensitive inference" off and stored as not sensitive.
         */
        private fun flag(e: JsonElement?): Boolean? = when {
            e == null || e is JsonNull -> false
            e is JsonPrimitive -> when (e.content.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }

        /** Items with `topic` or `new` are topics. */
        private fun signalShaped(o: JsonObject) = present(o, "topic") || present(o, "new")

        /** Items with `type` or `relation` and without `topic` / `new` are contacts. */
        private fun contactShaped(o: JsonObject) = !signalShaped(o) && (present(o, "type") || present(o, "relation"))

        /**
         * The items in the reply, split into a topic pile and a contact pile. Only the last value the model gave counts ([ReplyJson.ending]):
         * - ending with an object: it must carry a `signals` and/or `contacts` array (unreadable if neither is an array).
         * - ending with an array: the same model sometimes omits the outer layer and gives `[{…},{…}]` directly; the items inside are split by field.
         * - a reply not ending in JSON is unreadable (the whole batch fails): if we fell back to looking for "the last object with `signals`", then when the model
         *   repeats a chunk of JSON written in the notification and follows it with a sentence, the repeated chunk would be taken as the conclusion. The prompt asks for JSON only anyway.
         * Anything over [ReplyJson.MAX_CHARS] is not read. Splitting by field (see [signalShaped], [contactShaped]) applies in every form;
         * items that look like neither stay in the pile they came from and are counted as bad by validation. Unreadable means null.
         */
        private fun itemsOf(raw: String): Pair<List<JsonElement>, List<JsonElement>>? {
            if (raw.length > ReplyJson.MAX_CHARS) return null
            val text = ReplyJson.normalise(raw)
            val (sig, con) = when (val end = ReplyJson.ending(text)) {
                is ReplyJson.Ending.Value -> when (val v = end.element) {
                    is JsonArray -> v.filterNot { it is JsonObject && contactShaped(it) } to v.filter { it is JsonObject && contactShaped(it) }
                    is JsonObject -> {
                        val s = v.field("signals") as? JsonArray
                        val c = v.field("contacts") as? JsonArray
                        if (s == null && c == null) return null
                        s.orEmpty().toList() to c.orEmpty().toList()
                    }
                    else -> return null
                }
                ReplyJson.Ending.GaveUp, ReplyJson.Ending.None -> return null
            }
            val sigItems = sig.filterNot { it is JsonObject && contactShaped(it) } + con.filter { it is JsonObject && signalShaped(it) }
            val conItems = con.filterNot { it is JsonObject && signalShaped(it) } + sig.filter { it is JsonObject && contactShaped(it) }
            return sigItems to conItems
        }
    }
}
