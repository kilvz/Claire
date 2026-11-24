package com.wangzan101.mika.core.screen.user

import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a message carrying the user's screen is written when given to the model; defined only here. Kept separate from gallery images (attachments, still plain files):
 * - the user's message itself becomes "the user just captured the screen they are looking at (app, time), with no accompanying words / accompanying words: …" (when screenshots are disallowed, state plainly that nothing was captured);
 * - the on-screen text and the id form a separate context section ([detail]), and with no accompanying words the rules for inferring intent from the screen are appended;
 * - screens from earlier turns collapse to a single line ([earlier]): app, time and the first few characters on screen, with the id to look again.
 */
object UserScreenPrompt {
    /** How many leading characters of that screen are kept for earlier turns. */
    const val GIST_CHARS = 40

    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)

    fun time(at: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    /** The user's message: [screen] null means the screenshot no longer exists. */
    fun said(id: String, screen: UserScreen?, words: String, zone: ZoneId): String {
        if (screen == null) return listOf(Feedback.format("user_screen.missing", "id" to id), words).filter(String::isNotBlank).joinToString("\n")
        val vars = arrayOf("app" to screen.app, "time" to time(screen.takenAt, zone), "words" to words)
        return when {
            screen.blocked && words.isEmpty() -> Feedback.format("user_screen.blocked", *vars)
            screen.blocked -> Feedback.format("user_screen.blocked_with_words", *vars)
            words.isEmpty() -> Feedback.format("user_screen.sent", *vars)
            else -> Feedback.format("user_screen.sent_with_words", *vars)
        }
    }

    /**
     * Details of this screen (the context body): id, app, time, and the on-screen text (wrapped in the untrusted marker: the on-screen text was written by others or by the app);
     * with [noWords] the rules for inferring intent from the screen are appended, and with [askVision] it says the vision model can be asked when the image cannot be seen. For screenshot-protected pages it only says how to reply when nothing could be captured.
     */
    fun detail(screen: UserScreen, noWords: Boolean, askVision: Boolean, zone: ZoneId): String {
        if (screen.blocked) return Feedback["user_screen.blocked_reply"]
        val lines = if (screen.lines.isEmpty()) Feedback["user_screen.no_text"]
            else Untrusted.wrap(screen.lines.joinToString("\n") { "(${it.x},${it.y}) ${it.text}" })
        val head = Feedback.format(
            "user_screen.detail", "id" to screen.id, "app" to screen.app,
            "package" to (screen.packageName?.takeIf { it != screen.app }?.let { "($it)" }.orEmpty()),
            "time" to time(screen.takenAt, zone), "lines" to lines,
        )
        return listOfNotNull(head, Feedback["user_screen.ask_vision"].takeIf { askVision }, Feedback["user_screen.no_words"].takeIf { noWords })
            .joinToString("\n\n")
    }

    /** Collapses a screen from an earlier turn into one line; when the screenshot no longer exists it only says so. */
    fun earlier(id: String, screen: UserScreen?, zone: ZoneId): String {
        if (screen == null) return Feedback.format("user_screen.missing", "id" to id)
        val vars = arrayOf("id" to id, "app" to screen.app, "time" to time(screen.takenAt, zone))
        if (screen.blocked) return Feedback.format("user_screen.earlier_blocked", *vars)
        val gist = gist(screen) ?: return Feedback.format("user_screen.earlier_blank", *vars)
        return Feedback.format("user_screen.earlier", *vars, "gist" to gist)
    }

    /** The first few characters on screen (skipping very short fragments), used for the earlier-turn line; null when there is no text. */
    fun gist(screen: UserScreen): String? {
        val text = screen.lines.map { it.text }.filter { it.length >= 2 }.joinToString(" ").ifEmpty { return null }
        return if (text.length <= GIST_CHARS) text else text.take(GIST_CHARS) + "…"
    }

    /** A user message from an earlier turn: the screen line is replaced with [earlier] and the accompanying words are appended after it (on one line). Messages without a screen are returned unchanged. */
    fun collapse(text: String, lookup: (String) -> UserScreen?, zone: ZoneId): String {
        val p = UserScreenMessage.parse(text)
        if (!p.hasScreen) return text
        return (p.screens.map { earlier(it, lookup(it), zone) } + p.words.replace('\n', ' ')).filter(String::isNotBlank).joinToString(" ")
    }
}
