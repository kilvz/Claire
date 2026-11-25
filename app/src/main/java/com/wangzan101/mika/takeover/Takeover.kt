package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device capabilities and Agent actions takeover needs; implemented by the task service for the current task. Task IDs are -1 when invalid. */
interface TakeoverPort {
    /** The topmost task on the Agent's virtual display. */
    suspend fun agentTopTask(): Int
    /** The task and package opened by launch_app. */
    suspend fun targetTask(): Int
    fun targetPackage(): String?
    suspend fun packageOf(taskId: Int): String?
    /** The display the task is on; -1 when the task is already gone (the page finished and closed itself). */
    suspend fun displayOf(taskId: Int): Int
    suspend fun moveToMain(taskId: Int): Boolean
    suspend fun moveBack(taskId: Int): Boolean
    /** 挪回目标 App。旧任务已消失时按同一份 App 恢复,恢复不了抛 [TargetAppRestoreFailed]。 */
    suspend fun restoreTarget(): Boolean
    /** 开或关该 App 的声音。 */
    fun setSound(packageName: String, on: Boolean)
    /** The user explicitly restores sound; implementations must update this task's auto-mute rules along with it. */
    fun unmute(packageName: String) = setSound(packageName, true)
    /** The user explicitly mutes again; implementations skip the auto-mute decision (explicit intent wins) and update this task's auto-mute rules along with it. */
    fun muteAgain(packageName: String) = setSound(packageName, false)
    /** Starts counting the user's actions on that package on the main screen; called when the app is handed to the user. */
    fun watchInput(packageName: String)
    /** The actions counted so far and the page they are on; null when not counting. */
    suspend fun handoverActivity(): HandoverActivity?
    fun stopWatching()
    /** Hands the takeover outcome back to the Agent loop. */
    suspend fun resume(result: HandBack)
    fun cancel()

    /**
     * When the current screen is a web page, brings the current tab (the same page) to the main screen for the user: true on success, false on failure; null when it is not a web screen, in which case the old path of moving the task is used.
     * [key] is the ID of this wait, which the buttons on the web takeover screen carry.
     */
    suspend fun showWeb(reason: String, key: Int): Boolean? = null

    /** Puts the web tab back into the background and returns the action count and page title from the takeover; when [loggedIn], records the current site as "logged in by the user". */
    suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? = null

    /** The web screen on the main screen is occupied at the moment (website login from the settings page is open): when [showWeb] fails, this is used to tell the user "finish that first" rather than "move failed". */
    fun webBusy(): Boolean = false
}

/**
 * The main screen hands over only one app to the user at a time: when several tasks request takeover at once they queue in order, and only the one at the front can say "I'll handle it".
 * The queue holds each task's [Takeover]; [changes] changes on every join and leave, and the task service repaints the notification based on it.
 * Those behind do not count against the wait timeout; timing only starts once they reach the front (see [Takeover.reachedFront]).
 */
class TakeoverQueue {
    private val waiting = mutableListOf<Takeover>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Synchronized fun join(owner: Takeover) {
        if (waiting.none { it === owner }) { waiting += owner; _changes.value++ }
    }

    fun leave(owner: Takeover) {
        val front = synchronized(this) {
            val wasFirst = waiting.firstOrNull() === owner
            if (!waiting.removeAll { it === owner }) return
            _changes.value++
            waiting.firstOrNull()?.takeIf { wasFirst }
        }
        front?.reachedFront()
    }

    /** Anything not in the queue (not waiting for takeover) also counts as first: it blocks nobody and is blocked by nobody. */
    @Synchronized fun isFirst(owner: Any): Boolean = waiting.indexOfFirst { it === owner } <= 0

    /** How many tasks ahead of [owner] are waiting for the user; -1 when it is not in the queue. */
    @Synchronized fun ahead(owner: Any): Int = waiting.indexOfFirst { it === owner }

    /** How many tasks behind [owner] are waiting for the user. */
    @Synchronized fun behind(owner: Any): Int {
        val i = waiting.indexOfFirst { it === owner }
        return if (i < 0) 0 else waiting.size - 1 - i
    }
}

/**
 * The takeover state machine: the Agent requests takeover → wait → "I'll handle it" moves the topmost task on the virtual display to the main screen →
 * "Done" / "I finished it myself" moves it back and hands back to the Agent; "Skip" hands back without handling it (if the app is on the main screen it is moved back first);
 * "Cancel task" is available at any time, and on cancel the app stays where it is.
 * If nobody taps "I'll handle it", the task and the scene are kept and a reminder fires every [TAKEOVER_WAIT_TIMEOUT_MS], never turning the wait into a failure;
 * once taken over, timing stops. Timing runs on [scope] and [now] measures the handover duration; tests substitute virtual time.
 * When [queue] is supplied, takeover queues with other tasks and those behind cannot say "I'll handle it".
 */
class Takeover(
    private val port: TakeoverPort,
    private val scope: CoroutineScope,
    private val queue: TakeoverQueue? = null,
    private val remind: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Stage { None, Waiting, HandedOver }
    enum class Problem { MoveToMainFailed, MoveBackFailed, WebBusy }

    private val _stage = MutableStateFlow(Stage.None)
    val stage: StateFlow<Stage> = _stage.asStateFlow()
    private val _problem = MutableStateFlow<Problem?>(null)
    /** The reason the most recent move failed; cleared after the next success. */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** The app is in the user's hands (main screen): its absence from the virtual display is intentional now and does not count as "taken by the user". */
    val handedOver: Boolean get() = _stage.value == Stage.HandedOver

    private val mutex = Mutex()
    /** The current wait and the one already handed back; the same state published again before the loop moves on must not re-enter waiting. */
    @Volatile private var current: TaskState.WaitingTakeover? = null
    @Volatile private var answered: TaskState.WaitingTakeover? = null
    /** Which wait this is; the notification buttons carry it, and a tap left over from a previous wait does not count. */
    @Volatile var waitKey = 0
        private set
    /** What was handed to the user is a web tab (not a task moved to the main screen). */
    private var handedWeb = false
    /** The task moved to the main screen and its package. */
    private var handed = -1
    private var handedPackage: String? = null
    private var handedAt = 0L
    private var timer: Job? = null

    fun onState(state: TaskState) {
        when (state) {
            is TaskState.WaitingTakeover -> if (state !== answered && _stage.value == Stage.None) {
                current = state
                queue?.join(this)
                waitKey++
                _problem.value = null
                _stage.value = Stage.Waiting
                // Queued behind another task's takeover, don't start timing yet: the user has not had a chance to look at it
                if (queue == null || queue.isFirst(this)) armTimer(state)
            }
            // While waiting for takeover the loop is parked there and publishes no other states; publishing another one means it moved on after the hand-back, or the task ended
            else -> {
                stopTimer()
                if (_stage.value == Stage.HandedOver) port.stopWatching()
                current = null
                answered = null
                handedWeb = false
                queue?.leave(this)
                handed = -1
                handedPackage = null
                _stage.value = Stage.None
                _problem.value = null
            }
        }
    }

    /** "I'll handle it". */
    suspend fun handOver(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.Waiting || !keyMatches(key)) return false
        if (queue != null && !queue.isFirst(this)) return false
        // Mark first, then move: a watcher polling for the move may see it the instant the move completes
        _stage.value = Stage.HandedOver
        // Web screen: bring the same tab to the front without moving the task or touching the sound
        when (port.showWeb(current?.reason ?: "other", waitKey)) {
            true -> {
                handedWeb = true
                stopTimer()
                handedAt = now()
                _problem.value = null
                return true
            }
            false -> {
                _stage.value = Stage.Waiting
                _problem.value = if (port.webBusy()) Problem.WebBusy else Problem.MoveToMainFailed
                return false
            }
            null -> Unit
        }
        // Move the topmost task on the virtual display: apps often open login and payment pages in another task
        val id = port.agentTopTask().takeIf { it >= 0 } ?: port.targetTask()
        if (id >= 0) {
            if (!port.moveToMain(id)) {
                _stage.value = Stage.Waiting
                _problem.value = Problem.MoveToMainFailed
                return false
            }
            handed = id
            handedPackage = port.packageOf(id) ?: port.targetPackage()
        }
        stopTimer()
        handedAt = now()
        _problem.value = null
        handedPackage?.let {
            port.setSound(it, true)
            port.watchInput(it)
        }
        true
    }

    /** "Done": the Agent reads the screen to verify before continuing. */
    suspend fun handBack(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete), key)

    /** "I finished it myself": the Agent stops operating, only verifies the result and ends. */
    suspend fun finishedMyself(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete, userFinished = true), key)

    /**
     * The overlay restoring sound only applies to apps actually handed to the user; waiting for takeover, a web takeover, or no target package all return false,
     * so the caller uses the regular phone audio path. Returning true means this takeover path handled it, not that the shell write necessarily succeeded.
     */
    @Synchronized
    fun unmute(): Boolean {
        if (_stage.value != Stage.HandedOver || handedWeb) return false
        val packageName = handedPackage ?: return false
        port.unmute(packageName)
        return true
    }

    /** The overlay mutes the sound again; conditions and routing are the same as [unmute]. */
    @Synchronized
    fun muteBack(): Boolean {
        if (_stage.value != Stage.HandedOver || handedWeb) return false
        val packageName = handedPackage ?: return false
        port.muteAgain(packageName)
        return true
    }

    /** The package handed to the user (not a web page); null in all other states. The overlay's sound toggle uses it to pick its key. */
    val handedOverPackage: String? get() = handedPackage.takeIf { _stage.value == Stage.HandedOver && !handedWeb }

    /** "Skip": don't handle it, and tell the Agent that no hand-back happened; if the app is on the main screen, move it back first, and leave it as is if the move fails. */
    suspend fun skip(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.skipped), key)

    /**
     * Pressing the system back key on the web takeover screen = "leave it for now": the tab goes back to the background and the task returns to waiting for takeover (the notification is still there, so "I'll handle it" can be tapped again),
     * and the wait starts reminding at the interval again. Only effective once already taken over.
     */
    suspend fun putAside(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.HandedOver || !handedWeb || !keyMatches(key)) return false
        port.returnWeb(loggedIn = false)
        handedWeb = false
        _stage.value = Stage.Waiting
        current?.let(::armTimer)
        true
    }

    /** "Cancel task": if the app is on the main screen it stays on the main screen instead of being pulled away from the user; a web tab goes back to the background (the task's tab is closed afterwards). */
    suspend fun cancel() = mutex.withLock {
        stopTimer()
        if (handedWeb) {
            port.returnWeb(loggedIn = false)
            handedWeb = false
        }
        if (_stage.value == Stage.HandedOver) port.stopWatching()
        handed = -1
        handedPackage = null
        _stage.value = Stage.None
        _problem.value = null
        queue?.leave(this)
        port.cancel()
    }

    /** A missing key (in-app button) is always accepted; a supplied one must match the current wait. */
    private fun keyMatches(key: Int?) = key == null || key == waitKey

    private suspend fun giveBack(result: HandBack, key: Int?): Boolean = mutex.withLock {
        if (!keyMatches(key)) false else giveBackLocked(result)
    }

    private suspend fun giveBackLocked(given: HandBack): Boolean {
        val from = _stage.value
        if (from == Stage.None) return false
        var result = given
        if (from == Stage.HandedOver && handedWeb) {
            // Handing back (done or finished myself) both count as the user having logged in on this site; skip does not
            val done = result.outcome == HandBackOutcome.complete
            val activity = port.returnWeb(loggedIn = done)
            if (done) result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt, url = activity?.url)
            handedWeb = false
        } else if (from == Stage.HandedOver) {
            // Which page they stopped on must be read before moving back: once moved back it is no longer on the main screen
            if (result.outcome == HandBackOutcome.complete) {
                val activity = port.handoverActivity()
                result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            }
            // 交出去的其他页面可能办完就关掉了;launch_app 的目标即使换了任务编号也要恢复
            val target = port.targetTask()
            if (handed >= 0 && handed != target && port.displayOf(handed) == MAIN_DISPLAY && !port.moveBack(handed)) {
                _problem.value = Problem.MoveBackFailed
                return false
            }
            try {
                if (target >= 0 && !port.restoreTarget()) {
                    _problem.value = Problem.MoveBackFailed
                    return false
                }
            } catch (e: TargetAppRestoreFailed) {
                result = result.copy(appReturnFailure = e.message)
            }
            port.stopWatching()
            setOfNotNull(handedPackage, port.targetPackage()).forEach { port.setSound(it, false) }
            handed = -1
            handedPackage = null
        }
        stopTimer()
        _problem.value = null
        _stage.value = Stage.None
        answered = current
        queue?.leave(this)
        port.resume(result)
        return true
    }

    /** The takeover ahead finished, so it is this one's turn: start reminding at the interval from now on. */
    internal fun reachedFront() {
        val waiting = current
        if (_stage.value == Stage.Waiting && waiting != null && timer == null) armTimer(waiting)
    }

    private fun armTimer(state: TaskState.WaitingTakeover) {
        timer?.cancel()
        timer = scope.launch {
            while (true) {
                delay(TAKEOVER_WAIT_TIMEOUT_MS)
                val waiting = mutex.withLock {
                    // No more reminders once it has been taken over, handed back, or replaced by the next wait.
                    _stage.value == Stage.Waiting && current === state
                }
                if (!waiting) break
                remind()
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        private const val MAIN_DISPLAY = 0
        /** Interval between repeated reminders while "I'll handle it" has not been tapped; there are no reminders after takeover, and the task never fails just from waiting. */
        const val TAKEOVER_WAIT_TIMEOUT_MS = 15 * 60_000L
    }
}
