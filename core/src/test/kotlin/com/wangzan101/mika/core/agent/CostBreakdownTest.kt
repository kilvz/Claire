package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.FakeRequest
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cost breakdown: runs the real AgentLoop to get the exact token structure of each round, then applies a cache model to estimate where the "money" goes.
 * Conventions: overhead = system prompt + tool definitions (resent every round, cacheable); history = message text (cacheable);
 * image = one ~1280px JPEG per screen look (983 tokens). The fake model has no vision, so screenshots are estimated at 983 each and land in the round after the observation result enters.
 * Cache model (Anthropic style): prefix hit ×0.1, new write ×1.25.
 * How to run: ./gradlew :core:test --tests '*CostBreakdownTest' --info
 */
class CostBreakdownTest {

    private fun overheadTokens(system: String, tools: List<ToolSpec>): Int =
        TokenEstimate.of(system) + tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }

    private fun historyTokens(req: FakeRequest): Int = TokenEstimate.of(req.messages)

    /** A phone task of "look at the screen → tap → look at the screen …": cycles taps, cycles+1 screen looks, 2+2*cycles rounds. */
    private suspend fun phoneScenario(name: String, cycles: Int): List<FakeRequest> {
        val screens = (0..cycles).map { i ->
            Screen("com.android.settings", listOf(
                label("@e1", "页面 ${i + 1} 的标题", 120),
                button("@e2", "下一页按钮 $i", 420),
                label("@e3", "这页正文的一些说明文字 ${i}", 280),
            ))
        }
        val main = fakeModel(name) {
            reply { thinking("先看当前这一屏"); text("看一下当前屏幕"); call("phone_observe") }
            repeat(cycles) { i ->
                call("phone_tap", "ref" to "@e2", "snapshotId" to "s${i + 1}")
                call("phone_observe")
            }
            answer("做完了,一共看了 ${cycles + 1} 页。")
        }
        val catalog by lazy { GuideCatalog.loadBundled() }
        val bundled by lazy { KnowledgeStore(KnowledgeStore.loadBundled()) }
        val loop = AgentLoop(
            main, toolDispatcher(ScriptedScreen(screens), skills = catalog), SystemPrompt.identity(),
            maxSteps = 300, knowledge = bundled, skills = catalog,
        )
        loop.run("完成这个多步手机任务")
        return main.requests
    }

    @Test fun costAcrossScenarios() = runTest {
        val imgPerObserve = 983
        val scenarios = listOf(
            "简单(3次看屏)" to 2,
            "中等(8次看屏)" to 7,
            "长任务(16次看屏)" to 15,
        )

        val sb = StringBuilder()
        sb.append("=== 成本剖析(真实 AgentLoop 的 token 结构 + 估算缓存成本) ===\n\n")
        sb.append("缓存模型:Anthropic 式 —— 命中前缀 ×0.1、新写入 ×1.25;图片是新内容(×1.25 首次,随历史复读 ×0.1)。\n\n")

        for ((name, cycles) in scenarios) {
            val requests = phoneScenario(name, cycles)
            val turns = requests.size
            val observes = cycles + 1

            var prevOverhead = 0
            var prevHistory = 0
            var totalOverhead = 0
            var totalHistory = 0
            var cachedCost = 0.0
            var writtenCost = 0.0

            requests.forEachIndexed { i, req ->
                val overhead = overheadTokens(req.system, req.tools)
                val hist = historyTokens(req)
                val newImg = if (i % 2 == 1) imgPerObserve else 0 // The observation-result screenshot enters the history in the next round
                totalOverhead += overhead
                totalHistory += hist
                val overheadChanged = overhead != prevOverhead
                val cached = (if (overheadChanged) 0 else overhead) + prevHistory + (i / 2) * imgPerObserve
                val written = (if (overheadChanged) overhead else 0) + (hist - prevHistory).coerceAtLeast(0) + newImg
                cachedCost += 0.1 * cached
                writtenCost += 1.25 * written
                prevOverhead = overhead
                prevHistory = hist
            }
            val imageTokens = observes * imgPerObserve
            val totalTokens = totalOverhead + totalHistory + imageTokens
            val estCost = cachedCost + writtenCost

            sb.append("■ $name  —  $turns 轮 / $observes 次看屏\n")
            sb.append("   token 构成: 固定开销(系统+工具) %8d  (%.0f%%)\n".format(totalOverhead, totalOverhead * 100.0 / totalTokens))
            sb.append("               历史文本        %8d  (%.0f%%)\n".format(totalHistory, totalHistory * 100.0 / totalTokens))
            sb.append("               图片            %8d  (%.0f%%)\n".format(imageTokens, imageTokens * 100.0 / totalTokens))
            sb.append("               合计            %8d token\n".format(totalTokens))
            sb.append("   估算成本(缓存后): ~%.0f 计价单位  (无缓存 %.0f, 缓存省 %.0f%%)\n".format(
                estCost, totalTokens * 1.0, (1 - estCost / totalTokens) * 100))
            sb.append("   其中: 缓存读 ~%.0f (%.0f%%), 新写入 ~%.0f (%.0f%%)\n\n".format(
                cachedCost, cachedCost * 100.0 / estCost, writtenCost, writtenCost * 100.0 / estCost))
        }
        println(sb)
    }
}
