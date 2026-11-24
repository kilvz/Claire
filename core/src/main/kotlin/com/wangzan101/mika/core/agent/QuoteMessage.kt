package com.wangzan101.mika.core.agent

/**
 * How a user message that quotes an earlier message is written: the first line states whose words are quoted (fixed
 * English the model understands at a glance; the conversation page recognizes this line), then every line starting
 * with "> " is the quoted content, and after a blank line come the user's words this time. For example
 * ```
 * > [Quoting your earlier reply]
 * > Light rain tomorrow morning…
 *
 * Should I bring an umbrella?
 * ```
 * This text is stored in the conversation and handed to the task verbatim (new tasks and interjected messages alike),
 * so this is exactly what the model sees; the conversation page uses it to draw the quoted content as a small pale
 * block above the bubble. The quoted part: language hints and the reply language check do not count it; memory and
 * skill quotes count it only when what is quoted is the user's own words (see [UserWords]). Text in old conversations
 * has no such line and is shown as-is.
 */
object QuoteMessage {
    /** Whose words are quoted: Claire's reply, or something the user said. */
    enum class From(val header: String) {
        Mika("> [Quoting your earlier reply]"),
        You("> [Quoting my earlier message]"),
    }

    /** At most this many characters of quoted content are handed to the model; the rest is cut off and "…" is appended. */
    const val MAX_CHARS = 1000

    data class Quote(val from: From, val text: String)

    /** A message split apart: [quote] is the quoted message (null when nothing is quoted), [words] are the user's words this time (the original text when nothing is quoted). */
    data class Parsed(val quote: Quote?, val words: String)

    /** Quoted content: trimmed, and truncated with "…" appended when it exceeds [MAX_CHARS]. */
    fun clip(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS).trimEnd() + "…"
    }

    /** The quote comes first and the words after it; with no quote, or empty quoted content, it is just the original text. */
    fun compose(quote: Quote?, words: String): String {
        val body = quote?.let { clip(it.text) }?.takeIf { it.isNotEmpty() } ?: return words
        val block = (listOf(quote.from.header) + body.lines().map { if (it.isBlank()) ">" else "> $it" }).joinToString("\n")
        return if (words.isEmpty()) block else "$block\n\n$words"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        val from = From.entries.firstOrNull { it.header == lines.firstOrNull()?.trim() } ?: return Parsed(null, text)
        var i = 1
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].startsWith(">")) {
            quoted += lines[i].removePrefix(">").removePrefix(" ")
            i++
        }
        return Parsed(Quote(from, quoted.joinToString("\n").trim()), lines.drop(i).joinToString("\n").trim())
    }

    /** The words with the quote removed; without a quote the text is returned as-is. */
    fun words(text: String): String = parse(text).words
}
