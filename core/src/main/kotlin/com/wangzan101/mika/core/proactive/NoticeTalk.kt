package com.wangzan101.mika.core.proactive

/**
 * The message Claire writes into the conversation when the user opens the hints: it lists the notifications judged worth surfacing. Pure functions, no model call; the fixed phrases ([Copy]) are supplied by the app per UI language.
 *
 * This message is displayed as Markdown (both the conversation page and the floating window). The words in notifications are written by others: everything is first cleaned and shortened ([NoticeFilter.clean]),
 * then Markdown-significant symbols are escaped ([plain]), and it is placed inside quotes and never at the start of a line: links, images, headings and lists written in notifications are just text,
 * neither clickable nor able to affect layout. How it is treated when handed to the model is not handled here (see [com.wangzan101.mika.core.agent.NoticeTurn]).
 */
object NoticeTalk {
    /** How many items one message lists at most (newest first); anything extra is only mentioned as a remaining count. */
    const val ITEMS_MAX = 6
    const val APP_MAX = 40
    const val TITLE_MAX = 60
    const val TEXT_MAX = 200
    const val REASON_MAX = 80

    /**
     * [app] is the app name read from the system by package name, and [time] is the time formatted by the app; [title] (the conversation's name) and [text] (the sentence, carrying who said it for groups)
     * come from the notification, and [reason] is the reason the model wrote.
     */
    data class Item(val app: String, val time: String, val title: String, val text: String, val reason: String)

    /** The fixed phrases: the opening ([intro], argument is the number listed), the reason line ([reason], argument is the reason), the not-listed one ([more], argument is the count) and the closing. */
    class Copy(val intro: (Int) -> String, val reason: (String) -> String, val more: (Int) -> String, val outro: String)

    /**
     * The composed message, and how many items it actually listed ([shown]: the first few of those handed in). Only the listed ones can be marked seen:
     * the ones not listed must stay for the next time the user opens the hints, to be listed then. The count is supplied here by whoever composes the message, so callers need not recompute it.
     */
    class Composed(val text: String, val shown: Int)

    /** [items] is newest first; the first [ITEMS_MAX] are listed. Returns null when empty (nothing to say). */
    fun compose(items: List<Item>, copy: Copy): Composed? {
        if (items.isEmpty()) return null
        val shown = items.take(ITEMS_MAX)
        val text = buildString {
            append(copy.intro(shown.size))
            shown.forEachIndexed { i, it ->
                append("\n\n")
                // first line: which app, which conversation (the notification's title: a person, a group, or things like "payment received") and what time
                val title = field(it.title, TITLE_MAX)
                append("**").append(i + 1).append(". ").append(field(it.app, APP_MAX))
                if (title.isNotEmpty()) append(" · ").append(title)
                if (it.time.isNotBlank()) append(" · ").append(field(it.time, APP_MAX))
                append("**")
                // second line: the sentence (carrying who said it for groups)
                val text = field(it.text, TEXT_MAX)
                if (text.isNotEmpty()) append('\n').append('“').append(text).append('”')
                val reason = field(it.reason, REASON_MAX)
                if (reason.isNotEmpty()) append('\n').append(copy.reason(reason))
            }
            if (items.size > shown.size) append("\n\n").append(copy.more(items.size - shown.size))
            append("\n\n").append(copy.outro)
        }
        return Composed(text, shown.size)
    }

    private fun field(s: String, max: Int): String = plain(NoticeFilter.clean(s, max))

    // symbols that do something in Markdown (CommonMark plus tables and strikethrough): a preceding backslash makes them plain text
    private const val SPECIAL = "\\`*_[]<>&~#|"

    /** Escapes the Markdown-significant symbols in [s]; [s] should already be a single cleaned line. */
    fun plain(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            if (ch in SPECIAL) append('\\')
            append(ch)
        }
    }
}
