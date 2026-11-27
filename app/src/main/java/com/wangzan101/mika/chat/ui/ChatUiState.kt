package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

enum class StepState { Ok, Run, Wait, Dim }

/**
 * One action within a step: what was done ([label], the tool's UI wording, not the tool name), on what, and how it went
 * (the effect in the run log).
 * Expanding the step lists them one by one — the row title is a summary; to see exactly what it tapped and what characters
 * it typed, look here.
 */
data class StepAction(val label: String, val target: String?, val effect: String?, val failed: Boolean)

/**
 * A row in the execution process, sourced from things actually done (activities in the run events): [title] is what was
 * done (reads merge into "looked at WeChat's chat list"), [reason] is the intent the model wrote for that step (empty when
 * it wrote none), and [subtitle] explains a failure and is normally empty.
 */
data class StepRow(val step: Int, val title: String, val subtitle: String, val state: StepState, val reason: String = "") {
    /** What this step reads as in the UI: the model's own words, falling back to the tool's wording when there are none. */
    val text: String get() = if (shouldHideRawReason(reason)) title else reason.ifEmpty { title }

    companion object {
        /**
         * When the UI language is not Chinese (e.g. the current language is English) and the model ignored the instruction
         * and produced an intent containing Chinese characters, fall back to the localized action title (e.g. Tap · WeChat)
         * instead of showing jarring, blunt Chinese in the UI.
         * "Looks Chinese" requires Han characters and no kana: written Japanese almost always mixes in kana (particles /
         * inflections), and only kana can separate Japanese from Han characters; otherwise, under a Japanese UI, Japanese
         * intents the model wrote per the language instruction would be hidden entirely.
         */
        internal fun shouldHideRawReason(reason: String, isChineseLocale: Boolean = uiLocale().language == "zh"): Boolean {
            if (reason.isEmpty()) return false
            if (isChineseLocale) return false
            return reason.any { it in '\u4e00'..'\u9fa5' } && reason.none { it in '\u3040'..'\u30ff' }
        }
    }
}

sealed interface ExchangeStatus {
    data object Working : ExchangeStatus
    data object WaitingUnlock : ExchangeStatus
    /** During payment takeover [item]/[amount]/[address] are listed separately on the card. */
    data class NeedsTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : ExchangeStatus
    data class NeedsInput(val prompt: String, val options: List<String>?) : ExchangeStatus
    /**
     * Waiting for the user to approve a single money-spending operation (approval only applies to this one time);
     * [title] reads "Needs your approval: <action>", and [body] has one field per line (app, target, full content, amount).
     * [key] is the id of this declaration, carried by the button, so tapping an older card does not count.
     */
    data class NeedsConfirmation(val title: String, val body: String, val key: Int) : ExchangeStatus
    /** Stopped and waiting; when [resumable], tapping "Continue" carries on, otherwise it resumes automatically once the condition is met (the card offers no "Continue"). */
    data class Paused(val why: String, val resumable: Boolean = true) : ExchangeStatus
    /** Not started yet, sitting in the start queue; it starts automatically when its turn comes, and can only be cancelled. */
    data class Queued(val why: String) : ExchangeStatus
    /**
     * [verified] false means it finished but could not be verified, with [note] as the reason; no green check is shown
     * then. [unconfirmed] holds the passages in the answer that still had no source after checking, listed one by one on
     * the card. [reviewed] false means nothing was checked at all (chats, or answers with no checkable values): no mark is
     * shown. [evidence] only exists for old records that have a model-written "Evidence:" section; new answers take their
     * sources from the run log (see [evidenceSource]).
     */
    data class Done(
        val answer: String, val evidence: String?, val verified: Boolean = true, val note: String? = null,
        val unconfirmed: List<String> = emptyList(), val reviewed: Boolean = true,
    ) : ExchangeStatus {
        val unverifiedLine: String? get() = when {
            unconfirmed.isNotEmpty() -> tr(R.string.chat_unconfirmed, unconfirmed.joinToString(tr(R.string.list_sep)))
            verified || !reviewed -> null
            else -> note?.let { tr(R.string.chat_unverified_because, it) } ?: tr(R.string.chat_unverified)
        }
    }
    data class Failed(val reason: String) : ExchangeStatus
    /**
     * The user stopped it. [summary] is the write-up added after stopping (what was done, what was left unfinished), used
     * as this turn's answer; null while it is not written yet or there is nothing to report.
     * While [summarizing], the write-up is still being written ("organizing what was done…").
     */
    data class Cancelled(val summary: String? = null, val summarizing: Boolean = false) : ExchangeStatus
}

/**
 * One question-answer: the user's sentence, the execution process, and the current state or result. [live] means it is a
 * task run since this app launch (with steps).
 * [runId] is the run record that can be viewed after the task ends; null while running or when there is no record.
 */
data class Exchange(
    val goal: String,
    val steps: List<StepRow>,
    val status: ExchangeStatus,
    val live: Boolean,
    val runId: String? = null,
    /** While a model request is in flight, show only the status rather than dressing the wait up as an execution step. */
    val waitingForModel: Boolean = false,
    /** The visible text the model has already produced for this request; before completion it may become a progress note or the reply. */
    val draft: String? = null,
    /** Memory notes below the result card (remembered / updated / forgot / should I always do this from now on). */
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** Ids of the memory entries used this time (recorded by code, not the model's own account). */
    val usedMemory: List<String> = emptyList(),
    /** Skill notes below the result card (learned / suspended for now because it stopped working recently). */
    val skillNotes: List<SkillNote> = emptyList(),
    /** Files exported to "Downloads/Claire" in this turn, shown as file cards. */
    val files: List<SharedFile> = emptyList(),
    /** Audio Claire generated and left in the workspace this turn, shown as audio cards. */
    val audios: List<com.wangzan101.mika.core.chat.GeneratedAudio> = emptyList(),
    /** Images Claire generated and left in the workspace this turn, shown as photo tiles. */
    val photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto> = emptyList(),
    /** 这一轮 Mika 生成并留在工作区里的视频,显示成视频卡片。 */
    val videos: List<com.wangzan101.mika.core.chat.GeneratedVideo> = emptyList(),
    /** 这次打开 App 后开始跟的时刻(毫秒);不知道时为 0。 */
    val startedAt: Long = 0L,
    /** Elapsed time computed at the end; null when not finished or when the start time is unknown. */
    val elapsedMs: Long? = null,
    /** What was actually done so far (run events); [steps] is derived from it. */
    val activities: List<RunEvent.Activity> = emptyList(),
    /** Wording the model wrote for each step (progress note or call intent), keyed by step number. */
    val notes: Map<Int, String> = emptyMap(),
    /** What it is doing right now: this step's wording from the model, or the progress while running several steps without the model; empty when there is none. */
    val line: String = "",
    /** Things the user interjected here while the task was running, and where each of them went. */
    val steers: List<SteerNote> = emptyList(),
    /** Site of the page left running in the background after this turn (see [Turn.keptPage]); null when none was kept. */
    val keptPage: String? = null,
    /** Reason that page was closed later ([Turn.keptPageClosed]); null when it is still open or unknown. */
    val keptPageClosed: String? = null,
    /** The actions actually performed in each step (by step number): the title is a summary, and expanding a step shows these details. */
    val stepActions: Map<Int, List<StepAction>> = emptyMap(),
    val goals: GoalChecklist? = null,
    /**
     * The front desk's reply for this turn (when chat goes through the front desk): non-null means this row is a front desk question-and-answer, drawn as a front desk bubble with no task card;
     * an empty string means it is still thinking (three dots). When it finished but the front desk said nothing (only dispatched tasks), no empty bubble is drawn.
     */
    val deskReply: String? = null,
    /** The ids of tasks dispatched in this turn (or referenced by a report), in order; each one gets a task card below this question-and-answer. */
    val deskTasks: List<String> = emptyList(),
    /** A small tag when the front desk adds a note to an existing task, replies to it, or cancels it this turn (shown only for this opening, not persisted). */
    val deskTool: String? = null,
    /** Which voice call this turn was spoken in ([com.wangzan101.mika.core.agent.Turn.callId]); null for typed chat. */
    val callId: String? = null,
    /** When this turn was stored; null while not yet stored (still in progress). Silent calls are placed in the conversation by it. */
    val atMs: Long? = null,
)

/**
 * The run that has a viewable run record. A notification reminder turn ([NoticeTurn]) is written straight into the
 * conversation by Claire without running a task, and its id exists only to identify it: "view process", sources and restart
 * are all gated on this, so that turn has none of them (otherwise tapping through would only say "the record has expired").
 * Identifying a turn and matching it against the saved copy (by [Exchange.runId] in [ChatViewModel]) still uses the original id.
 */
val Exchange.recordedRun: String? get() = runId?.takeUnless { it.startsWith(NoticeTurn.RUN_PREFIX) || it.startsWith(DeskChatOps.RUN_PREFIX) }

/**
 * Tasks newly dispatched in this turn. The first item of a front desk report row (which has no user words) is the one being reported on ([deskRef]) and does not count as new;
 * anything else the front desk dispatched along with a report rows after it and counts as new. "Work in this conversation" lists them.
 */
val Exchange.deskCards: List<String> get() = if (goal.isEmpty()) deskTasks.drop(1) else deskTasks

/** Which task the front desk report row is reporting on; null when it is not a report. */
val Exchange.deskRef: String? get() = if (goal.isEmpty()) deskTasks.firstOrNull() else null

/** Whether this front desk row draws text: it does while thinking (three dots), when it said something, or when it failed; when it finished without a word (only dispatched tasks) no empty bubble is drawn. */
internal fun deskReplyShown(ex: Exchange): Boolean =
    ex.deskReply != null && (ex.status == ExchangeStatus.Working || ex.deskReply.isNotBlank() || ex.status is ExchangeStatus.Failed)

/** The single line of progress while running: what it is waiting for when waiting on you; only a neutral status while a model request is in flight; otherwise this step's wording, or the latest thing done when there is none. */
fun liveLine(ex: Exchange): String? {
    when (ex.status) {
        is ExchangeStatus.NeedsTakeover -> return tr(R.string.takeover_title)
        is ExchangeStatus.NeedsInput -> return tr(R.string.chat_wait_answer)
        is ExchangeStatus.NeedsConfirmation -> return tr(R.string.chat_wait_approval)
        is ExchangeStatus.Queued -> return tr(R.string.chat_queued)
        else -> Unit
    }
    if (ex.waitingForModel) return tr(R.string.chat_working)
    return ex.line.ifEmpty { null } ?: ex.steps.lastOrNull()?.text
}

/** The small line under a steer; left out while it has no destination yet. With [paused], the task is waiting for the user, so it notes that this only takes effect after continuing. */
fun steerLabel(state: SteerState, paused: Boolean = false): String? = when (state) {
    SteerState.Pending, SteerState.Withdrawn -> null
    SteerState.Received -> tr(if (paused) R.string.steer_received_paused else R.string.steer_received)
    SteerState.Joined -> tr(R.string.steer_joined)
    SteerState.Next -> tr(R.string.steer_next)
    SteerState.Cancelled -> tr(R.string.steer_cancelled)
}

/**
 * Whether a steer can be pulled back for editing. Ones inserted from the floating pill carry a screen, and ones inserted
 * from selected text on a browser page carry page notes; pulling those back puts the `[[screen:…]]` line, or the original
 * text of the page notes, at the start of the input field, so deleting them loses the screen and the page and mangling them
 * makes the page unrecognizable. These two are not offered for editing.
 */
internal fun editableSteer(text: String): Boolean = !UserScreenMessage.has(text) && AttachedPage.parse(text).page == null

/**
 * An attachment picked and waiting to be sent out with the next message: content URI ([uri]), name and size; it is copied
 * into the conversation workspace only when sending.
 * Those with [photo] appear above the input field as thumbnails, the rest as small file cards.
 */
data class PendingAttachment(
    val uri: String,
    val name: String,
    val size: Long,
    val photo: Boolean = AttachmentMessage.isPhoto(name),
    val workspaceConversationId: String? = null,
    val workspacePath: String? = null,
)

enum class PipMode { Running, NeedsYou, Done }

/**
 * Picture-in-picture carries only the screen: its state is conveyed purely by the border glow (green while working, an
 * orange breath when it needs you), and whatever it needs you to do is in the chat card.
 * [closed]: the user closed this task's small window, leaving only a small button in the bottom-right corner (tap it to
 * reopen; it turns orange when it needs you). Closing only affects this one task; the next task shows its small window as usual.
 */
data class PipUi(
    val visible: Boolean = false,
    val mode: PipMode = PipMode.Running,
    val expanded: Boolean = false,
    val closed: Boolean = false,
)

enum class ComposerMode {
    /** No task; sending starts a new task. */
    Idle,
    /** A task opened directly in this conversation is running (from an attachment, or in an older conversation): show a stop button when nothing is typed; typed text still goes to the front desk. */
    Busy,
    /** The agent is waiting for an answer; sending is the answer. */
    Answer,
}

/** When a follow-up is sent from the floating capsule while a task is running, who receives it: the current task (carried on the next step), or this conversation's next task (chat goes through the front desk and no longer uses it). */
enum class FollowUpMode { Steer, Queue }

data class ChatUi(
    val conversationId: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** Owning project group id; for a new conversation before the first message is sent it lives only in the draft/UI state, and is written to the sidecar file when the first message is sent. */
    val projectId: String? = null,
    /** 这个对话下一次任务使用的主模型;未发首句时选择只留在界面进程内。 */
    val mainModel: MainModelUi = MainModelUi(),
    /** 上次发送被拦下时缺的条件;为空表示没被拦。 */
    val blocked: List<Problem>? = null,
    val pip: PipUi = PipUi(),
    val composer: ComposerMode = ComposerMode.Idle,
    /** The next item explicitly queued for this conversation that has not started (in order); removed here once it starts or is cancelled. */
    val queued: List<QueuedFollowUp> = emptyList(),
    /** Tasks this conversation's front desk has dispatched and their stages (in dispatch order); running ones show in the "In progress" row above the input bar, and each maps to a card in the conversation. */
    val deskTasks: List<com.wangzan101.mika.core.frontdesk.DeskTaskView> = emptyList(),
    val takeoverStage: Takeover.Stage = Takeover.Stage.None,
    /** The attachment button next to the input field exists only once the sandbox is ready. */
    val attachEnabled: Boolean = false,
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    /** Attachments are being copied into the workspace (at send time). */
    val importing: Boolean = false,
    /** Attachments that were not accepted (too large or unreadable); empty means no hint is shown. */
    val rejectedAttachments: List<AttachmentFailure> = emptyList(),
    /** How many were not added because they exceeded the per-send count ([AttachTray.LIMIT]); 0 means no hint is shown. */
    val overLimit: Int = 0,
    /** Content just pre-filled into a new conversation by a system share; only shown in the input field and never starts a task. */
    val sharedDraft: SharedDraft? = null,
    /** When the current conversation already has a draft, a system share received in the background parks here first, awaiting the user's confirmation to replace it or cancel. */
    val pendingSharedDraft: SharedDraft? = null,
    /** The quote above the input field to be sent with the next message (long-press a message and choose "Quote"); null when there is none. Kept with the conversation draft. */
    val quote: QuoteMessage.Quote? = null,
) {
    /** The task is currently operating on the background virtual screen (the hint above the input field): the screen was opened, it is working, and it is not waiting for you or for an unlock. */
    val operatingPhone: Boolean
        get() = pip.visible && pip.mode == PipMode.Running && exchanges.lastOrNull()?.status == ExchangeStatus.Working
}

internal data class ComposerDraft(
    val input: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val quote: QuoteMessage.Quote? = null,
    val sharedDraft: SharedDraft? = null,
    val importing: Boolean = false,
    val rejected: List<AttachmentFailure> = emptyList(),
    val overLimit: Int = 0,
    val projectId: String? = null,
) {
    val hasContent: Boolean get() = input.isNotEmpty() || attachments.isNotEmpty() || quote != null || importing || rejected.isNotEmpty()
    fun applyTo(ui: ChatUi) = ui.copy(input = input, attachments = attachments, quote = quote,
        sharedDraft = sharedDraft, importing = importing,
        rejectedAttachments = rejected, overLimit = overLimit, projectId = projectId ?: ui.projectId)
}

internal fun ChatUi.draft() = ComposerDraft(input, attachments, quote, sharedDraft,
    importing, rejectedAttachments, overLimit, projectId)

/**
 * Rebuilds the UI uniformly when switching conversations: tasks and input content belong to their own conversation, while
 * attachment capability belongs to the device and is not dropped by rebuilding for a conversation.
 * Every switch path goes through here, then restores the target conversation's own draft. A system share may explicitly fill in its own text and attachments.
 */
internal fun ChatUi.rebuildForConversation(
    conversationId: String?,
    exchanges: List<Exchange> = emptyList(),
    takeoverStage: Takeover.Stage = Takeover.Stage.None,
    input: String = "",
    sharedDraft: SharedDraft? = null,
    attachments: List<PendingAttachment> = emptyList(),
    projectId: String? = null,
) = copy(
    conversationId = conversationId,
    exchanges = exchanges,
    projectId = projectId,
    blocked = null,
    pip = PipUi(),
    composer = ComposerMode.Idle,
    input = input,
    queued = emptyList(),
    deskTasks = emptyList(),
    takeoverStage = takeoverStage,
    attachments = attachments,
    importing = false,
    rejectedAttachments = emptyList(),
    overLimit = 0,
    sharedDraft = sharedDraft,
    pendingSharedDraft = null,
    quote = null,
)
