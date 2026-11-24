package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ActivityInfo

/** When a background display does not take focus from the main display, the system may shrink a landscape Activity into the top of a portrait screen. Correct the display orientation using the app window aspect ratio reported by the system. */
internal object DisplayOrientation {
    fun rotation(
        bufferWidth: Int,
        bufferHeight: Int,
        current: Int,
        appWidth: Int,
        appHeight: Int,
        requestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    ): Int? {
        if (bufferWidth == bufferHeight) return null
        val appLandscape = when {
            appWidth > 0 && appHeight > 0 && appWidth != appHeight -> appWidth > appHeight
            else -> requestedLandscape(requestedOrientation) ?: return null
        }
        val naturalLandscape = bufferWidth > bufferHeight
        val currentLandscape = if (current % 2 == 0) naturalLandscape else !naturalLandscape
        if (currentLandscape == appLandscape) return null
        return if (naturalLandscape == appLandscape) 0 else 1
    }

    private fun requestedLandscape(orientation: Int): Boolean? = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false
        else -> null
    }
}
