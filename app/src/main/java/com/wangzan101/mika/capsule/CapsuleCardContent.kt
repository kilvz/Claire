package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.chat.ui.FollowUpMode
import androidx.compose.ui.graphics.ImageBitmap
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

internal val CardShape = RoundedCornerShape(22.dp)
internal val ScreenShape = RoundedCornerShape(14.dp)
internal val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
internal val ScreenWidth = 104.dp
internal val ZoomWidth = 300.dp
internal val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
internal val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
internal val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
internal val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
internal val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** Fixed strings on the card (buttons, links, screen-reader descriptions). */
object CardCopy {
    val STOP: String get() = tr(R.string.chat_stop)
    val TAKE_OVER: String get() = tr(R.string.action_take_over)
    val RESUME: String get() = tr(R.string.chat_continue)
    val APPROVE_ONCE_ONLY: String get() = tr(R.string.card_approve_once)
    val DECLINE: String get() = tr(R.string.action_decline)
    val SEE_PROCESS: String get() = tr(R.string.card_see_process)
    val BACK_TO_MIKA: String get() = tr(R.string.card_back_to_mika)
    val SEND: String get() = tr(R.string.chat_send)
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
    val COLLAPSE: String get() = tr(R.string.collapse)
    val SCREEN: String get() = tr(R.string.card_screen)
    val SCREEN_LABEL: String get() = tr(R.string.card_screen_label)
    val ZOOMED: String get() = tr(R.string.card_zoomed)
    val ZOOM_HINT: String get() = tr(R.string.card_zoom_hint)
    val HIDE_CAPSULE: String get() = tr(R.string.capsule_hide)

    /** Hint after the capsule is turned off: where to turn it back on ("Settings › Floating capsule", named the same as in settings). */
    fun hiddenHint(): String = tr(R.string.capsule_hidden_hint, tr(R.string.settings_title), tr(R.string.capsule_settings_title))
}

/** The buttons on the card; all map to existing actions of the task service (see [CapsuleController]). [runKey] is the identifier of the item at the time it is pressed. */
interface CardActions {
    fun collapse()
    fun stop(runKey: String)
    fun takeOver(runKey: String)
    fun resume(runKey: String)
    fun answer(runKey: String, text: String)
    fun approve(runKey: String, requestKey: Int)
    fun decline(runKey: String, requestKey: Int)
    fun openMika(conversationId: String)
    fun zoom(on: Boolean)
    /** The bottom input field was tapped: only then does the overlay switch to accepting the keyboard. */
    fun openInput()
    fun draft(text: String)
    /** Send what is in the input field (see [CapsuleCards.input] for who receives it). */
    fun send()
    /** Idle card: [fresh] true starts a new conversation, false continues the most recent conversation. */
    fun freshChat(fresh: Boolean)
    /** The conversation card was scrolled to the top: load a few more rounds of history. */
    fun loadEarlier()
    /** The screenshot button to the left of the input field: hide the card first, capture the user's current screen, and attach it above the input field. */
    fun captureScreen()
    /** The × on the attachment strip: discard this screenshot. */
    fun dropScreen()
    /** The "Close page" button on the card of the page left running in the background. */
    fun closeKeptPage() {}
    /** The "Turn off floating capsule" at the very bottom of the card: the switch in settings is turned off and the capsule and card disappear on the spot (tasks keep running and notifications keep coming). */
    fun hideCapsule() {}
    /** "Teach Claire" on the idle card. */
    fun teach() {}
    /** "Voice chat" on the idle card: opens Claire directly on the call page. */
    fun voiceChat() {}
    /** Switches between "steer / queue" inside the capsule card. */
    fun setFollowUpMode(mode: FollowUpMode) {}
    /** The power button in the title bar: replaces the card with the "quit completely" confirmation instead of quitting right away. */
    fun askQuit() {}
    /** "Cancel" in the quit confirmation: returns to the previous card. */
    fun cancelQuit() {}
    /** "Quit" in the quit confirmation: actually quits Claire completely. */
    fun quit() {}
}

/** A captured screen attached above the input field waiting to be sent: [thumb] is the thumbnail (absent for pages that do not allow screenshots). */
data class PendingScreen(val shot: CapturedScreen, val thumb: ImageBitmap?)

/**
 * How the card's bottom input field looks right now: with [open] it is a typeable field (the overlay can accept the keyboard), otherwise it is a single hint line that opens only when tapped;
 * [screen] is the captured screen attached above it (sent along with the next message, or on its own).
 */
data class CardInputUi(val open: Boolean = false, val draft: String = "", val screen: PendingScreen? = null,
    /** Who receives this message while a task is running; returns to steer after the card collapses or the message is sent. */
    val followUpMode: FollowUpMode = FollowUpMode.Steer) {
    /** Text has been typed or a screen is attached: it can be sent. */
    val canSend: Boolean get() = draft.isNotBlank() || screen != null

    /** When the card collapses or the capsule is hidden: the input field collapses, the captured screen is dropped, unsent text is kept so it can be edited again on the next expansion. */
    fun collapsed(): CardInputUi = CardInputUi(draft = draft)

    /** Append the spoken text after the unsent text (the same way the chat page's input field does). */
    fun withSpoken(text: String): CardInputUi = copy(draft = HoldToTalk.join(draft, text))
}

/**
 * The card that opens when the capsule is tapped; it holds no state and tells a single item (see [CapsuleCards.of] for how it is picked).
 * On a task card ([CardView.Detail]) the virtual-screen small view [preview] is on the left (present only when that item has a screen), and the right side holds the steps, what it needs from you and the buttons, according to state;
 * a conversation card ([CardView.Chat]) shows that conversation's back-and-forth [thread] (null while still loading, left blank in the meantime).
 * A conversation card is a fixed [height] tall (the visible card, excluding the shadow margins; when unspecified, [CardDocking.SHARE] of the screen height) and does not change with content; a task card
 * is sized to content, at most [height], with no blank space below — its middle section takes whichever is taller, the small screen (by the screen's aspect ratio) or the text on the right. Working steps are at most as tall as the
 * small screen (with no screen yet the same height is derived from the main screen's aspect ratio, so the card does not jump when the screen arrives), and no matter how many steps there are the card does not grow taller: the newest step is at the bottom and older ones are scrolled to above; other content that does not fit scrolls inside,
 * while the buttons you tap and the input field are pinned below. When the height changes (switching cards, content changing) it snaps into place: the overlay window follows the content size, changing only once rather than per frame; the top edge does not move (see [CardDocking]).
 * The expand/collapse animation is wrapped around the outside ([OverlayMotion]).
 * [onHandle] reports the title bar's position within the window (pixels): holding it drags the card.
 * When [zoomed], the whole card is replaced by the enlarged screen, and a tap returns. [elapsed] turns a start time into the elapsed time (updated every second).
 */
@Composable
fun CapsuleCard(
    card: CardView,
    preview: Bitmap?,
    zoomed: Boolean,
    actions: CardActions,
    elapsed: @Composable (Long?) -> String?,
    modifier: Modifier = Modifier,
    input: CardInputUi = CardInputUi(),
    thread: ChatThread? = null,
    height: Dp = Dp.Unspecified,
    onHandle: (Rect) -> Unit = {},
    /** How many items are explicitly queued for this conversation and not started yet; the card shows one line, and tapping it opens the chat page to manage them. */
    queuedCount: Int = 0,
    onQueued: (() -> Unit)? = null,
) {
    val field = CapsuleCards.input(card)
    // While the task is still going (working, Claire asking you, holding), stop sits to the right of the input field just as on the chat page, instead of taking a separate button
    val stopKey = CapsuleCards.stopKey(card)
    val h = if (height == Dp.Unspecified) (LocalConfiguration.current.screenHeightDp * CardDocking.SHARE).dp else height
    val report by rememberUpdatedState(onHandle)
    val handle = Modifier.testTag("capsule-header").onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        report(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
    val inputRow: @Composable () -> Unit = {
        field?.let { InputRow(it, input, actions, stop = stopKey?.let { k -> { actions.stop(k) } }) }
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            zoomed && preview != null -> Zoomed(preview, actions)
            card is CardView.Detail -> Detail(card, preview, actions, elapsed, h, handle, inputRow, queuedCount, onQueued)
            card is CardView.Chat -> ChatCard(card, thread, actions, elapsed, h, handle, inputRow, queuedCount, onQueued)
            card is CardView.Kept -> KeptCard(card, preview, actions, h, handle)
        }
    }
}

/**
 * Appearance and disappearance animation for the content inside the overlay (the card expanding and collapsing, shrinking back to a dot at the edge when collapsed): the overlay window is at its final size and position immediately, and the content only grows out of [origin] via layer
 * scale and alpha (the direction is given for the current size, e.g. the card coming out of the capsule), calling [onOpened] when it actually starts appearing;
 * when [open] becomes false it shrinks back toward that point and fades out, then calls [onClosed].
 * Only layers move, the layout does not change, so the window is not resized per frame. With animations turned off it snaps into place, and [onClosed] is called immediately on collapse.
 * Neither expanding nor collapsing can wait on an endless stream of frames: if the overlay receives no further frame, it times out into place (see [runOverlayOpen], [runOverlayClose]) instead of leaving the card half open.
 */
@Composable
fun OverlayMotion(
    open: Boolean, origin: (Size) -> TransformOrigin, onClosed: () -> Unit, onOpened: () -> Unit = {},
    /** 这一下直接到位、不动效:从贴边半圆出来的一颗要跟手,等它淡入、放大看着像卡住。 */
    instant: Boolean = false,
    content: @Composable () -> Unit,
) {
    val still = MikaTheme.reduceMotion || instant
    val shown = remember { Animatable(if (still) 1f else 0f) }
    val closed by rememberUpdatedState(onClosed)
    val opened by rememberUpdatedState(onOpened)
    val from by rememberUpdatedState(origin)
    LaunchedEffect(open, still) {
        if (open) {
            if (still) {
                shown.snapTo(1f)
                opened()
            } else {
                var openedCalled = false
                var finished = false
                runOverlayOpen(still) {
                    // The first frame after appearing is the slowest: layout happens and the window changes size. Wait until it has been drawn before starting, so the first frames of the animation are not swallowed by it
                    if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }
                    openedCalled = true
                    opened()
                    shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate))
                    finished = true
                }
                // If frames stop (the overlay receives no further frame) the code above cannot finish: snap the value into place and fill in the callbacks, so the card is not left at a half-open scale and the window state does not stay stuck in opening (#323)
                if (!finished) shown.snapTo(1f)
                if (!openedCalled) opened()
            }
        } else {
            runOverlayClose(still) {
                shown.animateTo(0f, tween(CARD_OUT_MS, easing = FastOutLinearInEasing))
                // Let the fully invisible frame be drawn first, then swap content and move the window, so no frame shows a translucent card
                withFrameNanos { }
            }
            // Even if animation frames stop, an intermediate frame must not stay on screen forever; the finishing callback swaps the content and restores the window state.
            if (!still) shown.snapTo(0f)
            closed()
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shown.value
            val scale = CARD_FROM_SCALE + (1f - CARD_FROM_SCALE) * v
            scaleX = scale
            scaleY = scale
            // Alpha advances faster than scale: it is legible as soon as it appears, and on collapse it finishes fading last
            alpha = (v * 2f).coerceAtMost(1f)
            transformOrigin = from(size)
        },
    ) { content() }
}

/**
 * Wait for the collapse animation and the final frame, but the overlay must also finish when no further frames arrive. The wait follows the system animation speed;
 * extremely high slow-motion scales are capped at ten times, so the window state does not stay in closing indefinitely.
 */
internal suspend fun runOverlayClose(reduceMotion: Boolean, animate: suspend () -> Unit) {
    if (reduceMotion) return
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor
        ?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    withTimeoutOrNull((CARD_OUT_MS * scale).toLong() + CLOSE_GRACE_MS) { animate() }
}

/**
 * Wait for the expand animation to complete, but the overlay must also land when no further frames arrive: same shape as collapse ([runOverlayClose]); after the frame stall times out, the caller
 * snaps the value into place directly and fills in the expand callback. The wait follows the system animation speed; extremely high slow-motion scales are capped at ten times, so the time stuck at an intermediate scale is not unbounded.
 */
internal suspend fun runOverlayOpen(reduceMotion: Boolean, animate: suspend () -> Unit) {
    if (reduceMotion) return
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor
        ?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    withTimeoutOrNull((CARD_IN_MS * scale).toLong() + OPEN_GRACE_MS) { animate() }
}

private const val CARD_IN_MS = 240
private const val CARD_OUT_MS = 180
private const val CLOSE_GRACE_MS = 500L
private const val OPEN_GRACE_MS = 500L
private const val CARD_FROM_SCALE = 0.6f
private const val SETTLE_FRAMES = 2

/** The card's current scale level: installed by [CapsuleController] from the settings flow (the scaling itself works by swapping [LocalDensity], see [Density.zoomed]). */
val LocalCapsuleCardScale = staticCompositionLocalOf { CapsuleCardScale.DEFAULT }

/** Card scaling: dp and sp are scaled up uniformly (which component takes effect is determined by measurements in CapsuleCardScaleDensityTest). */
internal fun Density.zoomed(factor: Float): Density = Density(density * factor, fontScale * factor)

@Composable
internal fun cardWidth() = LocalCapsuleCardScale.current.width(LocalConfiguration.current.screenWidthDp)
