package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.PlacePace
import kotlin.random.Random

/**
 * The "operating pace" in settings: whether to operate fast or human-like inside Apps. The gestures themselves (pause
 * before landing, hold duration, landing jitter, arcs) are human-like in all three tiers, see [GesturePacing].
 *
 * - [FAST]: no extra dwell between actions, typing writes the whole text at once, no screen cap (the previous
 *   behavior);
 * - [STANDARD] (default): typing is emitted in chunks, and actions dwell briefly between each other (about
 *   0.5–1.2 seconds on the same screen, about 1–2 seconds on a new screen), while browsing-speed-limited Apps follow
 *   the slower set (dwell longer, read by character count, screen caps apply, type slower);
 * - [HUMAN]: every App uses the slower set for dwelling and typing; the screen cap still applies only to
 *   browsing-speed-limited Apps.
 *
 * The global tier and the per-app overrides ([AppPacePlan]) together decide each App's effective pacing.
 */
enum class OperatingPace {
    FAST, STANDARD, HUMAN;

    /** Pacing for step-by-step operation inside Apps during this task (as it looks without per-app overrides; with overrides see [AppPacePlan]). */
    fun appPacing(random: Random = Random.Default): AppPacing = when (this) {
        FAST -> AppPacing.Off
        STANDARD -> AppPacing.human(random)
        HUMAN -> AppPacing.human(random, limits = AppPacing.Limits.CAREFUL)
    }

    /** Which tempo Apps are operated at under this tier (dwelling and typing; see [AppTempo]). */
    val tempo: AppTempo
        get() = when (this) {
            FAST -> AppTempo.FAST
            STANDARD -> AppTempo.NORMAL
            HUMAN -> AppTempo.CAREFUL
        }

    /** Typing pacing inside an App whose App knowledge says [pace]. */
    fun typing(pace: PlacePace, random: Random = Random.Default): TypingPacing = when {
        this == FAST -> TypingPacing.Instant
        this == HUMAN || pace == PlacePace.CAREFUL -> TypingPacing.human(random, TypingPacing.Limits.CAREFUL)
        else -> TypingPacing.human(random)
    }

    /** Typing pacing inside an App running at [tempo] (fast writes at once, careful types slower). */
    fun typing(tempo: AppTempo, random: Random = Random.Default): TypingPacing = when (tempo) {
        AppTempo.FAST -> TypingPacing.Instant
        AppTempo.CAREFUL -> TypingPacing.human(random, TypingPacing.Limits.CAREFUL)
        AppTempo.NORMAL -> TypingPacing.human(random)
    }

    /** Typing pacing inside Apps under this tier (fast writes at once, human/careful is slower; used for the tier after per-app overrides). */
    fun typing(random: Random = Random.Default): TypingPacing = typing(tempo, random)

    companion object {
        val DEFAULT = STANDARD

        /** The stored name (case-insensitive); null when unrecognized. */
        fun parse(value: String?): OperatingPace? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
