package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.screen.uistate.PageDialog

data class TabInfo(val tabId: String, val url: String, val title: String, val current: Boolean)

/**
 * [externalLink]: the page wanted to jump to an app (intent:, custom scheme, app store) and did not jump.
 * [settled]: with DevTools, true when network idle plus a quiet page were reached after loading, false when the cap was reached without it ever going quiet; null without DevTools.
 * [netError]: the error code the browser gave when the main document did not open (e.g. net::ERR_NAME_NOT_RESOLVED); null when it opened.
 * [upgradedFrom]: the original http URL when plain http was refused and https was retried instead.
 * [download]: the URL was really a file, so opening it turned into a download; its download record.
 */
data class NavResult(
    val tab: TabInfo,
    val loaded: Boolean,
    val httpStatus: Int?,
    val newTab: Boolean,
    val externalLink: ExternalLink?,
    val settled: Boolean? = null,
    val netError: String? = null,
    val upgradedFrom: String? = null,
    val download: DownloadInfo? = null,
    /** A dialog popped up while loading and is still awaiting a reply (the load wait stopped as soon as it appeared). */
    val dialog: PageDialog? = null,
)

/**
 * One segment of the whole-page body text; [links] and [images] are (text or description, URL); [nextOffset] is the start of the next segment, null once reading is complete.
 * [quality] holds the read's quality signals (present when reading according to [PageRead]); [netError] is the error code the browser gave when opening the URL in a background tab failed.
 */
data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val links: List<Pair<String, String>>,
    val images: List<Pair<String, String>>,
    val truncated: Boolean,
    val nextOffset: Int?,
    val quality: ReadQuality? = null,
    val netError: String? = null,
)

/** [file] is the sandbox path /workspace/downloads/<name>; [status] is done|failed|running. */
data class DownloadInfo(val file: String, val bytes: Long, val mime: String?, val status: String, val error: String?)

/**
 * Result of web_keep_open: when [keep] is true this page ([tabId], [url], [title], site [host]) stays running in the background after the task ends,
 * for at most [hours] hours; [replaces] is the site of the other page kept right now (it is closed when the task ends), null when there is none. When [keep] is false, the keep in this task was taken back.
 */
data class KeepOpenResult(
    val keep: Boolean,
    val tabId: String?,
    val url: String?,
    val title: String?,
    val host: String?,
    val hours: Int,
    val replaces: String? = null,
)

/** The current tab is the page the user is looking at in Claire's own browser (a borrowed one): it does not close with the task anyway, so there is no need to keep it. */
class WebTabIsUsers(val tabId: String) : Exception("tab $tabId is the user's own page")

/** Result of web_site_mode: [host] is the current page's site, [mobile] whether the mobile version is in use now, [reloaded] whether the current page was reloaded with the new version. */
data class SiteModeResult(val host: String, val mobile: Boolean, val reloaded: Boolean)

sealed interface EvalJs {
    /** [json] is the redacted and truncated result; [fresh]: this time the tab's read world was created anew, so whatever was stored in state before is gone. */
    data class Ok(val json: String, val truncated: Boolean, val fresh: Boolean = false) : EvalJs

    /**
     * [error] is script_error|timeout|read_only (the script tried to change the page)|invalid_selector|blocked_network|blocked_navigation|
     * limit_exceeded|network_error|too_large|navigated (the page switched documents)|devtools_unavailable|no_tab_open.
     */
    data class Failed(val error: String, val message: String) : EvalJs
}

/** A web_wait_for condition; [wire] is the spelling used in the tool arguments. */
enum class WaitKind(val wire: String) {
    TEXT_PRESENT("text_present"), TEXT_ABSENT("text_absent"), NETWORK_IDLE("network_idle"), URL_CONTAINS("url_contains");

    companion object {
        fun of(wire: String): WaitKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** [met]: the condition held within the time limit; [url] is the address at the end. */
data class WaitOutcome(val met: Boolean, val waitedMs: Long, val url: String?)

/** The current tab has no DevTools session (the system does not support it, or it cannot be connected): tools that need network records give the result devtools_unavailable. */
class DevtoolsUnavailable : Exception("devtools unavailable")

/** A web tab: the same interfaces used for screen reading and actions on the phone, plus web-only navigation, page reading, tabs, downloads and uploads. */
interface WebPort : ScreenPort {
    suspend fun navigate(url: String, newTab: Boolean): NavResult
    /** [scrollLazy]: scroll through once before reading to trigger lazily loaded content (this does touch the page); false when gathering evidence for a check, reading without scrolling. */
    suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean = true): PageText

    /**
     * The web_read_page way of reading: with quality signals and folded list blocks; when [PageRead.url] is given, the page is opened in a temporary background tab and closed once read, without touching the current tab.
     * When background reads are unsupported and a url is given, throws [UnsupportedOperationException].
     */
    suspend fun read(req: PageRead): PageText =
        if (req.url == null) readPage(req.maxChars, req.offset, req.lazy) else throw UnsupportedOperationException("background reads are not available")
    /**
     * Read script: runs [script] read-only in the read world of the current tab's real page (see [ReadScriptProtocol]), with [timeoutMs] as the limit;
     * [args] are the arguments of a site command (args inside the script). Calls on the same tab and document share state.
     * Reports devtools_unavailable when DevTools cannot be used, with no fallback to another approach.
     */
    suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject? = null): EvalJs
    suspend fun tabs(): List<TabInfo>
    suspend fun switchTab(tabId: String): TabInfo?
    suspend fun closeTab(tabId: String): Boolean
    /** Throws [UnsupportedOperationException] while downloads are not wired up yet; the tool result is downloads_unavailable. */
    suspend fun downloads(): List<DownloadInfo>
    suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult
    suspend fun historyBack(): ActionResult
    /**
     * Switch the current tab's site to the mobile or the desktop web version (desktop by default), remembered per site, and reload the current page.
     * Null when no page is open; throws [UnsupportedOperationException] when unsupported.
     */
    suspend fun setSiteMode(mobile: Boolean): SiteModeResult? = throw UnsupportedOperationException("site mode is not available")

    /**
     * Keep the current tab running after the task ends ([keep] false takes back the keep in this task). By default all tabs are closed when the task ends; the kept page stays for at most
     * [KeepOpenResult.hours] hours, and only one page is kept globally. Null when no page is open; throws [WebTabIsUsers] when the current tab is the user's own page;
     * throws [UnsupportedOperationException] when unsupported.
     */
    suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? = throw UnsupportedOperationException("keeping a page open is not available")

    /** The version the current tab is using right now: `mobile` or `desktop`; null when no page is open or it is unknown. */
    suspend fun siteMode(): String? = null

    /**
     * Runs [code] (an async function body) in an isolated world of the current tab's real page, with `mika.*` calls going back to Claire through [bridge]; the limit is [timeoutMs].
     * Reports devtools_unavailable when DevTools cannot be used, with no fallback to another approach.
     */
    suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun =
        ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "Page scripts need the page's DevTools connection, which is not available here.")

    // web_interact: actions that need a DevTools session; when it cannot be connected they return devtools_unavailable instead of pretending the action happened
    suspend fun hover(at: WebPoint): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** Select one item in a native dropdown; [option] is the option's displayed text or value. */
    suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** Answer the page dialog awaiting a reply on the current tab (see [PageDialogs]); [text] is given to prompt dialogs only. Reports no_dialog when there is none. */
    suspend fun answerDialog(accept: Boolean, text: String?): ActionResult = ActionResult.Failed(NO_DIALOG)
    /** Press a key combination once on the current focus. */
    suspend fun pressCombo(combo: KeyCombo): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** The XHR/fetch responses recorded for the current tab (without bodies), oldest first. Throws [DevtoolsUnavailable] without DevTools. */
    suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> = throw DevtoolsUnavailable()

    /** Fetch one response, with its body when one can be fetched (JSON/text, not too large); null when it is not in the records. Throws [DevtoolsUnavailable] without DevTools. */
    suspend fun capture(id: String): CapturedResponse? = throw DevtoolsUnavailable()

    /** Wait up to [timeoutMs] for [kind] to hold. Conditions that look at the network throw [DevtoolsUnavailable] without DevTools. */
    suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome = throw DevtoolsUnavailable()

}

/** Where web_interact lands: a screen-read ref, or view pixel coordinates. */
sealed interface WebPoint {
    data class Ref(val ref: String, val snapshotId: String) : WebPoint
    data class Xy(val x: Int, val y: Int) : WebPoint
}

const val DEVTOOLS_UNAVAILABLE = "devtools_unavailable"
const val NO_DIALOG = "no_dialog"
/** The page has a dialog awaiting a reply: no other action happens until it has been answered. */
const val DIALOG_OPEN = "dialog_open"

/** The current tab is with the user (handover in progress); methods that do not return an ActionResult report it this way, and the tool result is handed_over. */
class WebTabHandedOver(val tabId: String) : Exception("tab $tabId is with the user")

/** A page dialog is pending on the current tab (the page is paused): methods that read the page or open URLs report it this way, and the tool result is dialog_open. */
class WebDialogOpen(val tabId: String) : Exception("tab $tabId has a page dialog open")

/** The tab's renderer process crashed (or was reclaimed by the system) and the tab is gone; methods that do not return an ActionResult report it this way, and the tool result is tab_crashed. */
class WebTabCrashed(val tabId: String) : Exception("tab $tabId crashed")
