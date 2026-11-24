package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/** How many tasks can run at the same time right now; [reason] explains why only one is possible (shown on the settings page), and is null when nothing is limited. */
data class ParallelLimit(val max: Int, val reason: String?)

/**
 * Decide how many tasks run in parallel from device capability: each virtual display needs independent focus (Android 14+); low-RAM devices do not run in parallel;
 * once the second display has failed to be created in this run, stop trying; otherwise follow the user-set limit, clamped to 1–3.
 */
object ParallelCapability {
    const val MIN_SDK = 34
    const val DEFAULT_MAX = 2
    private const val MAX = 3
    val SECOND_DISPLAY_FAILED: String get() = tr(R.string.parallel_second_display_failed)

    fun detect(sdk: Int, lowRam: Boolean, userMax: Int, secondDisplayFailed: Boolean): ParallelLimit = when {
        sdk < MIN_SDK -> ParallelLimit(1, tr(R.string.parallel_needs_14))
        lowRam -> ParallelLimit(1, tr(R.string.parallel_low_ram))
        secondDisplayFailed -> ParallelLimit(1, SECOND_DISPLAY_FAILED)
        else -> ParallelLimit(userMax.coerceIn(1, MAX), null)
    }
}
