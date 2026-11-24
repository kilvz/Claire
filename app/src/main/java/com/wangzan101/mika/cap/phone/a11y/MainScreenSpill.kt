package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent

/**
 * Decides whether one of the Agent's actions opened an app on the user's main screen. When an app opens another
 * app not from its own page on Claire's screen but from the background (a service, a notification's intent, a
 * system picker, a cross-user hand-off), the system puts it on the main screen by default; Claire's screen shows no
 * change, so the model thinks the tap missed and taps again and again. Once recognized it is moved back to Claire's
 * screen (see AndroidScreen).
 *
 * Only tasks that came to the top of the main screen within a short time after the action started count (recorded
 * by the built-in assistant, see MainScreenLog). Not counted: ones outside that window; pages such as the launcher
 * or recents, Claire itself, launcher apps ([excluded]); ones moved over from another screen (a takeover, another
 * task's takeover); ones no longer on the main screen now; the user tapped something on the main screen during that
 * window, or first returned to the launcher and then opened the app from the launcher or the notification shade
 * (treated as opened by the user); the task that was on top of the main screen before the action coming back on top
 * (revealed after the one it opened was moved away).
 * The task that was on top of the main screen before the action changed page: the app the user is using was opened,
 * right in front of the user, leave it alone ([InUse]).
 * A task that appeared and that the user is already tapping (the tapped window belongs to it): also the user using
 * it, do not move.
 * Pure logic.
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /**
     * A task that came to the top of the main screen; [from] which screen it was on when last seen ([NEW] if
     * never seen before), [now] which screen it is on right now (-1 if gone).
     */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** The user tapped on the main screen: [at] the moment, [packageName] the package the tap was in (null if unreadable). */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** The action opened it on the main screen: move it back to Claire's screen. */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** Opened into the app the user is using on the main screen: do not touch it, tell the model. */
    data class InUse(override val arrival: Arrival) : Verdict

    /**
     * This event on the main screen can prove the user is operating by hand; system touch events do not require
     * the app to also emit a view-click event.
     */
    fun isUserTap(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        -> true
        else -> false
    }

    /**
     * [since] the moment the action started; anything appearing after [windowMs] does not belong to this action;
     * [topBefore] the task on top of the main screen when the action started;
     * [userTaps] times and packages of the user's taps on the main screen; [userActiveBefore] the user had just
     * operated the main screen before the action started (see ScreenSpill's counter baseline);
     * when attribution is unclear, do not pull back. One entry per task (the last one), ordered by time.
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homePackages: Set<String> = emptySet(),
        userActiveBefore: Boolean = false,
    ): List<Verdict> {
        val ordered = arrivals.sortedBy { it.at }
        val homeReturns = ordered.filter { it.packageName in homePackages }.map { it.at }
        val out = LinkedHashMap<Int, Verdict>()
        for (a in ordered) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps, homeReturns, userActiveBefore) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** Move back to Claire's screen (moves the task only; the app that opened it is still the original one). */
        Move,
        /** The system is asking which one to open: do not move, leave it for the user to choose on their own screen, the task waits. */
        LeaveForUser,
    }

    /**
     * What to do with this one opened on the main screen: a system package's window that is either a system picker or
     * whose options match an app installed twice ([optionsMatch]) means the system is asking which one to open, so
     * leave it for the user; move everything else back. Claire never reopens it in place of the other app.
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * The one opened after the user finished choosing in the main screen's chooser (the chooser is already gone at
     * [gone]): the one that came to the main screen after the chooser (not counting the chooser itself [chooserTask]
     * nor the [topBefore] on top of the main screen before it appeared); the user just tapped an option, so it is not
     * excluded as "user tapped". Whether the chooser has closed is checked every so often, so one appearing within
     * [slackMs] before it closed also counts. null if none.
     */
    fun picked(
        arrivals: List<Arrival>,
        chooserTask: Int,
        gone: Long,
        slackMs: Long,
        waitMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        homePackages: Set<String> = emptySet(),
    ): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList(), homePackages)
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * The chosen app needs the user's own hands: the window has a password field, or nothing can be read from the
     * tree and a main-screen screenshot reports a secure window (payment and password-entry pages are often like
     * this). Merely an unreadable tree (some apps have sparse trees to begin with) does not count.
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    /**
     * Whether this package is a system account-login service: it is among the authenticators [authenticators] and
     * has no launcher icon of its own ([hasLauncher] false). What such a package brings to the main screen is only
     * add-account, login or verification pages, which the user must do by hand; even a full-screen one without a
     * password field must not be moved to Claire's screen. Authenticator packages that do have a launcher icon (chat
     * apps that sync contacts, mail apps) merely registered an account in passing; their pages are judged by the
     * other criteria as usual. Pure logic.
     */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        pkg in authenticators && !hasLauncher

    private fun verdict(
        a: Arrival,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homeReturns: List<Long>,
        userActiveBefore: Boolean,
    ): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        // The user first went back to the main screen's launcher and then opened the app from the launcher or the
        // notification shade: this navigation chain does not depend on the view emitting a separate click event
        if (homeReturns.any { it in since..a.at }) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // The user had just operated the main screen before the action started (between two actions there is only a
        // count, no exact moment): who opened this one is unclear, so conservatively do not pull back and report it
        // as the user using it, lest the app the user is using be taken away (#798/#651)
        if (userActiveBefore) return InUse(a)
        // The user is already tapping it (taps on this app's window after it appeared): leave it for the user, do not move
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
