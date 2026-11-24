package com.wangzan101.mika.core.agent

/**
 * How attachments carried in the user's words are written: one line per attachment at the end of the message
 * "Attachment: /workspace/inbox/<name> (<size>)". These lines are stored verbatim in the conversation and handed to
 * the task.
 * The conversation page uses them to render attachments as photo tiles and file cards (the app's
 * AttachmentMessage); language hints, the answer language check and memory, and skill quotations do not count these
 * lines (see [UserWords]).
 * Messages in old conversations are written the same way.
 */
object AttachmentLines {
    private const val PREFIX = "附件:"

    /** Names may contain parentheses ("report (1).csv"): only the last pair of parentheses is taken as the size, and only its "number + B/KB/MB"; the path is under /workspace in the sandbox. */
    private val LINE = Regex("^附件:(/workspace/.+)\\((\\d+(?:\\.\\d+)? (?:B|KB|MB))\\)$")

    /** A split message: [files] is the path and the size written in the message ("2 KB") for each attachment, in order of appearance; [words] is the text other than attachment lines (trimmed). */
    data class Parsed(val files: List<Pair<String, String>>, val words: String)

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun composeBytes(text: String, files: List<Pair<String, Long>>): String =
        compose(text, files.map { (path, bytes) -> path to sizeLabel(bytes) })

    fun line(path: String, size: String): String = "$PREFIX$path($size)"

    /** The words come first, one line per attachment after; when the words are empty only the attachment lines remain. */
    fun compose(text: String, files: List<Pair<String, String>>): String =
        (listOf(text).filter { it.isNotEmpty() } + files.map { (p, s) -> line(p, s) }).joinToString("\n")

    fun parse(text: String): Parsed {
        val files = mutableListOf<Pair<String, String>>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) files += m.groupValues[1] to m.groupValues[2] else rest += l
        }
        if (files.isEmpty()) return Parsed(emptyList(), text.trim())
        return Parsed(files, rest.joinToString("\n").trim())
    }

    /** The words with attachment lines removed; returned as-is when there are no attachment lines. */
    fun words(text: String): String = parse(text).let { if (it.files.isEmpty()) text else it.words }
}
