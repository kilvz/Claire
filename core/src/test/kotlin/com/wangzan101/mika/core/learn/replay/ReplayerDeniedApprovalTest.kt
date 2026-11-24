package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs from non-Ui origins such as remote (MCP) and scheduled replay routes as usual too (TaskRun wires routes for every origin).
 * Payment steps recorded in a route are uniformly denied at this moment in these runs (#217's approval_unavailable);
 * the Replayer should treat that as a mismatch and hand back to the model, instead of crashing the whole task
 * with "unexpected refusal" the way the case AgentLoop fixed did (RouteReplay's contract: route problems never affect the task).
 */
class ReplayerDeniedApprovalTest {
    @Test fun deniedPaymentStepHandsBackToTheModelInsteadOfCrashing() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        // replay inherits this run's provenance: payments from remote origins are always denied (see ApprovalPolicy)
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink),
            provenance = RunProvenance(RunOrigin.Mcp, Attendance.Unattended))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("denied payment step should hand back to the model, got $out", out is ReplayOutcome.Diverged)
        assertTrue("the denied tap must not run", s.taps.isEmpty())
    }

    /** Contrast: in a Ui/attended run the same route takes the existing confirmation_required path and still hands back to the model without crashing. */
    @Test fun confirmationRequiredStepStillStopsForConfirmation() = runTest {
        val shop = listOf(
            ui("@e1", "text", "订单确认", listOf(0, 0, 999, 60), clickable = false),
            ui("@e2", "button", "确认支付", listOf(300, 800, 700, 900), rid = "n:id/pay"),
        )
        val s = PagedScreen(mapOf("shop" to shop), start = "shop").apply { pkg = "com.shop" }
        val step = RouteStep("phone_tap", "{}",
            Selector("button", "确认支付", "n:id/pay", listOf(300, 800, 700, 900), 0, true),
            Anchors("com.shop", listOf("订单确认", "确认支付")),
            Anchors("com.shop", listOf("已支付")))
        val route = Route("r-pay", "买{1}", 1, listOf("com.shop"), emptyMap(), listOf(step), lastUsedMs = 0)
        val sink = Events()
        val d: ToolDispatcher = toolDispatcher(s).also { it.captureTargets = true }
        val executor = ActionExecutor(d, OperationGuards(), ConfirmationGate(), {}, ActionRecorder(sink))
        val out = Replayer(d, executor, gate = {}, sink = sink).run(RouteChoice(route, listOf("矿泉水")))
        assertTrue("UI replay should stop for confirmation, got $out", out is ReplayOutcome.StoppedForConfirmation)
        assertTrue(s.taps.isEmpty())
    }
}
