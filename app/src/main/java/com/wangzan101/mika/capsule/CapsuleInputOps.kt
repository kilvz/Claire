package com.wangzan101.mika.capsule

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.screenThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_GONE_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_POLL_MS

// Capsule: input row, sending, and screen capture attachments
/** Input row tapped: only now does the overlay accept the keyboard, and the IME pops up pointing at it. */
internal fun CapsuleController.openInput() {
    if (inputUi.open) return
    cardWindow?.setFocusable(true)
    inputUi = inputUi.copy(open = true)
}

/** Collapse the input row and immediately return focus to the app below. */
internal fun CapsuleController.closeInput() {
    if (!inputUi.open) return
    inputUi = inputUi.copy(open = false)
    cardWindow?.setFocusable(false)
}

/** Back key: collapse the input row first, then dismiss the zoomed image, and finally collapse the card. */
internal fun CapsuleController.back() {
    when {
        inputUi.open -> closeInput()
        zoomed -> zoom(false)
        expanded -> collapse()
    }
}

/** Zoom in on or dismiss the image; while zoomed the card cannot be dragged (there is no title bar). */
internal fun CapsuleController.zoom(on: Boolean) {
    zoomed = on
    applyDrag()
}

/**
 * Send what is in the input row, following the same path as sending from the conversation screen (both are recorded in that conversation):
 * interject into a task that is running or waiting on a condition, answer when Claire asks you something, and continue in the same conversation once it is done;
 * when idle, continue the most recent conversation or start a new one; after the latter two, the card follows that conversation's task to show the answer.
 */
internal fun CapsuleController.send() {
    val app = context.applicationContext as? com.wangzan101.mika.MikaApp
    if (app != null && app.teachReady && app.teach.blocksTasks()) {
        android.widget.Toast.makeText(context, com.wangzan101.mika.i18n.tr(com.wangzan101.mika.R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val text = inputUi.draft.trim()
    val shot = inputUi.screen?.shot
    val c = card ?: return
    val kind = CapsuleCards.input(c)?.kind ?: return
    if (text.isEmpty() && shot == null) return
    val mode = inputUi.followUpMode
    inputUi = CardInputUi()
    cardWindow?.setFocusable(false)
    if (shot != null) deliver(c, kind, shot, text, mode) else submit(c, kind, text, CapsuleCards.conversationOf(c), mode)
    refresh()
}

/** Send [text] to whoever the input row currently addresses ([kind]); the utterance starts in [conversationId] (a new one is created when null). */
internal fun CapsuleController.submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?, mode: FollowUpMode = FollowUpMode.Steer) {
    val run = when (c) {
        is CardView.Detail -> c.run.run
        is CardView.Chat -> c.run?.run
        is CardView.Kept -> null
    }
    when (kind) {
        CardInputKind.Steer -> run?.let {
            if (mode == FollowUpMode.Queue) {
                TaskService.enqueueFollowUp(context, it.conversationId, UUID.randomUUID().toString(), text)
            } else {
                TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString())
            }
        }
        CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
        CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
        CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
    }
}

/**
 * Send the captured screen together with the accompanying words [words]: first save it into the conversation this utterance is headed for (a new conversation gets its id here),
 * then compose it into a message with the screen ([UserScreenMessage]) and send it as usual; if it cannot be saved, do not send.
 */
internal fun CapsuleController.deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String, mode: FollowUpMode = FollowUpMode.Steer) {
    val access = (context.applicationContext as MikaApp).appAccess.current()
    if (shot.sourcePackages.isEmpty() || shot.sourcePackages.any { !access.canShare(it) }) {
        inputUi = inputUi.copy(screen = null)
        android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
    scope.launch {
        val saved = runCatching { (context.applicationContext as MikaApp).userScreens.save(conversationId, shot) }
            .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
        val latest = (context.applicationContext as MikaApp).appAccess.current()
        if (shot.sourcePackages.any { !latest.canShare(it) }) {
            inputUi = inputUi.copy(screen = null)
            android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
            return@launch
        }
        submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId, mode)
        refresh()
    }
}

/**
 * Double tap or the screen reader's capture action: expand the card that a single tap would open, and attach the current screen to the preview of the existing input row.
 * Only capture and fill [PendingScreen] here: no saving into the conversation and no submission; after the user checks the preview and presses the existing send key, [send] calls [deliver].
 * When that card has no input row (it wants you to take over or to approve), there is nowhere to preview, so just expand like a single tap does.
 */
internal fun CapsuleController.previewScreen() {
    val v = view ?: return
    if (capturing) return
    scope.launch {
        if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) idle = loadIdle()
        val target = CapsuleCards.of(model.cardRuns(), follow, idle)
        val kind = target?.let(CapsuleCards::input)?.kind
        if (target == null || kind == null) {
            expand()
            return@launch
        }
        CapsuleCards.conversationOf(target)?.let { follow = it }
        open()
        refresh()
        val shot = captureScreen() ?: return@launch
        val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
        if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
    }
}

/** The capture button inside the card: attach the capture above the input row; adding a line is optional. */
internal fun CapsuleController.captureForCard() {
    if (capturing) return
    scope.launch {
        val shot = captureScreen() ?: return@launch
        val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
        if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
    }
}

/**
 * Capture what the user's screen shows right now: if the input row is open, collapse it first (wait for the IME to go away); hide the overlays, capture only after they are really off the screen, then bring them back immediately and flash the dot;
 * then tidy up the text on the screen in the background. Returns null when Claire itself is in the foreground and nothing can be captured.
 */
internal suspend fun CapsuleController.captureScreen(): CapturedScreen? {
    val service = windowOn ?: return null
    val w = window ?: return null
    val c = cardWindow ?: return null
    capturing = true
    try {
        if (inputUi.open) {
            closeInput()
            withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
        }
        // The empty window (the dot one while expanded, the card one while collapsed) draws nothing, so there is no need to wait for it
        suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
        val store = (context.applicationContext as MikaApp).appAccess
        val sources = MainScreenCapture.sourcePackages(service) ?: return null
        if (context.packageName in sources || !store.current().available) return null
        for (pkg in sources) {
            if (!store.current().canShare(pkg) && !com.wangzan101.mika.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))) return null
        }
        val capture = w.hidden { c.hidden {
            if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) null
            else MainScreenCapture.shoot(service)
        } } ?: return null
        val (shot, read) = capture
        if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return null
        }
        flash++
        wake()
        val area = service.overlays().area()
        val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
        if (sources.any { !store.current().canShare(it) } || MainScreenCapture.sourcePackages(service) != sources) return null
        val captured = (r as? MainScreenCapture.Result.Captured)?.shot ?: return null
        return CapturedScreen(captured.jpeg, captured.screen, sources)
    } finally {
        capturing = false
    }
}

/**
 * Start saying [text] in conversation [conversationId]; queue it if another task is running, do not drop it. The card keeps following this conversation;
 * the idle card also switches to continue this conversation (a newly created conversation's title is this utterance).
 */
internal fun CapsuleController.startIn(conversationId: String, text: String) {
    TaskService.start(context, text, conversationId)
    follow = conversationId
    idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
}
