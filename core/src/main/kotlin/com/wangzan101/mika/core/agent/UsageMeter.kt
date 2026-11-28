package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.TokenEstimate
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import kotlin.math.ceil

/**
 * How large the whole next request is (system prompt + tool definitions + messages, the same convention as [ContextLimits]), counting fixed overhead only once:
 *
 * - With an anchor (the same model's most recent reply that carries real usage): the input tokens of that request (reported by the provider, already including the prompt and tool definitions at that time)
 *   + the output of this reply that will be sent again (output minus the reasoning tokens the provider reports separately, since reasoning does not count as context growth)
 *   + the estimate for subsequent messages + the estimate of how the fixed overhead has changed since then (e.g. a set of tools was expanded).
 * - Without an anchor (first step, model switched, just compacted): messages + fixed overhead, all estimated.
 *
 * Every estimate is [TokenEstimate] multiplied by the same calibration ratio [scale] (see [ContextCompressor.scale]).
 */
internal object UsageMeter {
    /** One reply's real usage, plus the estimate of the fixed overhead when that request was sent (before applying the ratio). */
    data class Anchor(val usage: Usage, val overhead: Int)

    fun of(messages: List<Message>, overhead: Int, scale: Double, anchorOf: (Message) -> Anchor? = { null }): Int {
        val at = messages.indexOfLast { it.role == Role.Assistant && anchorOf(it) != null }
        if (at < 0) return estimate(TokenEstimate.of(messages) + overhead, scale)
        val a = anchorOf(messages[at])!!
        val later = TokenEstimate.of(messages.subList(at + 1, messages.size)) + (overhead - a.overhead)
        return a.usage.inputTokens + resent(a.usage) + estimate(later, scale)
    }

    /** The part of the reply that will be sent again as input next time: output minus the separately reported reasoning tokens. */
    fun resent(u: Usage): Int = (u.outputTokens - u.reasoningTokens).coerceAtLeast(0)

    fun estimate(tokens: Int, scale: Double): Int = ceil(tokens * scale).toInt()
}
