package com.wangzan101.mika.core.proactive

import java.time.ZoneId

/**
 * The read-only source tasks use to read notifications: the last two days of notification originals and segmented notes, plus the stored day, week and month digests (kept longer than the originals; month digests are kept for two years). Supplied by the app (see ProactiveRuntime): only when the proactive feature and "daily message digest" are both on
 * and the aggressiveness level allows bringing things into tasks; when it is not supplied the read-notifications tool does not appear. Only apps whose source tier qualifies are included.
 */
interface NoticeSource {
    val zone: ZoneId

    /** The current time ("today" and "yesterday" are computed from it and [zone]). */
    fun now(): Long = System.currentTimeMillis()

    /** The app's name (read from the system by package name, not from the notification). */
    fun appLabel(packageName: String): String

    /**
     * The notification originals and segmented notes between [fromMs] (inclusive) and [toMs] (exclusive).
     * Null when unreadable (turned off while the task is running, the stored copy temporarily unreadable, waited too long): this must not be taken as "nothing was received in this span".
     */
    suspend fun read(fromMs: Long, toMs: Long): NoticeView?

    /**
     * The stored day (week, month) digests, newest first; only sentences from apps whose source tier qualifies. Null when unreadable (same as [read]).
     */
    suspend fun digests(level: DigestLevel): DigestsView?
}

/** Which digest level: day, week or month. */
enum class DigestLevel { Day, Week, Month }

/**
 * One digest handed to a task: [start] to [end] are the days it covers (epoch days, both inclusive); [partial] true means it was made "up to now" before that span ended
 * ([madeAtMs] is how far it went); [lines] are the sentences that qualify to be shown, along with which apps (package names) each came from.
 * [leftOut]: how many next-level sentences did not fit and were not seen when this was made (greater than 0 means this digest was not written from all of them).
 */
class DigestView(val start: Long, val end: Long, val madeAtMs: Long, val partial: Boolean, val lines: List<DigestViewLine>, val leftOut: Int = 0)

class DigestViewLine(val text: String, val apps: List<String>)

/** [withheldLines]: how many sentences were withheld because their source tier does not qualify (count only). */
class DigestsView(val digests: List<DigestView>, val withheldLines: Int = 0)

/**
 * [sinceMs] is from when the notification record is complete (nothing earlier, or part of it was pushed out for being too much); [entries] are the notification originals (oldest to newest)
 * and [notes] are the segmented notes for this span (oldest to newest). All text was written by other apps and other people (the segmented notes are written by the model from them).
 * [withheldApps]: how many apps had notifications in this span but were left out because their source tier does not qualify (count only).
 * [notesUnavailable]: the segmented notes could not be read right now — [notes] is empty, but that means "unknown", not "none".
 */
class NoticeView(
    val sinceMs: Long, val entries: List<NoticeEntry>, val notes: List<DigestNote>, val withheldApps: Int = 0,
    val notesUnavailable: Boolean = false,
)
