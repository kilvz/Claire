package com.wangzan101.mika.capsule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.TransformOrigin
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.delay

// Capsule: the UI for the card, the voice panel, and the capsule itself
/** Inside the card window: the card while expanded; while collapsed the window is explicitly hidden first and the content cleared afterwards. When the power button in the title bar has been tapped, it is replaced by the quit confirmation.
 * At non-standard levels the whole card is rendered with the [Density.zoomed] density: width, text, and spacing scale proportionally; the height cap is still converted from the real screen height (toDp in the Provider). */
@Composable
internal fun CapsuleController.CardOverlay() {
    val c = card ?: return
    MikaTheme(reduceMotion = animationsOff(context)) {
        val scale = last?.cardScale ?: CapsuleCardScale.DEFAULT
        CompositionLocalProvider(
            LocalDensity provides LocalDensity.current.zoomed(scale.factor),
            LocalCapsuleCardScale provides scale,
        ) {
            val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
            OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = {
                cardOut = true
                syncWindowVisibility()
            }) {
                if (quitConfirm) QuitCard(actions, height)
                else CapsuleCard(
                    c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle,
                    queuedCount = CapsuleCards.conversationOf(c)?.let { id ->
                        followUps.count { it.conversationId == id && !it.claimed && it.source == com.wangzan101.mika.task.QueuedSource.Explicit }
                    } ?: 0,
                    onQueued = { CapsuleCards.conversationOf(c)?.let(::open) },
                )
            }
        }
    }
}

/** Inside the panel window: the full-screen layer shown while holding to talk, anchored to the dot's current position; normally there is nothing. */
@Composable
internal fun CapsuleController.VoicePanelOverlay() {
    if (!talk.active) return
    val a = windowOn?.overlays()?.area()
    val top = placedY + room()
    val bottom = placedY + (window?.height ?: 0) - room()
    MikaTheme(reduceMotion = animationsOff(context)) {
        CapsuleVoicePanel(talk, top, bottom.coerceAtLeast(top), a?.insetTop ?: 0, a?.insetBottom ?: 0)
    }
}

/** In the side window: the dot, the capsule, or that bar; empty once the card appears, and when the card shrinks back the dot pops out of the edge at the same time. When a note is flashing on the capsule, that note is shown. */
@Composable
internal fun CapsuleController.CapsuleOverlay() {
    val shown = view ?: return
    if (card != null && cardOut && !closing) return
    val n = note
    val v = if (n == null || shown.kind == CapsuleKind.HandedOver) shown
        else shown.copy(kind = n.kind, text = n.text, detail = null, count = 0, needCount = 0, pill = true, widthEpoch = -n.id)
    if (edgeHidden) {
        MikaTheme(reduceMotion = animationsOff(context)) {
            CapsuleEdgeContent(dock.side, alpha) {
                resetTaps()
                wake()
                place()
            }
        }
        return
    }
    val talking = talk.active && !talk.released
    MikaTheme(reduceMotion = animationsOff(context)) {
        OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
            CapsuleContent(
                v,
                onTap = ::tapCapsule,
                onSendScreen = ::previewScreen,
                flash = flash,
                onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                side = dock.side,
                tapLabel = when {
                    n?.opensMika == true -> tr(R.string.capsule_voice_open)
                    v.kind == CapsuleKind.Idle -> CapsuleCopy.TALK
                    v.kind == CapsuleKind.Notice -> tr(R.string.notice_hint_open)
                    else -> CapsuleCopy.EXPAND
                },
                onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                alpha = alpha,
                holding = talking && talk.byTouch,
                onTalk = if (canTalk()) ::toggleTalk else null,
                talkLabel = tr(if (talking && !talk.byTouch) R.string.capsule_voice_send else R.string.capsule_voice_talk),
            )
        }
    }
}

/** Elapsed time, updated every second while shown; null when no elapsed time is displayed. */
@Composable
internal fun CapsuleController.elapsed(startedAt: Long?): String? {
    if (startedAt == null) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return ChatCopy.clock(now - startedAt)
}

internal fun CapsuleController.appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

internal fun CapsuleController.reasonLabel(reason: String): String? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    // 'Needs your decision' would repeat after 'Needs you: ', so use the text written by the model
    else -> null
}?.let(context::getString)
