package com.wangzan101.mika.selfcheck

import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome

/**
 * The "main screen was not disturbed" verdict. When the virtual display has no focus of its own (below Android 14, or the system did not set that flag),
 * key presses and input may land on the main screen, and the top task there changing is a known system limitation: list the stages where it changed truthfully,
 * but report partial support with the same advice as the virtual display check; only being touched while it has its own focus counts as a failure.
 */
internal object MainScreenOutcome {
    fun of(changed: List<String>, focusShared: Boolean, who: String): CheckOutcome {
        if (changed.isEmpty()) return CheckOutcome.pass("top task unchanged ($who stayed on top)")
        val detail = "top task changed during: ${changed.joinToString()}"
        return if (focusShared) CheckOutcome.partial("$detail (virtual display has no own focus)", Advice.FocusShared)
        else CheckOutcome.fail(detail, Advice.MainScreenChanged)
    }
}
