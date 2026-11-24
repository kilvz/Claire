package com.wangzan101.mika.core.proactive

/** The conclusion of [NoticeFilter.decide]: keep, or why it was dropped. */
enum class NoticeDecision {
    Keep,
    /** Not among the apps the user selected. */
    NotWhitelisted,
    /** A notification from Claire itself (any of its install packages): not accepted even when selected. */
    SelfPackage,
    /** An app disabled under "app access". */
    AppBlocked,
    Ongoing,
    GroupSummary,
    ForegroundService,
    NotClearable,
    /** Categories such as progress, playback control, background service, incoming call, alarm, navigation and stopwatch. */
    Category,
    /** Both title and text are blank. */
    Blank,
    /** Authentication secrets must never reach the inbox, journal or models. */
    AuthenticationSecret,
}

/**
 * Notification filtering: pure functions. The package name is checked first (whitelist → self → disabled under app access), then the notification's traits, and finally whether it has any content.
 * The package-name gate ([decidePackage]) runs before the notification content is read at all: notifications outside the whitelist never have their content read.
 */
object NoticeFilter {
    const val SELF_PREFIX = "com.wangzan101.mika"
    const val TITLE_MAX = 200
    const val TEXT_MAX = 2_000
    const val MESSAGES_MAX = 20

    /** Categories that are not accepted (values match android.app.Notification.CATEGORY_*). */
    val DROPPED_CATEGORIES = setOf("progress", "transport", "service", "call", "alarm", "navigation", "stopwatch")

    /** Claire's own packages: the release build, .dev, .dev.test and so on all count. */
    fun isSelf(packageName: String): Boolean = packageName.startsWith(SELF_PREFIX)

    /** The gate that looks at the package name only; [blocked] holds the package names disabled under app access. */
    fun decidePackage(packageName: String, whitelist: Set<String>, blocked: Set<String>): NoticeDecision = when {
        packageName !in whitelist -> NoticeDecision.NotWhitelisted
        isSelf(packageName) -> NoticeDecision.SelfPackage
        packageName in blocked -> NoticeDecision.AppBlocked
        else -> NoticeDecision.Keep
    }

    /** The conclusion for a whole record. [record] should already have gone through [sanitize]; a title made only of control characters counts as blank. */
    fun decide(record: NoticeRecord, whitelist: Set<String>, blocked: Set<String>): NoticeDecision {
        val byPackage = decidePackage(record.packageName, whitelist, blocked)
        if (byPackage != NoticeDecision.Keep) return byPackage
        return when {
            LearnText.sensitive(listOf(record.title, record.text, record.messages.joinToString(" ") { it.text }).joinToString(" ")) -> NoticeDecision.AuthenticationSecret
            record.ongoing -> NoticeDecision.Ongoing
            record.groupSummary -> NoticeDecision.GroupSummary
            record.foregroundService -> NoticeDecision.ForegroundService
            !record.clearable -> NoticeDecision.NotClearable
            record.category in DROPPED_CATEGORIES -> NoticeDecision.Category
            record.title.isBlank() && record.text.isBlank() -> NoticeDecision.Blank
            else -> NoticeDecision.Keep
        }
    }

    /** Cleaning: strips control characters, bidirectional text controls and invisible formatting characters, collapses whitespace to a single space and truncates by length; only the most recent [MESSAGES_MAX] messages are kept. */
    fun sanitize(record: NoticeRecord): NoticeRecord = record.copy(
        title = clean(record.title, TITLE_MAX),
        conversationTitle = record.conversationTitle?.let { clean(it, TITLE_MAX) }?.ifBlank { null },
        text = clean(record.text, TEXT_MAX),
        messages = record.messages
            .map { m -> m.copy(sender = m.sender?.let { clean(it, TITLE_MAX) }?.ifBlank { null }, text = clean(m.text, TEXT_MAX)) }
            .filter { it.text.isNotBlank() }
            .takeLast(MESSAGES_MAX),
    )

    private val BIDI = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C]")
    // Invisible formatting characters: soft hyphen, Mongolian vowel separator, word joiner through invisible operator. Embedded in the closing marker they would make it look unlike a marker;
    // they are stripped along with the bidirectional controls before whitespace is collapsed (U+180E still counts as a space on some platforms). U+200C / U+200D are kept: emoji and some scripts need them
    private val INVISIBLE = Regex("[\\u00AD\\u180E\\u2060-\\u2064]")
    // No (?U): the regex engine bundled with Android (ICU) does not know that flag and throws as soon as it is loaded; so the Unicode whitespace characters are listed explicitly here,
    // which gives the same result on a desktop JVM and on Android. \p{Z} covers the various spaces and the line/paragraph separators, plus zero-width space and the BOM.
    private val SPACES = Regex("[\\s\\p{Z}\\u200B\\uFEFF]+")

    internal fun clean(s: String, max: Int): String {
        val noControl = buildString(s.length) { s.forEach { append(if (Character.isISOControl(it)) ' ' else it) } }
        val collapsed = SPACES.replace(INVISIBLE.replace(BIDI.replace(noControl, ""), ""), " ").trim()
        return cap(collapsed, max)
    }

    /**
     * An app's display name, tightened up before the model sees it. The name is written by the installed app itself in its package (third-party text), yet we put it outside the untrusted markers
     * and use it as our own label: so clean it, cut it to [APP_NAME_MAX] characters, and remove the symbols that could build markers or structure (angle brackets, square brackets, curly braces, quotes, backticks,
     * colons, commas, semicolons). This only stops it from building anything that looks like formatting in our input; it does not make it trustworthy — the words in the name are still there.
     */
    fun appName(raw: String): String =
        clean(buildString(raw.length) { for (ch in raw) append(if (ch in APP_NAME_MARKUP) ' ' else ch) }, APP_NAME_MAX)

    /** Same as above; if the result is empty (the name was all symbols), [packageName] stands in so there is never an empty label. */
    fun appName(raw: String, packageName: String): String = appName(raw).ifEmpty { packageName }

    const val APP_NAME_MAX = 40
    // Compared character by character instead of with a regex: this class is loaded for every incoming notification, and the regex engine bundled with Android is pickier about patterns than a desktop JVM (see the SPACES comment below),
    // so a pattern that works fine on a computer may throw as soon as it is loaded on a phone
    private const val APP_NAME_MARKUP = "<>[]{}\"'`:：,，;；"

    /** Truncates to at most [max] UTF-16 units without splitting a surrogate pair. */
    internal fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }
}
