package com.wangzan101.mika.core.device.shell

/**
 * The wish Claire hands to the built-in helper: whether to bring it back up after its process is killed ([revive]), and whether to write Claire's accessibility service back into the system list before doing so ([accessibility]).
 * The helper cannot read Claire's settings and only honors the latest copy Claire delivered over the Binder (see [RestoreWishSender]).
 */
data class RestoreWish(val revive: Boolean, val accessibility: Boolean) {
    /** Integer passed over AIDL. */
    val flags: Int get() = (if (revive) REVIVE else 0) or (if (accessibility) ACCESSIBILITY else 0)

    companion object {
        const val REVIVE = 1
        const val ACCESSIBILITY = 2

        /** When the helper has just started and Claire has not delivered a wish yet: do nothing. */
        val NONE = RestoreWish(revive = false, accessibility = false)

        fun fromFlags(flags: Int) = RestoreWish(flags and REVIVE != 0, flags and ACCESSIBILITY != 0)

        /**
         * On an abnormal death, restore the capabilities the user was still using: the capsule is always shown, a task is running, or the accessibility service is already on.
         * The last one also restores the service permission; it must not be fixed only when the persistent capsule is present or a task is running.
         * After the user explicitly exits (including clearing recent tasks), [userExited] makes the wish empty until Claire is opened manually next time.
         * The accessibility service is only written back when the user has not turned off "turn on accessibility for me" inside Claire ([accessibilityWanted])
         * and Claire is actually in the system list right now ([accessibilityOn]): if the user turned it off in the system settings while Claire was alive, it is not turned back on for them.
         */
        fun of(
            capsuleAlways: Boolean,
            taskRunning: Boolean,
            accessibilityWanted: Boolean,
            accessibilityOn: Boolean,
            userExited: Boolean = false,
        ): RestoreWish {
            if (userExited) return NONE
            val revive = capsuleAlways || taskRunning || accessibilityOn
            return RestoreWish(revive, revive && accessibilityWanted && accessibilityOn)
        }
    }
}

/**
 * The Claire side: hands the wish to the helper in hand. It is only delivered when the helper changes (a new Binder) or the wish changes; a failed delivery is not recorded and is retried next time.
 * [send] returns whether it was delivered.
 */
class RestoreWishSender<T : Any>(private val send: (T, RestoreWish) -> Boolean) {
    private var shell: T? = null
    private var sent: RestoreWish? = null

    @Synchronized
    fun update(current: T?, wish: RestoreWish) {
        if (current == null) {
            shell = null
            sent = null
            return
        }
        if (current === shell && wish == sent) return
        if (send(current, wish)) {
            shell = current
            sent = wish
        } else {
            shell = null
            sent = null
        }
    }
}

/** What the helper tells Claire during the handoff: when it last brought Claire up, how many times it has done so within the recent window, and when it last gave up for pulling too often (wall-clock milliseconds, 0 means never). */
data class HelperRestoreReport(val lastRestoredAtMs: Long, val recent: Int, val gaveUpAtMs: Long) {
    val empty: Boolean get() = lastRestoredAtMs == 0L && gaveUpAtMs == 0L
}

/**
 * The helper side: decides whether to bring Claire up when its process is gone and has not returned after [GRACE_MS].
 * It only looks at the [RestoreWish] Claire delivered last and whether the system has marked the package as force-stopped; it restores at most [maxAttempts] times within [windowMs], and if Claire dies again right after being brought up it stops and waits for the window to pass.
 */
class HelperRestorePolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    enum class Decision {
        Restore,
        /** Claire did not ask for it (the user explicitly exited, or no capability is still in use that needs restoring). */
        NotWanted,
        /** The user force-stopped Claire in the system; this death must not be brought back automatically like an abnormal death. */
        ForceStopped,
        /** Claire came back on its own during this wait (or is handing off). */
        AppBack,
        /** Pulled too often recently: Claire dies again as soon as it is brought up, so it stops pulling. */
        BackedOff,
    }

    private val attempts = ArrayDeque<Long>()
    private var lastRestoredAt = 0L
    private var gaveUpAt = 0L

    @Synchronized
    fun decide(wish: RestoreWish, appBack: Boolean, now: Long, forceStopped: Boolean = false): Decision {
        if (appBack) return Decision.AppBack
        if (forceStopped) return Decision.ForceStopped
        if (!wish.revive) return Decision.NotWanted
        prune(now)
        if (attempts.size >= maxAttempts) {
            gaveUpAt = now
            return Decision.BackedOff
        }
        attempts.addLast(now)
        lastRestoredAt = now
        return Decision.Restore
    }

    @Synchronized
    fun report(now: Long): HelperRestoreReport {
        prune(now)
        return HelperRestoreReport(lastRestoredAt, attempts.size, gaveUpAt)
    }

    private fun prune(now: Long) {
        // Also cleared when the clock goes backwards: if it cannot be told clearly, it does not count
        while (attempts.isNotEmpty() && (now - attempts.first() >= windowMs || now < attempts.first())) attempts.removeFirst()
    }

    companion object {
        /** Wait this long after Claire dies: the system or the user may reopen it right away, in which case the helper does not need to act. */
        const val GRACE_MS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** The command that writes the accessibility service back before the helper brings Claire up (written the way stock Android's settings does it). */
object RestoreCommands {
    const val ENABLE_ACCESSIBILITY = "settings put secure accessibility_enabled 1"

    /** Appends Claire when it is not in the list (leaving the other services untouched), and only turns on the master switch when it is; [current] is the raw output read by `settings get`. */
    fun accessibility(current: String?, component: String): List<String> =
        if (AccessibilityList.contains(current, component)) listOf(ENABLE_ACCESSIBILITY)
        else PermissionCommands.writeAccessibility(AccessibilityList.with(current, component))
}

/** The accessibility service problem seen when Claire starts: it was removed from the system list, or it is in the list but never got bound. */
enum class A11yLoss { Removed, Unbound }

/** The accessibility service problem seen at the last Claire start, and when (wall-clock milliseconds). */
data class A11yLossRecord(val loss: A11yLoss, val atMs: Long)

object A11yStartCheck {
    /** How long to wait for the service to connect: right after the process starts the system usually binds it within a few seconds. */
    const val CONNECT_WAIT_MS = 5_000L

    /**
     * [initial] is the state right after Claire started, [connectedSoon] is whether it connected within [CONNECT_WAIT_MS];
     * when [expected] is false (the service has never connected, e.g. not set up yet) it is not a problem.
     */
    fun loss(initial: A11yObservation, connectedSoon: Boolean, expected: Boolean): A11yLoss? = when {
        !expected || initial.connected -> null
        !initial.enabled -> A11yLoss.Removed
        !connectedSoon -> A11yLoss.Unbound
        else -> null
    }
}
