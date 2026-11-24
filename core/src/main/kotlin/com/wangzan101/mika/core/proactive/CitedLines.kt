package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.ModelJson.field
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shared reading method used at all levels of daily-message summarization: the model's reply is a series of sentences, and each sentence must point to the input items it is based on (ids from this request); sentences that point to none are discarded;
 * if any referenced id is not from this request, the whole sentence is discarded as well.
 * Only the JSON value at the end of the reply ([ReplyJson]) is recognized: `{"<key>":[…]}` or a bare array; other forms, and overly long ones, cannot be read.
 * The text of each sentence is cleaned the way notification text is, with URLs and long digit runs masked ([LearnText.maskLinksAndLongNumbers]), then truncated to [maxLen];
 * sentences that fail the sensitive-information check ([LearnText.sensitive]: passwords, verification codes, card numbers and the like, plus alternative names such as login codes) are discarded entirely.
 */
internal object CitedLines {
    /**
     * [brief]: a one-line overview given alongside the sentences in the reply (present only in the `{"<key>":[…],"brief":"…"}` form; used for daily, weekly and monthly summaries).
     * It points to no input, so it is kept only when it says exactly the sentences that were accepted: when none were accepted, or when sentences were dropped because they could not state a basis,
     * failed the sensitive-information check, or the like ([NoteDrop.OverLimit] does not count — that is merely writing too much), the brief may still carry dropped content, so it is not kept. Null when absent or unsuitable.
     */
    class Parsed(val lines: List<NoteSignal>, val dropped: Map<NoteDrop, Int>, val brief: String? = null)

    /**
     * [idPrefix] plus a sequence number is the id of an input item (`e1`…, `s1`…), [count] of them in total; at most [maxItems] sentences are recognized at a time. Null when it cannot be read.
     */
    fun parse(raw: String, key: String, idPrefix: String, count: Int, maxItems: Int, maxLen: Int): Parsed? {
        if (raw.length > ReplyJson.MAX_CHARS) return null
        var rawBrief: String? = null
        val items = when (val end = ReplyJson.ending(ReplyJson.normalise(raw))) {
            is ReplyJson.Ending.Value -> when (val v = end.element) {
                is JsonArray -> v
                is JsonObject -> {
                    rawBrief = (v.field("brief") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    v.field(key) as? JsonArray ?: return null
                }
                else -> return null
            }
            else -> return null
        }
        // Something was given but not a single entry is an object (e.g. [1,2,3]): the reply's structure is wrong and cannot be read — it must not be accepted as "nothing was written"
        // (in chunked summarization, a batch that wrote nothing marks the original text as done)
        if (items.isNotEmpty() && items.none { it is JsonObject }) return null
        val dropped = LinkedHashMap<NoteDrop, Int>()
        fun drop(d: NoteDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
        val out = ArrayList<NoteSignal>()
        items.forEachIndexed { i, e ->
            if (i >= maxItems) return@forEachIndexed drop(NoteDrop.OverLimit)
            val o = e as? JsonObject ?: return@forEachIndexed drop(NoteDrop.Malformed)
            val ids = (o.field("ids") as? JsonArray).orEmpty()
                .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.let { id -> index(id, idPrefix, count) } }
            // quoted an id that is not in this request: part of this sentence cannot state a basis, so drop the whole sentence (not just that one id)
            if (ids.any { it == null }) return@forEachIndexed drop(NoteDrop.UnknownId)
            val valid = ids.filterNotNull().distinct()
            if (valid.isEmpty()) return@forEachIndexed drop(NoteDrop.NoIds)
            val rawText = (o.field("text") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val full = NoticeFilter.clean(rawText, 1_000)
            if (full.isEmpty()) return@forEachIndexed drop(NoteDrop.Malformed)
            if (LearnText.sensitive(full)) return@forEachIndexed drop(NoteDrop.Sensitive)
            val text = NoticeFilter.cap(LearnText.maskLinksAndLongNumbers(full, LearnTuning.NOTE_DIGIT_RUN_MAX), maxLen).trim()
            if (text.isEmpty()) return@forEachIndexed drop(NoteDrop.Malformed)
            out += NoteSignal(valid, text)
        }
        val clean = out.isNotEmpty() && dropped.keys.all { it == NoteDrop.OverLimit }
        return Parsed(out, dropped, if (clean) rawBrief?.let(LearnText::brief) else null)
    }

    fun id(prefix: String, index: Int): String = "$prefix${index + 1}"

    private fun index(id: String, prefix: String, count: Int): Int? {
        if (!id.startsWith(prefix)) return null
        val n = id.substring(prefix.length).toIntOrNull() ?: return null
        return (n - 1).takeIf { it in 0 until count && id(prefix, it) == id }
    }
}
