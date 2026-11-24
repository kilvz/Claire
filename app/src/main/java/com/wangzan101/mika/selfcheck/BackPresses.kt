package com.wangzan101.mika.selfcheck

/**
 * Pressing back and waiting for the page to leave in the self-check: for the press made while the IME was up, Android first uses it to hide the IME (or to clear text that was not committed to the field), so it does not count;
 * when the IME is not up, a failure is only counted if the page is still there after [tries] presses. At most [maxPresses] presses are made in total, so it will not keep pressing if the IME refuses to hide.
 */
internal object BackPresses {
    suspend fun until(
        tries: Int,
        maxPresses: Int,
        keyboardUp: suspend () -> Boolean,
        press: suspend () -> Unit,
        left: suspend () -> Boolean,
    ): Boolean {
        var counted = 0
        var presses = 0
        while (counted < tries && presses < maxPresses) {
            val keyboard = keyboardUp()
            press()
            presses++
            if (left()) return true
            if (!keyboard) counted++
        }
        return false
    }
}
