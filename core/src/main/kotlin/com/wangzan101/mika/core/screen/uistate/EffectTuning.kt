package com.wangzan101.mika.core.screen.uistate

/**
 * All thresholds used to judge action effects, collected in one place. They are initial values to be calibrated
 * against runs recorded on real devices (the UI state before and after actions, and the results); when changing them,
 * review the samples in ActionEffectTest and FrameDiffTest along with them. Coordinates are normalized to 0–999.
 */
object EffectTuning {
    // ---- UI structure ----
    /** Clearly changed: at least this many changes... */
    const val MAJOR_MIN = 3
    /** ...and at least this fraction of the elements. */
    const val MAJOR_SHARE = 0.10
    /** Text changed in the same input field: centers before and after are no farther apart than this. */
    const val NEAR = 50
    /** Treated as the same input field when reading back: centers no farther apart than this. */
    const val SAME_FIELD = 120
    /** When there are fewer informative elements than this (custom-drawn UIs), a large visual change also counts as clearly changed. */
    const val FEW_ELEMENTS = 3

    // ---- Frame (corroboration; grid and thresholds in FrameTuning) ----
    /** How large an area around the landing point is examined for coordinate actions (half width). */
    const val POINT_REGION = 60

    // ---- Waiting ----
    /** When the port did not wait for the UI during the action, the upper bound for waiting for the UI to settle before judging the effect. */
    const val SETTLE_MS = 1_500L
    /** When nothing seems to have changed, wait this long and look again (the UI may update asynchronously). */
    const val RECHECK_MS = 700L
    /** The most time a pre-action look may take; if it does not fit, judge only from the port facts. */
    const val PROBE_MS = 1_000L
    /** The most extra time judging the effect after the action may take (waiting plus reading the UI); once it is used up, conclude from the facts already available. */
    const val BUDGET_MS = 2_500L
}
