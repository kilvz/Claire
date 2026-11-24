package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/** Texts the conversation page derives from state; it touches no UI and is unit-tested directly. */
object ChatCopy {
    /** Greeting for an empty conversation, by time of day. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)
    /** A value statement for the empty conversation: explains what Claire can do. */
    val EMPTY_VALUE get() = tr(R.string.chat_empty_value)

    /** The kind of a built-in example; it only decides the icon and tint of the card on the home screen. */
    enum class ExampleKind { Web, Follow, GroupChat, Alarm, Schedule }
    data class Example(val kind: ExampleKind, val title: String, val prompt: String)

    /**
     * The text of the built-in recommended tasks. Used to initialize once only when no recommended task has been saved on this device yet;
     * both the title and the prompt come from this entry, and after the user edits them in settings they are not added back by a language change or an upgrade.
     */
    val examples get() = listOf(
        Example(ExampleKind.Web, tr(R.string.chat_example_github_star_title), tr(R.string.chat_example_github_star)),
        Example(ExampleKind.Follow, tr(R.string.chat_example_follow_title), tr(R.string.chat_example_follow)),
        Example(ExampleKind.GroupChat, tr(R.string.chat_example_group_summary_title), tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Alarm, tr(R.string.chat_example_alarms_title), tr(R.string.chat_example_alarms)),
        Example(ExampleKind.Schedule, tr(R.string.chat_example_schedule_title), tr(R.string.chat_example_schedule)),
    )

    fun recommendationSeeds(): List<Pair<String, String>> = examples.map { it.title to it.prompt }

    /**
     * Which kind a recommended task belongs to: when the prompt matches a built-in example exactly it is that built-in one (recognized even if the title was edited);
     * tasks added by the user have no kind, so null is returned and the UI uses a generic icon.
     */
    fun kindOf(prompt: String): ExampleKind? = examples.firstOrNull { it.prompt == prompt }?.kind

    /**
     * Examples that used to be built in and have since been removed. On upgrade, the entry the user has not touched is cleared from the recommended list;
     * one whose title or prompt was edited is kept — it is the user's own task by now.
     */
    fun retiredRecommendationSeeds(): List<Pair<String, String>> = listOf(
        tr(R.string.chat_example_pdf_title) to tr(R.string.chat_example_pdf),
    )

    /** Title of the conversation page's top bar: always the app name; the subtitle says what is happening right now. */
    const val APP_TITLE = "Claire"

    /** Name of the conversation: its first message (first line only, attachment lines excluded; when only the screen was sent it is "your current screen"); empty for an empty conversation. Used in the drawer to refer to the conversation. */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * How a user message is turned into a title: strip the page note ([AttachedPage]), quotes ([QuoteMessage]), the attachment lines (when only attachments were sent it becomes something like "Photo" or "contract.pdf") and the screen line (when only the screen was sent it is "your current screen"),
     * then keep the first line only.
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(QuoteMessage.words(AttachedPage.words(text)))).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * Title of a branch: the source title (or first message) followed by the localized suffix, no longer than [max] characters in total.
     * The source title is what gets truncated and the suffix is kept; truncation is by code point, so an emoji is never cut in half.
     */
    fun branchTitle(base: String, suffix: String, max: Int = 24): String {
        val room = (max - suffix.length).coerceAtLeast(0)
        var end = 0
        var taken = 0
        while (end < base.length && taken < room) {
            end += Character.charCount(base.codePointAt(end))
            taken++
        }
        return base.substring(0, end).trimEnd() + suffix
    }

    enum class Tone { Accent, Warn, Ok }

    /** Top bar subtitle: this conversation keeps a page running in the background (when no task is running). */
    val KEPT_PAGE: String get() = tr(R.string.kept_chat_sub)

    /** Top bar subtitle: what it is doing (accent color) or waiting for you (orange); null when no task is running. [place] is the app or website being operated. */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.Queued -> tr(R.string.chat_queued) to Tone.Accent
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** The progress line: the current progress while running, and "Paused" when it has stopped to wait for you. */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** The task has stopped and waits for you (needs you to act, answer, approve, unlock or tap continue). */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** Elapsed time on the right of the progress line: "0:24", "12:05", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** While running, only the most recent steps are listed; earlier ones collapse into a single line "n earlier steps" that expands to show all. */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** The small line below the steps while operating the phone in the background. */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    /**
     * The outcome of one action (the effect code in the record → a sentence for people to read). Error codes only mean something to developers,
     * so users are always told "didn't succeed", except for the cases that are declined as intended ([declinedLabel]). The run details page uses the same wording.
     */
    fun effectLabel(effect: String?): String = when (effect) {
        "changed", "done", "place_changed" -> tr(R.string.result_effective)
        "small_change" -> tr(R.string.effect_small_change)
        "unchanged", "no_effect" -> tr(R.string.result_unchanged)
        "completed" -> tr(R.string.result_completed)
        null, "" -> ""
        else -> declinedLabel(effect) ?: tr(R.string.step_failed)
    }

    /**
     * Writes to memory, skill saves and profile edits can be declined by policy (not said by the user themselves, sensitive, no explicit request to change or delete): this is not a malfunction,
     * and saying "didn't succeed" would make it look broken. State the declination and the reason; null for other error codes.
     */
    fun declinedLabel(code: String?): String? = when (code) {
        "quote_not_from_user", "content_not_from_user" -> tr(R.string.step_declined_not_said)
        "inference_not_requested" -> tr(R.string.step_declined_not_requested)
        "sensitive" -> tr(R.string.step_declined_sensitive)
        "edit_not_requested" -> tr(R.string.step_declined_not_asked)
        else -> null
    }

    /** One line per action after expanding a step: what was done · on what · the outcome (omitted when the outcome is empty). */
    fun actionLine(a: StepAction): String =
        listOfNotNull(a.label, a.target?.takeIf(String::isNotBlank), effectLabel(a.effect).takeIf(String::isNotBlank)).joinToString(" · ")

    fun placeholder(mode: ComposerMode, hasHistory: Boolean): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }

    /** The centered grey text when hold-to-talk is available: tells the user they can hold to speak or tap to type; while a task is running, what is said becomes a follow-up message. */
    fun voicePlaceholder(mode: ComposerMode): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.voice_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.voice_placeholder_answer)
        ComposerMode.Idle -> tr(R.string.voice_placeholder_idle)
    }
}
