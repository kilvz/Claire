package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMeterTest {
    private val task = Message(Role.User, listOf(Part.Text("帮我查天气")), Author.User)
    private var seq = 0
    private fun call() = Message(Role.Assistant, listOf(Part.ToolCall("c${++seq}", "phone_tap", """{"target":"按钮"}""")), Author.Mika)
    private fun result(pad: Int = 400) = Message(Role.Tool, listOf(Part.ToolResult("c$seq", """{"ok":true,"pad":"${"x".repeat(pad)}"}""", false)), Author.Mika)

    @Test fun noUsageYetEstimatesMessagesAndOverheadOnce() {
        val h = listOf(task, call(), result())
        // First step: no anchor yet, so messages + fixed overhead are estimated together and multiplied by the same ratio
        assertEquals(UsageMeter.estimate(TokenEstimate.of(h) + 11_000, 1.2), UsageMeter.of(h, 11_000, 1.2))
    }

    @Test fun anchorsOnTheLatestReplyWithUsage() {
        val first = call()
        val r1 = result()
        val second = call()
        val r2 = result()
        val h = listOf(task, first, r1, second, r2)
        val usage = mapOf(first to UsageMeter.Anchor(Usage(10_000, 50), 9_000), second to UsageMeter.Anchor(Usage(12_000, 40), 9_000))
        // Real input (already including the prompt and tools of that moment) + the reply's output + an estimate of the messages after it; the fixed overhead is unchanged, so it is not added again
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)), 1.0), UsageMeter.of(h, 9_000, 1.0, usage::get))
        // Afterwards the fixed overhead changed (a tool group was expanded): only the difference is added
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)) + 500, 1.0), UsageMeter.of(h, 9_500, 1.0, usage::get))
    }

    @Test fun thinkingTokensAreNotContextGrowth() {
        val reply = call()
        val h = listOf(task, reply)
        // Of the 3000 output tokens, 2900 are reasoning the provider reports separately; only 100 is sent again as the next input
        val anchor = UsageMeter.Anchor(Usage(inputTokens = 8_000, outputTokens = 3_000, reasoningTokens = 2_900), 0)
        assertEquals(8_100, UsageMeter.of(h, 0, 1.0) { if (it === reply) anchor else null })
        assertEquals(0, UsageMeter.resent(Usage(1, 10, reasoningTokens = 50)))
    }

    @Test fun anchoredOverheadIsNotCountedTwiceInTheTrigger() {
        // A 32K local model with 11K of prompt and tools: real usage already includes those 11K, so the trigger point does not deduct them again and it does not compact on every step
        val window = 32_000
        val overhead = 11_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var input = 12_000
        var compactions = 0
        repeat(10) {
            val reply = call()
            h += reply
            h += result(pad = 200)
            val anchor = UsageMeter.Anchor(Usage(input, 30), overhead)
            val size = UsageMeter.of(h, overhead, c.scale) { if (it === reply) anchor else null }
            if (c.compress(h, window, size, fixedOverhead = overhead).foldedSteps > 0) compactions++
            input += 100
        }
        assertEquals(0, compactions)
        // It compacts only after crossing the trigger point (18K)
        val reply = call()
        h += reply
        h += result()
        val over = UsageMeter.of(h, overhead, c.scale) { if (it === reply) UsageMeter.Anchor(Usage(18_500, 30), overhead) else null }
        assertTrue(over > ContextLimits.of(window).trigger)
        assertTrue(c.compress(h, window, over, fixedOverhead = overhead).messages != h)
    }

    @Test fun firstStepWithHeavyOverheadOnSmallWindowDoesNotCompactEveryStep() {
        // No real usage and the fixed overhead is already past the trigger point: one compaction leaves hysteresis, so it does not compact again on every step
        val window = 32_000
        val overhead = 16_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var rewrites = 0
        repeat(30) {
            h += call()
            h += result()
            if (c.compress(h, window, fixedOverhead = overhead).rewritten) rewrites++
        }
        assertTrue("$rewrites", rewrites in 1..3)
    }
}
