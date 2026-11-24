package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpGeometry
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTimeoutException
import com.wangzan101.mika.core.cap.web.cdp.InputOutcome
import com.wangzan101.mika.core.cap.web.cdp.InputTrack
import com.wangzan101.mika.core.cap.web.cdp.input
import com.wangzan101.mika.core.cap.web.cdp.mouseClick
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.relocate
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebTabHandedOver
import com.wangzan101.mika.cap.web.cdp.NetworkTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import com.wangzan101.mika.cap.web.WebScreen.InputUncertain
import com.wangzan101.mika.cap.web.WebScreen.PageState
import com.wangzan101.mika.cap.web.WebScreen.Reach
import com.wangzan101.mika.cap.web.WebScreen.Snap
import com.wangzan101.mika.cap.web.WebScreen.Spot
import com.wangzan101.mika.cap.web.WebScreen.Target
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAG
import com.wangzan101.mika.cap.web.WebScreen.Companion.INPUT_TIMEOUT_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.UPLOAD_NOT_OPENED
import com.wangzan101.mika.cap.web.WebScreen.Companion.OBSCURED
import com.wangzan101.mika.cap.web.WebScreen.Companion.OFFSCREEN
import com.wangzan101.mika.cap.web.WebScreen.Companion.DEVTOOLS_REF
import com.wangzan101.mika.cap.web.WebScreen.Companion.STALE

// Web screen: debugging protocol connection, network-quiet waiting, clicking and coordinate conversion
/** The current tab for network and waiting work: crashes and takeovers in progress throw per the page-reading rules; null when there is no tab. */
internal fun WebScreen.netTab(): WebTab? {
    crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
    val tab = currentTab() ?: return null
    if (tab.crashed) throw WebTabCrashed(tab.id)
    if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
    return tab
}

internal fun WebScreen.crashedTab(): String? = taskId?.let { pool?.crashedTab(it) }

internal fun WebScreen.uploadResult(r: ActionResult, handed: Boolean, prepared: WebFiles.Upload.Ok): ActionResult {
    if (r !is ActionResult.Ok) return r
    return if (handed) {
        r.copy(changed = true, hint = listOfNotNull(r.hint, "Gave ${prepared.names.size} file(s) to the page: ${prepared.names.joinToString()}.").joinToString(" "))
    } else {
        ActionResult.Failed(UPLOAD_NOT_OPENED)
    }
}

/**
 * Ref → the element that is still in the page. If the element from the snapshot is still attached to the document, use it directly (page elements have real identity, so list reuse does not matter);
 * if it is gone (re-rendered, page changed), read one more screen and find the unique match by the M1 rules, or null (ref_expired) if none is found.
 * Both paths scroll the element into the center of the viewport.
 */
internal suspend fun WebScreen.resolve(tab: WebTab, ref: String, snapshotId: String): Target? {
    val snap = synchronized(history) { history.firstOrNull { it.id == snapshotId } } ?: return null
    if (snap.tab !== tab || snap.key == null) return null
    val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return null
    val element = snap.snapshot.elements.getOrNull(index) ?: return null
    val s = script(tab)
    val pageRef = snap.pageRef(ref)
    if (pageRef != null) {
        if (s.centerOf(pageRef, snap.key) != null) return Target(snap.key, pageRef, element, null, snap.scale)
    } else {
        devtoolsTarget(tab, snap, ref, snap.key, element, null)?.let { return it }
    }
    val key = "w${tab.id}-r${++internalReads}"
    val fresh = read(tab, key, key, fuse = true)
    val shortSide = min(fresh.snapshot.width, fresh.snapshot.height)
    val found = relocate(snap.locators[index], snap.locators, fresh.locators, shortSide) as? Relocation.Found ?: return null
    val ref = "@e${found.index + 1}"
    val now = fresh.snapshot.elements[found.index]
    val freshRef = fresh.pageRef(ref) ?: return devtoolsTarget(tab, fresh, ref, key, now, now.name)
    if (s.centerOf(freshRef, key) == null) return null
    return Target(key, freshRef, now, now.name, fresh.scale)
}

/** Elements known only to the accessibility tree: scroll into the viewport and re-take the box through DevTools; null when the session is gone or the element is no longer there. */
internal suspend fun WebScreen.devtoolsTarget(tab: WebTab, snap: Snap, ref: String, key: String, element: UiElement, relocatedTo: String?): Target? {
    val anchor = snap.anchors[ref] ?: return null
    val session = tab.devtools.session(tab) ?: return null
    val placed = ElementProbe(session).locate(anchor) ?: return null
    val (w, h) = viewSize(tab)
    val x = (placed.x * snap.scale).roundToInt()
    val y = (placed.y * snap.scale).roundToInt()
    val point = (x to y).takeIf { x in 0 until w && y in 0 until h }
    return Target(key, "$DEVTOOLS_REF${anchor.backendId}", element, relocatedTo, snap.scale, devtools = true, point = point, covered = placed.onTop == false,
        cssPoint = placed.x.roundToInt() to placed.y.roundToInt())
}

// Click only when the target point really is it (or one of its descendants); when it is covered, say what covers it
internal suspend fun WebScreen.reachable(tab: WebTab, t: Target): Reach = if (t.devtools) {
    when {
        t.point == null || t.cssPoint == null -> Reach.Blocked(ActionResult.Failed(OFFSCREEN))
        t.covered -> Reach.Blocked(ActionResult.Failed(OBSCURED))
        else -> Reach.Point(t.point.first, t.point.second, t.cssPoint.first, t.cssPoint.second)
    }
} else when (val hit = script(tab).hitTest(t.ref, t.key)) {
    is HitResult.Hit -> Reach.Point(px(hit.x, t.scale), px(hit.y, t.scale), hit.x, hit.y)
    is HitResult.Obscured -> Reach.Blocked(
        ActionResult.Failed(OBSCURED, hint = hit.byName.takeIf { it.isNotBlank() }?.let { "Covered by 「$it」." }),
    )
    HitResult.Offscreen -> Reach.Blocked(ActionResult.Failed(OFFSCREEN))
    HitResult.Gone -> Reach.Blocked(STALE)
}

// The fingerprint does not count focus on non-input elements: focusing a button by clicking it is not "the UI changed"
internal suspend fun WebScreen.state(tab: WebTab): PageState {
    val snap = read(tab, null, "w${tab.id}-state").snapshot
    val fingerprint = snap.elements.map { e -> listOf(e.role, e.name, e.bounds, e.editable && e.focused) }.hashCode()
    // With a dialog up the page script is stalled, so the mutation count cannot be read
    val mutations = if (snap.web?.dialog != null) -1L else script(tab).mutationCount()
    return PageState(snap.web?.url, mutations, fingerprint)
}

/** Called before an action starts: when DevTools is available, obtains (opening it if needed) this tab's network recording and notes "this action starts here". */
internal suspend fun WebScreen.network(tab: WebTab): NetworkTracker? = NetworkTracker.of(tab, clock)?.also { it.markAction() }

/** Quiet when there has been no page mutation for [quietMs] in a row, the URL is unchanged and the main document is not loading; returns false if it is still not quiet at [capMs]. */
internal suspend fun WebScreen.awaitQuiet(tab: WebTab, quietMs: Long, capMs: Long): Boolean {
    // A page dialog is up: the page is stalled, it will not change again and cannot be read either
    if (tab.dialogs.current != null) return true
    val s = script(tab)
    val deadline = clock() + capMs
    var lastMut = s.mutationCount()
    var lastUrl = url(tab)
    var since = clock()
    while (true) {
        delay(POLL_MS)
        if (tab.crashed) return false
        // The action raised a page dialog: the page is stalled, it will not change again and cannot be read either
        if (tab.dialogs.current != null) return true
        val m = s.mutationCount()
        val u = url(tab)
        val now = clock()
        if (m != lastMut || u != lastUrl) {
            lastMut = m
            lastUrl = u
            since = now
        }
        if (!tab.loading && now - since >= quietMs) return true
        if (now >= deadline) return false
    }
}

/** The session usable for input on this tab right now; null if there is none, it has closed, or Input got stuck on it before. */
internal fun WebScreen.devtools(tab: WebTab): CdpSession? {
    val s = runCatching { tab.devtools.session(tab) }.getOrNull()?.takeIf { !it.closed.value } ?: return null
    return s.takeIf { synchronized(inputStuck) { inputStuck[tab] } !== s }
}

/**
 * Performs one input action through DevTools [block]: true when done; false when not a single event was sent (no session, session closed, rejected on the very first send), so the
 * caller may fall back to native input; failing after events were sent (timeouts included) throws [InputUncertain], and any key being held has been released.
 */
internal suspend fun WebScreen.cdp(tab: WebTab, what: String, block: suspend InputTrack.() -> Unit): Boolean {
    val s = devtools(tab) ?: return false
    return when (val o = s.input(INPUT_TIMEOUT_MS, pacing, block)) {
        is InputOutcome.Done -> true
        is InputOutcome.NotSent -> {
            stuckIfTimedOut(tab, s, o.error)
            Log.w(TAG, "devtools $what not sent, using native input", o.error)
            false
        }
        is InputOutcome.Uncertain -> {
            stuckIfTimedOut(tab, s, o.error)
            Log.w(TAG, "devtools $what failed after sending input; not retrying", o.error)
            throw InputUncertain()
        }
    }
}

/** The scroll wheel is only used on desktop-mode tabs currently attached and shown in a window: on a background (not drawing) page the wheel makes Input hang. */
internal suspend fun WebScreen.wheelable(tab: WebTab): Boolean = !tab.devtools.isMobile(tab) && withContext(Dispatchers.Main) {
    val v = tab.view
    v.isAttachedToWindow && v.isShown && v.windowVisibility == android.view.View.VISIBLE
}

internal fun WebScreen.stuckIfTimedOut(tab: WebTab, s: CdpSession, e: Throwable) {
    if (e is CdpTimeoutException) synchronized(inputStuck) { inputStuck[tab] = s }
}

internal suspend fun WebScreen.click(tab: WebTab, p: Reach.Point) {
    if (!cdp(tab, "click") { mouseClick(p.cssX.toDouble(), p.cssY.toDouble()) }) input(tab).tap(p.x, p.y)
}

/** Converts the target to CSS pixels: a ref is resolved by the click rules with an occlusion check; coordinates are view pixels and are converted with the current viewport width. */
internal suspend fun WebScreen.cssPoint(tab: WebTab, at: WebPoint): Spot = when (at) {
    is WebPoint.Ref -> {
        val t = resolve(tab, at.ref, at.snapshotId)
        if (t == null) Spot.Fail(STALE) else when (val hit = reachable(tab, t)) {
            is Reach.Point -> Spot.At(hit.cssX.toDouble(), hit.cssY.toDouble(), t.relocatedTo)
            is Reach.Blocked -> Spot.Fail(hit.result)
        }
    }
    is WebPoint.Xy -> {
        val k = (devtools(tab)?.input(INPUT_TIMEOUT_MS) { cssScale(tab) } as? InputOutcome.Done)?.value
        if (k == null) Spot.Fail(ActionResult.Failed(DEVTOOLS_UNAVAILABLE)) else Spot.At(CdpGeometry.toCss(at.x, k), CdpGeometry.toCss(at.y, k), null)
    }
}

internal suspend fun WebScreen.viewSize(tab: WebTab): Pair<Int, Int> = withContext(Dispatchers.Main) {
    val v = tab.view
    if (v.width > 0 && v.height > 0) v.width to v.height else host.width to host.height
}

internal fun WebScreen.px(css: Int, scale: Double) = (css * scale).roundToInt()
