package com.wangzan101.mika.core.proactive

/**
 * Recognizes a "status display": the same notification re-posted by an app over and over, changing a few words each time — navigation's "turn right in 300 meters", how many stops a delivery is away, a score, a countdown.
 * These are not separate events; capturing one entry per change would mean hundreds of useless entries within minutes, filling detection, summarization and the notification text.
 *
 * Ongoing, foreground-service, non-dismissible notifications, and those whose category says navigation or progress, are already rejected by [NoticeFilter]. This deals with the unlabeled ones:
 * the navigation announcements of a maps app seen on a real device were an ordinary dismissible notification, with no category, silent, re-posted every two or three seconds.
 * The only thing left that can identify it is "how densely it is re-posted", so only that is used, not which app it is or what the title says:
 * - silent notifications ([NoticeRecord.silent]): re-posted up to the [SILENT_POSTS]-th time within a minute;
 * - audible notifications: re-posted up to the [LOUD_POSTS]-th time within a minute (people cannot send messages that densely).
 * Once recognized, nothing under that key is captured until it has been quiet for [QUIET_MS].
 *
 * Chat does not count: a conversation notification is updated once per incoming message by nature. Notifications with a conversation style, with a message/email/social category, or already split by the chat wording
 * ([NoticeRecord.group] not null) are all ignored. A chat app without these markers that has not been tested has to reach [LOUD_POSTS] in a minute on one key before it is taken for a status display
 * ([SILENT_POSTS] when the user has set its notifications to silent).
 *
 * In memory only, never persisted; keyed by notification key, recording at most [capacity] keys. Not thread-safe: used on the single serial coroutine of ProactiveRuntime.
 */
class LiveUpdates(private val capacity: Int = 200) {
    enum class Verdict {
        /** Capture as usual. */
        Normal,
        /** This re-post made it be recognized as a status display: this entry is not captured, and the few captured just before it should be removed as well. */
        BecameLive,
        /** Already recognized as a status display: do not capture. */
        Live,
    }

    private class Track(var lastPostedAtMs: Long = Long.MIN_VALUE, val posts: ArrayDeque<Long> = ArrayDeque(), var liveUntilMs: Long = Long.MIN_VALUE)

    private val tracks = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>) = size > capacity
    }

    /** [record] is already cleaned and split. Looking at the same posting again (same posted time) does not count it twice. */
    fun observe(record: NoticeRecord): Verdict {
        if (conversation(record)) return Verdict.Normal
        val at = record.postedAtMs
        val t = tracks.getOrPut(record.key) { Track() }
        // the clock was turned back a lot: earlier records cannot be compared, start over
        if (at < t.lastPostedAtMs - WINDOW_MS) { t.posts.clear(); t.liveUntilMs = Long.MIN_VALUE; t.lastPostedAtMs = Long.MIN_VALUE }
        val live = at <= t.liveUntilMs
        if (at != t.lastPostedAtMs) {
            t.lastPostedAtMs = maxOf(t.lastPostedAtMs, at)
            t.posts.addLast(at)
            while (t.posts.isNotEmpty() && t.posts.first() < at - WINDOW_MS) t.posts.removeFirst()
            while (t.posts.size > LOUD_POSTS) t.posts.removeFirst()
        }
        if (live) {
            t.liveUntilMs = maxOf(t.liveUntilMs, at + QUIET_MS)
            return Verdict.Live
        }
        if (t.posts.size >= if (record.silent) SILENT_POSTS else LOUD_POSTS) {
            t.liveUntilMs = at + QUIET_MS
            return Verdict.BecameLive
        }
        return Verdict.Normal
    }

    /**
     * The user opened or dismissed the notification under this key: this entry ends here, and the next one under the same key is counted from scratch (one previously taken for a status display is recognized again too).
     * Otherwise: many apps share one notification key across all pushes, and several unrelated notifications the user handles one by one would be counted as "the same one being re-posted".
     * Withdrawals and re-posts done by the app itself do not count (that is usually exactly the re-posting); only the user's own action calls this. If a real status display keeps being re-posted after being dismissed, it is still recognized once the count is reached again.
     */
    fun ended(key: String) {
        tracks.remove(key)
    }

    fun clear() = tracks.clear()

    companion object {
        /** The window for counting re-posts. */
        const val WINDOW_MS = 60_000L
        /** Silent notifications: the re-post count within the window at which it counts as a status display. */
        const val SILENT_POSTS = 4
        /** Audible notifications: the re-post count within the window that is required. */
        const val LOUD_POSTS = 12
        /** After being recognized as a status display, it must be quiet this long before being captured as usual again. */
        const val QUIET_MS = 5 * 60_000L

        private val CONVERSATION_CATEGORIES = setOf("msg", "email", "social")

        /** This is a conversation notification (updating once per message is its normal shape). */
        internal fun conversation(r: NoticeRecord): Boolean =
            r.conversation || r.messages.isNotEmpty() || r.group != null || r.category in CONVERSATION_CATEGORIES
    }
}
