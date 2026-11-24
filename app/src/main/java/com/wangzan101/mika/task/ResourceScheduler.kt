package com.wangzan101.mika.task

import com.wangzan101.mika.cap.web.TabBudget
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.takeover.TakeoverQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock

/** Entry point for shared resources inside the process. TaskCenter manages the run queue; this manages resources that runs contend for with each other. */
class ResourceScheduler(
    val tabBudget: TabBudget = TabBudget(),
    val takeoverQueue: TakeoverQueue = TakeoverQueue(),
    val clipboard: ReentrantLock = ReentrantLock(true),
) {
    /** App (package name; a copy in another user is written as "package@user") -> the task holding it. */
    private val apps = HashMap<String, String>()
    /** Task waiting for another task to release an app -> the app it is waiting for. */
    private val waiting = HashMap<String, String>()
    /** Incremented whenever a task releases an app it held: waiting tasks use this to look again immediately instead of waiting for the next poll. */
    private val released = MutableStateFlow(0L)

    /** Reservation and checking happen under the same lock; takeover is allowed once the app has been released or closed by another task. */
    @Synchronized
    fun reserveApp(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (!appFree(packageName, owner, holderActive, stillUsed)) return false
        apps[packageName] = owner
        return true
    }

    /** Whether [owner] can reserve [packageName] right now: nobody holds it, the owner itself holds it, or the holding task is gone or no longer using it ([stillUsed] is false). Checks only, does not reserve. */
    @Synchronized
    fun appFree(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        val holder = apps[packageName]
        return holder == null || holder == owner || !holderActive(holder) || !stillUsed()
    }

    @Synchronized fun releaseOwner(owner: String) {
        waiting.remove(owner)
        if (apps.values.removeAll { it == owner }) released.value++
    }

    @Synchronized fun appHolder(packageName: String): String? = apps[packageName]

    /**
     * [owner] waits for [packageName] to be released, at most [timeoutMs]. [free] is the caller's complete check (beyond the registry it also looks whether the app is on other screens),
     * evaluated every [pollMs], and again immediately when a task releases an app. Waits only, does not reserve: once free, the caller reserves and opens it itself.
     *
     * Mutual waits do not wait: when the task holding it (followed down the chain of "whose app in whose hands it is waiting for") ends up waiting for an app held by [owner],
     * [AppWait.WouldDeadlock] is returned; the check and the registration share one lock, so when two tasks start waiting at the same time the later one sees the earlier one.
     * [holderActive] tells whether a holding task still exists (a task that is gone does not count as holding).
     */
    suspend fun awaitApp(
        packageName: String, owner: String, timeoutMs: Long,
        holderActive: (String) -> Boolean, free: suspend () -> Boolean, pollMs: Long = POLL_MS,
    ): AppWait {
        if (!startWaiting(owner, packageName, holderActive)) return AppWait.WouldDeadlock
        try {
            return withTimeoutOrNull(timeoutMs) {
                while (!free()) {
                    val seen = released.value
                    withTimeoutOrNull(pollMs) { released.first { it != seen } }
                }
                AppWait.Free
            } ?: AppWait.TimedOut
        } finally {
            stopWaiting(owner)
        }
    }

    @Synchronized private fun startWaiting(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        if (wouldDeadlock(owner, packageName, holderActive)) return false
        waiting[owner] = packageName
        return true
    }

    @Synchronized private fun stopWaiting(owner: String) {
        waiting.remove(owner)
    }

    /** Starting from the holder of [packageName], follow "whose app in whose hands it is waiting for" downwards; coming back around to [owner] means a mutual wait. */
    private fun wouldDeadlock(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        fun holderOf(pkg: String) = apps[pkg]?.takeIf(holderActive)
        var holder = holderOf(packageName)?.takeIf { it != owner } ?: return false
        val seen = HashSet<String>()
        while (seen.add(holder)) {
            val next = holderOf(waiting[holder] ?: return false) ?: return false
            if (next == owner) return true
            holder = next
        }
        return false
    }

    private companion object {
        /** An app on another screen closing itself does not notify this: look again after this long. */
        const val POLL_MS = 1_000L
    }
}
