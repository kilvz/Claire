package com.wangzan101.mika.capsule

import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.capsule.CapsuleController.Snapshot
import com.wangzan101.mika.capsule.CapsuleController.Touch
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG

// Capsule: render windows by state, keep visibility in sync, and reset
internal fun CapsuleController.render(s: Snapshot) {
    last = s
    val service = s.service
    if (windowOn != null && windowOn !== service) {
        talk.abort()
        window = null
        cardWindow = null
        panelWindow = null
        windowOn = null
    }
    val inputs = CapsuleInputs(
        s.runs, s.mode, s.mikaInForeground, screenInteractive = !s.locked,
        serviceAvailable = service != null && service !== failedOn, cardOpen = expanded, kept = s.kept, fullscreen = s.fullscreen,
        notices = s.notices,
    )
    val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
    schedule(model.nextChangeAt)
    if (shown == null || service == null) {
        val suppressed = s.mode == CapsuleMode.Off || service == null || service === failedOn ||
            s.fullscreen || s.mikaInForeground || s.locked
        hide(clearLastLook = s.mode == CapsuleMode.Off || !suppressed)
        return
    }
    // The app has been handed to you: the bar at the top replaces the card
    if (shown.kind == CapsuleKind.HandedOver) resetCard()
    view = shown
    card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle, s.kept) else null
    if (expanded && card == null) resetCard()
    updateThread(card as? CardView.Chat, s.runs)
    // Switched to content without an input row (e.g. it now needs your approval): collapse the input row and return focus to the app below
    if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
    val detail = card as? CardView.Detail
    val keptCard = card as? CardView.Kept
    if (keptCard != null) watchKept(keptCard.page.id)
    else watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
    val nowLook = CapsuleLook.of(shown)
    val previousLook = look
    val wakeOnShow = CapsuleEdge.shouldWake(
        hideAtEdge = s.hideAtEdge,
        visible = previousLook != null,
        previous = previousLook ?: lastLook,
        current = nowLook,
    )
    if (nowLook != previousLook) resetTaps()
    look = nowLook
    if (wakeOnShow) wake() else fade()
    val w = window ?: openWindows(service)
    if (w == null) {
        fadeJob?.cancel()
        look = null
        lastActive = 0L
        edgeHidden = false
        view = null
        card = null
        _showing.value = false
        return
    }
    lastLook = nowLook
    applyDrag()
    place()
    _showing.value = true
}

/** Open the two windows (the dot first, then the card, so the card is on top); if either fails, drop both and fall back to notifications. */
internal fun CapsuleController.openWindows(service: ScreenAccessibility): OverlayWindow? {
    val overlays = service.overlays()
    // Open the panel first so it sits under the dot: the dimmed layer during hold-to-talk does not cover the capsule
    val panel = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) { VoicePanelOverlay() }
    val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT), allowOffscreen = true) { CapsuleOverlay() }
    val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
    if (ball == null || cardW == null) {
        ball?.close()
        panel?.close()
        failedOn = service
        Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
        return null
    }
    window = ball
    cardWindow = cardW
    panelWindow = panel
    windowOn = service
    syncWindowVisibility()
    // Hide the newly opened panel window first: when not talking it should leave nothing on the screen
    syncPanelWindow()
    for (it in listOf(ball, cardW)) it.onResize(::place)
    ball.onTouch { e ->
        // Replacing the half-circle on ACTION_DOWN would turn the same touch into a full-ball click.
        if (!edgeHidden) wake()
        capsuleTouch = Touch(e.eventTime, e.rawX, e.rawY, e.source)
    }
    cardW.onTouch { wake() }
    cardW.onBack(::back)
    watchIme(service)
    return ball
}

internal fun CapsuleController.refresh() {
    last?.let(::render)
}

/**
 * Conversation [id] was deleted: its just-finished tasks are removed from the capsule and card immediately, and the card no longer continues it (if words had just been sent in the card;
 * if it was the one the idle card continues, it becomes the new-conversation card while expanded), and the expanded card is picked again per [CapsuleCards.of] (collapsing when nothing is left). Deleting a conversation first stops its tasks and
 * waits for them to wrap up (at most 15 seconds) before deleting the record, so at this point its tasks have usually finished already; for ones that fail to wrap up, or for words sent in the card after
 * wrapping up but before deleting the record (which starts a new task in this conversation), the turn saved afterwards recreates the conversation and the capsule shows it as usual.
 */
internal fun CapsuleController.forget(id: String) {
    model.forget(id)
    if (follow == id) follow = null
    if (idle?.conversationId == id) idle = if (expanded) CardView.Chat(null) else null
    refresh()
}

internal fun CapsuleController.hide(clearLastLook: Boolean = false) {
    resetTaps()
    snap?.cancel()
    fadeJob?.cancel()
    look = null
    if (clearLastLook) lastLook = null
    lastActive = 0L
    imeJob?.cancel()
    imeJob = null
    imeTop = null
    cardImeTop = null
    dragging = false
    tucking = false
    tuckJob?.cancel()
    tuckPending = false
    revealInstant = false
    edgeHidden = false
    talk.abort()
    talkIdle = null
    noteJob?.cancel()
    note = null
    window?.close()
    window = null
    cardWindow?.close()
    cardWindow = null
    panelWindow?.close()
    panelWindow = null
    windowOn = null
    view = null
    resetCard()
    card = null
    _showing.value = false
}

internal fun CapsuleController.resetCard() {
    expanded = false
    closing = false
    cardOut = false
    syncWindowVisibility()
    follow = null
    zoomed = false
    quitConfirm = false
    cardHandle = null
    closeInput()
    inputUi = inputUi.collapsed()
    watchPreview(null)
    updateThread(null, emptyList())
}

/** Explicitly show or hide when the two windows swap; empty content only handles layout and is not responsible for taking the system window's old frame off the screen. */
internal fun CapsuleController.syncWindowVisibility() {
    val visible = capsuleWindowVisibility(expanded, cardOut, closing)
    window?.setVisible(visible.capsule)
    cardWindow?.setVisible(visible.card)
}

/**
 * The voice panel window: it fills the whole screen (dimming what is behind) and only then becomes visible while talking; when it collapses it is explicitly made invisible first and then shrunk to no size.
 * Like the dot and the card, empty content and zero size must not be treated as already invisible: Compose may stop producing new frames once the content is cleared,
 * and the last 'converting…' frame would stay on the system canvas, making the panel look stuck on the screen.
 */
internal fun CapsuleController.syncPanelWindow() {
    val w = panelWindow ?: return
    if (talk.active) {
        w.setFullScreen(true)
        w.setVisible(true)
    } else {
        w.setVisible(false)
        w.setFullScreen(false)
    }
}
