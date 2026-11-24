package com.wangzan101.mika.core.screen.uistate

/**
 * The result of turning a wheel to a value. [reached] whether it got there; [value] the value displayed right now (null if unreadable), [from] the one displayed at the start; [attempts] how many actions were performed
 * (a type-in, a scroll and a drag each count once); [method] the approach used last: text (direct input), actions (accessibility scroll), drag (dragging);
 * [error] the reason it did not get there (ref_expired, not_a_wheel, unknown_value, at_end, not_moving, not_reached, …), [hint] extra explanation from the port.
 */
data class WheelOutcome(
    val reached: Boolean,
    val value: String?,
    val from: String?,
    val attempts: Int,
    val method: String,
    val error: String? = null,
    val hint: String? = null,
)
