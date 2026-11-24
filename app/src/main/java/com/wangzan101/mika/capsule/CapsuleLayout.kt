package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.view.Gravity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Rect
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.capsule.CapsuleController.Companion.EDGE_MARGIN_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.TOP_GAP_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.PILL_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.BAR_DP
import com.wangzan101.mika.capsule.CapsuleController.Companion.SNAP_MS

// Capsule: position calculation, dragging, and edge snapping
/** The area it can occupy; when [ime] is true it keeps clear of the IME ([at], defaulting to the current IME top). */
internal fun CapsuleController.area(ime: Boolean = true, at: Int? = imeTop): DockArea? = windowOn?.overlays()?.area()?.let {
    val room = room()
    DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
        .let { a -> if (ime) a.above(at, dp(TOP_GAP_DP), room) else a }
}

/** The area for the card: keeps clear of the tallest the IME has been this round, so the card does not change height or shift up and down when the pinyin candidate row appears or retracts. */
internal fun CapsuleController.cardArea(): DockArea? = area(at = cardImeTop)

internal fun CapsuleController.room() = dp(CapsuleShadowRoom.value.toInt())

internal fun CapsuleController.isBar() = view?.kind == CapsuleKind.HandedOver

/**
 * Place the windows at the recorded positions: the capsule sticks to the recorded side, the 'you handle it' bar sits at the top center, and the card is horizontally centered with its top set by the recorded fraction;
 * when it does not fit or the IME would cover it, move it up (and the card's maximum height shrinks accordingly). When the card's height changes (different card, changed content) the window follows and is placed again, with its top staying put.
 */
internal fun CapsuleController.place() {
    val w = window ?: return
    if (dragging || snap?.isRunning == true) return
    val a = area() ?: return
    placeCard()
    val h = w.height.coerceAtLeast(dp(PILL_DP))
    if (isBar()) {
        placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
        return
    }
    // While the card is expanded this window is empty, but it still parks at the edge so the dot reappears in place when collapsing
    val d = dock
    // The IME covers the original position: move both the capsule and the card above it, and return to the original position after collapsing
    val base = area(ime = false) ?: a
    val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
    placedY = CapsuleDocking.fitY(natural, a, h)
    val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
    val offset = if (edgeHidden) CapsuleEdge.offset(d.side, a, room()) else CapsuleDocking.edgeOffset(d.side, a)
    w.place(OverlayPlacement(Gravity.TOP or side, offset, placedY))
}

/**
 * Place the card window: horizontally centered, top set by the recorded fraction. The card's maximum height is decided by the screen and the IME; when the content changes it re-lays out and the window is placed again after being resized.
 * The IME is measured at its tallest in this round ([cardArea]): when the pinyin candidate row gains or loses a line while typing, the card neither changes height nor shifts up and down.
 * When it has just expanded and the window is still empty, place it using the height the card will have (fixed for a chat card; content-based for a task card, starting from the shortest), so it does not need to move once content arrives.
 */
internal fun CapsuleController.placeCard() {
    val w = cardWindow ?: return
    val c = card ?: return
    val a = cardArea() ?: return
    windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
    val base = area(ime = false) ?: a
    val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
    cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
    cardTop = cardY
    w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
}

/** The window height of the tallest card (the chat card) in the area [base] that does not avoid the IME, shadow edge included: the card's top fraction is computed from it. */
internal fun CapsuleController.cardFull(base: DockArea): Int =
    CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

/**
 * What can be dragged where: the dot and that bar are entirely draggable; the card can only be dragged by its title bar (vertical swipes in the middle scroll), and cannot be dragged while the image is zoomed.
 * The dot and capsule also support hold-to-talk (the bar cannot; nor can it when voice input is set to off, where holding still counts as a tap).
 */
internal fun CapsuleController.applyDrag() {
    window?.draggable(drag)
    val talkable = canTalk()
    // Talking is no longer allowed (for example the app was handed to you and the capsule turned into that bar): drop this segment and do not send, since the user never got a chance to release over a target
    if (!talkable && talk.active) talk.abort()
    window?.holdable(talkHold.takeIf { talkable }, CapsuleTalk.HOLD_MS)
    cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
}

/** The card reports the position of its title bar. */
internal fun CapsuleController.onHandle(r: Rect) {
    if (r == cardHandle) return
    cardHandle = r
    applyDrag()
}

/** Slide to the edge after release; with animations off, just place it there. */
internal fun CapsuleController.snapTo(fromX: Int, y: Int, toX: Int) {
    val w = window ?: return
    placedY = y
    if (animationsOff(context) || fromX == toX) {
        place()
        return
    }
    snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
        addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                snap = null
                place()
            }
        })
        start()
    }
}

/** Stand-in for dragging when using a screen reader: swap to the other side. */
internal fun CapsuleController.swapSide() {
    resetTaps()
    dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
    flags.setCapsuleDock(dock)
    place()
}
