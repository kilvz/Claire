package com.wangzan101.mika.core.agent

/**
 * How the sentence is written when the user asks Claire in Claire's browser about the page they are currently
 * viewing: first a block explaining which page it is (fixed English, which the model understands at once; the
 * conversation page and cards recognise this block), then a blank line, then what the user said this time (as-is).
 * For example
 * ```
 * [Web page I'm viewing in Claire's browser. It is attached to this task as your current web tab.]
 * Title: Noise-cancelling headphones - search results
 * URL: https://shop.example.com/s?k=noise-cancelling+headphones
 * Selected text:
 * | In-ear noise-cancelling headphones B2 Pro
 * [End of page]
 *
 * Pick one under 700 from this page
 * ```
 * This sentence is stored verbatim in the conversation and handed to the task, so this is exactly what the model
 * sees; a task started from a browser page carries it, and a steer also carries it when it brings newly selected
 * text.
 * Language hints, the answer language check and memory quotations only count the characters the user typed after the
 * block ([words]).
 * Each line of selected text starts with "| ", which keeps it apart from the other lines of the block; both the page
 * title and the selected text are truncated.
 */
object AttachedPage {
    const val HEADER = "[Web page I'm viewing in Claire's browser. It is attached to this task as your current web tab.]"
    const val END = "[End of page]"
    private const val TITLE = "Title: "
    private const val URL = "URL: "
    private const val SELECTED = "Selected text:"
    private const val SELECTED_LINE = "| "

    /** The page title is at most this many characters. */
    const val MAX_TITLE = 200

    /** The selected text is handed to the model up to this many characters; anything beyond is cut off and "…" is appended. */
    const val MAX_SELECTION = 2000

    /** The URL is at most this long (an extremely long tracking parameter is not carried in full). */
    const val MAX_URL = 1000

    /** The page being viewed: [selection] is the text the user selected, null when nothing is selected. */
    data class Page(val url: String, val title: String, val selection: String? = null)

    /** A split sentence: [page] is that page (null when not carried), [words] is what the user said this time (the original text when not carried). */
    data class Parsed(val page: Page?, val words: String)

    /** The block comes first and the words after; when the words are empty only the block remains. */
    fun compose(page: Page, words: String): String {
        val lines = mutableListOf(HEADER)
        oneLine(page.title).take(MAX_TITLE).takeIf(String::isNotEmpty)?.let { lines += TITLE + it }
        lines += URL + oneLine(page.url).take(MAX_URL)
        clip(page.selection)?.let { sel ->
            lines += SELECTED
            sel.lines().forEach { lines += SELECTED_LINE + it }
        }
        lines += END
        val block = lines.joinToString("\n")
        val w = words.trim()
        return if (w.isEmpty()) block else "$block\n\n$w"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return Parsed(null, text)
        val end = lines.indexOfFirst { it.trim() == END }
        if (end < 0) return Parsed(null, text)
        var title = ""
        var url = ""
        val selected = mutableListOf<String>()
        var inSelection = false
        for (l in lines.subList(1, end)) {
            when {
                inSelection && l.startsWith(SELECTED_LINE.trimEnd()) -> selected += l.removePrefix(SELECTED_LINE.trimEnd()).removePrefix(" ")
                l.startsWith(TITLE) -> title = l.removePrefix(TITLE)
                l.startsWith(URL) -> url = l.removePrefix(URL)
                l.trim() == SELECTED -> inSelection = true
            }
        }
        val selection = selected.joinToString("\n").trim().ifEmpty { null }
        return Parsed(Page(url.trim(), title.trim(), selection), lines.drop(end + 1).joinToString("\n").trim())
    }

    /** The words with the page block removed; returned as-is when not carried. */
    fun words(text: String): String = parse(text).words

    /** This sentence carries the page block. */
    fun has(text: String): Boolean = text.lineSequence().firstOrNull()?.trim() == HEADER

    /** The selected text: trimmed at both ends, trailing whitespace removed from each line, truncated with "…" appended if longer than [MAX_SELECTION]; null when empty. The block's end line never appears inside it. */
    fun clip(selection: String?): String? {
        val t = selection?.lines()?.joinToString("\n") { it.trimEnd() }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val cut = if (t.length <= MAX_SELECTION) t else t.take(MAX_SELECTION).trimEnd() + "…"
        return cut.replace(END, "[End]")
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
