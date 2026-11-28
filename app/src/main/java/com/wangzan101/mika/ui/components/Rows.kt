package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.roundToInt

/** Group heading: 13 / 500, secondary text color. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 8.dp), style = MikaType.Label, color = MikaTheme.colors.ink3)
}

/** The note under a group, aligned with the text inside the cards. */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MikaType.Hint, color = color.takeOrElse { MikaTheme.colors.ink3 },
    )
}

/** One line of summary + an expandable "details": by default only [summary] is shown; tapping "details" expands the full [detail]. Spacing is provided by the caller via [modifier]. */
@Composable
fun DetailNote(summary: String, detail: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(summary, style = MikaType.Hint, color = MikaTheme.colors.ink3)
        if (detail.isNotBlank()) {
            Row(
                Modifier.clickable { open = !open }.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionalIcon(
                    MikaIcons.Chevron, null,
                    Modifier.size(MikaSize.SmallIcon).rotate(if (open) 90f else 0f),
                    tint = MikaTheme.colors.accent,
                )
                Text(
                    tr(R.string.details), Modifier.padding(start = 2.dp),
                    style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.accent,
                )
            }
            if (open) {
                Text(detail, Modifier.padding(top = 6.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
            }
        }
    }
}

/** Background and glyph colors of the icon tile. Neutral by default; only status gets color. */
enum class TileTone {
    Neutral, Accent, Ok, Warn, Bad;

    fun colors(c: MikaColors): Pair<Color, Color> = when (this) {
        Neutral -> c.neutralSoft to c.ink
        Accent -> c.accentSoft to c.accent
        Ok -> c.okSoft to c.ok
        Warn -> c.warnSoft to c.warn
        Bad -> c.badSoft to c.bad
    }
}

/**
 * Icon tile to the left of a row: 30dp, corner radius 9, with an 18dp glyph inside.
 * When [brandTile] is non-null it is a self-colored brand mark: its color is used as the background (the same in both modes) and the glyph keeps its own colors.
 */
@Composable
fun IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral, brandTile: Color? = null) {
    val (bg, fg) = if (brandTile != null) (brandTile to Color.Unspecified) else tone.colors(MikaTheme.colors)
    // Plain list: draw the icon directly with no backing block; the placeholder width stays the same so dividers and text still line up
    if (brandTile == null && LocalPlainList.current) {
        Box(Modifier.size(MikaSize.Tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (tone == TileTone.Neutral || tone == TileTone.Ok) MikaTheme.colors.ink else fg)
        }
        return
    }
    Box(Modifier.size(MikaSize.Tile).background(bg, MikaShapes.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(MikaSize.TileIcon), tint = fg)
    }
}

/** Divider between rows, drawn from [start] to the right edge; the first row of a group ([first]) gets none. */
private fun Modifier.topHairline(first: Boolean, start: Dp, color: Color): Modifier =
    if (first) this
    else drawBehind {
        val y = 0.25.dp.toPx()
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }

/** Where text starts when there is an icon tile: left padding 16 + icon tile 30 + gap 14. */
private val TextStartWithIcon = 60.dp
private val TextStartPlain = 16.dp

/**
 * A list row: optional icon tile on the left, title and description in the middle, [trailing] on the right (value, pill, switch, chevron).
 * [detail] is a small line of text under the description (such as "last result"), colored by [detailColor].
 * With [first] the top divider is not drawn for the first row of a group; when [onClick] is null the whole row is not clickable.
 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    titleColor: Color = Color.Unspecified,
    tone: TileTone = TileTone.Neutral,
    brandTile: Color? = null,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Maximum lines for the title, ellipsized beyond that (long titles in lists use 1). */
    titleMaxLines: Int = Int.MAX_VALUE,
    /** The note explaining what this row is: in the plain list it moves below the card as a footnote; elsewhere it stays under the title as before. */
    note: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    val noteInCard = rememberCardNote(title, note)
    val icon = if (brandTile != null) icon else rememberCardIcon(icon)
    val sub = listOfNotNull(subtitle?.takeIf { it.isNotEmpty() }, note?.takeIf { !noteInCard && it.isNotEmpty() }).joinToString("\n").ifEmpty { null }
    Row(
        modifier.fillMaxWidth()
            .topHairline(first, if (icon != null) TextStartWithIcon else TextStartPlain, c.hair)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MikaSize.RowMin)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, tone, brandTile)
            Spacer(Modifier.width(RowGap))
        }
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(title, style = MikaType.Body, color = titleColor.takeOrElse { c.ink },
                maxLines = titleMaxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (!detail.isNullOrEmpty()) Text(detail, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = detailColor.takeOrElse { c.ink3 })
        }
        // At most a bit more than half the width available next to the title: when the value is longer only the value is ellipsized, and the title is not squeezed away
        Row(
            Modifier.trailingSlot(TrailingMaxFraction, RowGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            content = trailing,
        )
    }
}

/** Spacing between the icon block, title and row end within a row. */
private val RowGap = 14.dp

/** The maximum fraction of the width beyond the icon block that the row end (trailing of [ListRow]) may take. */
private const val TrailingMaxFraction = 0.52f

/**
 * Position of the row end: width no more than [fraction] of the max width the parent gives (ignored when width is unbounded), with [gap] left before it when there is content,
 * and taking no space at all when empty (as before, the title runs all the way to the row end). Single measure, no subcomposition.
 */
private fun Modifier.trailingSlot(fraction: Float, gap: Dp) = layout { measurable, constraints ->
    val gapPx = gap.roundToPx()
    val c = if (constraints.hasBoundedWidth) {
        val max = ((constraints.maxWidth * fraction).roundToInt() - gapPx).coerceAtLeast(0)
        constraints.copy(minWidth = 0, maxWidth = max)
    } else constraints.copy(minWidth = 0)
    val p = measurable.measure(c)
    if (p.width == 0) layout(0, p.height) {}
    else layout(p.width + gapPx, p.height) { p.placeRelative(gapPx, 0) }
}

enum class ValueTone { Plain, Ok, Warn, Bad, Link }

/** The row-end value. Ellipsized when it does not fit, yielding first to the switch and arrow at the same row end. */
@Composable
fun RowScope.RowValue(text: String, tone: ValueTone = ValueTone.Plain, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Text(
        text, modifier.weight(1f, fill = false), style = MikaType.Value,
        fontWeight = if (tone == ValueTone.Link) FontWeight.Medium else FontWeight.Normal,
        color = when (tone) {
            ValueTone.Plain -> c.ink3
            // In the plain list, "normal" is not colored; only things that need you get a color
            ValueTone.Ok -> if (LocalPlainList.current) c.ink3 else c.ok
            ValueTone.Warn -> c.warn
            ValueTone.Bad -> c.bad
            ValueTone.Link -> c.accent
        },
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** The small chevron at the end of a row: tapping leads to another page. */
@Composable
fun RowChevron() {
    DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = MikaTheme.colors.ink3)
}

/** Radio row: a circle on the left, solid accent color when selected. The divider starts at the text. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    first: Boolean = false,
    enabled: Boolean = true,
    titleStyle: TextStyle = MikaType.Body,
    /** Extra content under the title (e.g. small capability tags). */
    below: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** The note for this item: in the plain list it is shown below the card only when selected; elsewhere it is shown under the title. */
    note: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    val noteInCard = rememberCardNote(title, note, selected)
    val sub = listOfNotNull(subtitle?.takeIf { it.isNotEmpty() }, note?.takeIf { !noteInCard && it.isNotEmpty() }).joinToString("\n").ifEmpty { null }
    Row(
        modifier.fillMaxWidth()
            .topHairline(first, 50.dp, c.hair)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioMark(selected, enabled)
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = titleStyle, color = if (enabled) c.ink else c.ink3)
            if (!sub.isNullOrEmpty()) Text(sub, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (below != null) Box(Modifier.padding(top = 4.dp)) { below() }
        }
        trailing()
    }
}

@Composable
private fun RadioMark(selected: Boolean, enabled: Boolean = true) {
    val c = MikaTheme.colors
    if (selected && enabled) {
        // In the plain list, selection uses the body color (matching the black switch); elsewhere it uses the accent color
        val fill = if (LocalPlainList.current) c.ink else c.accent
        Box(Modifier.size(22.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(if (LocalPlainList.current) c.surface else c.onAccent, CircleShape))
        }
    } else {
        Box(Modifier.size(22.dp).border(1.6.dp, c.hair, CircleShape))
    }
}

/**
 * Input row inside a card: fixed-width label on the left, a single-line field on the right (shows [placeholder] when empty).
 * When [secret] is true it is displayed as a password; [trailing] goes to the right of the field (e.g. "show").
 * When [onClear] is non-null and the field has text, a clear button is placed to the right of the text (the same set as the search field's X).
 */
@Composable
fun InputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    first: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    labelWidth: Dp = 84.dp,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    clearDescription: String = "",
    clearModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().topHairline(first, TextStartPlain, c.hair).keepsKeyboard()
            .heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(labelWidth), style = MikaType.Body, color = c.ink)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MikaType.Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
        if (onClear != null && value.isNotEmpty()) {
            FieldClearButton(clearDescription, onClear, clearModifier.padding(start = 4.dp))
        }
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

/**
 * A choice row in a card laid out in a column with [InputRow]: a title of the same width on the left, the currently selected value starting from where text in the input field starts, and a small arrow at the row end;
 * tapping opens a picker provided by the caller. Use it in a card that has both input rows and choice rows, so the contents of both line up vertically.
 */
@Composable
fun PickRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    first: Boolean = false,
    labelWidth: Dp = 84.dp,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth().topHairline(first, TextStartPlain, c.hair)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(labelWidth), style = MikaType.Body, color = c.ink)
        Text(
            value, Modifier.weight(1f), style = MikaType.Body, color = c.ink,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        RowChevron()
    }
}

/** The X at the end of the input field: tap to clear. Same size and colour as the search field's clear button; the caller only shows it when there is content. */
@Composable
fun FieldClearButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Close, description, Modifier.size(14.dp), tint = MikaTheme.colors.ink3)
    }
}

/** Divider between self-arranged blocks inside a card, drawn from [start] to the right edge. */
@Composable
fun Hairline(start: Dp = TextStartWithIcon) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(MikaTheme.colors.hair))
}
