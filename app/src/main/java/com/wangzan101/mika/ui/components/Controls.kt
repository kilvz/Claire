package com.wangzan101.mika.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import androidx.compose.runtime.getValue

// ---------- Switch ----------

/**
 * Switch: 44×26, accent blue when on. The animation is 200ms with deceleration; with system animations off it jumps straight into place.
 * When [onCheckedChange] is null it is display-only (used when the whole row is clickable).
 */
@Composable
fun MikaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val position by animateFloatAsState(if (checked) 1f else 0f, MikaMotion.switch(reduce), label = "switch")
    // In the plain list, on is black (reversed in dark mode: light background, dark knob), not the accent color
    val plain = LocalPlainList.current
    val on = if (plain) c.ink else c.accent
    val track by animateColorAsState(if (checked) on else c.greySoft, MikaMotion.switch(reduce), label = "switch-track")
    val thumb = if (plain && c.isDark && checked) c.surface else c.thumb
    val ring = c.floatShadow.copy(alpha = 0.12f)
    Box(
        modifier
            .then(
                if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize()
                    .toggleable(checked, enabled = enabled, role = Role.Switch, interactionSource = null, indication = null, onValueChange = onCheckedChange)
                else Modifier,
            )
            .size(44.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val r = 11.dp.toPx()
                val inset = 2.dp.toPx()
                val cx = inset + r + (size.width - 2 * (inset + r)) * position
                val center = Offset(cx, size.height / 2)
                drawCircle(ring, r + 0.6.dp.toPx(), center.copy(y = center.y + 0.5.dp.toPx()))
                drawCircle(thumb, r, center)
            },
    )
}

// ---------- Pills ----------

enum class PillTone { Ok, Warn, Neutral, Accent }

/** Status pill: available, needs you, disabled; the accent tone marks "in use" (e.g. which purpose a model is serving). */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    // Plain list: status is written in grey, and only things that need you use orange
    if (LocalPlainList.current) {
        Text(text, modifier, style = MikaType.Value, color = if (tone == PillTone.Warn) c.warn else c.ink3, maxLines = 1)
        return
    }
    val (bg, fg) = when (tone) {
        PillTone.Ok -> c.okSoft to c.ok
        PillTone.Warn -> c.warnSoft to c.warn
        PillTone.Accent -> c.accentSoft to c.accent
        PillTone.Neutral -> c.greySoft to c.ink2
    }
    Text(
        text, modifier.background(bg, MikaShapes.Pill).padding(horizontal = 9.dp, vertical = 3.dp),
        style = PillText, color = fg, maxLines = 1,
    )
}

private val PillText = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold)

// ---------- Buttons ----------

/** Primary button (solid accent blue), light secondary button (white background, blue text), light filled button, text button. */
enum class ButtonKind { Primary, Secondary, Tonal, Text }

/** Large: 52 at the bottom of a page; medium: 42 side by side in a card; small: 32 pill inline. */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val text: TextStyle, val padding: Dp) {
    Large(MikaSize.Button, MikaShapes.Button, MikaType.BodyStrong, 20.dp),
    Medium(42.dp, MikaShapes.Chip, MikaType.Body.copy(fontWeight = FontWeight.SemiBold), 14.dp),
    Small(32.dp, MikaShapes.Card, MikaType.Caption.copy(fontWeight = FontWeight.SemiBold), 14.dp),
}

/** A button. The width is set by [modifier] (fill, weighted, or wrapped around the text). */
@Composable
fun MikaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    maxLines: Int = 1,
) {
    val c = MikaTheme.colors
    val shape = size.shape
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.accent to c.onAccent
        ButtonKind.Secondary -> c.surface to c.accent
        ButtonKind.Tonal -> c.fill to c.ink
        ButtonKind.Text -> Color.Transparent to c.accent
    }
    Box(
        modifier
            .then(if (kind == ButtonKind.Secondary) Modifier.cardSurface(c, shape) else Modifier.background(bg, shape))
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (maxLines == 1) Modifier.height(size.height).padding(horizontal = size.padding)
                else Modifier.heightIn(min = size.height).padding(horizontal = size.padding, vertical = 4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = size.text, color = fg, textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Segmented control ----------

/** Segmented control: light filled background, the selected segment is white and raised. [tags] are the test tags for each segment.
 *  [maxLines] is 2 for labels that can be long (locale-dependent), so they wrap instead of being cut off inside the segment. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
    maxLines: Int = 1,
) {
    val c = MikaTheme.colors
    // Two-line labels need a taller track that follows the system font scale; single-line controls keep their 32dp height everywhere else.
    val segmentHeight = if (maxLines > 1) with(LocalDensity.current) { (20 * maxLines).sp.toDp() + 4.dp } else 32.dp
    Row(modifier.fillMaxWidth().background(c.fill, SegmentOuter).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(segmentHeight)
                    .then(
                        if (on) Modifier.shadow(1.dp, SegmentInner, clip = false, ambientColor = c.cardShadow, spotColor = c.cardShadow)
                            .background(c.surface, SegmentInner)
                        else Modifier,
                    )
                    .clip(SegmentInner)
                    .selectable(on, role = Role.Tab) { onSelect(i) }
                    .then(if (tags != null) Modifier.testTag(tags[i]) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, Modifier.fillMaxWidth().padding(horizontal = 2.dp), style = SegmentText,
                    color = if (on) c.ink else c.ink2, textAlign = TextAlign.Center,
                    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SegmentOuter = MikaShapes.Tile
private val SegmentInner = MikaShapes.Tile
private val SegmentText = MikaType.Caption.copy(
    fontWeight = FontWeight.Medium,
    // Center the glyphs inside the line box as well, so the label sits optically in the middle of the segment.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Mirrors horizontal navigation symbols in RTL while leaving non-directional artwork unchanged. */
@Composable
fun DirectionalIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = rtl && (icon == MikaIcons.Back || icon == MikaIcons.Forward || icon == MikaIcons.Chevron)
    Icon(icon, contentDescription, modifier.graphicsLayer { if (mirror) scaleX = -1f }, tint = tint)
}

// ---------- Top bar ----------

/**
 * Round icon button for the top bar, 40dp. When [glass] is true it is a white glass circle floating over the content.
 * When [badge] is true, a small accent dot in the top-end corner (something else is in progress); when [enabled] is false it dims and can't be tapped.
 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    glass: Boolean = false,
    enabled: Boolean = true,
    badge: Boolean = false,
) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(MikaSize.IconButton)
            .then(if (glass) Modifier.floatShadow(c, CircleShape).background(c.surface, CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DirectionalIcon(icon, label, Modifier.size(MikaSize.Icon).alpha(if (enabled) 1f else 0.35f), tint = c.ink)
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(8.dp)
                    .background(c.bg, CircleShape).padding(1.dp).background(c.accent, CircleShape),
            )
        }
    }
}

/**
 * Top bar: 56 tall, one icon button slot on each side, centered title, optionally with a line of subtitle.
 * The background defaults to the page background, so text doesn't show through when content scrolls beneath it.
 */
@Composable
fun TopBar(
    title: String,
    left: ImageVector? = null,
    onLeft: () -> Unit = {},
    leftLabel: String = "",
    /** The small dot on the left button; see [BarIconButton]. */
    leftBadge: Boolean = false,
    right: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    background: Color = MikaTheme.colors.bg,
    titleModifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().pageBackground(background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(MikaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (left != null) BarIconButton(left, leftLabel, onLeft, badge = leftBadge) else Spacer(Modifier.size(MikaSize.IconButton))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp).then(titleModifier), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(title, style = MikaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle, style = MikaType.Hint, fontWeight = FontWeight.Medium,
                        color = if (subtitleColor == Color.Unspecified) c.ink3 else subtitleColor, maxLines = 1,
                    )
                }
            }
            if (right != null) Row(verticalAlignment = Alignment.CenterVertically, content = right)
            else Spacer(Modifier.size(MikaSize.IconButton))
        }
    }
}

// ---------- Select chips ----------

/** Selectable pill (provider, API type): 34 tall, white card background; when selected it is a solid dark fill with inverted text. [small] is the inline small variant. */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val shape = if (small) SmallChipShape else MikaShapes.Pill
    Box(
        modifier
            .then(
                when {
                    selected && !enabled -> Modifier.background(c.ink.copy(alpha = 0.38f), shape)
                    selected -> Modifier.background(c.ink, shape)
                    small -> Modifier.background(c.fill, shape)
                    else -> Modifier.cardSurface(c, shape)
                },
            )
            .clip(shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .height(if (small) 28.dp else 34.dp)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (small) SmallChipText else ChipText, maxLines = 1, softWrap = false,
            color = when {
                selected -> c.bg
                !enabled -> c.ink.copy(alpha = 0.38f)
                else -> c.ink
            },
        )
    }
}

private val SmallChipShape = MikaShapes.Tile
private val ChipText = MikaType.Body.copy(fontWeight = FontWeight.Medium)
private val SmallChipText = MikaType.Hint.copy(fontWeight = FontWeight.Medium)

// ---------- Small tags ----------

enum class TagTone { Neutral, Accent }

/** Small capability tag (tool calling, image input, 64K): 11 / 600, corner radius 7. */
@Composable
fun CapabilityTag(text: String, tone: TagTone = TagTone.Neutral) {
    val c = MikaTheme.colors
    val (bg, fg) = if (tone == TagTone.Accent) c.accentSoft to c.accent else c.greySoft to c.ink2
    Text(text, Modifier.background(bg, TagShape).padding(horizontal = 7.dp, vertical = 2.dp), style = TagText, color = fg, maxLines = 1)
}

private val TagShape = MikaShapes.Tile
private val TagText = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold)

// ---------- Pagination dots ----------

/** Pagination dots at the bottom of the onboarding flow: the current step is an 18-wide accent bar, the rest are 6-wide grey dots. */
@Composable
fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 18.dp else 6.dp, 6.dp).background(if (i == current) c.accent else c.hair, CircleShape))
        }
    }
}
