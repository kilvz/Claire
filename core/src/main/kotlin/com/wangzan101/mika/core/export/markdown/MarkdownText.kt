package com.wangzan101.mika.core.export.markdown

import java.net.URLEncoder

/** Escaping for Markdown export: ordinary text is treated as plain text; link destinations are handled separately. */
internal object MarkdownText {
    private val SPECIAL = setOf('\\', '`', '*', '_', '[', ']', '<', '>', '|', '#', '!', '~')

    /**
     * Plain text such as user wording, titles and captions: escapes characters Markdown would interpret as structure or HTML.
     * Paired ~~ would be read as strikethrough, so the whole character set is escaped. Line starts have their own whole-line structure:
     * -/+ starts a list, a run of =/- lifts the previous line into a setext heading, and a number followed by . or ) starts an ordered list, so the leading character is escaped too.
     */
    fun plain(text: String): String = buildString(text.length) {
        var lineStart = true
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                append(ch)
                i++
                lineStart = true
                continue
            }
            if (lineStart) {
                lineStart = false
                // CommonMark still opens a list/setext block with up to three
                // leading spaces; four spaces are an indented code block, where
                // escaping the marker would change the literal code text.
                var marker = i
                while (marker < text.length && marker - i < 4 && text[marker] == ' ') marker++
                if (marker - i <= 3) {
                    while (i < marker) { append(' '); i++ }
                    if (i < text.length) {
                        val markerChar = text[i]
                        if (markerChar == '-' || markerChar == '+' || markerChar == '=') {
                            append('\\')
                            append(markerChar)
                            i++
                            continue
                        }
                        if (markerChar in '0'..'9') {
                            var j = i
                            while (j < text.length && text[j] in '0'..'9') j++
                            if (j < text.length && (text[j] == '.' || text[j] == ')')) {
                                append(text, i, j)
                                append('\\')
                                append(text[j])
                                i = j + 1
                                continue
                            }
                        }
                        if (markerChar in SPECIAL) append('\\')
                        append(markerChar)
                        i++
                        if (markerChar == '\n' || markerChar == '\r') lineStart = true
                        continue
                    }
                    continue
                }
            }
            if (ch in SPECIAL) append('\\')
            append(ch)
            i++
        }
    }

    /** Inline code: uses a fence longer than the longest backtick run in the content, so backticks in the content cannot close it early. */
    fun codeSpan(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(1, longest + 1))
        return "$fence$text$fence"
    }

    /** Allows http/https only; the URL is wrapped in angle brackets so parentheses or spaces in the address cannot end the link early. */
    fun destination(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        val safe = trimmed.replace("<", "%3C").replace(">", "%3E").replace("\r", "").replace("\n", "%0A")
        return "<$safe>"
    }

    /** In-package asset path: the file name is percent-encoded as UTF-8, so parentheses and the like cannot break out of the link. */
    fun assetPath(fileName: String): String = "assets/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /** Tool output may contain backtick runs of any length; the fence is at least one longer than the longest run. */
    fun codeFence(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longest + 1))
    }

    /**
     * The answer body keeps Markdown, but HTML outside fenced code blocks is not passed through verbatim:
     * model output may be steered by web page content, and the exported file may be opened by other renderers.
     * Fences are recognized only with 0–3 spaces of indentation (CommonMark); `<` inside inline code is left alone so code display is not broken.
     */
    fun safeHtml(text: String): String {
        val out = StringBuilder(text.length)
        var fenceChar: Char? = null
        var fenceLength = 0
        for (line in text.lineSequence()) {
            val trimmed = line.dropWhile { it == ' ' }
            val indent = line.length - trimmed.length
            val current = fenceChar
            if (current == null) {
                val open = openingFence(trimmed, indent)
                if (open != null) {
                    fenceChar = open.first
                    fenceLength = open.second
                    out.append(line).append('\n')
                } else {
                    out.append(escapeInlineHtml(line)).append('\n')
                }
            } else if (closesFence(trimmed, indent, current, fenceLength)) {
                fenceChar = null
                fenceLength = 0
                out.append(line).append('\n')
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun openingFence(trimmed: String, indent: Int): Pair<Char, Int>? {
        if (indent > 3 || trimmed.isEmpty()) return null
        val ch = trimmed[0]
        if (ch != '`' && ch != '~') return null
        var length = 0
        while (length < trimmed.length && trimmed[length] == ch) length++
        if (length < 3) return null
        // A backtick fence's info string must not contain another backtick; otherwise it is not a fence and cannot be used to turn escaping off.
        if (ch == '`' && '`' in trimmed.substring(length)) return null
        return ch to length
    }

    private fun closesFence(trimmed: String, indent: Int, ch: Char, length: Int): Boolean {
        if (indent > 3) return false
        var n = 0
        while (n < trimmed.length && trimmed[n] == ch) n++
        return n >= length && trimmed.substring(n).isBlank()
    }

    /** `<` inside inline code is kept verbatim; `<` outside code is escaped so that no HTML tag is formed. */
    private fun escapeInlineHtml(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                var run = 1
                while (i + run < line.length && line[i + run] == '`') run++
                val marker = "`".repeat(run)
                val close = line.indexOf(marker, i + run)
                if (close >= 0) {
                    out.append(line, i, close + run)
                    i = close + run
                    continue
                }
            }
            val ch = line[i]
            if (ch == '<') out.append("&lt;") else out.append(ch)
            i++
        }
        return out.toString()
    }
}
