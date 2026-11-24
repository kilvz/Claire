package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Q1+Q2 prototype: the stable key for "locate an item + find the next one, skip those already done" in the `do_each`
 * loop, plus "scroll to find more".
 *
 * Conclusions (Q1 verified, Q2 continued):
 * - `ref` is reassigned on every screen read, so "the same item" cannot be recognised across snapshots;
 * - `resourceId` exists in real a11y trees, but list items are isomorphic (same widget type, identical resourceId), so
 *   it cannot tell item 1 from item 2;
 * - the identity that is stable across snapshots and scrolls is `contentKey = role|name` (the content);
 * - items with the same name are distinguished by `ordinal` (the occurrence index of that name within this snapshot)
 *   within **a single screen**, but ordinal depends on position, becomes invalid after scrolling, and cannot be used
 *   across screens.
 *
 * This is an unwired prototype, only for validating the stable key and the scrolling approach; it will be finalized
 * when `do_each` is formally implemented.
 */
internal class EachCursor {

    /** A matched item: the element visible to the model + locating information. */
    data class Item(
        val contentKey: String, // role|name -- identity stable across snapshots and scrolls
        val ordinal: Int,       // index of items with the same name in this snapshot (0,1,…); distinguishes same-name items within this snapshot only, not used across screens
        val role: String,
        val name: String,
        val ref: String,        // ref used for tapping this round (the clickable ancestor's actionRef if present, otherwise its own ref; valid within this snapshot)
    )

    /** Match [elements] against the pattern (role? / text?), computing contentKey and ordinal for each. */
    fun match(elements: List<JsonObject>, role: String?, text: String?): List<Item> {
        val count = mutableMapOf<String, Int>()
        val out = mutableListOf<Item>()
        for (e in elements) {
            val r = (e["role"] as? JsonPrimitive)?.contentOrNull ?: ""
            val n = (e["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            // The tap must land on a clickable ancestor: in find results actionRef is the clickable ancestor and ref is the matched node itself.
            val ref = (e["actionRef"] as? JsonPrimitive)?.contentOrNull
                ?: (e["ref"] as? JsonPrimitive)?.contentOrNull ?: ""
            val roleOk = role == null || r == role
            val textOk = text == null || n.contains(text)
            if (!roleOk || !textOk) continue
            val ck = "$r|$n"
            val o = count.getOrDefault(ck, 0)
            count[ck] = o + 1
            out += Item(ck, o, r, n, ref)
        }
        return out
    }
}

/**
 * Q2 prototype: a scrolling cursor — across multiple screen reads and multiple scrolls, do each contentKey exactly
 * once, and stop when the bottom is reached.
 *
 * Usage (the caller drives scrolling; the cursor only makes decisions):
 * ```
 * var justScrolled = false
 * while (true) {
 *     val elements = readScreen()
 *     when (val s = cursor.advance(elements, justScrolled)) {
 *         is DoNext -> { doItem(s.item); cursor.finish(s.item); justScrolled = false }
 *         Scroll    -> { scrollDown(); justScrolled = true }
 *         End       -> break
 *     }
 * }
 * ```
 *
 * Stop condition: it has just scrolled once, and this screen read produced no new contentKey at all (the bottom is
 * reached).
 * Known limitation: items with the same name (identical contentKey) converge to "done once" — the element itself does
 * not carry enough information to distinguish two same-name items across scrolls; the real fix is finer item matching
 * (making contentKey unique), not the key.
 */
internal class ScrollCursor(
    private val role: String?,
    private val text: String?,
) {
    private val each = EachCursor()
    val seen = mutableSetOf<String>()   // contentKeys seen (across scrolls)
    val done = mutableSetOf<String>()   // contentKeys already handled

    sealed class Step {
        data class DoNext(val item: EachCursor.Item) : Step()
        object Scroll : Step()
        object End : Step()
    }

    /** Given the [elements] read from the current screen and whether it just scrolled [justScrolled], returns the next step. */
    fun advance(elements: List<JsonObject>, justScrolled: Boolean): Step {
        val items = each.match(elements, role, text)
        val newContent = items.filter { it.contentKey !in seen }.map { it.contentKey }
        seen += items.map { it.contentKey }
        val next = items.firstOrNull { it.contentKey !in done }
        if (next != null) return Step.DoNext(next)
        return if (justScrolled && newContent.isEmpty()) Step.End else Step.Scroll
    }

    /** Mark the contentKey of [item] as done after it has been handled. */
    fun finish(item: EachCursor.Item) {
        done += item.contentKey
    }
}
