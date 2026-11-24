package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import kotlinx.coroutines.flow.map
import com.wangzan101.mika.settings.ui.StatusCopy

/** Folds task state and run events into a single question-answer on the chat card (pure functions). */
internal object ChatReduce {
    internal fun TaskState.active() = when (this) {
        TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
        else -> true
    }

    internal fun ExchangeStatus.active() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
        else -> true
    }

    internal fun ExchangeStatus.needsUser() =
        this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
            this is ExchangeStatus.NeedsConfirmation

    internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
        state(ex, s, appLabel).let {
            it.copy(steps = rows(it, toolLabel, appLabel), stepActions = actionsOf(it.activities, appLabel, toolLabel))
        }

    /** Action details grouped by step number: listed when a step is expanded. */
    internal fun actionsOf(activities: List<RunEvent.Activity>, appLabel: (String) -> String, toolLabel: (String) -> String) =
        activities.groupBy { it.step }.mapValues { (_, list) ->
            // Use the tool's UI wording (the same as on the run progress page) instead of exposing raw tool names to the user; MCP calls name the connector
            list.map { a ->
                val label = a.mcpConnector?.let(StatusCopy::calledMcp) ?: toolLabel(a.label)
                StepAction(label, a.target?.let { where(it, appLabel) }, a.effect, a.failed)
            }
        }

    internal fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
        TaskState.Idle -> ex
        is TaskState.Running -> {
            val goals = (s.progress as? RunProgress.Task)?.goals
            // While calling MCP, name the connector first, taking precedence over what the model wrote
            val progress = s.mcpConnector?.let(StatusCopy::callingMcp) ?: s.progress?.takeIf { goals == null }?.let(StatusCopy::progress)
            // For "what is it doing", prefer the wording the model wrote alongside the call, then fall back to the intent in the call
            val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
            val waiting = progress == null && s.tool == null && said == null
            ex.copy(
                status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview, goals = goals ?: ex.goals,
                line = if (waiting) "" else progress ?: said.orEmpty(),
                notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
            )
        }
        // Waiting on a capability precondition: resume automatically when the screen is locked; queue until start; otherwise offer resume only when it can be resumed
        is TaskState.Held -> ex.copy(
            status = when {
                s.reason == TaskGate.LOCKED -> ExchangeStatus.WaitingUnlock
                s.reason.queued -> ExchangeStatus.Queued(s.reason.why)
                else -> ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
            },
            waitingForModel = false, draft = null,
        )
        is TaskState.WaitingTakeover ->
            ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
        is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
        is TaskState.WaitingConfirmation -> ex.copy(
            waitingForModel = false,
            draft = null,
            status = ExchangeStatus.NeedsConfirmation(
                ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                ConfirmationNotifier.requestKey(s),
            ),
        )
        // The answer is shown as-is (there is no longer a model-written evidence section); sources come from what the code recorded in the run log
        is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
            waitingForModel = false, draft = null, line = "")
        is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
        TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
    }

    /**
     * Step list: one row per thing actually done. Reads show up only when we know what was read, and consecutive reads of
     * the same place merge into one row ("looked at WeChat's chat list"); actions read "tap · WeChat", and if the model
     * wrote something for that step its wording is used; failures just say it failed, without showing error codes.
     * The last row's state follows this question-answer: running, waiting for you, finished.
     */
    /** The small line under a step that did not go through: ones blocked by rules give the reason, the rest say "failed". */
    internal fun failedLabel(a: RunEvent.Activity) = ChatCopy.declinedLabel(a.effect) ?: tr(R.string.step_failed)

    internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
        val out = mutableListOf<StepRow>()
        val said = mutableSetOf<Int>()
        for (a in ex.activities) {
            val where = a.target?.let { where(it, appLabel) }
            val mcp = a.mcpConnector?.let(StatusCopy::calledMcp)
            if (mcp != null) {
                // MCP calls get a row of their own naming the connector, so a failure still shows which connector it was
                out += StepRow(a.step, mcp, if (a.failed) failedLabel(a) else "", StepState.Ok)
            } else if (a.kind == "observe") {
                // Read succeeded but we do not know what was read: omit it; failed reads are still listed
                if (where == null && !a.failed) continue
                val text = if (where == null) toolLabel(a.label)
                    else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                val subtitle = if (a.failed) failedLabel(a) else ""
                val last = out.lastOrNull()
                if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
            } else {
                val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                out += StepRow(a.step, title, if (a.failed) failedLabel(a) else "", StepState.Ok, reason)
            }
        }
        if (out.isEmpty()) return out
        val last = when (ex.status) {
            ExchangeStatus.Working -> StepState.Run
            is ExchangeStatus.Done -> StepState.Ok
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
            else -> StepState.Wait
        }
        out[out.lastIndex] = out.last().copy(state = last)
        return out
    }

    /** Human-readable form of an activity target: package names become app names, and "site domain" keeps only the domain. */
    internal fun where(target: String, appLabel: (String) -> String): String? = when {
        target.isBlank() -> null
        target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
        com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
        else -> target
    }

    internal fun fromTurn(t: Turn): Exchange {
        val status = when (t.status) {
            "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
            "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
            "cancelled" -> {
                val rawSummary = Conversation.stopSummary(t)
                val summary = if (rawSummary != null && uiLocale().language != "zh") {
                    rawSummary
                        .replace("已经停下。停止前:", "Stopped. Before stopping:")
                        .replace("已经停下,停止前做了这些:", "Stopped. Actions before stop:")
                        .replace("已经停下,还没开始操作。", "Stopped before starting any actions.")
                } else rawSummary
                ExchangeStatus.Cancelled(summary)
            }
            else -> ExchangeStatus.Failed(t.outcome)
        }
        return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
            memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
            audios = t.audios,
            photos = t.photos,
            keptPage = t.keptPage, keptPageClosed = t.keptPageClosed,
            steers = t.steers.mapIndexed { i, text ->
                SteerNote("turn-$i", text, SteerState.Joined, t.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty),
                    t.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty))
            })
    }

    /** One line per attachment at the end of the message: "Attachment: /workspace/inbox/<name> (<size>)"; [files] is (path, bytes) after copying into the workspace. */
    internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
        AttachmentLines.composeBytes(text, files)

    fun sizeLabel(bytes: Long): String = AttachmentLines.sizeLabel(bytes)
}
