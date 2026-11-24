package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject

/**
 * Q3+Q4 prototype: the loop runner for `do_each` (not wired up). It wraps [ScrollCursor]'s "locate/scroll/deduplicate"
 * and adds the failure semantics of "do one item" (stop/skip) plus a compact summary. `read`/`scroll`/`doItem` are all
 * callbacks — in the real implementation they are suspend + go through ActionExecutor; here plain callbacks pin the
 * semantics down.
 *
 * Q3 conclusion: in-place looping (changing state/removing in place without opening an item) and
 * "open→do→return" are **indistinguishable** to the executor — the executor only cares whether "this item was done";
 * with in-place removal the list shifts up, but contentKey does not change, and the cursor deduplicates as usual.
 *
 * Q4 conclusion: failure semantics = stop when it cannot be done (Stop, the safe default, leaving it retryable) or
 * skip and continue (Skip, common for delivery-type work; a failure is still recorded as done so the next round does
 * not hit the same item again).
 */
internal class EachRunner(
    private val role: String?,
    private val text: String?,
    private val onError: OnError,
) {
    enum class OnError { Stop, Skip }

    data class Outcome(val contentKey: String, val ok: Boolean)

    /** A compact summary of one loop — exactly what is handed back to the model when N rounds are compressed into 1. */
    data class Summary(
        val done: Int,          // number of items done successfully
        val failed: Int,        // number of items that failed
        val stoppedAt: String?, // in Stop mode, which item it stopped at (contentKey); null when it ran to completion
        val perItem: List<Outcome>,
    )

    /** [doItem] returns true=done, false=failed. In Stop mode a failure stops immediately (that item is not recorded as done); in Skip mode a failure is still recorded as done and the loop continues. */
    suspend fun run(
        read: suspend () -> List<JsonObject>,
        scroll: suspend () -> Unit,
        doItem: suspend (EachCursor.Item) -> Boolean,
        maxItems: Int = MAX_ITEMS,
    ): Summary {
        val cursor = ScrollCursor(role, text)
        val outcomes = mutableListOf<Outcome>()
        var justScrolled = false
        while (outcomes.size < maxItems) {
            val elements = read()
            when (val s = cursor.advance(elements, justScrolled)) {
                is ScrollCursor.Step.DoNext -> {
                    val ok = doItem(s.item)
                    outcomes += Outcome(s.item.contentKey, ok)
                    if (ok || onError == OnError.Skip) {
                        cursor.finish(s.item)
                        justScrolled = false
                    } else {
                        return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, s.item.contentKey, outcomes)
                    }
                }
                ScrollCursor.Step.Scroll -> { scroll(); justScrolled = true }
                ScrollCursor.Step.End -> return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, null, outcomes)
            }
        }
        // The cap limits only the number of items attempted; scrolling does not consume quota, and when scrolling cannot proceed the cursor's End terminates the loop.
        return Summary(outcomes.count { it.ok }, outcomes.count { !it.ok }, "__guard__", outcomes)
    }

    companion object {
        private const val MAX_ITEMS = 100_000
    }
}
