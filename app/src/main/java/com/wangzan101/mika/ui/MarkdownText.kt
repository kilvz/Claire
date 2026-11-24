package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes

/** Displays a reply as Markdown; streaming drafts use a syntax preview with temporary closing. */
private val LocalReportStyle = staticCompositionLocalOf { false }

/**
 * The terms in-page find must mark inside Markdown: every matching character is marked with [soft].
 * The occurrence at index [focusIndex] inside the text block that [focus] points at (matched by object identity) is marked with [strong]; other hits are unaffected.
 * When null, everything behaves as usual, so showing a reply in the conversation costs no extra effort.
 */
internal class FindHighlight(
    val query: String,
    val soft: Color,
    val strong: Color,
    val focus: CharSequence? = null,
    val focusIndex: Int = 0,
)

internal val LocalFindHighlight = compositionLocalOf<FindHighlight?> { null }

/**
 * Markdown layout flavor for one place in the UI; when not supplied, it uses the shared default.
 * The chat page's answers use it: dark grey body, pure black bold, headings not enlarged, muted list bullets, and quotes as a light background block instead of a vertical line.
 */
@Immutable
data class MarkdownLook(
    /** Distance between paragraphs (dp). */
    val gap: Int = 8,
    /** Headings are about the same size as body text and differ only by bold weight. */
    val flatHeadings: Boolean = false,
    /** Body color: when given, replaces the primary text color supplied by the caller. */
    val bodyInk: Color = Color.Unspecified,
    /** Color of bold text. */
    val strongInk: Color = Color.Unspecified,
    /** Color of list bullets and numbers. */
    val markerInk: Color = Color.Unspecified,
    /** Background of quotes; when given, draws a light background block instead of a left vertical line. */
    val quoteFill: Color = Color.Unspecified,
    /** Background of inline code. */
    val codeFill: Color = Color.Unspecified,
)

val LocalMarkdownLook = staticCompositionLocalOf { MarkdownLook() }

@Composable
fun MarkdownText(markdown: String, style: TextStyle, modifier: Modifier = Modifier, streaming: Boolean = false, reportStyle: Boolean = false) {
    val look = LocalMarkdownLook.current
    val colors = MikaTheme.colors
    val body = if (look.bodyInk.isSpecified && style.color == colors.ink) style.copy(color = look.bodyInk) else style
    MarkdownDocument(markdown, streaming, reportStyle) { blocks ->
        MarkdownBlocks(blocks, body, modifier, gap = if (reportStyle) 16 else look.gap)
    }
}

@Composable
internal fun MarkdownDocument(markdown: String, streaming: Boolean = false, reportStyle: Boolean = false, content: @Composable (List<MdBlock>) -> Unit) {
    // Streaming fragments update frequently, so parse them in the background; the previous frame is kept until the new fragment is ready, avoiding main-thread jank and text flashing empty.
    val colors = MikaTheme.colors
    val look = LocalMarkdownLook.current
    val styles = remember(colors, reportStyle, look) {
        MarkdownModel.Styles.of(
            colors, if (reportStyle) FontWeight.Bold else FontWeight.SemiBold,
            strongColor = look.strongInk, codeFill = look.codeFill.takeOrElse { colors.fill },
        )
    }
    val blocks = if (streaming) {
        val parsed by produceState<List<MdBlock>>(emptyList(), markdown, styles) {
            value = withContext(Dispatchers.Default) { MarkdownModel.parseStreaming(markdown, styles) }
        }
        parsed
    } else remember(markdown, styles) { MarkdownModel.parse(markdown, styles) }
    val context = LocalContext.current
    val uriHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // Web links open in the built-in browser
                if ((uri.startsWith("https://", true) || uri.startsWith("http://", true)) && InAppLinks.open?.invoke(uri) == true) return
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, tr(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalReportStyle provides reportStyle) {
        content(blocks)
    }
}

@Composable
internal fun MarkdownBlocks(blocks: List<MdBlock>, style: TextStyle, modifier: Modifier = Modifier, depth: Int = 0, gap: Int = 8) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        blocks.forEach { Block(it, style, depth) }
    }
}

/** A single Markdown block: for places that lazily load block by block (the file viewer page); styles inside the block are the same as [MarkdownText]. */
@Composable
internal fun MarkdownBlock(b: MdBlock, style: TextStyle) {
    Block(b, style, depth = 0)
}

/** Highlights the hit with a background color; the one at [FindHighlight.focus] uses a deeper color so it's obvious at a glance where "next" jumped to. */
private fun CharSequence.withFind(find: FindHighlight?): AnnotatedString {
    val base = this as? AnnotatedString ?: AnnotatedString(toString())
    if (find == null || find.query.isEmpty()) return base
    val ranges = findRanges(this, find.query, FIND_LIMIT)
    if (ranges.isEmpty()) return base
    val focus = if (this === find.focus) ranges.getOrNull(find.focusIndex) else null
    return buildAnnotatedString {
        append(base)
        ranges.forEach {
            addStyle(SpanStyle(background = if (it == focus) find.strong else find.soft), it.first, it.last + 1)
        }
    }
}

// CJK ideographs and kana. Punctuation (full-width symbols such as 、。：) does not count; spacing is only stretched between character and character.
private fun isCjkLetter(cp: Int) =
    cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
        cp in 0xF900..0xFAFF || cp in 0x20000..0x3FFFF

private val TextStyle.justified: Boolean get() = textAlign == TextAlign.Justify

/**
 * Positions within a line that can be spread: between two adjacent Han characters (or kana); returns the range of the former character.
 * Nothing is spread next to punctuation, Latin letters or digits — those are exactly the places typesetting should never widen.
 */
internal fun cjkGapRanges(text: String, start: Int, end: Int): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = start
    while (i < end) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        val next = i + n
        if (isCjkLetter(cp) && next < end && isCjkLetter(text.codePointAt(next))) out += i until next
        i = next
    }
    return out
}

/**
 * Chinese text justification.
 *
 * Why not `TextAlign.Justify`: before Android 35 it only stretches the spaces between words, and a full Chinese line has no spaces,
 * so line ends stay ragged, and only lines with English mixed in get forcibly stretched.
 *
 * Approach: first measure at the same width to learn how far each line falls short of the right edge, and spread the remainder over the letter spacing between adjacent Han characters on that line;
 * then have [Text] lay out at the same width. The copy itself is untouched (no characters inserted, no line breaks added), so selection, copying, find and text-to-speech are unaffected.
 */
@Composable
private fun JustifiedText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val dense = LocalDensity.current
    // The width comes from the text's own layout: we can't wrap in BoxWithConstraints, because a quote block's parent is a Row measured by content, and subcomposition can't measure intrinsics.
    var widthPx by remember { mutableIntStateOf(0) }
    val spaced = remember(text, style, widthPx, measurer) {
        if (widthPx <= 0) return@remember text
        val probe = measurer.measure(
            text,
            style.copy(textAlign = TextAlign.Start),
            constraints = Constraints(maxWidth = widthPx),
            softWrap = true,
            maxLines = Int.MAX_VALUE,
        )
        if (probe.lineCount <= 1) return@remember text
        val fontPx = with(dense) { style.fontSize.toPx() }
        if (fontPx <= 0f) return@remember text
        val plain = text.text
        val out = AnnotatedString.Builder(text)
        for (line in 0 until probe.lineCount - 1) {
            // Don't stretch the last line: like a paragraph in a book, the final line keeps its natural length
            val start = probe.getLineStart(line)
            val end = probe.getLineEnd(line, visibleEnd = true)
            val used = probe.getLineRight(line) - probe.getLineLeft(line)
            // Keep a ten percent margin so rounding doesn't push the character at the line end onto the next line
            val slack = (widthPx - used) * 0.9f
            if (slack < 0.5f) continue
            val gaps = cjkGapRanges(plain, start, end)
            if (gaps.isEmpty()) continue
            // Stretch by at most 0.25 em at each place; wider actually looks worse
            val em = (slack / gaps.size / fontPx).coerceAtMost(0.25f)
            if (em <= 0f) continue
            gaps.forEach { out.addStyle(SpanStyle(letterSpacing = em.em), it.first, it.last + 1) }
        }
        out.toAnnotatedString()
    }
    Text(
        spaced,
        style = style.copy(textAlign = TextAlign.Start),
        modifier = modifier.fillMaxWidth().onSizeChanged { widthPx = it.width },
    )
}

@Composable
private fun Block(b: MdBlock, style: TextStyle, depth: Int) {
    val find = LocalFindHighlight.current
    val look = LocalMarkdownLook.current
    when (b) {
        is MdBlock.Para -> {
            val text = remember(b.text, find) { b.text.withFind(find) }
            if (style.justified) JustifiedText(text, style, Modifier.fillMaxWidth()) else Text(text, style = style)
        }
        is MdBlock.Heading -> Text(
            remember(b.text, find) { b.text.withFind(find) },
            Modifier.padding(top = if (LocalReportStyle.current) 12.dp else if (look.flatHeadings) 4.dp else 2.dp),
            style = style.copy(
                color = look.strongInk.takeOrElse { style.color },
                fontSize = if (look.flatHeadings) (style.fontSize.value + if (b.level <= 2) 1f else 0.5f).sp
                    else when (b.level) { 1 -> 19.sp; 2 -> 17.5.sp; 3 -> 16.sp; else -> style.fontSize },
                lineHeight = if (look.flatHeadings) style.lineHeight
                    else when (b.level) { 1 -> 25.sp; 2 -> 23.sp; else -> style.lineHeight },
                fontWeight = if (LocalReportStyle.current) FontWeight.Bold else FontWeight.SemiBold,
                // Headings stay left-aligned: justification would stretch headings of two or more lines to full width, and a heading shouldn't be stretched out
                textAlign = TextAlign.Start,
            ),
        )
        is MdBlock.Code -> CodeBlock(b, style)
        is MdBlock.Quote -> if (look.quoteFill.isSpecified) {
            MarkdownBlocks(
                b.blocks, style.copy(color = MikaTheme.colors.ink2),
                Modifier.fillMaxWidth().background(look.quoteFill, MikaShapes.Button).padding(horizontal = 11.dp, vertical = 7.dp), depth + 1, look.gap,
            )
        } else Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MikaTheme.colors.hair, MikaShapes.Micro))
            MarkdownBlocks(b.blocks, style.copy(color = MikaTheme.colors.ink2), Modifier.padding(start = 10.dp), depth + 1)
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(if (b.tight) (if (look.flatHeadings) 4.dp else 3.dp) else look.gap.dp)) {
            b.items.forEachIndexed { i, item ->
                val reportItem = LocalReportStyle.current && depth == 0
                Row(Modifier.then(if (reportItem) Modifier.fillMaxWidth().clip(MikaShapes.Button)
                    .background(MikaTheme.colors.bg).padding(14.dp) else Modifier)) {
                    val marker = if (b.start != null) "${b.start + i}." else if (depth % 2 == 0) "•" else "◦"
                    Text(marker, Modifier.widthIn(min = 18.dp).padding(end = 6.dp), style = style.copy(color = if (reportItem) MikaTheme.colors.accent else look.markerInk.takeOrElse { MikaTheme.colors.ink2 }))
                    MarkdownBlocks(item, style, depth = depth + 1, gap = if (b.tight) 3 else 8)
                }
            }
        }
        is MdBlock.Table -> TableBlock(b, style)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(MikaTheme.colors.hair))
    }
}

/** Table: each column takes the width of its widest cell (capped; beyond that it wraps), each row takes the height of its tallest cell; the whole table can scroll horizontally. */
@Composable
private fun TableBlock(t: MdBlock.Table, style: TextStyle) {
    val report = LocalReportStyle.current
    val find = LocalFindHighlight.current
    val cols = t.header.size
    if (cols == 0) return
    // Cells are left-aligned: table column widths are fixed anyway, and justification would only stretch short sentences to full width
    val cellStyle = style.copy(fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Start)
    val rows = listOf(t.header) + t.rows
    Box(Modifier.then(if (report) Modifier.clip(MikaShapes.Card).background(MikaTheme.colors.surface) else Modifier)
        .horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    row.forEach { cell ->
                        Box(
                            Modifier.then(if (report) Modifier else Modifier.border(0.5.dp, MikaTheme.colors.hair))
                                .then(if (r == 0) Modifier.background(MikaTheme.colors.fill) else Modifier)
                                .padding(horizontal = if (report) 12.dp else 9.dp, vertical = if (report) 12.dp else 6.dp),
                        ) {
                            Text(
                                remember(cell, find) { cell.withFind(find) },
                                style = if (r == 0) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle,
                            )
                        }
                    }
                }
            },
            modifier = if (report) Modifier else Modifier.border(0.5.dp, MikaTheme.colors.hair, MikaShapes.Micro),
        ) { measurables, _ ->
            val maxCol = 240.dp.roundToPx()
            val minCol = 48.dp.roundToPx()
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCol, maxCol))
            }
            val heights = measurables.chunked(cols).map { row -> row.withIndex().maxOf { (c, m) -> m.minIntrinsicHeight(widths[c]) } }
            // Cells in the same row are the same height, so borders and the header background line up
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                placeables.chunked(cols).forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        p.place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

/** Code block: a lightweight top bar on top (language label on the left, a "Copy / Copied" button on the right), with the code body scrolling horizontally below. */
@Composable
private fun CodeBlock(b: MdBlock.Code, style: TextStyle) {
    val c = MikaTheme.colors
    val find = LocalFindHighlight.current
    val context = LocalContext.current
    var copyVersion by remember(b.code) { mutableIntStateOf(0) }
    val copied = copyVersion > 0
    LaunchedEffect(copyVersion) {
        if (copyVersion > 0) {
            delay(2_000)
            copyVersion = 0
        }
    }
    val shape = MikaShapes.Tile
    val language = b.language
    Column(Modifier.fillMaxWidth().clip(shape).background(c.fill)) {
        // When wrapped in a SelectionContainer (e.g. "select text" mode or assistant replies), the top bar label and button don't take part in drag selection.
        DisableSelection {
            Row(
                Modifier.fillMaxWidth().padding(start = 11.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (language.isNotEmpty()) {
                    Text(
                        text = language,
                        modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("code-block-language"),
                        style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                val label = stringResource(if (copied) R.string.chat_copied else R.string.chat_copy)
                val tint = if (copied) c.ok else c.ink2
                Row(
                    Modifier
                        .clip(RoundedCornerShape(MikaRadius.BubbleTail))
                        .alpha(if (b.code.isNotEmpty()) 1f else 0.4f)
                        .clickable(
                            enabled = b.code.isNotEmpty(),
                            role = Role.Button,
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(language.ifEmpty { "code" }, b.code))
                                copyVersion++
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("code-block-copy"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(if (copied) MikaIcons.Check else MikaIcons.Copy, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
                    Text(label, style = MikaType.Hint.copy(fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(remember(b.code, find) { b.code.withFind(find) }, style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), softWrap = false)
        }
    }
}
