package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.device.access.AppAccessDenied
import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.device.access.AppAccessState
import kotlinx.coroutines.delay

/**
 * App access boundary for the phone port: every [ScreenPort] member is explicitly written out here (no `by`
 * delegation), so members added to the port later cannot slip through unchecked; tests verify this member by member.
 *
 * Screen reads and actions check the foreground window's ownership once before and once after; target-based opens
 * (apps, shares, links with an explicit app) only look at the target.
 * The window probe [packages] only reads ownership, does not capture content, and does not create a screen for the
 * check. When the blocked list is empty it does nothing extra.
 *
 * Ask for windows as little as possible: [snapshot] and [peek] check against the window ownership already present in
 * the screen that was read, without asking again; when [windowToken] (a counter that changes whenever the window
 * changes) is given, a verified result is reused as long as the window has not changed, the list has not changed, and
 * no more than [CHECK_TTL_MS] has passed (the screenshot right after the same read, the frame-by-frame previews).
 */
class AppAccessScreenPort(
    private val inner: ScreenPort,
    override val appAccess: AppAccessPolicy,
    private val packages: suspend () -> List<String?>,
    private val protectedUi: () -> Boolean = { false },
    private val ownPackage: String? = null,
    private val windowToken: (() -> Long)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScreenPort {
    /** The window counter, list revision, whether Claire's own UI was being guarded, and the time of the last successful check. */
    private class Verified(val token: Long, val revision: Long, val guarded: Boolean, val at: Long)

    @Volatile private var verified: Verified? = null

    override suspend fun checkAppAccess() {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return
        if (stillVerified(state, guarded)) return
        val token = windowToken?.invoke()
        verify(state, packages(), guarded, token)
    }

    private fun stillVerified(state: AppAccessState, guarded: Boolean): Boolean {
        val v = verified ?: return false
        val token = windowToken?.invoke() ?: return false
        return v.token == token && v.revision == state.revision && v.guarded == guarded && clock() - v.at <= CHECK_TTL_MS
    }

    /** Verifies the ownership of the windows in [found]; if any ownership is unrecognized, asks for the windows again after a short wait. On success it is recorded for [stillVerified] to reuse. */
    private suspend fun verify(state: AppAccessState, found: List<String?>, guarded: Boolean, token: Long?) {
        verified = null
        var s = state
        var targets = found
        var t = token
        if (s.blocked.isNotEmpty() && targets.any { it.isNullOrBlank() }) {
            delay(RETRY_MS)
            t = windowToken?.invoke()
            targets = packages()
            s = appAccess.current()
        }
        if (!s.available) throw AppAccessDenied("app_access_unavailable", null)
        for (pkg in targets) {
            if (guarded && ownPackage != null && pkg == ownPackage) throw AppAccessDenied("app_access_protected", pkg)
            s.denial(pkg)?.let { throw it }
        }
        verified = t?.let { Verified(it, s.revision, guarded, clock()) }
    }

    /** Reads one screen: what is read carries its window ownership, which is then used for the check; when the port provides no windows, it falls back to asking for them. */
    private suspend fun observed(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return read()
        // Take the counter first: if the window changes during the read, the next check will not reuse this result
        val token = windowToken?.invoke()
        val snap = read()
        verify(appAccess.current(), snap.accessPackages() ?: packages(), guarded, token)
        return snap
    }

    private suspend fun <T> checked(body: suspend () -> T): T {
        checkAppAccess()
        val value = body()
        checkAppAccess()
        return value
    }

    private fun target(packageName: String) {
        appAccess.current().denial(packageName)?.let { throw it }
    }

    // ---------- Screen reads ----------
    override suspend fun snapshot() = observed { inner.snapshot() }
    override suspend fun peek() = observed { inner.peek() }
    override suspend fun screenshotPng() = checked { inner.screenshotPng() }
    override suspend fun readVisibleText() = checked { inner.readVisibleText() }
    override suspend fun frameGrid() = checked { inner.frameGrid() }
    override suspend fun isProtected() = checked { inner.isProtected() }
    override suspend fun checkScreen() = checked { inner.checkScreen() }

    // ---------- Actions on the current screen ----------
    override suspend fun tapRef(ref: String, snapshotId: String) = checked { inner.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = checked { inner.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = checked { inner.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = checked { inner.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = checked { inner.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = checked { inner.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = checked { inner.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = checked { inner.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = checked { inner.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = checked { inner.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun idleScroll(fraction: Double) = checked { inner.idleScroll(fraction) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = checked { inner.gesture(paths, durationMs) }
    override suspend fun back() = checked { inner.back() }
    override suspend fun pressKey(keyCode: Int) = checked { inner.pressKey(keyCode) }
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = checked { inner.setWheel(ref, snapshotId, value) }

    // ---------- Waiting and events ----------
    override suspend fun waitStable(timeoutMs: Long) = checked { inner.waitStable(timeoutMs) }
    override suspend fun awaitChange(timeoutMs: Long) = checked { inner.awaitChange(timeoutMs) }
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = checked { inner.waitFor(condition, value, timeoutMs) }
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) = checked { inner.nextEvent(kind, packageName, timeoutMs) }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        checked { inner.watchEvents(kind, packageName, durationMs, maxEvents) }

    // ---------- Target-based opens: may leave a restricted app, only the target is checked ----------
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openApp(packageName, cold)
    }

    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openAppCopy(packageName, userId, cold)
    }

    override suspend fun shareText(packageName: String, text: String): ActionResult {
        target(packageName)
        return inner.shareText(packageName, text)
    }

    /** Whether audio can be shared depends only on whether the inner port implements it, without touching the UI; an actual share is checked against the list by target app. */
    override val canShareAudio: Boolean get() = inner.canShareAudio

    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult {
        target(packageName)
        return inner.shareAudio(packageName, path, text)
    }

    /** Whether images can be shared depends only on whether the inner port implements it, without touching the UI; an actual share is checked against the list by target app. */
    override val canShareImage: Boolean get() = inner.canShareImage

    override suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult {
        target(packageName)
        return inner.shareImage(packageName, path, text)
    }

    /** When an app is specified it is checked here; otherwise the port picks the handling app from the list (only the port knows which one opens it). */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        packageName?.let(::target)
        return inner.openLink(action, uri, packageName)
    }

    /** Only waits for the occupancy registration, without reading the screen; the target is checked first as for an open, so a restricted app is rejected on the spot instead of waiting. */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        target(packageName)
        return inner.awaitAppFree(packageName, userId, timeoutMs)
    }

    /** The handling app is picked by the port from the list (only the port knows which one handles it); the foreground after it finishes is checked as usual by the execution entry. */
    override suspend fun systemAction(action: SystemAction) = inner.systemAction(action)

    // ---------- Touching no app UI at all: passed through unchanged ----------
    override suspend fun systemCommand(command: String) = inner.systemCommand(command)
    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>) = inner.bulkCalendarInsert(events)
    override suspend fun listApps() = inner.listApps()
    override suspend fun otherProfiles() = inner.otherProfiles()
    override suspend fun appCopies() = inner.appCopies()
    override val afterTask: AfterTaskMarks? get() = inner.afterTask

    companion object {
        /** How long to wait before asking for the windows again when some ownership is unrecognized (a freshly opened window takes a moment to become readable). */
        const val RETRY_MS = 100L

        /** How long a verified result may be reused while the window has not changed; covers the short window where a window event arrives late. */
        const val CHECK_TTL_MS = 1_000L
    }
}

/**
 * The ownership of the app windows on this screen, with the same rules as the port's window probe: if there are app
 * windows it is their package names (null for ones whose root node cannot be read); with no app windows it is the
 * identifiable foreground app, and with neither the screen is empty (empty list). Null when the port provides no
 * window hierarchy (web, test ports), and the caller asks separately.
 */
internal fun ScreenSnapshot.accessPackages(): List<String?>? {
    val apps = windows.filter { it.type == "application" }
    if (apps.isNotEmpty()) return apps.map { it.packageName }
    if (hasWindow) return null
    return listOfNotNull(unreadableApp)
}
