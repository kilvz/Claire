package com.wangzan101.mika.core.run.record

/**
 * How many tokens all turns of one conversation used together, computed from each turn's run summary ([RunMetrics]); the same
 * measure as the line at the top of "view process" ([RunMetrics.tokens]: input plus output reported by each main-loop reply).
 *
 * [missing] counts the turns that are not fully accounted for: the summary is not written yet, it was old enough to be pruned (only the most recent [RunMetricsStore.DEFAULT_KEEP] are kept),
 * or some replies had no usage reported by the provider; the UI then says "at least" instead of estimating.
 */
data class ConversationTokens(val tokens: Long, val turns: Int, val missing: Int) {
    val partial: Boolean get() = missing > 0

    companion object {
        /** Tokens for one turn; null when there is no summary, or when nothing at all was reported (all zeros), so the UI does not write "0 Token". */
        fun turn(m: RunMetrics?, includeSideCalls: Boolean = false): Long? {
            m ?: return null
            val input = m.allInputTokens
            val output = m.allOutputTokens
            val tokens = if (includeSideCalls && input != null && output != null) input + output else m.tokens
            return tokens.takeIf { it > 0 }
        }

        /** [runIds] are the finished turns of this conversation (a repeated id counts once); null when there is not a single number, so the UI shows nothing. */
        fun of(runIds: List<String>, metrics: Map<String, RunMetrics>): ConversationTokens? {
            val ids = runIds.distinct()
            var sum = 0L
            var missing = 0
            for (id in ids) {
                val m = metrics[id]
                if (m == null) { missing++; continue }
                sum += m.tokens
                if (m.callsWithoutUsage > 0) missing++
            }
            if (sum <= 0) return null
            return ConversationTokens(sum, ids.size, missing)
        }
    }
}
