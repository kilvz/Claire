package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.WebInfo
import com.wangzan101.mika.core.screen.uistate.PageDialog

/** Fake web port: records calls, and results can be swapped per test. Snapshot ids are w<tab>-<n> and never collide with the phone's. */
class FakeWebPort : WebPort {
    val calls = mutableListOf<String>()
    var url = "https://shop.example.com/cart"
    var title = "购物车"
    var tabId = "t1"
    /** Site version of the current tab (the answer of siteMode). */
    var mode: String? = "desktop"
    override suspend fun siteMode(): String? = mode
    var elements: List<UiElement> = listOf(
        UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false),
        UiElement("@e2", "file", "选择文件", 540, 600, listOf(0, 200, 999, 300), "#pick", true, false, false),
    )
    var result: ActionResult = ActionResult.Ok(changed = true)
    var externalLink: ExternalLink? = null
    var crash = false
    var page = PageText(url, title, "正文", listOf("下一页" to "https://shop.example.com/2"), emptyList(), false, null)
    var eval: EvalJs = EvalJs.Ok("42", false)
    var tabList = listOf(TabInfo("t1", url, title, true), TabInfo("t2", "https://example.org/", "其他", false))
    var downloadList = listOf(DownloadInfo("/workspace/downloads/a.pdf", 1234, "application/pdf", "done", null))
    var reads = 0
    var check: ScreenCheck? = null
    /** The current tab is handed over to the user: reading the page, running scripts, opening URLs and switching tabs all report handed_over. */
    var handedOver = false
    var downloadsAvailable = true
    /** null: switching between mobile and desktop sites is unsupported (falls back to the interface default). */
    var siteModeHost: String? = null
    /** Whether DevTools is present: without it, network capture and waiting for network idle report unavailable. */
    var devtools = false
    var captured: List<CapturedResponse> = emptyList()
    var waitOutcome = WaitOutcome(true, 120, url)
    /** A dialog the page popped up and is waiting on an answer for; carried in snapshots. */
    var dialog: PageDialog? = null
    override suspend fun answerDialog(accept: Boolean, text: String?): ActionResult {
        if (dialog == null) return ActionResult.Failed(NO_DIALOG)
        calls += "dialog $accept ${text ?: "-"}"
        dialog = null
        return result
    }
    /** The answer for a gentle scroll while idling: false means scrolling is not convenient right now. */
    var idleOk = true
    /** How the page changes on a gentle scroll (tests replace elements as needed). */
    var onIdle: () -> Unit = {}
    override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle $fraction"; if (idleOk) onIdle(); return idleOk }
    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult { calls += "doubleTapRef $ref $snapshotId"; return result }

    private fun read() = ScreenSnapshot("w$tabId-${++reads}", null, elements, 1080, 2400, web = WebInfo(url, title, tabId, 200, dialog = dialog))
    override suspend fun snapshot() = read().also { calls += "snapshot" }
    // Like the real port: a peek registers no snapshot id and does not count as a page read
    override suspend fun peek() = ScreenSnapshot("w$tabId-peek", null, elements, 1080, 2400, web = WebInfo(url, title, tabId, 200, dialog = dialog))
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { calls += "tapXY $x $y"; return result }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $text"; return result }
    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult { calls += "clear $ref"; return result }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult { calls += "scroll"; return result }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { calls += "swipe"; return result }
    override suspend fun back(): ActionResult { calls += "back"; return result }
    override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return result }
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { calls += "open $packageName"; return result }
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps(): List<Pair<String, String>> = emptyList()
    override suspend fun checkScreen() = check

    override suspend fun navigate(url: String, newTab: Boolean): NavResult {
        calls += "navigate $url $newTab"
        if (crash) throw WebTabCrashed(tabId)
        if (handedOver) throw WebTabHandedOver(tabId)
        if (externalLink == null) this.url = url
        return NavResult(TabInfo(tabId, this.url, title, true), true, 200, newTab, externalLink)
    }
    override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean): PageText {
        if (handedOver) throw WebTabHandedOver(tabId)
        calls += "readPage $maxChars $offset" + if (scrollLazy) "" else " noscroll"; return page
    }
    /** Quality signal of web_read_page; when reading a URL in the background, a non-null [readUrlError] means it counts as unopenable. */
    var quality: ReadQuality? = null
    var readUrlError: String? = null
    override suspend fun read(req: PageRead): PageText {
        if (req.url == null) return readPage(req.maxChars, req.offset, req.lazy).copy(quality = quality)
        calls += "readUrl ${req.url}" + if (req.unfold) " unfold" else ""
        return page.copy(url = req.url, quality = quality, netError = readUrlError)
    }
    /** Args received by the most recent script run (the args of a site command). */
    var evalArgs: kotlinx.serialization.json.JsonObject? = null
    override suspend fun evaluate(script: String, timeoutMs: Long, args: kotlinx.serialization.json.JsonObject?): EvalJs {
        if (handedOver) throw WebTabHandedOver(tabId)
        evalArgs = args
        calls += "eval ${script.length}"; return eval
    }
    override suspend fun tabs() = tabList
    override suspend fun switchTab(tabId: String): TabInfo? {
        calls += "switch $tabId"
        if (handedOver) throw WebTabHandedOver(tabId)
        return tabList.firstOrNull { it.tabId == tabId }?.also { this.tabId = it.tabId }
    }
    override suspend fun closeTab(tabId: String): Boolean { calls += "close $tabId"; return tabList.any { it.tabId == tabId } }
    override suspend fun downloads(): List<DownloadInfo> {
        if (!downloadsAvailable) throw UnsupportedOperationException("downloads")
        return downloadList
    }
    override suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult {
        calls += "upload $ref ${paths.joinToString(",")}"; return result
    }
    override suspend fun historyBack(): ActionResult { calls += "historyBack"; return result }
    /** When pages can be kept open (the variant that supports it); [keptHost] is the site of the other page currently kept. */
    var keepAvailable = false
    var keptHost: String? = null
    var usersPage = false
    override suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? {
        if (!keepAvailable) return super.keepOpen(keep, reason)
        calls += "keepOpen $keep $reason"
        if (crash) throw WebTabCrashed(tabId)
        if (usersPage) throw WebTabIsUsers(tabId)
        return KeepOpenResult(keep, tabId, url, title, "shop.example.com", KEEP_OPEN_HOURS, replaces = keptHost)
    }

    override suspend fun setSiteMode(mobile: Boolean): SiteModeResult? {
        val host = siteModeHost ?: return super.setSiteMode(mobile)
        calls += "siteMode ${if (mobile) "mobile" else "desktop"}"
        return SiteModeResult(host, mobile, reloaded = true)
    }

    override suspend fun hover(at: WebPoint): ActionResult { calls += "hover $at"; return result }
    override suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult { calls += "drag $from $to $steps"; return result }
    override suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult { calls += "select $ref $snapshotId $option"; return result }
    override suspend fun pressCombo(combo: KeyCombo): ActionResult { calls += "combo $combo"; return result }
    override suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> {
        if (!devtools) throw DevtoolsUnavailable()
        calls += "captures $urlContains $method $sinceLastAction"; return captured
    }
    override suspend fun capture(id: String): CapturedResponse? {
        if (!devtools) throw DevtoolsUnavailable()
        calls += "capture $id"; return captured.firstOrNull { it.id == id }
    }
    override suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome {
        calls += "wait ${kind.wire} $value $timeoutMs"
        if (kind == WaitKind.NETWORK_IDLE && !devtools) throw DevtoolsUnavailable()
        return waitOutcome
    }
}
