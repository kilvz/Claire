package com.wangzan101.mika.core.cap.phone

/**
 * Which tempo an App is actually operated at during a task: the result of resolving the global tier of the
 * "Operating pace" setting together with the per-app overrides (see [OperatingPace] and the per-app list in the app
 * settings). Kept separate from the two-state [com.wangzan101.mika.core.screen.uistate.PlacePace] in App knowledge:
 * the knowledge only says "this App limits browsing speed" ([AppTempo.CAREFUL]), it has no "fast" state — fast comes
 * from the user's settings.
 */
enum class AppTempo {
    /** Fast: no dwell between actions in this App, typing written in one go, no screen cap. */
    FAST,
    /** Standard: a short dwell on the same screen, slightly longer on a screen change, typing in chunks. */
    NORMAL,
    /** Human-like / browsing-speed limited: dwell longer, read by character count, type slower. */
    CAREFUL;

    companion object {
        /** The pace value in the App knowledge header (`careful`; when absent or unrecognized, [NORMAL]). */
        fun of(value: String?): AppTempo = if (value == "careful") CAREFUL else NORMAL
    }
}
