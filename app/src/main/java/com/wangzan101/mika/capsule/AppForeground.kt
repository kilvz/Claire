package com.wangzan101.mika.capsule

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Claire's own UI (any Activity such as the chat page or the web takeover page) is visible right now: counts Activities that have been onStart'ed but not yet onStop'ed.
 * During a recreation such as a rotation the old one stops first and the new one starts afterwards, and that gap does not count as leaving the foreground. Registered by the App at startup; callbacks arrive only on the main thread.
 */
class AppForeground : Application.ActivityLifecycleCallbacks {
    private var started = 0
    /** Number of Activities stopped because of a recreation whose new instance has not started yet. */
    private var recreating = 0
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        if (recreating > 0) recreating-- else started++
        _visible.value = started > 0
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) recreating++ else started = (started - 1).coerceAtLeast(0)
        _visible.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
