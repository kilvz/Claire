package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ui.PipPhysics.Corner
import com.wangzan101.mika.cap.phone.display.PipInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.components.floatShadow
import androidx.compose.ui.unit.Dp

/** Small-window width: collapsed / expanded. The height follows the aspect ratio of the frame (of the main screen when there is no frame yet), so the whole screen is shown without cropping the bottom. */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** Height/width ratio of the main screen in real pixels, including system bars: the virtual screen is created at the main screen's size, and until a frame exists the small window sizes itself by it. */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Floating picture-in-picture: overlays the conversation and only shows the live frame of the Agent's virtual screen; view only, no tapping. It carries no text or buttons of its own — everything you need to do lives in the conversation cards.
 * State is conveyed only by the border glow: a thin green edge while working, an orange breathing halo when it needs you (expanded automatically at the start of a takeover, by [ChatViewModel]).
 * At rest it sits in the bottom-right corner above the composer, clear of the top bar and the latest messages.
 * Dragging follows the finger 1:1; on release the landing point is projected by velocity and snapped to the nearest corner (critically damped spring, see [PipPhysics]);
 * a tap expands it, tapping × or outside collapses it; when it needs you, a tap hands over to [onAttention] (collapse and scroll to that card).
 * While expanded, the top-left "turn off the small picture" ([onClose]): until this task ends only a small button in the bottom-right corner remains ([PipUi.closed]),
 * tapping it opens the picture again ([onReopen]); when you are needed the small button turns orange and breathes, and tapping it likewise hands over to [onAttention].
 * With [reduceMotion] (system animations off) there is no spring, scaling or breathing — it snaps into place.
 * [topInset]/[bottomInset] are the pixel heights occupied by the top bar and the composer; the small window never comes to rest beneath them.
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    /** Hook for operating directly inside the small window (taps/swipes/back injected into the virtual screen); null while the screen is not ready or once the task has ended. */
    pipInput: PipInput? = null,
    /** The target app's sound was restored by the user: the button shows "Mute" and pressing it again mutes it; otherwise it shows "Unmute". */
    soundOn: Boolean = false,
    /** The user enabled "let me operate directly in the small window" in settings. */
    operateEnabled: Boolean = false,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit = {},
    onReopen: () -> Unit = {},
    onToggleSound: () -> Unit = {},
    /** The name of the task shown in the bottom-left corner (identifies which one when the front desk is handling several at once); omitted when null. */
    label: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        // Closed: only the small button in the bottom-right corner remains (not kept during the few seconds after the task finishes)
        AnimatedVisibility(
            pip.visible && pip.closed && pip.mode != PipMode.Done, Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) {
            val bottom = with(LocalDensity.current) { bottomInset.toDp() }
            ClosedPip(pip.mode == PipMode.NeedsYou, reduceMotion, Modifier.padding(end = 12.dp, bottom = bottom + 12.dp), onReopen, onAttention)
        }
        AnimatedVisibility(pip.visible && !pip.closed && pip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(MikaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(pip.visible && !pip.closed, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                pip, frame, webContent, pipInput, soundOn, operateEnabled, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
                onClose = onClose,
                onToggleSound = onToggleSound,
                label = label,
            )
        }
    }
}

/**
 * The small button left once the small picture is closed: normally it reads "Show picture" and opens it again on tap; when you are needed it breathes orange and reads "Waiting for you",
 * and tapping it scrolls to the card that needs you (nothing that needs you is missed even while the small picture is closed).
 */
@Composable
private fun ClosedPip(needsYou: Boolean, reduceMotion: Boolean, modifier: Modifier, onReopen: () -> Unit, onAttention: () -> Unit) {
    val c = MikaTheme.colors
    val shape = MikaShapes.Pill
    val breath = if (needsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "closedBreathe").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "closedBreathe",
        ).value
    } else 1f
    val label = stringResource(if (needsYou) R.string.pip_closed_needs_you else R.string.pip_reopen)
    Row(
        modifier.floatShadow(c, shape).background(c.glass, shape)
            .border(if (needsYou) 2.dp else 0.5.dp, if (needsYou) c.warnSolid.copy(alpha = breath) else c.glassEdge, shape)
            .clip(shape)
            .clickable(onClickLabel = label, role = Role.Button) { if (needsYou) onAttention() else onReopen() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pip-closed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (needsYou) Box(Modifier.size(7.dp).alpha(breath).background(c.warnSolid, CircleShape))
        else Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink2)
        Text(label, style = MikaType.Hint, color = if (needsYou) c.warn else c.ink2)
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    pipInput: PipInput?,
    soundOn: Boolean,
    operateEnabled: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit,
    onToggleSound: () -> Unit,
    label: String?,
) {
    val density = LocalDensity.current
    var expandedScale by rememberSaveable { mutableStateOf(1f) }
    var pinching by remember { mutableStateOf(false) }
    val sizeSpec = if (reduceMotion || pinching) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // The size follows the frame's aspect ratio: phone games and desktop web pages can both be landscape, so the height is derived from a fixed width,
    // and the whole frame fits; when it does not fit, everything shrinks proportionally
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeFrame = frame != null && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeFrame -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val inset = if (pip.expanded) 8.dp else 6.dp
        val requested = base * if (pip.expanded) PipSizing.scale(expandedScale) else 1f
        val fitted = with(density) { PipSizing.fit(requested.toPx(), aspect, availableWidth.toPx(), availableHeight.toPx(), inset.toPx()) }
        with(density) { fitted.width.toDp() to fitted.height.toDp() }
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    // The collapsed small window keeps clear of the top bar and the composer (so it never blocks typing or the top bar's buttons); when expanded the whole backdrop is already dimmed and they are unreachable anyway,
    // so the window moves to the full screen and only avoids the system bars, instead of shifting around inside a small central area. During expand/collapse the same duration interpolates it, so it moves along with the size without jumping.
    val collapsedShare by animateFloatAsState(
        if (pip.expanded) 0f else 1f,
        if (reduceMotion) snap() else tween<Float>(350, easing = Ease),
        label = "pipInset",
    )
    val (restTop, restBottom) = PipPhysics.restingInsets(
        collapsedTop = topInset,
        collapsedBottom = bottomInset,
        safeTop = WindowInsets.statusBars.getTop(density).toFloat(),
        safeBottom = WindowInsets.navigationBars.getBottom(density).toFloat(),
        collapsedShare = collapsedShare,
    )
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, restTop, restBottom)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // Position during dragging or springing; null means it is attached to the corner (and stays attached while expanding/collapsing)
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** Spring from [start] to corner [c] with velocity (vx, vy); snaps into place immediately when animations are off. */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // On completion, if it rests in either bottom corner, move it to the top corner on the same side first so it does not cover the result card that just appeared
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = RoundedCornerShape(if (pip.expanded) MikaRadius.Sheet else MikaRadius.Card)
    val inner = RoundedCornerShape(if (pip.expanded) MikaRadius.Bubble else MikaRadius.Chip)
    val insetDp = if (pip.expanded) 4.dp else 3.dp
    // Direct operation: inject taps/swipes into the virtual screen only when the setting is on, the window is expanded, a frame exists and the hook is still alive
    val operate = operateEnabled && pip.expanded && frame != null && pipInput != null
    val operateInput = pipInput.takeIf { operate }
    val breath = if (pip.mode == PipMode.NeedsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou) {
            // Orange halo: one ring larger than the window, blurred so it bleeds past the edge (no blur below Android 12, just a pale orange ring)
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp).alpha(breath)
                    .blur(10.dp).background(MikaTheme.colors.warnSolid.copy(alpha = 0.55f), outer),
            )
        }
        Box(
            Modifier.testTag("pip-window").size(w, h)
                .alpha(if (pip.mode == PipMode.Done) 0.85f else 1f)
                .floatShadow(MikaTheme.colors, outer)
                .background(MikaTheme.colors.glass, outer)
                .border(0.5.dp, MikaTheme.colors.glassEdge, outer)
                .then(if (operate) Modifier else Modifier.pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // Accumulate displacement manually instead of using detectDragGestures: when the touch slop is crossed, the movement within the slop is added too,
                    // so the window always keeps the grab offset from the moment of the press — truly 1:1 with the finger
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        var resized = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (expanded && event.changes.count { it.pressed } >= 2) {
                                    resized = true
                                    pinching = true
                                    settle?.cancel()
                                    free = null
                                    expandedScale = PipSizing.scale(expandedScale * event.calculateZoom())
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                // Once a pinch starts, no remaining finger can become a drag or tap.
                                if (resized) {
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                total += change.positionChange()
                                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    settle?.cancel()
                                }
                                if (dragging) {
                                    change.consume()
                                    free = origin + total
                                    tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                                }
                            }
                        } finally {
                            pinching = false
                        }
                        if (dragging && !resized) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                })
                .then(if (operate) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { if (needsYou) attend() else if (!expanded) expand() } })
                .padding(insetDp),
        ) {
            // The frame fills the whole inner box by aspect ratio; in direct-operation mode taps/swipes are normalized to the inner box and converted into virtual-screen coordinates before injection
            Box(
                Modifier.fillMaxSize().clip(inner).background(MikaTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    val fit = fittedSize(frame.width, frame.height, w - insetDp * 2, h - insetDp * 2)
                    Box(
                        Modifier.size(fit.first, fit.second).testTag("pip-frame")
                            .then(if (operateInput != null) operateInputModifier(operateInput, scope) else Modifier),
                    ) {
                        Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize(), alignment = Alignment.Center,
                            contentScale = ContentScale.Fit)
                    }
                }
            }
            if (label != null) {
                Text(
                    label,
                    Modifier.align(Alignment.BottomStart).padding(6.dp).widthIn(max = if (pip.expanded) w * 0.5f else w - 12.dp)
                        .background(MikaTheme.colors.glass, MikaShapes.Pill).padding(horizontal = 7.dp, vertical = 1.dp)
                        .testTag("pip-label"),
                    style = MikaType.Micro, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (pip.expanded) {
                Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PipSizeButton("−", stringResource(R.string.pip_size_smaller), "pip-size-smaller") {
                        expandedScale = PipSizing.scale(expandedScale / 1.2f)
                    }
                    PipSizeButton("+", stringResource(R.string.pip_size_larger), "pip-size-larger") {
                        expandedScale = PipSizing.scale(expandedScale * 1.2f)
                    }
                    PipSizeButton("↺", stringResource(R.string.pip_size_reset), "pip-size-reset") { expandedScale = 1f }
                }
                // Top-left: back (in direct-operation mode), sound toggle (restore/mute again, its direction following the real audio record of the task), close the small picture; never fake a toggle state unrelated to the real audio
                Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (operateInput != null) {
                        PipButton(
                            icon = MikaIcons.Back,
                            label = stringResource(R.string.pip_back),
                            onClick = { scope.launch { operateInput.back() } },
                            tag = "pip-back",
                        )
                    }
                    PipButton(
                        icon = if (soundOn) MikaIcons.Sound else MikaIcons.Mute,
                        label = stringResource(if (soundOn) R.string.pip_mute else R.string.pip_unmute),
                        onClick = onToggleSound,
                        tag = if (soundOn) "pip-mute" else "pip-unmute",
                    )
                    PipButton(
                        icon = MikaIcons.EyeOff,
                        label = stringResource(R.string.pip_close),
                        onClick = onClose,
                        tag = "pip-close",
                    )
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = MikaTheme.colors.ink) }
            }
        }
        // The border glow is drawn along the outer edge of the glass frame
        val glow = when (pip.mode) {
            PipMode.Running -> MikaTheme.colors.ok to 2.dp
            PipMode.NeedsYou -> MikaTheme.colors.warnSolid.copy(alpha = breath) to 2.dp
            PipMode.Done -> null
        }
        if (glow != null) Box(Modifier.size(w, h).border(glow.second, glow.first, outer))
    }
}

@Composable
private fun PipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.size(26.dp)
            .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(13.dp), tint = MikaTheme.colors.ink) }
}

/** Size of the frame after fitting it into innerW×innerH by aspect ratio (no cropping, no stretching); falls back to the whole box when there is no frame or the dimensions are invalid. */
private fun fittedSize(frameW: Int, frameH: Int, innerW: Dp, innerH: Dp): Pair<Dp, Dp> {
    if (frameW <= 0 || frameH <= 0) return innerW to innerH
    val a = frameW.toFloat() / frameH
    val w = if (innerW / a <= innerH) innerW else innerH * a
    return w to w / a
}

/** Touch handling on the small window's frame in direct-operation mode: a press that does not move becomes a tap, movement becomes a swipe along the traversed points; coordinates are normalized to 0..1 within this box. */
private fun operateInputModifier(input: PipInput, scope: CoroutineScope): Modifier = Modifier.pointerInput(input) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val points = mutableListOf(down.position)
        var lastUptime = down.uptimeMillis
        var moved = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            points += change.position
            lastUptime = change.uptimeMillis
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
        }
        val pts = points.map { p ->
            (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
        }
        if (!moved) {
            val (fx, fy) = pts.last()
            scope.launch { input.tap(fx, fy) }
        } else {
            val duration = (lastUptime - down.uptimeMillis).coerceIn(80L, 600L)
            scope.launch { input.swipe(pts, duration) }
        }
    }
}

/** Discrete controls also let a screen-reader user resize without a two-finger gesture. */
@Composable
private fun PipSizeButton(mark: String, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(26.dp).floatShadow(MikaTheme.colors, CircleShape)
        .background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(mark, style = MikaType.Hint, color = MikaTheme.colors.ink)
    }
}
