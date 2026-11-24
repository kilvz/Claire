package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.learn.replay.MemRoutes
import com.wangzan101.mika.core.learn.replay.Notes
import com.wangzan101.mika.core.learn.replay.PagedScreen
import com.wangzan101.mika.core.learn.replay.RouteMatcher
import com.wangzan101.mika.core.learn.replay.RouteStep
import com.wangzan101.mika.core.learn.replay.Selector
import com.wangzan101.mika.core.learn.replay.ui
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.testing.PromptSurface
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * The notice handed back to the model when experience replay returns control, one per outcome, produced by the real
 * AgentLoop and copied verbatim into the golden file (prompt-surface/feedback-replay.txt).
 * The golden file was generated from the code before the refactor: once the notices are assembled from the feedback catalog, they must match word for word.
 */
class ReplayNoticeSurfaceTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)

    private class FailingTap(base: PagedScreen) : ScreenPort by base {
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed("obscured")
    }

    /** The notice appended to the conversation on hand-back (the passage that says "as before" / "the earlier way"). */
    private fun notice(screen: ScreenPort, route: com.wangzan101.mika.core.learn.replay.Route = Notes.route()): String {
        val main = FakeModel(text("写好了"))
        runBlocking {
            AgentLoop(main, toolDispatcher(screen), "BASE", flags = SpeedFlags(replay = true),
                routes = RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}""")), MemRoutes(route), null)).run("在备忘录里写今天天气不错")
        }
        return main.requests.first().flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }.single { "following a way that worked before" in it || "earlier way of doing this" in it }
    }

    @Test fun everyReplayOutcome() {
        val items = mutableListOf<Pair<String, String>>()
        items += "completed" to notice(Notes.screen())
        items += "diverged, not executed" to notice(Notes.screen().apply {
            pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        })
        items += "diverged, executed without effect" to notice(Notes.screen().apply { links.clear() })
        items += "diverged with error code" to notice(FailingTap(Notes.screen()))
        val send = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "提交订单", "n:id/send", listOf(0, 900, 300, 999), 0, true), Notes.homeA, Notes.editorA)
        items += "stopped for confirmation" to notice(Notes.screen().apply {
            pages["home"] = Notes.home + ui("@e5", "button", "提交订单", listOf(0, 900, 300, 999), rid = "n:id/send")
        }, Notes.route(send))
        PromptSurface.check("feedback-replay", PromptSurface.entries(items))
    }
}
