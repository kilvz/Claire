package com.wangzan101.mika.cap.phone.shell

/**
 * What happens to the apps on Claire's screen when a task ends (rules on the sibling side; they do not
 * touch system APIs, so unit tests target them directly).
 *
 * Kept by default: moved to the main screen and placed at the very back, so the user sees it in Recents
 * still sitting on the page Claire finished on, and the next time Claire opens it, it resumes from that
 * page. Anything currently making sound (playing audio/video) is closed by default, so it does not
 * suddenly start playing when the task ends and the mute is restored. What the model marked per the
 * user's words ([keep] / [close]) takes precedence over the default. Claire's own pages and anything
 * that is not a normal app page (launcher, Recents and the like) are always closed with the display.
 */
object AppsAfterTask {
    /** Why a task was kept or closed this way; [wire] is written into the result and the run record. */
    enum class Why(val wire: String) {
        /** The model marked it. */
        Marked("marked"),
        /** Currently making sound. */
        Playing("playing"),
        /** Whether it is making sound cannot be read, and Claire is muting it: it may suddenly play when the mute is restored, so it counts as playing. */
        PlaybackUnknown("playback_unknown"),
        /** Default: keep it. */
        Default("default"),
        /** Claire's own page. */
        Own("mika"),
        /** Not a normal app page. */
        NotApp("not_app"),
        /** This system cannot move tasks, so it is closed with the display as before. */
        MoveUnsupported("move_not_supported"),
        /** Still visible after moving to the main screen until the timeout: it cannot stay in front of the user, so it is moved back to Claire's display and closed with it. */
        StillInFront("still_in_front"),
    }

    /**
     * A task on the display: [keys] are the keys built from the packages its pages belong to (see
     * [keyOf]), [playing] is null when it cannot be read, and [muted] means Claire is muting it right now
     * (it is restored when wrapping up).
     */
    data class Task(val id: Int, val keys: List<String>, val standard: Boolean, val own: Boolean, val playing: Boolean?, val muted: Boolean = false)

    data class Fate(val task: Task, val keep: Boolean, val why: Why) {
        val key: String get() = task.keys.firstOrNull().orEmpty()
    }

    /** Key: the original copy (in Claire's user) is just the package name, another copy is package@user, the same notation as the mute ledger ([AppAudioKey]). */
    fun keyOf(packageName: String, userId: Int, myUser: Int): String =
        AppAudioKey.of(packageName, userId.takeIf { it != myUser && it >= 0 })

    /** Converts the model's keys to the same notation: a copy written for Claire's own user equals the original, and malformed keys are dropped. */
    fun normalize(keys: Array<String>?, myUser: Int): Set<String> = keys.orEmpty().mapNotNull { k ->
        AppAudioKey.parse(k)?.let { (pkg, user) -> AppAudioKey.of(pkg, user?.takeIf { it != myUser }) }
    }.toSet()

    fun decide(tasks: List<Task>, keep: Set<String>, close: Set<String>): List<Fate> = tasks.map { t ->
        when {
            t.own -> Fate(t, false, Why.Own)
            !t.standard -> Fate(t, false, Why.NotApp)
            t.keys.any { it in close } -> Fate(t, false, Why.Marked)
            t.keys.any { it in keep } -> Fate(t, true, Why.Marked)
            t.playing == true -> Fate(t, false, Why.Playing)
            // Playback state unreadable: keep it if it is not muted (if it were going to make sound it already would, and wrapping up changes nothing); if it is muted, count it as playing so it does not suddenly sound when the mute is restored
            t.playing == null && t.muted -> Fate(t, false, Why.PlaybackUnknown)
            else -> Fate(t, true, Why.Default)
        }
    }

    /**
     * A second look after the move: anything not marked keep that has started making sound by now is
     * closed instead (it would sound as soon as the mute is restored). Items marked keep stay.
     */
    fun recheck(fate: Fate, playingNow: Boolean?): Fate =
        if (fate.keep && fate.why != Why.Marked && playingNow == true) fate.copy(keep = false, why = Why.Playing) else fate

    /**
     * The uids currently playing according to `dumpsys audio`: one line per player,
     * `AudioPlaybackConfiguration piid:.. u/pid:<uid>/<pid> state:started ..`.
     * Null when the output is not recognizable as the audio service's (the playback-record section is
     * missing); the caller then treats it as "unreadable".
     */
    fun playingUids(dumpsys: String): Set<Int>? {
        if (!dumpsys.contains(PLAYBACK_SECTION)) return null
        return dumpsys.lineSequence().filter { "AudioPlaybackConfiguration" in it }
            .mapNotNull { PLAYER.find(it) }
            .filter { it.groupValues[2] == "started" }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 0 }
            .toSet()
    }

    /** A closed entry in the result: key:reason. */
    fun closedEntry(f: Fate): String = "${f.key}:${f.why.wire}"

    /**
     * One line for the diagnostics: counts only, never package names (diagnostics do not record what is
     * installed or what was opened).
     * e.g. "kept 1, closed 1 (playing 1)", "closed 2 (move not supported)", "none".
     */
    fun diagnosticsLine(kept: Int, closed: List<String>, note: String?): String {
        if (kept == 0 && closed.isEmpty()) return note?.let { "none ($it)" } ?: "none"
        val reasons = closed.map { it.substringAfterLast(':') }.groupingBy { it }.eachCount()
            .entries.sortedBy { it.key }.joinToString(", ") { "${it.key.replace('_', ' ')} ${it.value}" }
        val parts = listOfNotNull(
            "kept $kept".takeIf { kept > 0 },
            if (closed.isEmpty()) null else "closed ${closed.size}" + (if (reasons.isEmpty()) "" else " ($reasons)"),
        )
        return parts.joinToString(", ") + (note?.let { "; $it" } ?: "")
    }

    // Bundle of finishDisplay
    const val KEY_KEPT = "kept"
    const val KEY_CLOSED = "closed"
    const val KEY_NOTE = "note"

    /** Text written into note when the task cannot be moved. */
    const val NOTE_MOVE_UNSUPPORTED = "move not supported"
    const val NOTE_PLAYBACK_UNKNOWN = "playback unknown"
    /** A task stayed visible after being moved over, so it was not kept. */
    const val NOTE_STILL_IN_FRONT = "still visible on main, closed"
    /** Preceded by a count: moved over but unhiding failed, so it is invisible in Recents and is moved back to make up for it the next time the same copy is opened. */
    const val NOTE_LEFT_HIDDEN = "left hidden on main"
    /** The main screen's task list could not be read, so it was not verified that the kept tasks had settled to the back. */
    const val NOTE_SETTLE_UNKNOWN = "behind check unavailable"

    // AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    const val PLAYER_STATE_STARTED = 2
    private const val PLAYBACK_SECTION = "PlaybackActivityMonitor"
    private val PLAYER = Regex("""u/pid:(-?\d+)/-?\d+\s+state:(\w+)""")
}
