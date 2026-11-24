package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import android.graphics.Rect
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.chat.ConversationStore
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayHold
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.takeover.TakeoverQueue
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.Exchange
import com.wangzan101.mika.chat.ui.ExchangeStatus
import com.wangzan101.mika.ui.ToolLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Wiring for the floating capsule: it hands each task's state ([TaskCenterState.runs] plus every [TaskRun]'s state, takeover stage and activities), the switches,
 * whether Claire's UI is in the foreground ([AppForeground]), whether the screen is on ([DeviceStateWatcher]) and whether the accessibility service exists to [CapsuleModel],
 * and, based on its result, adds, moves or tears down the overlay windows through [com.wangzan101.mika.cap.phone.a11y.OverlayHost].
 * Tapping the ball or the capsule expands the card ([CapsuleCards] decides the content and tells a single item); tapping outside the card, the collapse button or Back shrinks it back to a ball (what was seen is not expanded again, and the model remembers); the buttons on the card and the capsule all map to existing task-service actions.
 * The ball and the card each live in their own window and keep their own positions (see [window]): on expansion the ball's window is explicitly hidden and the card scales and fades in within its own window from the ball's direction,
 * and on collapse it shrinks back and the ball pops out at the edge again ([OverlayMotion]: only layers move; the window is not repositioned and its size is not changed per frame).
 * Double-tapping the ball or capsule (see [DoubleTap]: a single tap waits out the double-tap timeout before expanding, and the two taps must come from the same source and land close together) captures the user's current screen into that card's input-field preview,
 * without sending it directly; only after the user has seen the preview and pressed Send does it take the same path as sending a message from the card. The screenshot button to the left of the card's input field also attaches it above the input field, so an extra message can be added before sending.
 * While capturing, the overlay is hidden first and comes back afterwards, with a flash on the ball.
 * Only while the card's bottom input field is open can the overlay accept the keyboard; it is handed back immediately on send, collapse, tapping outside or Back; when the IME appears, the capsule and the card move above it.
 * Long-pressing the ball or capsule talks (see [CapsuleTalk], [CapsuleVoicePanel]): while held, the whole screen dims and a panel with two targets, "Cancel" and "Amend", floats up next to the capsule;
 * releasing sends straight to the card that a single tap would expand (same target lookup as sending a screen on double tap), dragging to "Amend" opens the card with the text in the input field, and dragging to "Cancel" discards it;
 * when sent, not heard clearly, or unavailable, a line flashes on the capsule ([note]). The panel lives in another window below the ball's window and does not take touches.
 * That item's screen is only captured while the card is expanded (at most [PREVIEW_FPS] frames per second; nothing is pushed when unchanged). Normally it is a single icon, and it expands into a capsule when there is something to say (the model decides);
 * it can be dragged, and on release it snaps to the nearest left or right edge; the resting position is recorded by edge and height ratio in [FeatureFlags], and re-placed by ratio after rotation.
 * The card is horizontally centered (a conversation card has a fixed height; a task card is sized to content, at most the same height), dragged up and down by its title bar, with the top ratio recorded separately, so switching cards leaves the top edge unchanged;
 * the conversation card's thread is read from [ConversationStore] (the same copy as the chat page),
 * and the apps operated are read from that turn's run record ([RunStore]).
 * If the overlay cannot be drawn (the service just disconnected, the system refuses), no further retries happen on this service instance; the capsule does not appear and notifications carry on as before. Everything runs on the main thread.
 */
class CapsuleController(
    internal val context: Context,
    internal val tasks: TaskCenterState,
    internal val foreground: AppForeground,
    /** The takeover queue shared by several tasks: the card and that bar read the ordering from it. */
    internal val takeoverQueue: TakeoverQueue,
) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val model = CapsuleModel(::reasonLabel)
    internal val flags = FeatureFlags.get(context)
    /** The page left running in the background after the task ended (with no task, the capsule talks about it and the card can show its screen and close it). */
    internal val keeper get() = (context.applicationContext as MikaApp).pageKeeper
    internal val toolLabels by lazy { ToolLabels(knownTools()) }
    internal val appLabels = HashMap<String, String>()
    /** How each task's current exchange looks right now, derived the same way as on the chat page (steps, what it needs from you, result). */
    internal val exchanges = HashMap<String, Exchange>()

    internal var view by mutableStateOf<CapsuleView.Shown?>(null)
    internal var card by mutableStateOf<CardView?>(null)
    /** The in-process queue; the card counts the items explicitly queued for its own conversation. */
    internal var followUps by mutableStateOf<List<com.wangzan101.mika.task.QueuedFollowUp>>(emptyList())
    internal var preview by mutableStateOf<Bitmap?>(null)
    internal var zoomed by mutableStateOf(false)
    internal var dock by mutableStateOf(flags.capsuleDock())
    internal var edgeHidden by mutableStateOf(false)
    internal var inputUi by mutableStateOf(CardInputUi())
    /** Incremented when a screen is captured: the ball flashes once. */
    internal var flash by mutableIntStateOf(0)
    internal val taps = DoubleTap(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    internal data class Touch(val at: Long, val x: Float, val y: Float, val source: Int)
    /** The most recent ACTION_DOWN of the capsule window; Compose's onClick carries no touch information, so it is filled in here. */
    internal var capsuleTouch: Touch? = null
    internal var tapJob: Job? = null

    /** Any interaction that is not an ordinary second tap cancels pending taps here, so a double tap cannot be assembled across actions. */
    internal fun resetTaps() {
        tapJob?.cancel()
        taps.reset()
        capsuleTouch = null
    }

    /** A capture is in progress (the overlay is hidden): taps and further captures are ignored during it. */
    internal var capturing = false
    /** The conversation where something was sent from the card: expansion follows its task so the answer can be seen. */
    internal var follow: String? = null
    /** The most recent conversation the idle card continues (read once when the idle icon is opened). */
    internal var idle: CardView.Chat? = null
    /** The power button in the card's title bar was tapped: the card switches to the "quit completely" confirmation instead of quitting right away. */
    internal var quitConfirm by mutableStateOf(false)

    /** Hold-to-talk; [talkIdle] is the most recent conversation read when talking starts from the idle icon (what is said is sent to it). */
    internal val talk = CapsuleTalk(context, { (context.applicationContext as MikaApp).voice }, scope, ::spoken)
    internal var talkIdle: Deferred<CardView.Chat>? = null
    /** A line briefly flashed on the capsule (sent, not heard clearly, unavailable); it disappears on its own. */
    internal var note by mutableStateOf<CapsuleNote?>(null)
    internal var noteJob: Job? = null
    internal var noteCount = 0

    /**
     * The conversation card's thread: all turns recorded for the conversation [threadOf] ([past], read from [ConversationStore] in the background, the same copy as the chat page; null while not yet read),
     * taking the last [shownTurns] turns and appending the tasks not yet recorded; [thread] is what is handed to the UI right now. [threadMark] is which tasks this conversation had and whether they had finished at the previous read,
     * so it re-reads when that changes (a task finishes or is removed, and only then is that turn recorded into the conversation). [placesOf] remembers, by run record ID, the apps and sites that turn operated.
     */
    internal var thread by mutableStateOf<ChatThread?>(null)
    internal var threadOf: String? = null
    internal var past: List<PastTurn>? = null
    /** The title already generated for this conversation (when absent, the card uses the first message). */
    internal var pastTitle: String? = null
    internal var shownTurns = CapsuleThreads.PAGE
    internal var threadMark: Any? = null
    internal var threadJob: Job? = null
    internal val placesOf = HashMap<String, List<String>>()
    internal val runStore by lazy { RunStore(context) }

    /** The card's maximum visible height (pixels, derived from the screen and the IME); 0 means it has not been placed yet. [cardFraction] is the card's top ratio, recorded in [FeatureFlags]. */
    internal var cardHeight by mutableIntStateOf(0)
    internal var cardFraction = flags.capsuleCardFraction()
    /** The card title bar's position within the window: only holding it drags the card. */
    internal var cardHandle: Rect? = null
    /** The top edge of the IME on the user's screen; null when it is not shown. Both the capsule and the card move out of its way. */
    internal var imeTop: Int? = null
    /**
     * The top edge used to place the card during this IME session: when the keyboard grows taller, follow at once; when it shrinks, ignore a one-row difference such as a candidate row or the toolbar
     * (see [CapsuleDocking.tallestImeTop]). When a Chinese IME gains or loses a row while typing pinyin, the card does not keep changing height and shifting up and down.
     * Cleared when the IME is hidden, and recorded afresh on the next pop-up.
     */
    internal var cardImeTop: Int? = null
    internal var imeJob: Job? = null

    /** The capsule's current opacity (it fades out a few seconds after the last touch); [lastActive] is when it was last touched or its state changed. */
    internal var alpha by mutableStateOf(1f)
    internal var lastActive = 0L
    internal var look: Any? = null
    internal var fadeJob: Job? = null

    /** The card is expanded; [closing] means it is shrinking back toward the capsule (it only really collapses once the animation finishes). */
    internal var expanded = false
    internal var closing by mutableStateOf(false)
    /** The card has really started to come out: only then does the ball give way (before the card is laid out and appears, the ball stays put so nothing looks empty); the ball comes back as soon as the card starts shrinking back. */
    internal var cardOut by mutableStateOf(false)
    /** The center of that ball before expansion (screen y) and the top edge of the card window: the card grows out of and shrinks back toward the ball's direction. */
    internal var anchorY = 0
    internal var cardTop by mutableIntStateOf(0)
    internal var previewKey: String? = null
    internal var previewJob: Job? = null

    /** The height ratio of the "You handle it" bar (remembered only for this run). */
    internal var barFraction = 0f
    internal var dragging = false
    internal var dragStartX = 0
    internal var dragStartY = 0
    internal var placedY = 0
    /** The top edge of the card window. */
    internal var cardY = 0
    internal var snap: ValueAnimator? = null

    /**
     * Two overlay windows, each staying in its own position, neither moving on expansion or collapse: [window] holds the ball, the capsule and that bar, parked at the edge; [cardWindow] holds the card,
     * centered horizontally (explicitly made invisible on collapse, after which it empties out and does not block the app below). If one window moved back and forth between the ball's and the card's positions, the system would turn the move into a sliding animation
     * (Android's stock window move animation), making it look like the ball sliding back from the middle to the edge and the card sliding from the edge to the middle.
     */
    internal var window: OverlayWindow? = null
    internal var cardWindow: OverlayWindow? = null
    /** The panel for hold-to-talk, below the ball's window (the dimming must not cover the capsule); normally empty with no size. If it cannot be added, hold-to-talk is unavailable. */
    internal var panelWindow: OverlayWindow? = null
    /** Which service instance the windows were added on; if the service changed, the old windows were already torn down with the old instance. */
    internal var windowOn: ScreenAccessibility? = null
    /** Adding windows has failed on this service instance: stop retrying and wait for the service to reconnect. */
    internal var failedOn: ScreenAccessibility? = null
    internal var tick: Job? = null
    internal val ticks = MutableStateFlow(0)
    internal var last: Snapshot? = null

    internal val _showing = MutableStateFlow(false)
    /** The capsule is really showing on a lit screen right now; the task service uses this to make "needs you" notifications quieter. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        val screen = DeviceStateWatcher(context) { -1 }
        // The user tapped "quit completely": treat it as the switch being off until the user opens Claire again (nothing reappears until [resume] clears the flag)
        val mode = combine(flags.capsuleModeChanges(), flags.quitChanges()) { m, quit -> if (quit) CapsuleMode.Off else m }
        val fullscreen = combine(flags.capsuleHideFullscreenChanges(), ScreenAccessibility.instance, mode) { enabled, service, m ->
            service.takeIf { enabled && m != CapsuleMode.Off }
        }.flatMapLatest { service -> service?.overlays()?.fullscreenChanges() ?: flowOf(false) }
        scope.launch {
            combine(
                combine(
                    runs(),
                    combine(
                        mode,
                        flags.capsuleIdleOpacityChanges(),
                        flags.capsuleHideAtEdgeChanges(),
                        flags.capsuleCardScaleChanges(),
                    ) { m, opacity, hideAtEdge, cardScale -> RenderSettings(m, opacity, hideAtEdge, cardScale) },
                    foreground.visible, screen.locked, ScreenAccessibility.instance,
                ) { r, settings, f, l, a ->
                    Snapshot(r, settings.mode, f, l, a,
                        opacity = settings.opacity, hideAtEdge = settings.hideAtEdge, cardScale = settings.cardScale)
                }.combine(fullscreen) { s, full -> s.copy(fullscreen = full) },
                ticks,
                keeper.page,
                // The number of items the proactive capability judged worth a notice and that are unseen (only the count is needed)
                com.wangzan101.mika.proactive.NoticeHintList.unseenCount(context),
            ) { s, _, kept, notices -> s.copy(kept = kept, notices = notices) }.collect(::render)
        }
        // The talk panel fills the whole screen when it appears (dimming what is behind) and is explicitly removed when it goes away (see [syncPanelWindow])
        scope.launch { snapshotFlow { talk.active }.collect { syncPanelWindow() } }
        // Queue line: the card shows how many items are left for its own conversation
        scope.launch { (context.applicationContext as MikaApp).followUpQueue.changes.collect { followUps = it } }
        // Once the post-stop summary has been written (or has failed to be), that turn has been merged into the conversation: re-read the recorded turns
        scope.launch { tasks.summarizing.collect { threadOf?.let(::loadPast) } }
        // A conversation was deleted: see [forget]
        scope.launch { ConversationStore.shared(context).deletions.collect(::forget) }
        // The title of this conversation on the card changed (the user renamed it in the sidebar, or the title finished generating in the background): the "Continue ‘…’" line at the top of the card follows
        scope.launch {
            val store = ConversationStore.shared(context)
            store.changes.collect { id ->
                if (id != threadOf) return@collect
                val title = withContext(Dispatchers.IO) { store.title(id) }
                if (id == threadOf && title != pastTitle) {
                    pastTitle = title
                    rebuildThread()
                }
            }
        }
        // Rotation: re-place by the recorded edge and ratio; the system may hand over the new size a little late, so place once more after a moment
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                resetTaps()
                // The screen size changed, so the IME position recorded last session no longer applies; record it again on the IME window's next change
                cardImeTop = null
                place()
                scope.launch {
                    delay(ROTATE_SETTLE_MS)
                    place()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    /** The user tapped "quit completely": tear down the overlay right away. The process may be restarted by the accessibility service, in which case the quit flag keeps it from drawing (see [start]). */
    fun stopNow() {
        quitConfirm = false
        hide()
    }

    /** The user opened Claire again: the quit flag has been cleared, so draw again from the current state (used when the process was not killed and comes back to the foreground). */
    fun resume() {
        quitConfirm = false
        refresh()
    }

    internal data class RenderSettings(
        val mode: CapsuleMode,
        val opacity: CapsuleIdleOpacity,
        val hideAtEdge: Boolean,
        val cardScale: CapsuleCardScale,
    )

    internal data class Snapshot(
        val runs: List<CapsuleRun>, val mode: CapsuleMode, val mikaInForeground: Boolean, val locked: Boolean,
        val service: ScreenAccessibility?,
        /** The page left running in the background after the task ended. */
        val kept: KeptPage? = null,
        val opacity: CapsuleIdleOpacity = CapsuleIdleOpacity.DEFAULT,
        val notices: Int = 0,
        /** The scale level of the expanded card; changes take effect immediately (see [CardOverlay]). */
        val cardScale: CapsuleCardScale = CapsuleCardScale.DEFAULT,
        val hideAtEdge: Boolean = false,
        val fullscreen: Boolean = false,
    )

    /**
     * How each task looks right now. After wrapping up, a task is removed from [TaskCenterState.runs]; by then its state is already terminal,
     * and it is still carried here (until the task list changes again) so the model can register "done" and pause for a while; once it has paused, listing it again does not count.
     * Ones removed before reaching a terminal state (the service was destroyed) simply do not count.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun runs(): Flow<List<CapsuleRun>> {
        var previous = emptyList<TaskRun>()
        return tasks.runs.flatMapLatest { current ->
            val gone = previous.filter { r -> current.none { it === r } && !r.state.value.isActive() }
            previous = current
            val all = current + gone
            exchanges.keys.retainAll(all.map { it.key }.toSet())
            if (all.isEmpty()) flowOf(emptyList())
            else combine(all.map { r ->
                // Operating a screen right now: it has its own virtual display, or is using the built-in browser (the card uses this to choose a task card or a conversation card)
                combine(r.state, r.takeoverStage, r.activities, r.displayId, r.webOpen) { s, st, acts, display, web ->
                    CapsuleRun(
                        r.key, r.conversationId, s, st, r.goal, exchange(r, s, acts),
                        runId = r.runId.value, onScreen = display >= 0 || web,
                    )
                }.combine(r.steers) { c, steers -> c.copy(steers = steers) }
            }) { it.toList() }.combine(takeoverQueue.changes) { list, _ ->
                // Ordering within the takeover queue: re-read when the queue changes (the item ahead handed back), so the next one becomes "needs you" immediately
                list.map { c ->
                    val owner = all.firstOrNull { it.key == c.key }?.takeover ?: return@map c
                    val ahead = takeoverQueue.ahead(owner)
                    if (ahead < 0) c else c.copy(queueAhead = ahead, queueBehind = takeoverQueue.behind(owner))
                }
            }
        }
    }

    internal fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    /** Collapse the card once a demonstration starts, so it does not cover the spot the user needs to point at. */
    fun yieldToTeaching() {
        if (expanded) collapse()
    }

    /** Drag the card up and down by its title bar (horizontally centered and unmoved); on release the top ratio is recorded, and later expansions, card switches and rotations place it by that. */
    internal val cardDrag = object : OverlayDrag {
        override fun start() {
            dragging = true
            dragStartY = cardY
        }

        override fun move(dx: Float, dy: Float) {
            val w = cardWindow ?: return
            val a = cardArea() ?: return
            if (!dragging) return
            val base = area(ime = false) ?: a
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, CardDocking.dragY(dragStartY, dy, base, a, cardFull(base), w.height)))
        }

        override fun end(dx: Float, dy: Float) {
            val w = cardWindow
            val a = cardArea()
            val was = dragging
            dragging = false
            if (!was || w == null || a == null) return
            val base = area(ime = false) ?: a
            val full = cardFull(base)
            cardFraction = CardDocking.release(CardDocking.dragY(dragStartY, dy, base, a, full, w.height), base, full)
            flags.setCapsuleCardFraction(cardFraction)
            place()
        }
    }

    /** Drag the capsule (horizontally and vertically) or the "You handle it" bar (vertically only); for the card see [cardDrag]. */
    internal val drag = object : OverlayDrag {
        override fun start() {
            val w = window ?: return
            val a = area() ?: return
            resetTaps()
            snap?.cancel()
            val wasHidden = edgeHidden
            wake()
            dragging = true
            // The revealed ball is 40dp plus its shadow room; its layout resize arrives next frame.
            dragStartX = CapsuleDocking.dockedX(dock.side, a, if (wasHidden) dp(PILL_DP) else w.width)
            dragStartY = placedY
        }

        override fun move(dx: Float, dy: Float) {
            val w = window ?: return
            val a = area() ?: return
            if (!dragging) return
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y))
            else w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width), y))
        }

        override fun end(dx: Float, dy: Float) {
            val w = window
            val a = area()
            if (!dragging || w == null || a == null) {
                dragging = false
                return
            }
            dragging = false
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) {
                barFraction = CapsuleDocking.fraction(y, a, dp(BAR_DP))
                place()
                return
            }
            val x = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            val base = area(ime = false) ?: a
            dock = CapsuleDocking.release(x, y, w.width, w.height, a, base)
            flags.setCapsuleDock(dock)
            wake()
            snapTo(x, y, CapsuleDocking.dockedX(dock.side, a, w.width))
        }
    }

    /** A long press delivered by the ball's window: start talking, finger movement picks the target, and release finishes according to the target. */
    internal val talkHold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) = beginTalk(byTouch = true)

        override fun move(rawX: Float, rawY: Float) {
            if (talk.byTouch) talk.move(rawX, rawY, dp(TARGET_SLOP_DP))
        }

        override fun end(rawX: Float, rawY: Float) {
            if (!talk.byTouch) return
            talk.finish(CapsuleVoice.drop(rawX, rawY, talk.cancelAt, talk.editAt, dp(TARGET_SLOP_DP)))
        }

        override fun cancel() {
            if (talk.byTouch) talk.abort()
        }
    }

    internal val actions = object : CardActions {
        override fun collapse() = this@CapsuleController.collapse()
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) {
            // Carries the ID of this wait just like "I'll handle it" on the notification: a tap on a wait that has already been replaced does not count
            TaskService.takeOver(context, waitKey = runFor(runKey)?.takeover?.waitKey, runKey = runKey)
            collapse()
        }
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            open(conversationId)
            collapse()
        }
        override fun zoom(on: Boolean) = this@CapsuleController.zoom(on)
        override fun openInput() = this@CapsuleController.openInput()
        override fun draft(text: String) {
            inputUi = inputUi.copy(draft = text)
        }
        override fun send() = this@CapsuleController.send()
        override fun freshChat(fresh: Boolean) = this@CapsuleController.freshChat(fresh)
        override fun loadEarlier() = this@CapsuleController.loadEarlier()
        override fun captureScreen() = captureForCard()
        override fun dropScreen() {
            inputUi = inputUi.copy(screen = null)
        }
        override fun setFollowUpMode(mode: FollowUpMode) {
            inputUi = inputUi.copy(followUpMode = mode)
        }
        override fun askQuit() {
            closeInput()
            // The confirmation card has no title bar of its own to drag, so do not use the previous card's position as the handle
            cardHandle = null
            applyDrag()
            quitConfirm = true
        }
        override fun cancelQuit() {
            quitConfirm = false
        }
        override fun quit() {
            com.wangzan101.mika.MikaQuit.quit(context)
        }
        override fun closeKeptPage() {
            keeper.close(KeptPageClose.User)
            collapse()
        }
        override fun teach() {
            val hint = inputUi.draft.trim().takeIf { it.isNotEmpty() }
            val intent = com.wangzan101.mika.task.TaskService.openAppIntent(context)
                .putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_TEACH, true)
            if (hint != null) intent.putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_TEACH_GOAL, hint)
            runCatching { context.startActivity(intent) }
            collapse()
        }
        override fun voiceChat() {
            val intent = com.wangzan101.mika.task.TaskService.openAppIntent(context)
                .putExtra(com.wangzan101.mika.ui.MainActivity.EXTRA_OPEN_VOICE_CALL, true)
            runCatching { context.startActivity(intent) }
            collapse()
        }
        override fun hideCapsule() {
            // Same place as choosing "off" in Settings › Floating capsule: when the switch changes, render tears down the capsule and the card according to CapsuleModel (hide), while tasks and notifications are unaffected
            flags.setCapsuleMode(CapsuleMode.Off)
            android.widget.Toast.makeText(context, CardCopy.hiddenHint(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    internal fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    internal companion object {
        const val TAG = "MikaCapsule"
        /** Distance of the visible capsule from the screen's left/right edges and from the status bar and the bottom. */
        const val EDGE_MARGIN_DP = 12
        const val TOP_GAP_DP = 8
        /** Window height of the capsule and of that bar including the shadow margin (used when placing the height by ratio). */
        const val PILL_DP = 40 + 16
        const val BAR_DP = 48 + 16
        const val SNAP_MS = 220L

        const val ROTATE_SETTLE_MS = 300L
        /**
         * The IME must shrink by this much (dp) at once to count as a different panel (emoji, handwriting and the like), letting the card restore itself; a pinyin candidate row or the toolbar
         * gaining or losing one row is nowhere near this height and is ignored, so the card does not keep resizing while typing (see [CapsuleDocking.tallestImeTop]).
         */
        const val IME_SHRINK_JUMP_DP = 120
        /** How far each of the two talk targets extends, so that a drag reaching it counts. */
        const val TARGET_SLOP_DP = 14
        /** How long the line flashed on the capsule stays: sent, not heard clearly, an error, or unavailable. */
        const val NOTE_SENT_MS = 1_500L
        const val NOTE_SHORT_MS = 2_000L
        const val NOTE_LONG_MS = 3_500L
        /** Before capturing, dismiss the IME from the card and wait at most this long for it to go down. */
        const val IME_GONE_MS = 700L
        const val IME_POLL_MS = 30L
    }
}

/** Whether the ball's and the card's windows should be visible right now; once the card has finished collapsing it must never be shown again, no matter which frame the animation stopped on. */
internal data class CapsuleWindowVisibility(val capsule: Boolean, val card: Boolean)

internal fun capsuleWindowVisibility(expanded: Boolean, cardOut: Boolean, closing: Boolean): CapsuleWindowVisibility =
    CapsuleWindowVisibility(capsule = !expanded || !cardOut || closing, card = expanded)

/** A line briefly flashed on the capsule: [kind] decides the leading marker (a check when done, an exclamation when failed); tapping it opens Claire when [opensMika]; [id] differs per line (the capsule re-measures its width by it). */
internal data class CapsuleNote(val text: String, val kind: CapsuleKind, val opensMika: Boolean, val id: Int)
