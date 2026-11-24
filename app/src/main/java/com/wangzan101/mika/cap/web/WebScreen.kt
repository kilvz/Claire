package com.wangzan101.mika.cap.web

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.wangzan101.mika.core.cap.web.cdp.CdpGeometry
import com.wangzan101.mika.core.screen.uistate.CdpKeys
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.cap.web.cdp.InputTrack
import com.wangzan101.mika.core.cap.web.cdp.SelectOutcome
import com.wangzan101.mika.core.cap.web.cdp.input
import com.wangzan101.mika.core.cap.web.cdp.InputPacing
import com.wangzan101.mika.core.cap.web.cdp.mouseWheel
import com.wangzan101.mika.core.cap.web.cdp.wheelInPlace
import com.wangzan101.mika.core.cap.web.cdp.typeText
import com.wangzan101.mika.core.cap.web.cdp.mouseClick
import com.wangzan101.mika.core.cap.web.cdp.mouseDoubleClick
import com.wangzan101.mika.core.cap.web.cdp.mouseDrag
import com.wangzan101.mika.core.cap.web.cdp.mouseMove
import com.wangzan101.mika.core.cap.web.cdp.pressCombo
import com.wangzan101.mika.core.cap.web.cdp.selectOptionAt
import com.wangzan101.mika.core.cap.web.cdp.viewportCssWidth
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.visual.FrameGrid
import com.wangzan101.mika.core.screen.uistate.ElementLocator
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.DevtoolsUnavailable
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.ElementProbe
import com.wangzan101.mika.core.cap.web.EvalJs
import com.wangzan101.mika.core.cap.web.NavResult
import com.wangzan101.mika.core.cap.web.cdp.CdpConnectionClosedException
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptRun
import com.wangzan101.mika.cap.web.cdp.PageScriptRunner
import com.wangzan101.mika.core.cap.web.cdp.ReadScriptRunner
import com.wangzan101.mika.core.cap.web.SiteModeResult
import com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS
import com.wangzan101.mika.core.cap.web.KeepOpenResult
import com.wangzan101.mika.core.cap.web.WebTabIsUsers
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebBarrier
import com.wangzan101.mika.core.cap.web.WebFacts
import com.wangzan101.mika.core.cap.web.DialogAnswer
import com.wangzan101.mika.core.cap.web.NO_DIALOG
import com.wangzan101.mika.core.cap.web.WebDialogOpen
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebTabHandedOver
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.cap.web.SettlePolicy
import com.wangzan101.mika.core.cap.web.WaitKind
import com.wangzan101.mika.core.cap.web.WaitOutcome
import com.wangzan101.mika.cap.web.cdp.NetworkTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The web port: implements the same screen reading and actions as the phone, on the current tab.
 * Taps, swipes, typing and key presses all go through real input, then verify honestly afterwards. When the tab has a DevTools session, taps, keys and inserted text go through
 * its mouse and keyboard (desktop pages need real mouse semantics); when there is no session or the call fails, they fall back to [WebInput]'s native touches and keys; scrolling
 * always uses native swipes (the DevTools wheel deadlocks page input in background tabs). Verification:
 * "did it change" compares the URL, the page mutation count and the element-tree fingerprint, and waits for the page to go quiet (500 ms with no change and the main document not
 * loading), for at most 3 seconds; when the tab has a DevTools session, requests issued by this action that are still in flight are also awaited, up to 5 seconds in total ([settle]),
 * and if that is not reached settled=false is recorded in the result; a changed host is recorded in appSwitched; typing is read back and compared as a whole after collapsing
 * whitespace, and password fields are not read back (UNKNOWN).
 * Numbering: snapshotId looks like w<tab>-<n>, and page scripts keep a numbering table per snapshot number; when an element is gone, the unique match is found in the new tree by
 * role, name and position. The tab is provided by [currentTab] (once the tab pool takes over, it is the current tab of the current task).
 * When the current tab is borrowed (the one the user is watching on the browser page, see [WebTabPool.attachExternal]): it is not on the web display, so screenshots draw this view
 * directly; before reading, the user's pinch zoom is zoomed back out to the whole page (numbered coordinates are based on the full page); no light scroll while idle; opening a URL
 * uses a new tab and does not replace the user's page. Read scripts (web_evaluate_js, site commands) run read-only in the current tab's read world, one [ReadScriptRunner] per tab.
 */
class WebScreen(
    internal val currentTab: () -> WebTab?,
    internal val host: WebHost,
    internal val clock: () -> Long,
    internal val pool: WebTabPool? = null,
    internal val taskId: String? = null,
    /** The workspace backing downloads and uploads; when null, both downloads and uploads report unavailable. */
    internal val files: WebFiles? = null,
    /** The page currently kept in the background (see [PageKeeper]); when null no page can be kept, and web_keep_open reports it as unavailable. */
    internal val keptNow: (() -> KeptPage?)? = null,
) : WebPort {
    /** The web port of one task: the current tab is this task's current tab in the tab pool. */
    constructor(pool: WebTabPool, taskId: String, host: WebHost, clock: () -> Long, files: WebFiles? = null, keptNow: (() -> KeptPage?)? = null) :
        this({ pool.current(taskId) }, host, clock, pool, taskId, files, keptNow)

    /** The page the model wants kept after the task ends (web_keep_open): [tabId] is which tab; taken by [WebStack.close] when the task ends. */
    data class KeepMark(val tabId: String, val reason: String)

    @Volatile var keepMark: KeepMark? = null
        internal set

    internal val scripts = WeakHashMap<WebTab, PageScript>()
    internal val readers = WeakHashMap<WebTab, ReadScriptRunner>()
    internal val inputs = WeakHashMap<WebTab, WebInput>()
    /** Sessions whose Input call has timed out: Input on a background page may be stuck altogether, so the same session is not used for input again, sparing every action a timeout wait. */
    internal val inputStuck = WeakHashMap<WebTab, CdpSession>()
    /** Pacing of DevTools mouse and keyboard input: path, hold duration, key interval, wheel notches (see [InputPacing]). */
    internal val pacing = InputPacing.human()
    internal val history = ArrayDeque<Snap>()
    internal var counter = 0
    internal var internalReads = 0

    /**
     * One screen read: the elements handed to the model, the features used to recognize them, and the CSS-pixel to view-pixel ratio. [key] is the name of the numbering table in the page.
     * Element fusion rearranges the numbering: [pageRefs] maps snapshot number -> number in the page script's table (null means the two are the same);
     * elements known only to the accessibility tree are not in it and are found through DevTools via [anchors]; [backendIds] are the matching DOM node ids.
     */
    internal class Snap(
        val id: String,
        val tab: WebTab,
        val key: String?,
        val snapshot: ScreenSnapshot,
        val locators: List<ElementLocator>,
        val scale: Double,
        val mutations: Long,
        val pageRefs: Map<String, String>? = null,
        val backendIds: Map<String, Int> = emptyMap(),
        val anchors: Map<String, ElementProbe.Anchor> = emptyMap(),
    ) {
        fun pageRef(ref: String): String? = if (pageRefs == null) ref else pageRefs[ref]
    }

    /**
     * Result of resolving a number: the numbering table [key] in the page and the number [ref]; after relocation [relocatedTo] is the current name of the recovered element.
     * [devtools] true means the element is known only to the accessibility tree (e.g. inside a cross-origin frame) and [ref] is not in the page script's table;
     * the point to click is [point] (view pixels, null when outside the viewport), and [covered] true means hit testing landed on another element.
     */
    internal class Target(
        val key: String,
        val ref: String,
        val element: UiElement,
        val relocatedTo: String?,
        val scale: Double,
        val devtools: Boolean = false,
        val point: Pair<Int, Int>? = null,
        val covered: Boolean = false,
        /** The top-level viewport CSS pixels corresponding to [point] (used for DevTools input). */
        val cssPoint: Pair<Int, Int>? = null,
    )

    internal data class PageState(val url: String?, val mutations: Long, val fingerprint: Int)

    internal sealed interface Ready {
        class Tab(val tab: WebTab) : Ready
        class Fail(val result: ActionResult) : Ready
    }

    @Synchronized
    internal fun script(tab: WebTab) = scripts.getOrPut(tab) { PageScript(tab) }

    @Synchronized
    internal fun input(tab: WebTab) = inputs.getOrPut(tab) { WebInput(tab) }

    /** While a page dialog is pending the page scripts are frozen: any call that needs to read the page reports dialog_open at once instead of waiting for a timeout. */
    internal fun dialogGuard(tab: WebTab) {
        if (tab.dialogs.current != null) throw WebDialogOpen(tab.id)
    }

    override suspend fun snapshot(): ScreenSnapshot {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return emptySnapshot(currentTab())
        if (borrowed(tab)) unzoom(tab)
        val id = "w${tab.id}-${nextId()}"
        val snap = read(tab, id, id, fuse = true)
        synchronized(history) {
            history.addFirst(snap)
            while (history.size > KEEP_SNAPSHOTS) history.removeLast()
        }
        return snap.snapshot
    }

    /** Reads a screen without registering numbers: the numbers the model holds are unaffected. */
    override suspend fun peek(): ScreenSnapshot {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return emptySnapshot(currentTab())
        return read(tab, null, "w${tab.id}-peek", fuse = true).snapshot
    }

    /** The DOM node id (backendNodeId) for [ref] in this screen; null when unregistered, unmatched, or not obtained through DevTools. */
    internal fun backendNodeId(ref: String, snapshotId: String): Int? =
        synchronized(history) { history.firstOrNull { it.id == snapshotId } }?.backendIds?.get(ref)

    override suspend fun screenshotPng(): ByteArray? {
        val tab = currentTab()?.takeIf { !it.crashed && borrowed(it) } ?: return withContext(Dispatchers.IO) { host.latestFramePng() }
        val bmp = viewFrame(tab) ?: return null
        return withContext(Dispatchers.IO) {
            java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
    }

    override suspend fun frameGrid(): FrameGrid? {
        val tab = currentTab()?.takeIf { !it.crashed && borrowed(it) }
        val bmp = (if (tab != null) viewFrame(tab) else withContext(Dispatchers.IO) { host.latestFrame() }) ?: return null
        return withContext(Dispatchers.IO) { try { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) } finally { bmp.recycle() } }
    }

    override suspend fun checkScreen(): ScreenCheck {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        val quiet = awaitQuiet(tab, QUIET_MS, CAP_MS)
        val snap = read(tab, null, "w${tab.id}-check").snapshot
        // While a dialog is pending the page scripts cannot be read: classify from the screen read alone (it carries the dialog)
        val facts = if (tab.dialogs.current != null) PageFacts(false, emptyList()) else script(tab).facts()
        val site = snap.packageName
        return WebBarrier.classify(
            WebFacts(
                base = ScreenFacts(
                    currentApp = site,
                    hasWindow = true,
                    readableElements = snap.elements.count { it.meaningful },
                    changing = !quiet,
                    blackFrames = emptyList(),
                    texts = snap.elements.map { it.name }.filter { it.isNotBlank() },
                    passwordField = facts.passwordField,
                ),
                httpStatus = tab.httpErrorStatus,
                frameUrls = facts.frameUrls,
                host = site,
            ),
        )
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return true
        return awaitQuiet(tab, QUIET_MS, timeoutMs)
    }

    /** The page's next change (document mutation count, URL, loading, dialog), using the same signals as waiting for quiet; with no tab, waits out the full timeout. */
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        // While a page dialog is pending the page is frozen: it will not change again, and the scripts cannot be read
        val tab = currentTab()?.takeIf { !it.crashed && it.dialogs.current == null } ?: run { delay(timeoutMs.coerceAtLeast(0)); return false }
        val s = script(tab)
        val deadline = clock() + timeoutMs
        val mut = s.mutationCount()
        val was = url(tab) to tab.loading
        while (clock() < deadline) {
            delay(POLL_MS)
            if (tab.crashed || tab.dialogs.current != null || (url(tab) to tab.loading) != was || s.mutationCount() != mut) return true
        }
        return false
    }

    // ---------- actions ----------

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val p = when (val hit = reachable(tab, t)) {
            is Reach.Point -> hit
            is Reach.Blocked -> return hit.result
        }
        return verified(tab, t.relocatedTo) {
            click(tab, p)
            true
        }
    }

    override suspend fun tapXY(x: Int, y: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            val clicked = cdp(tab, "click") {
                val k = cssScale(tab)
                mouseClick(CdpGeometry.toCss(x, k), CdpGeometry.toCss(y, k))
            }
            if (!clicked) input(tab).tap(x, y)
            true
        }
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val p = when (val hit = reachable(tab, t)) {
            is Reach.Point -> hit
            is Reach.Blocked -> return hit.result
        }
        return verified(tab, t.relocatedTo) {
            if (!cdp(tab, "double click") { mouseDoubleClick(p.cssX.toDouble(), p.cssY.toDouble()) }) nativeDoubleTap(tab, p.x, p.y)
            true
        }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            val clicked = cdp(tab, "double click") {
                val k = cssScale(tab)
                mouseDoubleClick(CdpGeometry.toCss(x, k), CdpGeometry.toCss(y, k))
            }
            if (!clicked) nativeDoubleTap(tab, x, y)
            true
        }
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            input(tab).swipe(x1, y1, x2, y2, durationMs)
            true
        }
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val s = script(tab)
        val (w, h) = viewSize(tab)
        val target = if (ref != null && snapshotId != null) resolve(tab, ref, snapshotId) ?: return STALE else null
        val cx: Int
        val cy: Int
        val distance: Int
        if (target != null) {
            val c = if (target.devtools) target.point ?: return STALE
            else s.centerOf(target.ref, target.key)?.let { px(it.first, target.scale) to px(it.second, target.scale) } ?: return STALE
            cx = c.first
            cy = c.second
            val heightPx = (target.element.bounds[3] - target.element.bounds[1]) * (h - 1) / 999
            distance = min(heightPx * 6 / 10, h / 2).coerceAtLeast(MIN_SCROLL_PX)
        } else {
            cx = w / 2
            cy = h / 2
            distance = h * 4 / 10
        }
        val before = s.scrollState(target?.ref, target?.key ?: PageScript.DEFAULT_KEY)
        // Desktop mode and the tab is visible: scroll notch by notch with the mouse wheel (touch would betray the desktop identity); otherwise, or if the wheel cannot be sent, use a native swipe
        val wheeled = wheelable(tab) && try {
            cdp(tab, "wheel") {
                val k = cssScale(tab)
                mouseWheel(CdpGeometry.toCss(cx, k), CdpGeometry.toCss(cy, k), (if (down) distance else -distance) / k)
            }
        } catch (e: InputUncertain) {
            // Scrolling is not an irreversible action: do not repeat it, just compare before and after as usual
            true
        }
        if (!wheeled) {
            val (from, to) = if (down) cy + distance / 2 to cy - distance / 2 else cy - distance / 2 to cy + distance / 2
            input(tab).swipe(cx, from, cx, to, SCROLL_SWIPE_MS)
        }
        awaitQuiet(tab, SCROLL_QUIET_MS, SCROLL_CAP_MS)
        val after = s.scrollState(target?.ref, target?.key ?: PageScript.DEFAULT_KEY)
        return ActionResult.Ok(
            changed = before != after,
            relocated = target?.relocatedTo != null,
            relocatedTo = target?.relocatedTo,
        )
    }

    /**
     * While idle, casually scroll the whole page a little: no scrolling when a page dialog is open, the IME is showing, a field has focus, or the tab is not displayed.
     * Desktop mode uses the wheel (the pointer does not move); otherwise one slow native swipe is used; neither moves the mouse nor clicks.
     */
    override suspend fun idleScroll(fraction: Double): Boolean {
        // The page the user is watching is not scrolled on their behalf
        val tab = (ready() as? Ready.Tab)?.tab?.takeIf { !borrowed(it) } ?: return false
        val shown = withContext(Dispatchers.Main) {
            val v = tab.view
            // WindowInsets.Type only exists from Android 11; earlier systems skip the IME check and keep the other conditions.
            val ime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            v.isAttachedToWindow && v.isShown && v.windowVisibility == android.view.View.VISIBLE && !ime
        }
        if (!shown) return false
        if (script(tab).fieldValue(null) !is FieldValue.NotAField) return false
        val (w, h) = viewSize(tab)
        val distance = (h * kotlin.math.abs(fraction)).roundToInt()
        if (distance < 1) return false
        val down = fraction > 0
        val wheeled = wheelable(tab) && try {
            cdp(tab, "idle wheel") {
                val k = cssScale(tab)
                wheelInPlace(CdpGeometry.toCss(w / 2, k), CdpGeometry.toCss(h / 2, k), (if (down) distance else -distance) / k)
            }
        } catch (e: InputUncertain) {
            true
        }
        if (!wheeled) {
            val cy = h / 2
            val (from, to) = if (down) cy + distance / 2 to cy - distance / 2 else cy - distance / 2 to cy + distance / 2
            input(tab).swipe(w / 2, from, w / 2, to, IDLE_SWIPE_MS)
        }
        return true
    }

    override suspend fun back(): ActionResult = historyBack()

    override suspend fun historyBack(): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        if (!withContext(Dispatchers.Main) { tab.view.canGoBack() }) return ActionResult.Failed(NO_HISTORY)
        return verified(tab) {
            withContext(Dispatchers.Main) { tab.view.goBack() }
            true
        }
    }

    override suspend fun pressKey(keyCode: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        if (keyCode !in ALLOWED_KEYS) return ActionResult.Failed(KEY_NOT_ALLOWED)
        return verified(tab) {
            val k = CdpKeys.forAndroid(keyCode)
            if (k == null || !cdp(tab, "key") { pressCombo(KeyCombo(emptyList(), k)) }) input(tab).key(keyCode)
            true
        }
    }

    // ---------- web_interact ----------

    override suspend fun hover(at: WebPoint): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        val p = when (val c = cssPoint(tab, at)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        return verified(tab, p.relocatedTo, DEVTOOLS_UNAVAILABLE) { cdp(tab, "hover") { mouseMove(p.x, p.y) } }
    }

    /** Resolve the end point first (which scrolls it into the viewport), then the start point, then measure the end point once more; when both ends cannot be in the viewport at once (too far apart), report offscreen truthfully. */
    override suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        (cssPoint(tab, to) as? Spot.Fail)?.let { return it.result }
        val a = when (val c = cssPoint(tab, from)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        val b = when (val c = cssPoint(tab, to)) { is Spot.Fail -> return c.result; is Spot.At -> c }
        var native = false
        val r = verified(tab, a.relocatedTo ?: b.relocatedTo, DEVTOOLS_UNAVAILABLE) {
            cdp(tab, "drag") { native = mouseDrag(a.x, a.y, b.x, b.y, steps) }
        }
        return if (native && r is ActionResult.Ok) r.copy(hint = listOfNotNull(r.hint, "Dropped with the page's own drag and drop.").joinToString(" ")) else r
    }

    override suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        devtools(tab) ?: return ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        val p = when (val c = cssPoint(tab, WebPoint.Ref(ref, snapshotId))) { is Spot.Fail -> return c.result; is Spot.At -> c }
        var outcome: SelectOutcome? = null
        val r = verified(tab, p.relocatedTo, DEVTOOLS_UNAVAILABLE) {
            cdp(tab, "select") { outcome = selectOptionAt(p.x, p.y, option) }
        }
        return when (val o = outcome) {
            null -> r
            is SelectOutcome.Selected -> if (r !is ActionResult.Ok) r else r.copy(
                changed = r.changed == true || o.changed,
                verification = Verification.MATCH,
                hint = listOfNotNull(
                    r.hint,
                    "Selected ${JsonPrimitive(o.text)} (option text comes from the page)." + if (o.changed) "" else " It was already selected.",
                    "The arrow keys did not change it, so it was set by script; check that the page reacted.".takeIf { o.byScript },
                ).joinToString(" "),
            )
            SelectOutcome.NotASelect -> ActionResult.Failed(NOT_A_SELECT)
            SelectOutcome.Disabled -> ActionResult.Failed(SELECT_DISABLED)
            is SelectOutcome.OptionDisabled -> ActionResult.Failed(OPTION_DISABLED, hint = JsonPrimitive(o.text).toString())
            is SelectOutcome.NotApplied -> ActionResult.Failed(SELECT_NOT_APPLIED)
            is SelectOutcome.NoSuchOption -> ActionResult.Failed(
                NO_SUCH_OPTION,
                hint = "Options: " + o.available.take(MAX_LISTED_OPTIONS).joinToString(", ") { JsonPrimitive(it).toString() } +
                    if (o.available.size > MAX_LISTED_OPTIONS) " and ${o.available.size - MAX_LISTED_OPTIONS} more." else ".",
            )
        }
    }

    /** Key combos: go through DevTools' keyboard when available (each modifier is pressed and released), otherwise native keys with modifier state. */
    override suspend fun answerDialog(accept: Boolean, text: String?): ActionResult {
        val tab = currentTab()?.takeIf { !it.crashed } ?: return ActionResult.Failed(if (crashedTab() != null) TAB_CRASHED else NO_TAB)
        if (pool?.isLocked(tab.id) == true) return ActionResult.Failed(HANDED_OVER)
        val before = url(tab)
        val answered = when (val a = tab.dialogs.answer(accept, text)) {
            DialogAnswer.NoDialog -> return ActionResult.Failed(NO_DIALOG)
            is DialogAnswer.Answered -> a
        }
        val settled = settle(tab)
        val after = url(tab)
        val hint = listOfNotNull(
            "Answered the page's ${answered.dialog.kind} dialog (${if (accept) "accept" else "dismiss"}).",
            "The text was not used: only an accepted prompt dialog takes text.".takeIf { answered.textIgnored },
        ).joinToString(" ")
        return ActionResult.Ok(
            changed = true,
            appSwitched = after?.let(WebUrlPolicy::host)?.takeIf { it != before?.let(WebUrlPolicy::host) },
            hint = hint, settled = settled,
        )
    }

    override suspend fun pressCombo(combo: KeyCombo): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        return verified(tab) {
            if (!cdp(tab, "key") { pressCombo(combo) }) input(tab).key(combo.key.androidKeyCode, combo.androidMeta)
            true
        }
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult =
        try { typeInto(ref, snapshotId, text) } catch (e: InputUncertain) { UNCERTAIN }

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult =
        try { clearField(ref, snapshotId) } catch (e: InputUncertain) { UNCERTAIN }

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult =
        throw UnsupportedOperationException("the web surface cannot open apps")

    override suspend fun listApps(): List<Pair<String, String>> = throw UnsupportedOperationException("the web surface has no apps")

    // ---------- web-only ----------

    override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean): PageText {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return PageText("", "", "", emptyList(), emptyList(), false, null)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val raw = script(tab).readPage(maxChars, offset, scroll = scrollLazy)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun pairs(k: String, a: String) = (o?.get(k) as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val first = (m[a] as? JsonPrimitive)?.content.orEmpty()
            val url = (m["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            first to url
        }
        return PageText(
            url = str("url").ifEmpty { url(tab).orEmpty() },
            title = str("title"),
            text = str("text"),
            links = pairs("links", "text"),
            images = pairs("images", "alt"),
            truncated = (o?.get("truncated") as? JsonPrimitive)?.booleanOrNull == true,
            nextOffset = (o?.get("nextOffset") as? JsonPrimitive)?.intOrNull,
        )
    }

    /** web_read_page: the current tab is read through [digestTab]; when a URL is given, it is read in a temporary background tab without touching the current tab. */
    override suspend fun read(req: PageRead): PageText {
        if (req.url != null) return readInBackground(req, req.url!!)
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return PageText("", "", "", emptyList(), emptyList(), false, null)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        return digestTab(tab, req)
    }

    /** Read script: runs read-only in the read world of the current tab's real page (see [ReadScriptRunner]); one is kept per tab and state persists across calls. Without a DevTools session it reports devtools_unavailable. */
    override suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject?): EvalJs {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return EvalJs.Failed(NO_TAB, "No web page is open.")
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val session = devtools(tab)
            ?: return EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "This page has no DevTools connection, so read scripts cannot run here. Use web_read_page or web_observe instead.")
        val runner = readers[tab]?.takeIf { it.session === session } ?: ReadScriptRunner(session).also { readers[tab] = it }
        return try {
            runner.run(script, args, timeoutMs)
        } catch (e: CdpConnectionClosedException) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "The page's DevTools connection was lost.")
        }
    }

    /** web_script: runs in the isolated world of the current tab's real page; actions come back through [bridge] into Claire's action path. Without a DevTools session it reports devtools_unavailable. */
    override suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun {
        crashedTab()?.takeIf { currentTab() == null }?.let { throw WebTabCrashed(it) }
        val tab = currentTab() ?: return ScriptRun.Failed(NO_TAB, "No web page is open.")
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        dialogGuard(tab)
        val session = tab.devtools.session(tab)
            ?: return ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "This page has no DevTools connection, so page scripts cannot run here.")
        return try {
            // Check again before every mika action: while the script runs the page may be handed to the user or crash
            PageScriptRunner(session, preflight = {
                when {
                    tab.crashed -> ScriptRun.Failed("tab_crashed", "The page's tab crashed.")
                    pool?.isLocked(tab.id) == true -> ScriptRun.Failed("handed_over", "The user took over this page, so the script stopped.")
                    currentTab() !== tab -> ScriptRun.Failed("tab_changed", "The current tab changed, so the script stopped.")
                    else -> null
                }
            }).run(code, timeoutMs, bridge)
        } catch (e: CdpConnectionClosedException) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "The page's DevTools connection was lost.")
        }
    }

    /**
     * Open a URL: opens one in the pool when there is no tab or a new tab is requested, otherwise uses the current tab. Waits for the main document to load (20 s max), then for
     * the page to go quiet (so script redirects are followed too), and returns the actual URL after redirects, the title, the main-document HTTP status (when available), and any blocked app-jump link.
     */
    override suspend fun navigate(url: String, newTab: Boolean): NavResult {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        val task = taskId!!
        var opened = false
        // Do not open another URL on the current page while the user is working on it
        pool.current(task)?.takeIf { !newTab && !borrowed(it) && pool.isLocked(it.id) }?.let { throw WebTabHandedOver(it.id) }
        // A dialog is pending on the current page: have it answered first, and do not open another URL on a frozen page
        pool.current(task)?.takeIf { !newTab && !it.crashed && !borrowed(it) }?.let(::dialogGuard)
        // Do not replace the page the user is watching (borrowed): other URLs are opened in a new tab
        val tab = withContext(Dispatchers.Main) {
            pool.current(task)?.takeIf { !newTab && !it.crashed && !borrowed(it) } ?: pool.open(task, null).also { opened = true }
        }
        val net = network(tab)
        val downloadsBefore = files?.downloads?.count() ?: 0
        tab.load(url, NAVIGATE_TIMEOUT_MS)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        // Cleartext http was refused: retry with https. The App's network security config already allows cleartext; this is only a fallback (when a device policy or the WebView still refuses)
        var upgraded: String? = null
        if (tab.mainFrameError?.contains(CLEARTEXT) == true && url.startsWith("http://", ignoreCase = true)) {
            upgraded = url
            tab.load("https://" + url.substring("http://".length), NAVIGATE_TIMEOUT_MS)
            if (tab.crashed) throw WebTabCrashed(tab.id)
        }
        // What opened is actually a file: a download is only registered after the page is asked for a name, so wait for that before counting
        files?.downloads?.awaitNamed(DOWNLOAD_NAMING_CAP_MS)
        val download = files?.downloads?.since(downloadsBefore)?.lastOrNull()
        val error = tab.mainFrameError.takeIf { download == null && !tab.loadBecameDownload }
        // A dialog appeared while loading: the load wait has stopped and the page is frozen, so do not wait or read any further
        val dialog = tab.dialogs.current
        val settled = if (tab.lastLoadFinished && error == null && dialog == null) settle(tab, net) else null
        if (tab.crashed) throw WebTabCrashed(tab.id)
        val status = tab.httpErrorStatus ?: if (error == null && download == null && dialog == null) script(tab).navStatus() else null
        return NavResult(
            tab = TabInfo(tab.id, url(tab).orEmpty(), title(tab), current = true),
            loaded = tab.lastLoadFinished && error == null && download == null && dialog == null,
            httpStatus = status,
            newTab = opened,
            externalLink = tab.lastExternalLink,
            settled = settled,
            netError = error,
            upgradedFrom = upgraded,
            download = download,
            dialog = dialog,
        )
    }

    override suspend fun tabs(): List<TabInfo> {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        return withContext(Dispatchers.Main) { pool.list(taskId!!) }
    }

    /** Only tabs of this task can be switched to; tabs of other tasks and nonexistent ones give null. */
    override suspend fun switchTab(tabId: String): TabInfo? {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        // The current page is in the user's hands: do not switch away (switching would swap it out of the takeover UI)
        pool.current(taskId!!)?.takeIf { pool.isLocked(it.id) }?.let { throw WebTabHandedOver(it.id) }
        return withContext(Dispatchers.Main) {
            pool.switchTo(taskId!!, tabId) ?: return@withContext null
            pool.list(taskId).firstOrNull { it.tabId == tabId }
        }
    }

    /** Only tabs of this task can be closed. */
    override suspend fun closeTab(tabId: String): Boolean {
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        return withContext(Dispatchers.Main) {
            pool.close(taskId!!, tabId)
        }
    }

    /**
     * Records keeping the current page after the task ends (record only; nothing is touched right now); [keep] false cancels it. A borrowed (the user's own) page is not closed
     * with the task anyway, so there is nothing to keep. Only one page is recorded: marking another tab replaces it.
     */
    override suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? {
        val now = keptNow ?: throw UnsupportedOperationException("no page keeper")
        val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
        if (!keep) {
            val had = keepMark
            keepMark = null
            return KeepOpenResult(false, had?.tabId, null, null, null, KEEP_OPEN_HOURS)
        }
        val tab = currentTab() ?: return crashedTab()?.let { throw WebTabCrashed(it) }
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool.isExternal(tab.id)) throw WebTabIsUsers(tab.id)
        val (u, title) = withContext(Dispatchers.Main) { tab.view.url to tab.view.title }
        keepMark = KeepMark(tab.id, reason)
        Log.i(TAG, "tab ${tab.id} marked to keep running after the task")
        return KeepOpenResult(true, tab.id, u, title?.takeIf { it.isNotBlank() }, u?.let(WebUrlPolicy::host), KEEP_OPEN_HOURS, replaces = now()?.host)
    }

    override suspend fun siteMode(): String? =
        currentTab()?.takeIf { !it.crashed }?.let { if (it.devtools.isMobile(it)) "mobile" else "desktop" }

    /** Records which version the current site uses, reopens the current page with the new version, and waits for it to go quiet. */
    override suspend fun setSiteMode(mobile: Boolean): SiteModeResult? {
        val tab = currentTab() ?: return crashedTab()?.let { throw WebTabCrashed(it) }
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (pool?.isLocked(tab.id) == true) throw WebTabHandedOver(tab.id)
        val u = url(tab)?.takeIf { it.startsWith("http") } ?: return null
        val site = WebUrlPolicy.host(u) ?: return null
        tab.devtools.setSiteMode(site, mobile)
        tab.load(u, NAVIGATE_TIMEOUT_MS)
        if (tab.crashed) throw WebTabCrashed(tab.id)
        if (tab.lastLoadFinished) awaitQuiet(tab, QUIET_MS, CAP_MS)
        return SiteModeResult(site, tab.devtools.isMobile(tab), tab.lastLoadFinished)
    }

    // ---------- network capture and waiting ----------

    override suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> {
        val tab = netTab() ?: return emptyList()
        val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
        return n.list(urlContains, method, sinceLastAction)
    }

    override suspend fun capture(id: String): CapturedResponse? {
        val tab = netTab() ?: return null
        val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
        return n.get(id)
    }

    /** Text conditions read the whole page body (no scrolling, no page changes); network idle requires DevTools. */
    override suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome {
        val start = clock()
        val tab = netTab() ?: return WaitOutcome(kind == WaitKind.TEXT_ABSENT, 0, null)
        if (kind == WaitKind.NETWORK_IDLE) {
            val n = NetworkTracker.of(tab, clock) ?: throw DevtoolsUnavailable()
            val met = n.awaitIdle(timeoutMs - (clock() - start))
            return WaitOutcome(met, clock() - start, url(tab))
        }
        val want = collapse(value.orEmpty())
        val deadline = start + timeoutMs
        while (true) {
            if (tab.crashed) throw WebTabCrashed(tab.id)
            val met = when (kind) {
                WaitKind.URL_CONTAINS -> url(tab).orEmpty().contains(value.orEmpty(), ignoreCase = true)
                else -> {
                    dialogGuard(tab)
                    val raw = script(tab).readPage(WAIT_TEXT_CHARS, 0, scroll = false)
                    val text = (runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()?.get("text") as? JsonPrimitive)?.content.orEmpty()
                    collapse(text).contains(want, ignoreCase = true) == (kind == WaitKind.TEXT_PRESENT)
                }
            }
            if (met || clock() >= deadline) return WaitOutcome(met, clock() - start, url(tab))
            delay(WAIT_POLL_MS)
        }
    }

    /** Has [tab]'s page script read out a blob: URL (used for downloads). */
    internal suspend fun readBlob(tab: WebTab, url: String, maxBytes: Long): PageScript.Blob = script(tab).readBlob(url, maxBytes)

    /** The download name the page gives on the link pointing to [url] (`<a download>`); null when absent or unreadable from the page. */
    internal suspend fun downloadName(tab: WebTab, url: String): String? = script(tab).downloadName(url)

    /** This task's downloads (sandbox path, size, type, status); reports unavailable when there is no workspace. */
    override suspend fun downloads(): List<DownloadInfo> =
        files?.downloads?.list() ?: throw UnsupportedOperationException(DOWNLOADS_UNAVAILABLE)

    /**
     * Upload: first register the workspace files as "waiting to be handed over", then really tap the file control; when the page opens a chooser, hand these files to it.
     * A path outside the workspace or missing (already checked in the tool layer, re-checked here before handing over) reports path_outside_workspace / file_not_found respectively.
     * Tapped but no chooser appeared: say so truthfully, and the waiting files are discarded.
     */
    override suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult {
        val tab = when (val r = ready()) { is Ready.Fail -> return r.result; is Ready.Tab -> r.tab }
        val f = files ?: return ActionResult.Failed(UPLOAD_UNAVAILABLE)
        val t = resolve(tab, ref, snapshotId) ?: return STALE
        val prepared = when (val u = withContext(Dispatchers.IO) { f.prepareUpload(paths) }) {
            is WebFiles.Upload.Refused -> return ActionResult.Failed(u.error)
            is WebFiles.Upload.Ok -> u
        }
        // What is handed to the page is a copy in the staging area: if the chooser took it, keep it for a while (it is only read when the page submits); if not, delete it at once
        var handed = false
        try {
            val p = when (val hit = reachable(tab, t)) {
                is Reach.Point -> hit
                is Reach.Blocked -> return hit.result
            }
            withContext(Dispatchers.Main) { tab.pendingUpload = prepared.uris }
            val r = verified(tab, t.relocatedTo) {
                click(tab, p)
                true
            }
            handed = withContext(Dispatchers.Main) { (tab.pendingUpload == null).also { tab.pendingUpload = null } }
            return uploadResult(r, handed, prepared)
        } finally {
            f.release(prepared.staged, consumed = handed)
        }
    }

    /** [x] and [y] are view pixels (for native touches); [cssX] and [cssY] are page-viewport CSS pixels (for DevTools). */
    internal sealed interface Reach {
        class Point(val x: Int, val y: Int, val cssX: Int, val cssY: Int) : Reach
        class Blocked(val result: ActionResult) : Reach
    }

    internal sealed interface Focus {
        /** [target] null means no number was given, so the current focus is used. */
        class Ok(val target: Target?) : Focus {
            val relocatedTo: String? get() = target?.relocatedTo
        }
        class Fail(val result: ActionResult) : Focus
    }

    /**
     * The wait after an action. It waits for the page to go quiet as before (up to [CAP_MS]); with DevTools, it keeps waiting only while requests issued by this action are still
     * in flight, up to [SETTLE_CAP_MS] in total (see [SettlePolicy]), and returns whether the network went idle; without DevTools it returns null.
     */
    internal suspend fun settle(tab: WebTab, net: NetworkTracker? = null): Boolean? {
        val n = net ?: NetworkTracker.of(tab, clock)
        if (n == null) {
            awaitQuiet(tab, QUIET_MS, CAP_MS)
            return null
        }
        return SettlePolicy.settle(clock, { cap -> awaitQuiet(tab, QUIET_MS, cap) }, n::actionBusy, CAP_MS, SETTLE_CAP_MS, POLL_MS)
    }

    /** Failed after DevTools input events were sent: the page may already have received them, so this action must not be redone with native input. */
    internal class InputUncertain : Exception()

    /**
     * How many view pixels one CSS pixel covers: based on the viewport width reported by the browser (in desktop mode, the emulated width, not the view width).
     * innerWidth from page scripts is not used: the page can rewrite it and steer clicks elsewhere.
     */
    internal suspend fun InputTrack.cssScale(tab: WebTab): Double = CdpGeometry.scale(viewSize(tab).first, viewportCssWidth())

    internal sealed interface Spot {
        class At(val x: Double, val y: Double, val relocatedTo: String?) : Spot
        class Fail(val result: ActionResult) : Spot
    }

    internal suspend fun url(tab: WebTab): String? = withContext(Dispatchers.Main) { tab.view.url }
    internal suspend fun title(tab: WebTab): String = withContext(Dispatchers.Main) { tab.view.title.orEmpty() }
    @Synchronized
    internal fun nextId() = ++counter

    internal companion object {
        const val TAG = "MikaWebScreen"
        /** Time limit for each DevTools input call; a stuck background page must not hold up the action for it. */
        const val INPUT_TIMEOUT_MS = 3_000L
        const val MAX_LISTED_OPTIONS = 30
        val SELECT_ALL = CdpKeys.parseCombo("Control+A")!!
        val BACKSPACE = CdpKeys.parseCombo("Backspace")!!
        const val NOT_A_SELECT = "not_a_select"
        const val SELECT_DISABLED = "select_disabled"
        const val OPTION_DISABLED = "option_disabled"
        const val NO_SUCH_OPTION = "no_such_option"
        const val SELECT_NOT_APPLIED = "select_not_applied"
        val UNCERTAIN = ActionResult.Failed("input_uncertain")
        const val KEEP_SNAPSHOTS = 4
        const val QUIET_MS = 500L
        const val CAP_MS = 3_000L
        /** Overall limit for waiting on "network idle + page quiet" after an action when DevTools is available. */
        const val SETTLE_CAP_MS = 5_000L
        const val WAIT_POLL_MS = 250L
        /** Wait for the body to stabilize before reading the page: sample every [CONTENT_POLL_MS] and treat it as stable after this many unchanged samples; with not a single character it takes longer. */
        const val CONTENT_POLL_MS = 250L
        const val CONTENT_STABLE_POLLS = 2
        const val CONTENT_EMPTY_POLLS = 6
        const val CONTENT_CAP_MS = 4_000L
        const val CONTENT_AFTER_SCROLL_CAP_MS = 2_500L
        /** How many background page-read tabs may be open at once process-wide; the rest queue. */
        const val BACKGROUND_READS = 3
        val backgroundReads = Semaphore(BACKGROUND_READS)
        const val BACKGROUND_CRASHED = "the page crashed while loading"
        const val BACKGROUND_DOWNLOAD = "the address is a file download, not a page"
        const val BACKGROUND_DIALOG = "the page opened a JavaScript dialog while loading"
        const val WAIT_TEXT_CHARS = 200_000
        /** Asking the page for a download name is one script round trip, usually tens of milliseconds; a stuck page must not hold up the action result for it. */
        const val DOWNLOAD_NAMING_CAP_MS = 1_500L
        const val POLL_MS = 100L
        const val FOCUS_QUIET_MS = 150L
        const val FOCUS_CAP_MS = 800L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 1_500L
        const val SCROLL_SWIPE_MS = 300L
        /** The idle swipe is slower so that it produces no inertia. */
        const val IDLE_SWIPE_MS = 450L
        const val SCROLL_QUIET_MS = 300L
        const val SCROLL_CAP_MS = 2_000L
        const val MIN_SCROLL_PX = 40
        const val TYPE_ATTEMPTS = 2
        const val FOCUS_TAPS = 2

        const val NO_TAB = "no_tab_open"
        const val TAB_CRASHED = "tab_crashed"
        const val NO_HISTORY = "no_history"
        const val KEY_NOT_ALLOWED = "key_not_allowed"
        const val NO_FOCUS = "no_focus"
        const val NOT_EDITABLE = "not_editable"
        const val FOCUS_MOVED = "focus_moved"
        const val HANDED_OVER = "handed_over"
        const val UPLOAD_UNAVAILABLE = "upload_unavailable"
        const val UPLOAD_NOT_OPENED = "upload_not_opened"
        const val DOWNLOADS_UNAVAILABLE = "downloads are not available yet"
        const val OBSCURED = "obscured"
        const val OFFSCREEN = "offscreen"
        const val NO_POOL = "this web port has no tab pool"
        /** Zooming back out of a pinch zoom: at most this many steps, waiting for the page to settle after each. */
        const val UNZOOM_STEPS = 12
        const val UNZOOM_SETTLE_MS = 120L
        /** "1" when the visual viewport is narrower than the layout viewport or has been panned away (the user zoomed in). */
        const val PINCH_PROBE = "(function(){var v=window.visualViewport;if(!v)return '0';" +
            "return (v.width<window.innerWidth*0.98||v.offsetLeft>1||v.offsetTop>1)?'1':'0';})()"
        const val NAVIGATE_TIMEOUT_MS = 20_000L
        /** The error code the browser reports when the system refuses cleartext http. */
        internal const val CLEARTEXT = "ERR_CLEARTEXT_NOT_PERMITTED"
        /** Prefix in [Target.ref] for elements known only to the accessibility tree; page scripts do not recognize it, so number lookups treat the element as absent. */
        const val DEVTOOLS_REF = "devtools:"
        val STALE = ActionResult.Failed("ref_expired")

        /** On the web only Enter, Backspace, Delete, Tab, arrow keys, and page keys are accepted. */
        val ALLOWED_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )

        internal val BLANK = Regex("[\\s\\u200B\\u200C\\u200D\\uFEFF]+")
        fun collapse(s: String) = s.replace(BLANK, " ").trim()
    }
}
