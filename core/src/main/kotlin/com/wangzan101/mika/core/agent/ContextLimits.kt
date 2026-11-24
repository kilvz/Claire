package com.wangzan101.mika.core.agent

/**
 * A few thresholds on a model's context window. All are counted over the **entire request** (system prompt + tool
 * definitions + messages, the same measure as the input tokens a provider reports); fixed overhead is not subtracted
 * here, it is accounted for exactly once on the measuring side ([UsageMeter]). Trigger points are computed in one
 * place only: [of].
 *
 * - [usable]: the window minus the reservation for output (at most [MAX_OUTPUT_RESERVE], or a quarter of the window
 *   when the window is small).
 * - [margin]: the headroom between the trigger point and [usable] (at most [MARGIN_TOKENS], or a quarter of [usable]
 *   when the window is small); it is also the hysteresis after compaction — the request must grow by at least this
 *   much more before compacting again, so it does not compact on every step.
 * - [trigger]: when the current request exceeds it, compact once. No lower than [MIN_TRIGGER_SHARE] of the window and
 *   no higher than [usable].
 * - [target]: a single compaction must bring the whole request below it ([AFTER_COMPACTION] of [usable], and at least
 *   one [margin] below the trigger point).
 */
class ContextLimits private constructor(
    val window: Int, val usable: Int, val trigger: Int, val margin: Int, val target: Int,
    val minTrigger: Int,
) {
    companion object {
        /** Upper limit of tokens reserved for model output. */
        const val MAX_OUTPUT_RESERVE = 20_000
        /** Upper limit of headroom left between the trigger point and the usable window. */
        const val MARGIN_TOKENS = 13_000
        /** A single compaction must bring the request below this fraction of the usable window. */
        const val AFTER_COMPACTION = 0.5
        /** Lower bound of the trigger point (as a share of the window): even if another trigger point is given, it will not be so low that compaction happens right at the start. */
        const val MIN_TRIGGER_SHARE = 0.5

        /**
         * [window]: the model's context length. [triggerOverride]: a per-model trigger point (tokens of the whole request); clamped between the lower bound and [usable].
         */
        fun of(window: Int, triggerOverride: Int? = null): ContextLimits {
            val w = window.coerceAtLeast(0)
            val usable = w - minOf(MAX_OUTPUT_RESERVE, w / 4)
            val margin = minOf(MARGIN_TOKENS, usable / 4)
            val floor = minOf((w * MIN_TRIGGER_SHARE).toInt(), usable)
            val trigger = (triggerOverride ?: (usable - margin)).coerceIn(floor, usable)
            val target = minOf((usable * AFTER_COMPACTION).toInt(), trigger - margin).coerceAtLeast(0)
            return ContextLimits(w, usable, trigger, margin, target, floor)
        }
    }
}
