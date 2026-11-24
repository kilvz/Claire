package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ElementLocator
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.PageTree
import com.wangzan101.mika.core.screen.uistate.WebInfo
import com.wangzan101.mika.core.screen.uistate.PageDialog
import com.wangzan101.mika.core.cap.web.DIALOG_OPEN
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import com.wangzan101.mika.cap.web.WebScreen.Ready
import com.wangzan101.mika.cap.web.WebScreen.Snap
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAG
import com.wangzan101.mika.cap.web.WebScreen.Companion.NO_TAB
import com.wangzan101.mika.cap.web.WebScreen.Companion.TAB_CRASHED
import com.wangzan101.mika.cap.web.WebScreen.Companion.HANDED_OVER
import com.wangzan101.mika.cap.web.WebScreen.Companion.UNZOOM_STEPS
import com.wangzan101.mika.cap.web.WebScreen.Companion.UNZOOM_SETTLE_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.PINCH_PROBE

// Web screen: page snapshot reads, dialog snapshots, element locating, and viewport zoom
internal fun WebScreen.ready(): Ready {
    val tab = currentTab() ?: return Ready.Fail(ActionResult.Failed(if (crashedTab() != null) TAB_CRASHED else NO_TAB))
    if (tab.crashed) return Ready.Fail(ActionResult.Failed(TAB_CRASHED))
    // The user is operating this page in the takeover UI: the Agent must not touch it at the same time
    if (pool?.isLocked(tab.id) == true) return Ready.Fail(ActionResult.Failed(HANDED_OVER))
    // While a page dialog is pending the page is stalled, so clicks and typing never land on it: report the dialog first
    if (tab.dialogs.current != null) return Ready.Fail(ActionResult.Failed(DIALOG_OPEN))
    return Ready.Tab(tab)
}

/** When [fuse] is true and the tab has a DevTools session, elements are fused in; the reads used for verification and obstruction checks skip this and stay fast. */
internal suspend fun WebScreen.read(tab: WebTab, key: String?, id: String, fuse: Boolean = false): Snap {
    // While a dialog is pending the page scripts are stalled, so reading the page would only wait for the timeout: report just the dialog
    tab.dialogs.current?.let { d -> return dialogSnap(tab, id, d) }
    val s = script(tab)
    val urlBefore = url(tab)
    val mutBefore = s.mutationCount()
    val json = s.tree(key)
    val (w, h) = viewSize(tab)
    val urlAfter = url(tab)
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
    val mut = (root?.get("mut") as? JsonPrimitive)?.longOrNull ?: mutBefore
    val vw = (root?.get("vw") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
    val info = WebInfo(urlAfter.orEmpty(), title(tab), tab.id, tab.httpErrorStatus)
    val parsed = PageTree.parse(json, w, h, id, info)
    val snapshot = parsed.copy(
        packageName = urlAfter?.let(WebUrlPolicy::host),
        changedDuringRead = urlBefore != urlAfter || mut != mutBefore,
    )
    val scale = vw?.let { w / it } ?: 1.0
    val session = if (fuse && snapshot.elements.isNotEmpty()) tab.devtools.session(tab) else null
    if (session != null) fused(tab, s, session, key, id, snapshot, scale, mut)?.let { return it }
    return Snap(id, tab, key, snapshot, locators(snapshot.elements), scale, mut)
}

/** Element fusion; null on DevTools timeout or error (the reason is logged), and the read then uses the page-script result. */
internal suspend fun WebScreen.fused(tab: WebTab, s: PageScript, session: CdpSession, key: String?, id: String, snapshot: ScreenSnapshot, scale: Double, mut: Long): Snap? {
    val outcome = try {
        ElementProbe(session).enrich(snapshot, s.refNodesExpression(key), scale)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ElementProbe.Outcome.Skipped(e.message ?: e.javaClass.simpleName)
    }
    if (outcome !is ElementProbe.Outcome.Fused) {
        Log.i(TAG, "element fusion skipped on tab ${tab.id}: ${(outcome as ElementProbe.Outcome.Skipped).reason}")
        return null
    }
    Log.d(TAG, "element fusion $id: ${outcome.stats}")
    val r = outcome.result
    // The page moved again while enriching: report it truthfully
    val moved = s.mutationCount() != mut
    val out = snapshot.copy(
        elements = r.elements,
        changedDuringRead = snapshot.changedDuringRead || moved,
        web = snapshot.web?.let { it.copy(truncated = it.truncated || r.omitted > 0, omitted = r.omitted) },
    )
    return Snap(id, tab, key, out, locators(out.elements), scale, mut, r.pageRefs, r.backendIds, outcome.anchors)
}

internal suspend fun WebScreen.dialogSnap(tab: WebTab, id: String, d: PageDialog): Snap {
    val u = url(tab).orEmpty()
    val (w, h) = viewSize(tab)
    val snapshot = ScreenSnapshot(id, WebUrlPolicy.host(u), emptyList(), w, h,
        web = WebInfo(u, title(tab), tab.id, tab.httpErrorStatus, dialog = d))
    return Snap(id, tab, null, snapshot, emptyList(), 1.0, 0L)
}

internal fun WebScreen.emptySnapshot(tab: WebTab?) = ScreenSnapshot(
    snapshotId = "w${tab?.id ?: "none"}-empty", packageName = null, elements = emptyList(),
    width = host.width, height = host.height, hasWindow = false,
)

// The context is the nearest preceding named element in traversal order, so a same-named element elsewhere cannot be mistaken for this one
internal fun WebScreen.locators(elements: List<UiElement>): List<ElementLocator> {
    var last: String? = null
    return elements.map { e ->
        ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, hint = e.hint).also {
            if (e.name.isNotBlank()) last = e.name
        }
    }
}

/** The current tab is borrowed (it lives in the user's UI, not on the web display). */
internal fun WebScreen.borrowed(tab: WebTab): Boolean = pool?.isExternal(tab.id) == true

/**
 * How the borrowed tab looks right now: the view itself is drawn into a bitmap (pixel-for-pixel with the view, so tap coordinates still apply),
 * excluding the UI layered on top of it (the conversation panel). Null while the view has not been laid out yet.
 */
internal suspend fun WebScreen.viewFrame(tab: WebTab): android.graphics.Bitmap? = withContext(Dispatchers.Main) {
    val v = tab.view
    if (tab.destroyed || v.width <= 0 || v.height <= 0) return@withContext null
    runCatching {
        android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
    }.onFailure { Log.w(TAG, "could not draw borrowed tab ${tab.id}", it) }.getOrNull()
}

/**
 * The user pinch-zoomed on the borrowed tab: zoom back out to the whole page before reading. Page scripts report coordinates in the layout
 * viewport, and when zoomed in only a slice of it is visible, so the numbered coordinates would not line up with the screen without zooming out.
 * Does nothing when not zoomed in; if zooming out fails (page forbids zoom, etc.) the read proceeds as usual.
 */
internal suspend fun WebScreen.unzoom(tab: WebTab) {
    if (!pinched(tab)) return
    repeat(UNZOOM_STEPS) {
        val more = withContext(Dispatchers.Main) { !tab.destroyed && tab.view.zoomOut() }
        delay(UNZOOM_SETTLE_MS)
        if (!more || !pinched(tab)) return
    }
}

// The visible part (visual viewport) is narrower than the layout viewport, or has been panned away: the user zoomed in
internal suspend fun WebScreen.pinched(tab: WebTab): Boolean {
    val raw = kotlinx.coroutines.withTimeoutOrNull(UNZOOM_SETTLE_MS * 4) {
        kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
            tab.view.post {
                if (tab.destroyed) c.resumeWith(Result.success(null))
                else tab.view.evaluateJavascript(PINCH_PROBE) { c.resumeWith(Result.success(it)) }
            }
        }
    }
    return raw?.trim('"') == "1"
}
