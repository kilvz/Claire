package com.wangzan101.mika.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import com.wangzan101.mika.ui.theme.LightColors
import java.net.URLDecoder
import com.wangzan101.mika.ui.theme.MikaColors

/** Display model the Markdown in a reply is parsed into: block structure + styled inline text. Pure data, independent of the UI. */
sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Code(val info: String, val code: String) : MdBlock {
        /** Language label shown in the top bar: the first whitespace-delimited word of the CommonMark info string (e.g. `kotlin {1}` yields `kotlin`), empty if none. */
        val language: String get() = info.trim().takeWhile { !it.isWhitespace() }
    }
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** A null [start] means an unordered list; each list item is a group of blocks. */
    data class ListBlock(val start: Int?, val tight: Boolean, val items: List<List<MdBlock>>) : MdBlock
    /** Each row's cell count is padded up to the header's column count. */
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownModel {
    /** Quotes and lists nested any deeper are shown as plain text, to avoid excessive recursion. */
    private const val MAX_DEPTH = 12
    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

    /** Styles for inline code and links; the colors follow light/dark mode and are supplied by the display site from the current palette. */
    class Styles(val code: SpanStyle, val link: TextLinkStyles, val strong: FontWeight = FontWeight.SemiBold) {
        companion object {
            fun of(c: MikaColors, strong: FontWeight = FontWeight.SemiBold) = Styles(
                code = SpanStyle(fontFamily = FontFamily.Monospace, background = c.fill),
                link = TextLinkStyles(SpanStyle(color = c.accent)),
                strong = strong,
            )

            val Light = of(LightColors)
        }
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    /** When parsing fails (including any surprise caused by half-written streaming text), the whole thing is shown as one plain-text paragraph. */
    fun parse(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = try {
        blocks(parser.parse(markdown), 0, styles)
    } catch (e: Exception) {
        plain(markdown)
    } catch (e: StackOverflowError) {
        plain(markdown)
    }

    /** A streaming fragment may stop in the middle of syntax; only the tail is completed at display time, and the actual reply is not modified. */
    fun parseStreaming(markdown: String, styles: Styles = Styles.Light): List<MdBlock> = parse(StreamingMarkdown.preview(markdown), styles)

    /**
     * The reply as plain text with Markdown markers stripped (for copying and quoting): paragraphs, headings and code keep their original text, with a blank line between blocks;
     * one line per list item, prefixed with "• " or "1. "; each quote line prefixed with "> "; one line per table row, with cells separated by tabs; thematic breaks are dropped.
     */
    fun toPlainText(markdown: String): String = blocksText(parse(markdown)).trim()

    /**
     * Of the destinations of Markdown images `![..]` and links `[..]` in the reply, returns those that start with `/workspace`, deduplicated in order of appearance.
     * Percent escapes in the destination are decoded (file names with spaces or non-ASCII characters must be written as `%20`, `%E6` and the like per CommonMark);
     * illegal sequences that cannot be decoded are kept as-is, and a literal `+` is not treated as a space either.
     */
    fun workspaceRefs(markdown: String): List<String> = try {
        val out = LinkedHashSet<String>()
        fun visit(n: Node) {
            val dest = when (n) {
                is Image -> n.destination
                is Link -> n.destination
                else -> null
            }
            if (dest != null && dest.startsWith("/workspace")) out.add(percentDecoded(dest))
            var c = n.firstChild
            while (c != null) { visit(c); c = c.next }
        }
        visit(parser.parse(markdown))
        out.toList()
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    /** Link destinations are written per URI rules: valid `%HH` is decoded as UTF-8; illegal escapes are left alone, and `+` is a literal plus in a file name, not a space. */
    private fun percentDecoded(dest: String): String =
        runCatching { URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)

    private fun blocksText(blocks: List<MdBlock>, sep: String = "\n\n"): String = blocks.mapNotNull { b ->
        when (b) {
            is MdBlock.Para -> b.text.text
            is MdBlock.Heading -> b.text.text
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> blocksText(b.blocks).lines().joinToString("\n") { "> $it".trimEnd() }
            is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
                val mark = if (b.start == null) "• " else "${b.start + i}. "
                mark + blocksText(item, "\n").lines().joinToString("\n" + " ".repeat(mark.length))
            }.joinToString("\n")
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("\t") { it.text } }
            MdBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }.joinToString(sep)

    private fun plain(s: String): List<MdBlock> = if (s.isBlank()) emptyList() else listOf(MdBlock.Para(AnnotatedString(s.trim())))

    private fun children(n: Node): Sequence<Node> = generateSequence(n.firstChild) { it.next }

    private fun blocks(parent: Node, depth: Int, s: Styles): List<MdBlock> = children(parent).mapNotNull { block(it, depth, s) }.toList()

    private fun block(n: Node, depth: Int, s: Styles): MdBlock? {
        if (depth > MAX_DEPTH) return MdBlock.Para(AnnotatedString(plainText(n)))
        return when (n) {
            is Paragraph -> MdBlock.Para(inlines(n, s))
            is org.commonmark.node.Heading -> MdBlock.Heading(n.level, inlines(n, s))
            is FencedCodeBlock -> MdBlock.Code(n.info.orEmpty().trim(), n.literal.orEmpty().trimEnd('\n'))
            is IndentedCodeBlock -> MdBlock.Code("", n.literal.orEmpty().trimEnd('\n'))
            is HtmlBlock -> MdBlock.Para(AnnotatedString(n.literal.orEmpty().trimEnd('\n')))
            is BlockQuote -> MdBlock.Quote(blocks(n, depth + 1, s))
            is ListBlock -> MdBlock.ListBlock(
                start = (n as? OrderedList)?.let { it.markerStartNumber ?: 1 },
                tight = n.isTight,
                items = children(n).filterIsInstance<ListItem>().map { blocks(it, depth + 1, s) }.toList(),
            )
            is TableBlock -> table(n, s)
            is ThematicBreak -> MdBlock.Rule
            is LinkReferenceDefinition -> null
            else -> plainText(n).takeIf { it.isNotBlank() }?.let { MdBlock.Para(AnnotatedString(it)) }
        }
    }

    private fun table(t: TableBlock, s: Styles): MdBlock.Table {
        val rows = mutableListOf<List<AnnotatedString>>()
        var header: List<AnnotatedString> = emptyList()
        for (section in children(t)) for (row in children(section).filterIsInstance<TableRow>()) {
            val cells = children(row).filterIsInstance<TableCell>().map { inlines(it, s) }.toList()
            if (section is TableHead && header.isEmpty()) header = cells else rows += cells
        }
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        fun pad(r: List<AnnotatedString>) = r + List(cols - r.size) { AnnotatedString("") }
        return MdBlock.Table(pad(header), rows.map(::pad))
    }

    private fun inlines(parent: Node, s: Styles): AnnotatedString = buildAnnotatedString { appendInlines(parent, s) }

    private fun AnnotatedString.Builder.appendInlines(parent: Node, s: Styles) {
        for (n in children(parent)) when (n) {
            is Text -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> withStyle(s.code) { append(n.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(n, s) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = s.strong)) { appendInlines(n, s) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(n, s) }
            is Link -> link(n.destination, s) { appendInlines(n, s) }
            // Images are not shown in chat, only their alt text is kept (made into a link when there is an openable address)
            is Image -> link(n.destination, s) { if (n.firstChild != null) appendInlines(n, s) else append(n.destination.orEmpty()) }
            is HtmlInline -> append(n.literal)
            else -> appendInlines(n, s)
        }
    }

    private inline fun AnnotatedString.Builder.link(dest: String?, s: Styles, content: AnnotatedString.Builder.() -> Unit) {
        val url = dest?.trim().orEmpty()
        if (url.substringBefore(':', "").lowercase() in LINK_SCHEMES) withLink(LinkAnnotation.Url(url, s.link)) { content() } else content()
    }

    private fun plainText(n: Node): String = buildString {
        fun walk(x: Node) {
            when (x) {
                is Text -> append(x.literal)
                is Code -> append(x.literal)
                is FencedCodeBlock -> append(x.literal)
                is IndentedCodeBlock -> append(x.literal)
                is HtmlBlock -> append(x.literal)
                is HtmlInline -> append(x.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> {
                    children(x).forEach(::walk)
                    if (x is Paragraph || x is org.commonmark.node.Heading) append('\n')
                }
            }
        }
        walk(n)
    }.trim()
}
