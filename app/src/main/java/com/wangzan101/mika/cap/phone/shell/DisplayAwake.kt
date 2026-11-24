package com.wangzan101.mika.cap.phone.shell

import java.lang.reflect.Method

/**
 * Keeps Claire's virtual displays awake. A display created with OWN_DISPLAY_GROUP (a trusted display since
 * Android 13) forms its own display group with its own screen-off timer; injected input does not count as user
 * activity, so if left alone the group sleeps once the screen-off time is reached: nothing is composited
 * anymore, screenshots stop changing and taps do nothing. While the display exists, report a user activity on
 * its behalf every [PERIOD_MS] (far shorter than the shortest screen-off timeout the system allows), and stop
 * once the display is released. When [poke] cannot report (the system has no per-display API), stop trying and
 * the display is used as usual.
 */
internal class DisplayAwake(
    /** Reports one user activity on behalf of this display; returns false when it cannot be reported. */
    private val poke: (Int) -> Boolean,
    /** Runs it once immediately and then every [PERIOD_MS]; returns a way to stop it. */
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        // If the very first report fails, giveUp may run before registration completes: stop once more
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        // Display.DEFAULT_DISPLAY_GROUP (hidden constant)
        private const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * Whether to report on behalf of this display: only when it really forms a group of its own. Reporting to
         * the default group (the one holding the user's main screen) would keep the user's main screen awake too.
         * [groupId] is null when it cannot be read; in that case the decision uses whether [ownGroupFlag] was
         * passed when the display was created.
         */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP

        /**
         * The per-display user activity overload in IPowerManager, `userActivity(int displayId, long time, int event, int flags)`
         * (since Android 12); null when only the old overload without a display id exists: it reports to the
         * default group and would keep the user's main screen awake, so it is not used.
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    }
}
