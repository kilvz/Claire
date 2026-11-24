package com.wangzan101.mika.core.cap.phone

/**
 * The "operating pace" resolved at the start of a task: the global tier plus the per-app override list from
 * settings (Apps without an override follow the global tier).
 * Apps whose built-in App knowledge marks them careful are pre-set to [OperatingPace.HUMAN] when the list is first
 * initialized (on the app-settings side); at runtime only this resolved plan is consulted.
 */
class AppPacePlan(
    /** Global tier (Settings › While working › Operating pace; when an evaluation hook sets a tier, this is the hook value and the overrides are empty). */
    val global: OperatingPace,
    /** Per-app overrides: package name → tier. */
    val overrides: Map<String, OperatingPace> = emptyMap(),
) {
    /** Effective tier for [pkg]: the override if there is one, otherwise the global tier; an unknown package name (null) gets the global tier. */
    fun of(pkg: String?): OperatingPace = overrides[pkg] ?: global

    /** Whether [pkg] is browsing-speed limited (screens counted, caps applied): only an explicit per-app "human" counts; a global human tier does not imply the cap (as before: the cap is only for careful Apps). */
    fun capped(pkg: String?): Boolean = pkg != null && overrides[pkg] == OperatingPace.HUMAN

    /** Every App is "fast" (the global tier is fast and no override is slower): dwelling can be turned off entirely ([AppPacing.Off]). */
    val allFast: Boolean get() = global == OperatingPace.FAST && overrides.values.all { it == OperatingPace.FAST }

    /** The pacing for step-by-step operation inside Apps during this task: with overrides present the whole thing cannot be morphed according to the global tier, so a single pair of limits is used and fast/slow is decided per App. */
    fun appPacing(random: kotlin.random.Random = kotlin.random.Random.Default): AppPacing =
        if (allFast) AppPacing.Off else AppPacing.human(random)
}
