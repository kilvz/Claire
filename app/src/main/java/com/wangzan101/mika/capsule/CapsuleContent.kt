package com.wangzan101.mika.capsule

import com.wangzan101.mika.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.ceil

/** Margin left around the capsule for the shadow: the overlay window matches the content size and the shadow is drawn inside the window; the less margin left, the narrower the transparent border of the window covering the app below. */
internal val CapsuleShadowRoom = 8.dp
private val CapsuleHeight = 40.dp
private val BarHeight = 48.dp
private val LogoSize = 30.dp
private const val FADE_MS = 400
/** The flash on the ball when a screen is captured: how bright and how long it takes to fade. */
private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 320
/** Duration of the ball expanding into a capsule and shrinking back to a ball. */
private const val MORPH_MS = 260
/** How much it scales up during hold-to-talk, how thick the outer ring is, and how long the transition takes. */
private const val HOLD_SCALE = 0.06f
private val HoldRing = 3.dp
private const val HOLD_MS = 180
/** Time for one full turn of the ring while working. */
private const val SPIN_MS = 1_200
private val CapsuleText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val BarDetail = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val CountText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)

/** Fixed strings on the capsule. */
object CapsuleCopy {
    val STOP: String get() = tr(R.string.capsule_stop)
    val EXPAND: String get() = tr(R.string.capsule_expand)
    val TALK: String get() = tr(R.string.capsule_talk)
    val SWAP_SIDE: String get() = tr(R.string.capsule_swap_side)
    const val IDLE = "Claire"

    /** What screen readers announce for the ball: what it is doing, whether it needs you, and the result; the count when there are several items is already in [CapsuleView.Shown.text]. */
    fun describe(view: CapsuleView.Shown): String = when {
        view.kind == CapsuleKind.Idle -> IDLE
        view.kind == CapsuleKind.Notice -> tr(R.string.notice_hint_capsule_desc, view.count)
        view.kind.needsYou -> tr(R.string.capsule_desc_needs, view.text)
        view.kind == CapsuleKind.Running -> tr(R.string.capsule_desc_running, view.text)
        view.kind == CapsuleKind.Held || view.kind == CapsuleKind.Cancelled -> tr(R.string.capsule_desc_other, view.text)
        else -> "Claire ${view.text}"
    }
}

/**
 * The floating capsule, holding no state: normally it is a single Claire icon, with state drawn on the outer ring and the corner badge (while working a blue arc spins around it, holding shows a gray ring, needing you turns the whole thing orange,
 * the ring breathes in and out; with several items the count is written on the corner; done gets a green check, failed and stopped get gray badges; idle shows nothing);
 * with [CapsuleView.Shown.pill] it expands into a one-line capsule (needs you, just finished), whose width changes gradually, with the icon hugging the docked side ([side]) without moving;
 * when the app is handed over to you it is the bar at the top: "You handle it · Not doing it · Handled".
 * The capsule holds a single line of text, at most 60% of the screen width, truncated beyond that; within the same [CapsuleView.Shown.widthEpoch] it only grows, never shrinks.
 * Tapping the ball or the capsule goes to [onTap] (expand the card); for screen readers this tap is announced as [tapLabel]; the two buttons on that bar go to [onStop] and [onHandBack].
 * When [onSwapSide] is provided, screen readers get an extra "Move to the other side of the screen" in place of dragging. [alpha] fades only the background and the icon, while the ring and badge stay solid.
 * Double taps are recognized by the caller from two [onTap]s; a screen-reader double tap is just a tap, so when [onSendScreen] is provided there is an extra capture-screen action.
 * Both capture entries only put the screen into the card preview; it enters the conversation only after Send is explicitly pressed.
 * Each increment of [flash] makes the ball flash once (a screen was captured).
 * While [holding] it is hold-to-talk: the capsule scales up slightly, with a blue ring around it. When [onTalk] is provided, screen readers get an extra talk action ([talkLabel]) instead of a long press:
 * tap once to start, tap again to send.
 */
@Composable
fun CapsuleContent(
    view: CapsuleView.Shown,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
    side: DockSide = DockSide.Right,
    tapLabel: String = CapsuleCopy.EXPAND,
    onSwapSide: (() -> Unit)? = null,
    alpha: Float = 1f,
    onSendScreen: (() -> Unit)? = null,
    flash: Int = 0,
    holding: Boolean = false,
    onTalk: (() -> Unit)? = null,
    talkLabel: String = "",
) {
    // Both fading out and coming back are gradual; with animations off it switches instantly
    val shown = if (MikaTheme.reduceMotion) alpha else animateFloatAsState(alpha, tween(FADE_MS), label = "fade").value
    val a11y = Modifier.semantics {
        customActions = listOfNotNull(
            onSwapSide?.let { f -> CustomAccessibilityAction(CapsuleCopy.SWAP_SIDE) { f(); true } },
            onSendScreen?.let { f -> CustomAccessibilityAction(ScreenCopy.SEND_SCREEN) { f(); true } },
            onTalk?.let { f -> CustomAccessibilityAction(talkLabel) { f(); true } },
        )
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        if (view.kind == CapsuleKind.HandedOver) TakeoverBar(view, onStop, onHandBack)
        else Capsule(view, side == DockSide.Right, onTap, tapLabel, a11y, shown, flash, holding)
    }
}

/** The ball or the expanded capsule: one and the same glass background, with the visible width gradually morphing between the two (changing only while drawing, see [Morph]); with [end] it hugs the right side (icon on the right). */
@Composable
private fun Capsule(view: CapsuleView.Shown, end: Boolean, onTap: () -> Unit, tapLabel: String, a11y: Modifier, alpha: Float, flash: Int, holding: Boolean) {
    val c = MikaTheme.colors
    // Hold-to-talk: scaled up slightly with a blue ring around it (drawn in the shadow margin, so the window size does not change)
    val press = if (MikaTheme.reduceMotion) (if (holding) 1f else 0f)
        else animateFloatAsState(if (holding) 1f else 0f, tween(HOLD_MS), label = "hold").value
    val need = view.kind.needsYou || view.needCount > 0
    // Screen captured: a white glow fading from bright to nothing, the same idea as the screen flashing when a screenshot is taken; no flash with animations off
    val glow = remember { Animatable(0f) }
    val still = MikaTheme.reduceMotion
    val morph = rememberMorph(animate = !still)
    LaunchedEffect(flash) {
        if (flash == 0 || still) return@LaunchedEffect
        glow.snapTo(FLASH_ALPHA)
        glow.animateTo(0f, tween(FLASH_MS))
    }
    Box(
        Modifier.graphicsLayer {
            scaleX = 1f + HOLD_SCALE * press
            scaleY = 1f + HOLD_SCALE * press
        }.drawBehind {
            if (press <= 0f) return@drawBehind
            val ring = HoldRing.toPx()
            val r = size.height / 2 + ring / 2
            drawRoundRect(
                c.accent.copy(alpha = press), Offset(-ring / 2, -ring / 2), Size(size.width + ring, size.height + ring),
                CornerRadius(r, r), style = Stroke(ring),
            )
        }.then(if (holding) Modifier.testTag("capsule-holding") else Modifier),
    ) {
        Box(
            Modifier.testTag(if (view.pill) "capsule" else "capsule-ball")
                .morphGlass(morph, end, c, need, alpha)
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
                .then(if (view.pill) Modifier else Modifier.semantics { contentDescription = CapsuleCopy.describe(view) })
                .then(a11y)
                .morphLayout(morph, end),
        ) {
            if (view.pill) PillRow(view, end, need)
            else Box(Modifier.size(CapsuleHeight), contentAlignment = Alignment.Center) { CapsuleLogo() }
            if (glow.value > 0f) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = glow.value)))
        }
        // The ring and the badge do not fade with the background; while shrinking back they hug the icon's side instead of following the width that has not finished collapsing
        if (!view.pill) Box(Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart).size(CapsuleHeight)) {
            Ring(view.kind, need, Modifier.matchParentSize())
            Badge(view, need, Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
        }
    }
}

/** The row inside the expanded capsule: icon, state marker, text; when docked right the icon is at the far right, in the same place as on the ball. */
@Composable
private fun PillRow(view: CapsuleView.Shown, end: Boolean, need: Boolean) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    // Within the same item and the same state, changing the sentence only grows the width: remember the widest moment in this epoch
    var widest by remember(view.widthEpoch) { mutableIntStateOf(0) }
    val minWidth = with(LocalDensity.current) { widest.toDp() }
    Row(
        Modifier.widthIn(min = minWidth.coerceAtMost(maxWidth), max = maxWidth)
            .onSizeChanged { if (it.width > widest) widest = it.width }
            .height(CapsuleHeight)
            .padding(start = if (end) 13.dp else 5.dp, end = if (end) 5.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        if (!end) CapsuleLogo()
        Leading(view.kind)
        Text(
            view.text, Modifier.weight(1f, fill = false), style = CapsuleText,
            color = if (need) c.warn else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (end) CapsuleLogo()
    }
}

/** The state marker before the text: a breathing dot when it needs you, a check when done, an exclamation when it failed, a stop sign when cancelled, and a green dot while a background page is running. */
@Composable
private fun Leading(kind: CapsuleKind) {
    val c = MikaTheme.colors
    when {
        kind.needsYou -> BreathingDot(c)
        kind == CapsuleKind.Done -> Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.ok)
        kind == CapsuleKind.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.Cancelled -> Icon(MikaIcons.Stop, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.KeptPage -> LiveDot(c)
        else -> Unit
    }
}

/** Background page running: a green dot inside a pale green ring. */
@Composable
private fun LiveDot(c: MikaColors) {
    Box(Modifier.size(14.dp).testTag("capsule-live-dot"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).background(c.okSoft, MikaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.ok, MikaShapes.Pill))
    }
}

/** Needs you: an orange dot inside a pale orange ring that breathes in and out; still with animations off. */
@Composable
private fun BreathingDot(c: MikaColors) {
    val halo = breathe(still = 0f)
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = halo }.background(c.warnSoft, MikaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.warnSolid, MikaShapes.Pill))
    }
}

/** Breathing (from 0 to 1 and back); stays at [still] with animations off. */
@Composable
private fun breathe(still: Float): Float = if (MikaTheme.reduceMotion) still else rememberInfiniteTransition(label = "breathe").animateFloat(
    0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe",
).value

/** The ball's outer ring: a blue arc spinning around while working (a full static ring with animations off), a full gray ring while holding, a full orange ring breathing in and out when it needs you; nothing otherwise. */
@Composable
private fun Ring(kind: CapsuleKind, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    val color = when {
        need -> c.warnSolid
        kind == CapsuleKind.Running -> c.accent
        kind == CapsuleKind.Held -> c.ink3
        else -> return
    }
    val spin = kind == CapsuleKind.Running && !need && !MikaTheme.reduceMotion
    val angle = if (!spin) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    ).value
    val strength = if (need) 0.45f + 0.55f * breathe(still = 1f) else 1f
    Canvas(modifier.testTag("capsule-ring")) {
        val stroke = 2.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        if (spin) {
            drawArc(c.accentSoft, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
            drawArc(color, angle - 90f, 100f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawArc(color.copy(alpha = strength), 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
        }
    }
}

/**
 * The badge on the ball's corner: with several items it shows the count (orange when it needs you, gray while holding, blue otherwise); with a single item, a green check when done, gray when failed or cancelled.
 * When it warrants a notice ([CapsuleKind.Notice]) it shows the number of unseen items, writing it even for a single one.
 */
@Composable
private fun Badge(view: CapsuleView.Shown, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    if (view.count >= 2 || (view.kind == CapsuleKind.Notice && view.count >= 1)) {
        val fill = when {
            need -> c.warnSolid
            view.kind == CapsuleKind.Held -> c.ink3
            else -> c.accent
        }
        Box(
            modifier.testTag("capsule-count").background(c.surface, MikaShapes.Pill).padding(1.5.dp)
                .sizeIn(minWidth = 15.dp, minHeight = 15.dp).background(fill, MikaShapes.Pill).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${view.count}", style = CountText, color = c.onAccent, maxLines = 1)
        }
        return
    }
    val (icon, tint) = when (view.kind) {
        CapsuleKind.Done -> MikaIcons.Check to c.ok
        CapsuleKind.Failed -> MikaIcons.Exclaim to c.ink3
        CapsuleKind.Cancelled -> MikaIcons.Stop to c.ink3
        else -> return
    }
    Box(
        modifier.testTag("capsule-badge").size(16.dp).background(c.surface, MikaShapes.Pill).padding(1.5.dp)
            .background(tint, MikaShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(9.dp), tint = c.surface)
    }
}

/**
 * The width morphs gradually between the ball and the capsule, changing only the glass that is drawn without touching the layout per frame: the overlay window follows the content size, and any layout change forces the window to relayout and swap canvases,
 * so changing per frame would stutter. [target] is the content's width right now, [width] is the visible width (animated), and [hold] is the width taken by the layout during the animation — the wider of the start and end points,
 * so when widening the window jumps to the widest at once, when narrowing it shrinks only after drawing finishes, and one morph changes the window at most once. On first appearance and with animations off it is the content width directly.
 */
private class Morph {
    val width = Animatable(0f)
    var target by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
}

@Composable
private fun rememberMorph(animate: Boolean): Morph {
    val m = remember { Morph() }
    LaunchedEffect(m.target, animate) {
        val t = m.target
        if (t == 0) return@LaunchedEffect
        if (!animate || m.width.value == 0f) m.width.snapTo(t.toFloat())
        else {
            m.hold = maxOf(ceil(m.width.value).toInt(), t)
            m.width.animateTo(t.toFloat(), tween(MORPH_MS))
        }
        m.hold = t
    }
    return m
}

/** The layout takes the wider of [Morph.hold] and the content, and the content is placed against the docked side ([end] means right): it grows inward and collapses toward the edge, so the icon stays put. */
private fun Modifier.morphLayout(m: Morph, end: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    if (p.width != m.target) m.target = p.width
    val w = maxOf(m.hold, p.width)
    layout(w, p.height) { p.place(if (end) w - p.width else 0, 0) }
}

/**
 * The glass background (same as [glass]) draws only the visible span of the width, against the docked side: shadow, clipping, fill and outline all follow it, so a width change only repaints layers.
 * [alpha] fades the background and the icon inside it.
 */
private fun Modifier.morphGlass(m: Morph, end: Boolean, c: MikaColors, need: Boolean, alpha: Float): Modifier =
    graphicsLayer {
        this.alpha = alpha
        shadowElevation = 8.dp.toPx()
        ambientShadowColor = c.floatShadow.copy(alpha = 0.25f)
        spotShadowColor = c.floatShadow.copy(alpha = 0.3f)
        shape = EdgePill(m.width.value, end)
        clip = true
    }.drawWithContent {
        val pill = EdgePill(m.width.value, end)
        val o = pill.createOutline(size, layoutDirection, this)
        drawOutline(o, c.surface.copy(alpha = 0.95f))
        if (need) drawOutline(o, c.warnSoft)
        drawContent()
        val stroke = (if (need) 1.dp else 0.5.dp).toPx()
        drawOutline(EdgePill(m.width.value, end, inset = stroke / 2).createOutline(size, layoutDirection, this),
            if (need) c.warnSolid.copy(alpha = 0.5f) else c.glassEdge, style = Stroke(stroke))
    }

/** A pill shape of width [width] (the whole block when 0 or overflowing) hugging one side (the logical end when [end]); [inset] shrinks it inward (used for drawing the outline). */
private class EdgePill(private val width: Float, private val end: Boolean, private val inset: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val atRight = end != (layoutDirection == LayoutDirection.Rtl)
        val left = if (atRight) size.width - w else 0f
        val r = size.height / 2 - inset
        return Outline.Rounded(RoundRect(left + inset, inset, left + w - inset, size.height - inset, CornerRadius(r, r)))
    }
}

/** The bar at the top while the app is in your hands: "You handle it" with a small line below it, and "Not doing it" / "Handled" on the right. */
@Composable
private fun TakeoverBar(view: CapsuleView.Shown, onStop: () -> Unit, onHandBack: () -> Unit) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Row(
        Modifier.testTag("capsule-bar")
            .glass(c, MikaShapes.Pill, need = false)
            .widthIn(max = maxWidth)
            .height(BarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleLogo()
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
            Text(view.text, style = CapsuleText, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            view.detail?.let { Text(it, style = BarDetail, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onStop)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(CapsuleCopy.STOP, style = CapsuleText.copy(fontWeight = FontWeight.Normal), color = c.ink2, maxLines = 1)
        }
        MikaButton(stringResource(R.string.action_hand_back), onHandBack, size = ButtonSize.Small)
    }
}

/** The Claire icon on the capsule (circular). */
@Composable
private fun CapsuleLogo() {
    MikaLogo(LogoSize, corner = LogoSize / 2)
}

/**
 * Glass background floating above other apps: an overlay window cannot get the picture of the app below for blurring, so it uses a nearly opaque card background, a thin outline and a floating shadow;
 * when it needs you, a pale orange layer is added and the outline turns orange.
 */
internal fun Modifier.glass(c: MikaColors, shape: Shape, need: Boolean): Modifier =
    shadow(8.dp, shape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.25f), spotColor = c.floatShadow.copy(alpha = 0.3f))
        .clip(shape)
        .background(c.surface.copy(alpha = 0.95f))
        .then(if (need) Modifier.background(c.warnSoft).border(1.dp, c.warnSolid.copy(alpha = 0.5f), shape) else Modifier.border(0.5.dp, c.glassEdge, shape))
