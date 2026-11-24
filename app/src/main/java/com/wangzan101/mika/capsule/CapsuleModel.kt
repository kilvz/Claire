package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.chat.ui.Exchange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The three-way choice for 'floating capsule' in settings; stored as a name, and anything unrecognized (never set, older version) falls back to the default 'always show' (send this screen by double tap at any time). */
enum class CapsuleMode {
    Off, DuringTasks, Always;

    companion object {
        val DEFAULT = Always
        fun parse(name: String?): CapsuleMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Opacity of a capsule sitting idle; the slider only stops at these four levels, and settings store the percentage. */
enum class CapsuleIdleOpacity(val percent: Int) {
    P20(20), P40(40), P70(70), P100(100);

    val alpha: Float get() = percent / 100f

    companion object {
        val DEFAULT = P40
        fun snap(percent: Int): CapsuleIdleOpacity = entries.minBy { kotlin.math.abs(it.percent - percent) }
        fun parse(percent: Int): CapsuleIdleOpacity = entries.firstOrNull { it.percent == percent } ?: DEFAULT
    }
}

/** Levels for the overall scale of the expanded capsule card; the slider only stops at these four levels, and settings store the percentage (85 = 0.85×). Only the expanded card is scaled,
 * while the collapsed dot, the capsule bar, and the hold-to-talk panel do not follow. */
enum class CapsuleCardScale(val percent: Int) {
    Small(85), Normal(100), Large(115), XLarge(130);

    val factor: Float get() = percent / 100f

    /**
     * Width the card uses before scaling (standard dp): the card content is rendered scaled by [factor], so the cap is divided back by [factor],
     * so that every level renders to at most 'screen width − 24dp' and at least 240dp, the same bounds as the standard level.
     */
    fun width(screenWidthDp: Int): Dp = minOf(CardWidth, ((screenWidthDp - 24) / factor).coerceAtLeast(240f / factor).dp)

    companion object {
        val DEFAULT = Normal
        fun parse(percent: Int): CapsuleCardScale = entries.firstOrNull { it.percent == percent } ?: DEFAULT
    }
}

/** The card width at the standard level; for the enlarged and reduced levels see [CapsuleCardScale.width]. */
val CardWidth = 322.dp

/**
 * What a task looks like right now: its key ([TaskRun.key]), the conversation it lives in, its state, and how far the takeover has progressed;
 * the card also needs its goal [goal] and how the current exchange looks right now [exchange] (steps, what it wants from you, the result, derived the same way as on the conversation screen).
 * [queueAhead]/[queueBehind] are how many items sit before and after it in the takeover queue (the main screen only hands over one app at a time); when it is not in the queue or that is unknown, [queueAhead] is -1
 * and ordering then falls back to when it started waiting for takeover. [runId] is its run record (the turn recorded in the conversation carries the same id; null while no record has been started).
 * [onScreen] means it is currently operating some screen: it has its own virtual display ([com.wangzan101.mika.task.TaskRun.displayId] is not -1),
 * or it is using the built-in browser ([com.wangzan101.mika.task.TaskRun.webOpen]); the card uses this to choose a task card or a chat card.
 * [steers] are the interjections made while it is running and their destinations ([com.wangzan101.mika.task.TaskRun.steers]); on a chat card they follow the original utterance in the exchange.
 */
data class CapsuleRun(
    val key: String,
    val conversationId: String,
    val state: TaskState,
    val stage: Takeover.Stage = Takeover.Stage.None,
    val goal: String = "",
    val exchange: Exchange? = null,
    val queueAhead: Int = -1,
    val queueBehind: Int = 0,
    val runId: String? = null,
    val onScreen: Boolean = false,
    val steers: List<SteerNote> = emptyList(),
)

/**
 * All the inputs that decide what the capsule shows: the tasks ([runs], including just-finished ones whose state is already terminal), the switch, whether Claire's own UI is in the foreground,
 * whether the screen is on and unlocked, whether the accessibility service is running and overlays can be drawn, whether the card is expanded ([cardOpen]), and the page left running in the background after a task finished ([kept]).
 */
data class CapsuleInputs(
    val runs: List<CapsuleRun>,
    val mode: CapsuleMode,
    val mikaInForeground: Boolean,
    val screenInteractive: Boolean,
    val serviceAvailable: Boolean,
    val cardOpen: Boolean = false,
    val kept: KeptPage? = null,
    val fullscreen: Boolean = false,
    /** How many notices the proactive feature judged worth reminding and the user has not seen in the reminder list yet (only the count, none of the notification text). */
    val notices: Int = 0,
)

/** Capsule states, ordered by priority from high to low (the smaller [ordinal] is shown first); [Idle] is the icon with no task when 'always show' is on. */
enum class CapsuleKind {
    HandedOver, NeedTakeover, NeedConfirm, NeedAnswer,
    /** It wants you to do something on the phone (such as picking one entry in a system chooser); it continues automatically once that is done. */
    NeedStep,
    Held, Running, Done, Failed, Cancelled,
    /** No task is running and the page left behind after a task finished is still running in the background ('web page running in background', green dot). */
    KeptPage,
    /** When there is nothing else, there are notices judged worth reminding that have not been seen yet: a number on the corner of the idle icon, tapping it opens the reminder list. It does not expand or show text. */
    Notice,
    Idle;

    /** It needs you to act (the capsule turns orange). The app already handed to you ([HandedOver]) is that bar at the top and does not count. */
    val needsYou: Boolean get() = this == NeedTakeover || this == NeedConfirm || this == NeedAnswer || this == NeedStep

    /** Not finished yet (running, waiting for you, waiting on a condition); [KeptPage], [Notice], and [Idle] do not count. */
    val active: Boolean get() = this != Done && this != Failed && this != Cancelled && this != KeptPage && this != Notice && this != Idle
}

sealed interface CapsuleView {
    data object Hidden : CapsuleView

    /**
     * Shows the capsule (or the 'you are handling it' bar): [text] is that line (already composed, including 'N total'), and [detail] only exists for [CapsuleKind.HandedOver],
     * as the small line under it. [count] is the number of tasks in progress (for [CapsuleKind.Notice] it is the number of unseen notices), and [needCount] is how many of them need you.
     * [runKey]/[conversationId] identify the thing the capsule is talking about. Normally it is a Claire icon with the state drawn on the ring and badge;
     * with [pill] it expands into a capsule carrying that line (when something needs you or something just finished). While [widthEpoch] stays the same the capsule only grows wider, never narrower;
     * when it changes (state or item changed) the width is calculated anew.
     */
    data class Shown(
        val kind: CapsuleKind,
        val text: String,
        val detail: String? = null,
        val count: Int = 0,
        val needCount: Int = 0,
        val runKey: String? = null,
        val conversationId: String? = null,
        val pill: Boolean = false,
        val widthEpoch: Int = 0,
    ) : CapsuleView
}

/**
 * The capsule's pure logic: call [update] whenever the inputs change (or [nextChangeAt] arrives) to get what should be shown right now. Rules:
 * - Hidden: the switch is off, the accessibility service is unavailable, Claire's own UI is in the foreground, or the screen is off or locked;
 * - Only the most important item is shown at a time, priority as in [CapsuleKind]; with several items 'N total' is appended, and it turns orange when something needs you: '1 needs you · 2 total';
 * - Normally a single icon: running or waiting states only change the ring and badge, and moving to the next step does not expand it;
 * - When something needs you and the user has not seen it in the card yet, it expands into a pill and stays there until the card is opened or that item no longer needs you;
 *   the items seen when the card opens (same item, same wait) are marked seen, and once collapsed it returns to a single icon (orange), expanding again only for something new;
 * - Done / failed / stopped first expands as a pill for [PILL_MS], then shrinks to a single icon carrying a result badge, and stays for [LINGER_MS] in total before disappearing (back to the icon with 'always show');
 *   items already seen in the card go straight to the single icon; while the card is expanded the clock pauses, so a result the user is looking at is not taken away and the remaining time continues after the card collapses;
 *   what has finished lingering never comes back (even if the caller still lists it); when the conversation is deleted ([forget]) its finished tasks disappear immediately;
 * - When there is no task and the results have finished lingering, but the page left after a task is still running in the background, it expands into 'web page running in background' and stays (with the switch set to 'never show' it still does not show,
 *   notifications only);
 * - When there is nothing else and the switch is 'always show', unseen notices judged worth reminding put a count on the icon's corner ([CapsuleKind.Notice]), without expanding or timing;
 * - Swapping the sentence within the same item and state keeps each sentence for at least [MIN_SENTENCE_MS], and changes arriving in between keep only the last one; a state or item change swaps immediately.
 * Which item the card picks and in what order is in [cardRuns].
 * [reasonLabel] turns a takeover reason (login etc.) into a human-readable name, null when unrecognized. Time comes from the caller; tests use a fake clock.
 */
class CapsuleModel(private val reasonLabel: (String) -> String? = { null }) {
    private class Ended(val run: CapsuleRun, val at: Long)

    /** The single thing an item is currently waiting on: same item, same state, same takeover stage counts as the same. */
    private data class Mark(val key: String, val state: TaskState, val stage: Takeover.Stage)

    /** When each task first appeared: ties are ordered by this, and the card shows the elapsed time. */
    private val firstSeen = HashMap<String, Long>()
    private val ended = HashMap<String, Ended>()
    /** Finished tasks removed because they finished lingering or their conversation was deleted: no longer recorded in [ended] while the caller still lists them; forgotten once the caller stops listing them. */
    private val dropped = HashSet<String>()
    /** Tasks waiting for takeover or already handed over, in the order they started waiting: the main screen hands over one at a time, so the front of the queue is handled first. */
    private val takeoverSince = LinkedHashMap<String, Long>()
    /** Items that need you and results the user has seen in the card: they no longer expand into a pill. */
    private val seen = HashSet<Mark>()
    /** When the card was expanded; while it is expanded, tasks that finish do not keep counting down. */
    private var frozenSince: Long? = null
    private var lastRuns: List<CapsuleRun> = emptyList()

    private var shownIdentity: Triple<CapsuleKind, String?, Boolean>? = null
    private var shownText: String? = null
    private var shownAt = 0L
    private var epoch = 0

    /** When the inputs stay the same, the next moment the output will change (the queued sentence to swap to, shrinking back after finishing, and disappearing); null when there is none. */
    var nextChangeAt: Long? = null
        private set

    fun update(inputs: CapsuleInputs, now: Long): CapsuleView {
        nextChangeAt = null
        lastRuns = inputs.runs
        val keys = inputs.runs.map { it.key }.toSet()
        inputs.runs.forEach { firstSeen.getOrPut(it.key) { now } }
        if (inputs.cardOpen) {
            if (frozenSince == null) frozenSince = now
        } else frozenSince?.let { since ->
            // Card collapsed: the time it was expanded does not count, and finished tasks continue with the remaining time
            val paused = now - since
            ended.replaceAll { _, e -> Ended(e.run, e.at + paused) }
            frozenSince = null
        }
        dropped.retainAll(keys)
        inputs.runs.filter { it.state.ended() && it.key !in dropped }.forEach { ended.getOrPut(it.key) { Ended(it, frozenSince ?: now) } }
        if (frozenSince == null) drop(ended.filterValues { now - it.at >= LINGER_MS }.keys)
        firstSeen.keys.retainAll(keys + ended.keys)
        val waiting = inputs.runs.filter { it.state is TaskState.WaitingTakeover && it.stage != Takeover.Stage.None }.map { it.key }
        takeoverSince.keys.retainAll(waiting.toSet())
        waiting.forEach { takeoverSince.getOrPut(it) { now } }
        // Keep only seen marks that still exist; while the card is expanded, anything needing you or any result right now counts as seen
        val marks = needMarks(inputs.runs) + ended.values.map { it.run.mark() }
        seen.retainAll(marks)
        if (inputs.cardOpen) seen += marks

        if (inputs.mode == CapsuleMode.Off || !inputs.serviceAvailable || inputs.fullscreen || inputs.mikaInForeground || !inputs.screenInteractive) {
            // Show the latest sentence directly when it reappears, instead of continuing the previous throttle
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        val candidate = activeView(inputs.runs) ?: endedView(now) ?: keptView(inputs.kept) ?: noticeView(inputs) ?: idleView(inputs.mode) ?: run {
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        return throttle(candidate, now)
    }

    /** Conversation [conversationId] was deleted: its finished tasks are removed from the capsule and card immediately, while running ones stay as they are. */
    fun forget(conversationId: String) = drop(ended.filterValues { it.run.conversationId == conversationId }.keys)

    private fun drop(keys: Set<String>) {
        ended.keys.removeAll(keys)
        dropped += keys
    }

    private fun needMarks(runs: List<CapsuleRun>): Set<Mark> =
        runs.filter { it.state != TaskState.Idle && !it.state.ended() && describe(it).kind.needsYou }.map { it.mark() }.toSet()

    private fun CapsuleRun.mark() = Mark(key, state, stage)

    private fun activeView(runs: List<CapsuleRun>): CapsuleView.Shown? {
        val active = runs.filter { it.state != TaskState.Idle && !it.state.ended() }
        if (active.isEmpty()) return null
        val described = active.map { it to describe(it) }
        // Among equally important items, mention the one ahead in the takeover queue first, then the one that started earlier
        val (top, line) = described.minWith(order)
        val count = active.size
        val needCount = described.count { it.second.kind.needsYou }
        var detail: String? = null
        val text = when {
            line.kind == CapsuleKind.HandedOver -> {
                val behind = spot(top)?.second ?: described.count { it.first !== top && it.second.kind == CapsuleKind.NeedTakeover }
                detail = if (behind > 0) trPlural(R.plurals.capsule_behind, behind)
                    else listOfNotNull(line.reason, tr(R.string.capsule_then_tap_right)).joinToString(" · ")
                line.text
            }
            count >= 2 && needCount > 0 -> tr(R.string.capsule_need_of_total, needCount, count)
            count >= 2 -> tr(R.string.capsule_of_total, line.text, count)
            else -> line.text
        }
        // Something needs you that has not been seen in the card yet: expand into a pill and stay there
        val pill = line.kind.needsYou && described.any { (r, l) -> l.kind.needsYou && r.mark() !in seen }
        return CapsuleView.Shown(line.kind, text, detail, count, needCount, top.key, top.conversationId, pill = pill)
    }

    /** The most recently finished item: first expands into a pill, then shrinks to a single icon with a result badge; already seen in the card means the single icon directly; the clock pauses while the card is expanded. */
    private fun endedView(now: Long): CapsuleView.Shown? {
        val last = ended.values.maxByOrNull { it.at } ?: return null
        val age = (frozenSince ?: now) - last.at
        val pill = age < PILL_MS && last.run.mark() !in seen
        if (frozenSince == null) later(last.at + if (pill) PILL_MS else LINGER_MS)
        val (kind, text) = endedLine(last.run.state)
        return CapsuleView.Shown(kind, text, runKey = last.run.key, conversationId = last.run.conversationId, pill = pill)
    }

    private fun endedLine(s: TaskState): Pair<CapsuleKind, String> = when (s) {
        is TaskState.Succeeded -> CapsuleKind.Done to tr(R.string.capsule_done_tap)
        is TaskState.Failed -> CapsuleKind.Failed to tr(R.string.capsule_failed_tap)
        else -> CapsuleKind.Cancelled to tr(R.string.capsule_stopped)
    }

    /**
     * The items the card picks one from (the inputs of the last [update], see [CapsuleCards.of]): running ones ordered by importance (equally important ones with the earlier start first),
     * followed by finished ones that have not disappeared yet (most recently finished first). Running and waiting tasks carry their start time; takeover waiters carry the counts before and after them.
     */
    fun cardRuns(): List<CardRun> {
        val active = lastRuns.filter { it.state != TaskState.Idle && !it.state.ended() }
            .map { it to describe(it) }
            .sortedWith(order)
        val activeKeys = active.map { it.first.key }.toSet()
        return active.map { (r, line) ->
            val (ahead, behind) = spot(r) ?: (0 to 0)
            val timed = line.kind == CapsuleKind.Running || line.kind == CapsuleKind.Held
            CardRun(r, line.kind, line.text, startedAt = if (timed) firstSeen[r.key] else null, ahead = ahead, behind = behind)
        } + ended.values.filter { it.run.key !in activeKeys }.sortedByDescending { it.at }.map { e ->
            val (kind, text) = endedLine(e.run.state)
            CardRun(e.run, kind, text)
        }
    }

    /**
     * How a single item looks in the card (without going through [update] and without ordering): used where only one item is followed elsewhere (the conversation panel of the page on the browser page).
     * Finished ones use the finished wording; takeover waiters use their own queue position.
     */
    fun cardRunOf(r: CapsuleRun): CardRun {
        if (r.state.ended()) {
            val (kind, text) = endedLine(r.state)
            return CardRun(r, kind, text)
        }
        val line = describe(r)
        return CardRun(r, line.kind, line.text, ahead = r.queueAhead.coerceAtLeast(0), behind = r.queueBehind)
    }

    /** More important first; among equally important ones, the one ahead in the takeover queue comes first, then by start time. */
    private val order = compareBy<Pair<CapsuleRun, Line>>(
        { it.second.kind.ordinal }, { spot(it.first)?.first ?: Int.MAX_VALUE }, { firstSeen[it.first.key] ?: 0L },
    )

    /** How many items sit before and after it in the takeover queue: use the real queue position when there is one, otherwise order by when it started waiting for takeover; null when it is not waiting for takeover. */
    private fun spot(r: CapsuleRun): Pair<Int, Int>? {
        if (r.queueAhead >= 0) return r.queueAhead to r.queueBehind
        val queue = takeoverSince.keys.toList()
        val i = queue.indexOf(r.key)
        return if (i < 0) null else i to queue.size - 1 - i
    }

    /** The page left running in the background: expands into a pill naming the conversation it came from. */
    private fun keptView(kept: KeptPage?): CapsuleView.Shown? =
        kept?.let { CapsuleView.Shown(CapsuleKind.KeptPage, KEPT, conversationId = it.conversationId, pill = true) }

    /**
     * Number of notices worth reminding: like the idle icon, only present with 'always show' ('show during tasks' covers tasks only; the reminder list is then entered from the proactive page).
     * It is only a number on the icon's corner and expands nothing or shows no text: notification text (which anyone can send) never goes onto the capsule.
     */
    private fun noticeView(inputs: CapsuleInputs): CapsuleView.Shown? =
        if (inputs.mode == CapsuleMode.Always && inputs.notices > 0) CapsuleView.Shown(CapsuleKind.Notice, "", count = inputs.notices) else null

    private fun idleView(mode: CapsuleMode): CapsuleView.Shown? =
        if (mode == CapsuleMode.Always) CapsuleView.Shown(CapsuleKind.Idle, "") else null

    /**
     * Swapping sentences within the same item and state: if less than [MIN_SENTENCE_MS] has passed since the last sentence, keep the previous one for now and swap to the latest one at that point.
     * Expanding and shrinking back also counts as a new segment, and the capsule calculates its width anew.
     */
    private fun throttle(v: CapsuleView.Shown, now: Long): CapsuleView.Shown {
        val identity = Triple(v.kind, v.runKey, v.pill)
        if (identity != shownIdentity) {
            shownIdentity = identity
            shownText = v.text
            shownAt = now
            epoch++
            return v.copy(widthEpoch = epoch)
        }
        if (v.text != shownText) {
            if (now - shownAt >= MIN_SENTENCE_MS) {
                shownText = v.text
                shownAt = now
            } else {
                later(shownAt + MIN_SENTENCE_MS)
            }
        }
        return v.copy(text = shownText ?: v.text, widthEpoch = epoch)
    }

    private fun later(at: Long) {
        nextChangeAt = nextChangeAt?.coerceAtMost(at) ?: at
    }

    private class Line(val kind: CapsuleKind, val text: String, val reason: String? = null)

    private fun describe(r: CapsuleRun): Line = when (val s = r.state) {
        is TaskState.WaitingTakeover -> when (r.stage) {
            Takeover.Stage.HandedOver -> Line(CapsuleKind.HandedOver, HANDED_OVER, reasonLabel(s.reason))
            Takeover.Stage.Waiting -> Line(
                CapsuleKind.NeedTakeover,
                tr(R.string.capsule_needs_you, reasonLabel(s.reason) ?: oneLine(s.message).ifEmpty { tr(R.string.capsule_handle_it) }),
            )
            // The short stretch after handing back while the loop has not advanced yet
            Takeover.Stage.None -> Line(CapsuleKind.Running, CONTINUING)
        }
        is TaskState.WaitingConfirmation -> Line(CapsuleKind.NeedConfirm, tr(R.string.capsule_needs_ok, ConfirmationCopy.actionLabel(s.request.action)))
        is TaskState.WaitingInput -> Line(CapsuleKind.NeedAnswer, tr(R.string.capsule_asks, oneLine(s.prompt)))
        is TaskState.Held -> if (s.reason.needsYou) Line(CapsuleKind.NeedStep, tr(R.string.capsule_needs_you, oneLine(s.reason.why).ifEmpty { tr(R.string.capsule_handle_it) }))
        else Line(CapsuleKind.Held, oneLine(s.reason.why).let { if (it.isEmpty()) tr(R.string.capsule_hold) else tr(R.string.capsule_hold_why, it) })
        is TaskState.Running -> Line(CapsuleKind.Running, runningText(s))
        else -> Line(CapsuleKind.Running, THINKING)
    }

    companion object {
        const val MIN_SENTENCE_MS = 1_500L
        /** How long it expands into a pill after finishing. */
        const val PILL_MS = 4_000L
        /** How long it stays in total after finishing (the expanded stretch plus the stretch as a single icon with a badge). */
        const val LINGER_MS = 10_000L
        val HANDED_OVER: String get() = tr(R.string.capsule_you_handle)
        val THINKING: String get() = tr(R.string.capsule_thinking)
        val PREPARING: String get() = tr(R.string.runtime_preparing)
        val CONTINUING: String get() = tr(R.string.capsule_continuing)
        val KEPT: String get() = tr(R.string.kept_capsule)

        /** What it is doing right now: when calling MCP, name the connector first; replay and batch execution show progress; otherwise prefer the text the model wrote with the call, then the intent of the call, and finally a fixed wording. */
        fun runningText(s: TaskState.Running): String {
            s.mcpConnector?.let { return StatusCopy.callingMcp(it) }
            s.progress?.let { return progressText(it) }
            val said = listOfNotNull(s.narration, s.reason).map(::oneLine).firstOrNull { it.isNotEmpty() }
            return said ?: if (s.step == 0) PREPARING else THINKING
        }

        fun progressText(p: RunProgress): String = when (p) {
            is RunProgress.Replay -> tr(R.string.capsule_replay, p.step, p.total)
            is RunProgress.Batch -> trPlural(R.plurals.capsule_batch, p.steps)
            is RunProgress.Task -> StatusCopy.progress(p)
            is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
        }

        /** Squash text written by the model onto a single line. */
        private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

        private fun TaskState.ended() = this is TaskState.Succeeded || this is TaskState.Failed || this == TaskState.Cancelled
    }
}

/**
 * Capsule opacity: a single icon that has not been touched for [IDLE_MS] fades to [BALL] (the ring and badge stay solid, managed by the UI);
 * touching it or a state change restores it immediately (the caller restarts the timer from that moment). It does not fade while expanded into a pill, while something needs you, or for the 'you handle it' bar.
 */
object CapsuleFade {
    const val IDLE_MS = 3_000L
    const val BALL = 0.4f

    /** [idleFor] is how long it has been since the last touch or state change. */
    fun alpha(kind: CapsuleKind, needCount: Int, pill: Boolean, idleFor: Long, idleAlpha: Float = BALL): Float = when {
        pill || kind.needsYou || needCount > 0 || kind == CapsuleKind.HandedOver -> 1f
        idleFor < IDLE_MS -> 1f
        else -> idleAlpha
    }
}
