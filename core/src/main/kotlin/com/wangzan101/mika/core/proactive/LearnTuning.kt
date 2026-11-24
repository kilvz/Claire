package com.wangzan101.mika.core.proactive

/**
 * All tunable numbers for "summarize from notifications", gathered here. They are provisional: first check on a real device whether what gets accumulated looks right, then adjust.
 */
object LearnTuning {
    // ---- collection and sending ----
    /** Maximum number of events kept in memory (when full, the oldest already-summarized ones are dropped first). */
    const val BUFFER_CAP = 200
    /** Maximum entries per batch (a single app, oldest sent first). */
    const val BATCH_MAX = 12
    /** Once one app has accumulated this many unsummarized entries, it is due to be sent. */
    const val DUE_COUNT = 12
    /** Or its oldest unsummarized entry is already this old (only checked when something happens, never on a timer). */
    const val DUE_AGE_MS = 30 * 60_000L
    /** Maximum time to wait for one request. */
    const val TIMEOUT_MS = 30_000L
    /**
     * How long to pause before sending the next batch after a batch failed to be summarized: it lengthens step by step with the number of consecutive failures, then stays at the last step (the same durations as detection uses).
     * No timer: even once the time is up, it waits for the next event before checking.
     */
    val FAILURE_HOLD_MS: List<Long> = listOf(2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    /** How long to pause after the [failures]-th consecutive failure (counting from 1). */
    fun failureHoldMs(failures: Int): Long = FAILURE_HOLD_MS[(failures - 1).coerceIn(0, FAILURE_HOLD_MS.lastIndex)]

    /** How long the notification title and body sent to the model are truncated to. */
    const val TITLE_MAX = 120
    const val TEXT_MAX = 200
    /**
     * For the same notification (same key) with the same text, an appearance after this much quiet counts as a new occurrence. A chat app re-posts the same notification verbatim for every new message,
     * and that does not count as new; but the same sentence arriving again the next day (a daily greeting, a fixed service reminder) is a new occurrence, and topics and contacts rely on it to accumulate "how many days it appeared".
     * Half a day: far longer than the interval at which one notification gets re-posted, yet shorter than "once a day". The summarization buffer counts from the most recent appearance (one that keeps being re-posted does not count);
     * the notification text counts from when it was last recorded (there is nowhere to record "most recent appearance").
     */
    const val REPEAT_WINDOW_MS = 12 * 3_600_000L
    /** In one notification's message list, how many of the messages seen for the first time this round are taken for summarization at most (newest first). */
    const val NEW_LINES_MAX = 5

    // ---- reply validation ----
    /** Maximum number of existing topics shown to the model in one request (highest strength × retention first). */
    const val EXISTING_MAX = 30
    /** Maximum number of entries recognized from one reply. */
    const val SIGNALS_MAX = 8
    /** Maximum topic name length (UTF-16 units). */
    const val LABEL_MAX = 24
    /** A topic name may not contain a digit run this long (order numbers, phone numbers, verification codes and the like). */
    const val DIGIT_RUN_MAX = 5

    // ---- topic pool ----
    /** Maximum number of distinct days recorded per app (only the most recent are kept). */
    const val DAYS_PER_APP = 60
    /** Maximum number of apps' evidence recorded per topic (when there are more, the app that has gone longest without appearing is dropped). */
    const val APPS_PER_TOPIC = 20
    /** How much strength is added each time the user opens it. */
    const val OPENED_BONUS = 0.5
    /** How much strength is subtracted each time the user dismisses it (strength never drops below 0). */
    const val DISMISSED_PENALTY = 0.25
    /** Forgetting time scale: S = this many days × (1 + strength); the more often something is reinforced, the more slowly it is forgotten (the Ebbinghaus / MemoryBank idea). */
    const val STABILITY_DAYS = 7.0
    /** Anything whose retention exp(-Δt / S) is below this is dropped at the next tidy-up. */
    const val FORGET_BELOW = 0.1
    /** Maximum number of topics kept in the pool; when there are more, the ones with the lowest strength × retention are dropped first. */
    const val POOL_CAP = 300
    /** How many different days it must have appeared on at least before it counts as "established". */
    const val ESTABLISHED_DAYS = 2

    // ---- contacts and relationships ----
    /**
     * How contact identity is computed (see [ContactIdentity]): the conversation name only. It was verified on real devices that the notification key is not one per conversation — some chat apps increment
     * the notification id, so the same group gets a different key after a while; if the key were part of it, every key change would add another contact for the same group and none would ever accumulate enough days.
     */
    val CONTACT_ID = ContactIdStrategy.Title
    /** Maximum number of contacts recognized from one reply. */
    const val CONTACTS_MAX = 6
    /** Maximum display name length (UTF-16 units). */
    const val CONTACT_NAME_MAX = 40
    /** Maximum number of distinct days recorded per contact (only the most recent are kept; type and relationship votes are also recorded per day and kept to the same count). */
    const val CONTACT_DAYS = 60
    /** Maximum number of topics linked to one contact (when there are more, the earliest linked are dropped). */
    const val CONTACT_TOPICS = 12
    /** How many different days it must have appeared on at least before a contact counts as "established". */
    const val CONTACT_ESTABLISHED_DAYS = 3
    /** How many different days must have voted for a guessed relationship before it is displayed. */
    const val RELATION_MIN_DAYS = 2
    /** Maximum number of contacts kept; when there are more, the ones with the lowest strength × retention are dropped first (those with a user-set relationship are not dropped). */
    const val CONTACT_CAP = 300
    /** Maximum number of topic names recorded as "do not record again" after deletion (when there are more, the earliest are dropped). */
    const val TOPIC_BLOCKED_CAP = 500
    /** Maximum number of merged topic names recorded (when there are more, the earliest are dropped; see [TopicPool.merge]). */
    const val TOPIC_MERGED_CAP = 500
    /** Maximum number of identity ids recorded as "do not record again" (when there are more, the earliest are dropped). */
    const val CONTACT_BLOCKED_CAP = 500

    // ---- daily-message summaries: level 1, chunk summaries ----
    /** Maximum number of notification text entries per batch (a single app, oldest sent first). Larger than the batch for topic summarization: only a few sentences are needed here. */
    const val NOTES_BATCH_MAX = 20
    /** Once one app has accumulated this many unsummarized entries, it is due to be sent. */
    const val NOTES_DUE_COUNT = 20
    /** Or its oldest entry is already this old (only checked when something happens, never on a timer). */
    const val NOTES_DUE_AGE_MS = 2 * 3_600_000L
    /** Maximum number of sentences recognized from one reply. */
    const val NOTES_MAX = 10
    /** Maximum length of one sentence (UTF-16 units). On real devices 120 was too short: a group discussion came down to just "talking about so-and-so", and it must be long enough to keep who said what and what the conclusion was. */
    const val NOTE_MAX = 240
    /** A digit run longer than this inside a sentence is replaced with "…" (tracking numbers, card numbers, phone numbers; amounts and dates are short and are kept). */
    const val NOTE_DIGIT_RUN_MAX = 6
    /** How many days chunk summaries are kept (needed until the weekly summary is done). */
    const val NOTES_KEEP_DAYS = 8L

    // ---- daily-message summaries: level 2, daily summaries ----
    /** Maximum number of chunk-summary sentences put into one request (when there are more, only that many of the newest are used). */
    const val DAY_NOTES_MAX = 60
    /** Maximum number of sentences per daily summary and the maximum length of each. */
    const val DAY_LINES_MAX = 15
    const val DAY_LINE_MAX = 300
    /**
     * Maximum length of a summary's (daily, weekly, monthly) one-line overview: [BRIEF_MAX_CJK] characters for mostly CJK text, while other scripts take several letters per word
     * and are relaxed to [BRIEF_MAX]. Anything longer is truncated.
     */
    const val BRIEF_MAX_CJK = 50
    const val BRIEF_MAX = 120
    /** How many days daily summaries are kept (needed for weekly and monthly summaries). */
    const val DAYS_KEEP = 35L
    /**
     * While today is not over yet, when an "up to now" daily summary is generated automatically (the user does not have to tap): at least [TODAY_AUTO_MIN_NOTES] sentences written
     * since the previous one, and either [TODAY_AUTO_READY_NOTES] sentences already exist or the earliest of them has waited [TODAY_AUTO_WAIT_MS];
     * and at least [TODAY_AUTO_INTERVAL_MS] since the previous one (that is three or four runs per day, so the budget is not spent rewriting over and over).
     * Like everything else it only takes a look when something happens; there is no timer.
     */
    const val TODAY_AUTO_MIN_NOTES = 2
    const val TODAY_AUTO_READY_NOTES = 5
    const val TODAY_AUTO_WAIT_MS = 2 * 3_600_000L
    const val TODAY_AUTO_INTERVAL_MS = 4 * 3_600_000L
    /** Maximum number of attempts at the same day's daily summary within one process (so an unreadable reply is not retried forever). */
    const val DAY_TRIES_MAX = 3

    // ---- daily-message summaries: level 3 (week), level 4 (month) ----
    /** Maximum total characters of lower-level sentences in one request; when exceeded, entries take turns and each keeps the sentences at its front. */
    const val SPAN_INPUT_CHARS = 16_000
    /** Maximum number of sentences per weekly or monthly summary and the maximum length of each. */
    const val SPAN_LINES_MAX = 20
    const val SPAN_LINE_MAX = 300
    /** How long a weekly or monthly summary request waits: the input is far longer than other batches, and 30 seconds is not enough for a slower model to read it. */
    const val SPAN_TIMEOUT_MS = 90_000L
    /** How many days weekly summaries are kept (26 weeks). */
    const val WEEKS_KEEP_DAYS = 182L
    /** How many days monthly summaries are kept (two years). */
    const val MONTHS_KEEP_DAYS = 730L
    /** Maximum number of attempts at the same week's (month's) summary within one process. */
    const val SPAN_TRIES_MAX = 3

    const val DAY_MS = 86_400_000L
}
