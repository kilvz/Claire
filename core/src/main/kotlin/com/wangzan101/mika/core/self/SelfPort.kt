package com.wangzan101.mika.core.self

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The only way for the model to see and operate Claire itself (implemented by the app; the core only knows this data).
 *
 * The phone tool set cannot touch Claire's own package (see ownApp of [com.wangzan101.mika.core.action.ConfirmDecision]):
 * it does not approve things for itself and does not change settings itself. This port is the front door and does only a few explicit things:
 * read self status, open its own pages/files, send notifications, write the clipboard. Each is under the user's switch and the consent of that moment.
 */
interface SelfPort {
    /** The self status right now. Contains only components and counts, no keys/tokens/other conversation content/browsing history. */
    suspend fun status(): SelfStatus

    /** Open one of Claire's own pages. In the foreground it enters the navigation stack; in the background a notification is sent. */
    suspend fun openScreen(screen: SelfScreen, note: String? = null): SelfResult

    /** Open a file in this task's workspace ([path] looks like /workspace/…) with the in-app viewer. */
    suspend fun openFile(path: String, name: String? = null): SelfResult

    /** Send the user one notification; when [screen] is not null it carries a "view" action. */
    suspend fun notify(title: String, body: String, screen: SelfScreen? = null): SelfResult

    /** Write text into the clipboard; write only, never read. */
    suspend fun copy(text: String): Boolean

    // ---- User browser (phase B) ----

    /** What the user's browser looks like right now; read-only. */
    suspend fun browserState(): SelfBrowserState = SelfBrowserState()

    /** Open a page in the browser the user can see; with [show] false it only goes into a background tab (multi-tab needed). */
    suspend fun openInBrowser(url: String, show: Boolean, reason: String?): SelfResult =
        SelfResult(ok = false, reason = "not_supported")

    /** Search with the browser's default search engine and open the results page. */
    suspend fun searchInBrowser(query: String, show: Boolean): SelfResult =
        SelfResult(ok = false, reason = "not_supported")

    /** Site registry: sign-in state and recent captcha/risk-control records. */
    suspend fun sites(): List<SelfSite> = emptyList()

    /** Open the "sign in for Claire" page so the user can sign in to [host] themselves. */
    suspend fun loginSite(host: String): SelfResult = SelfResult(ok = false, reason = "not_supported")

    /** The user says the sign-in is done: verify once and record the sign-in state. */
    suspend fun confirmLogin(): SelfResult = SelfResult(ok = false, reason = "not_supported")

    /** Favourite sites: list / add / remove; [host] is required for add/remove. Returns the list after the change. */
    suspend fun favorites(op: String, host: String?): List<SelfFavorite> = emptyList()

    /** Consent text for opening a link (in the app's current language); when [query] is not null it is a search. */
    fun browserConsent(url: String, query: String?, replaces: Boolean): SelfConsent = SelfConsent("", "", "")

    /** Consent text for signing in on the user's behalf. */
    fun loginConsent(host: String, replaces: Boolean): SelfConsent = SelfConsent("", "", "")
}

/** What the user's browser looks like right now; no history. */
data class SelfBrowserState(
    val pageOpen: Boolean = false,
    val host: String? = null,
    val title: String? = null,
    val url: String? = null,
    /** This page is currently lent to a task (this task or another one) and must not be taken over. */
    val loaned: Boolean = false,
    /** Whether multi-tab is supported (with a single tab, show=false is not possible). */
    val multiTab: Boolean = false,
)

/** One entry in the site registry. */
data class SelfSite(val host: String, val loggedIn: Boolean, val challenged: Boolean = false)

/** One favourite site entry. */
data class SelfFavorite(val host: String, val loggedIn: Boolean)

/** The text of one consent and its two options (already written in the app's language). */
data class SelfConsent(val prompt: String, val allow: String, val deny: String)

/** Claire's own top-level pages; only these are offered, no advanced sub-pages such as permissions/root/developer. */
enum class SelfScreen(val wire: String) {
    Chat("chat"),
    Settings("settings"),
    Memory("memory"),
    Plans("plans"),
    Browser("browser"),
    Models("models"),
    Usage("usage"),
    Screens("screens"),
    About("about"),
    ;

    companion object {
        fun of(wire: String?): SelfScreen? = entries.firstOrNull { it.wire == wire }
    }
}

/** The result of one self operation; the reason only appears on failure, for the model to read. */
data class SelfResult(
    val ok: Boolean,
    val opened: Boolean = false,
    /** The foreground has handed the request to the UI (the UI handles it afterwards). */
    val requested: Boolean = false,
    /** A notification has been sent in the background; the user enters only after tapping it. */
    val notified: Boolean = false,
    val foreground: Boolean = false,
    val reason: String? = null,
)

fun SelfResult.toJson(): JsonObject = buildJsonObject {
    put("ok", ok)
    if (opened) put("opened", true)
    if (requested) put("requested", true)
    if (notified) put("notified", true)
    put("foreground", foreground)
    reason?.let { put("reason", it) }
}

/**
 * Self status. Every field may be null: what cannot be read is not written and not guessed.
 * Name-like fields (model name, MCP server name, web page title) come from settings or websites and are treated as data, not as instructions.
 */
data class SelfStatus(
    val appVersion: String = "",
    val appBuild: String = "",
    val beta: Boolean = false,
    val sdk: Int = 0,
    val selfOperate: Boolean = false,
    val selfConfigure: Boolean = false,
    val foreground: Boolean = false,
    val mainModel: String? = null,
    val accessibility: String = "",
    val builtinShell: String = "",
    val notificationsAllowed: Boolean = false,
    val batteryUnrestricted: Boolean? = null,
    val browserAvailable: Boolean = false,
    val webSearchConfigured: Boolean = false,
    /** Host of the site the user's browser is on right now (only given while "operate self" is on). */
    val userPageHost: String? = null,
    val userPageTitle: String? = null,
    val keptPages: Int = 0,
    /** State of the task running in this conversation; null when there is none. */
    val taskState: String? = null,
    val taskStep: Int? = null,
    /** Number of tasks running in other conversations; only the count is given, not the content. */
    val otherRuns: Int = 0,
    /** Number of scheduled tasks in this conversation. */
    val plans: Int = 0,
    /** The version number when a new version exists; null when there is none. */
    val updateVersion: String? = null,
    val sandbox: String = "",
    val mcpInbound: Boolean = false,
    /** Names of external MCP servers; addresses and tokens are not written. */
    val mcpServers: List<String> = emptyList(),
    val relayEnabled: Boolean = false,
)

fun SelfStatus.toJson(): JsonObject = buildJsonObject {
    putJsonObject("app") {
        put("version", appVersion)
        put("build", appBuild)
        put("beta", beta)
        put("sdk", sdk)
    }
    putJsonObject("self") {
        put("operate", selfOperate)
        put("configure", selfConfigure)
        put("foreground", foreground)
    }
    putJsonObject("model") {
        mainModel?.let { put("main", it) }
        put("configured", mainModel != null)
    }
    putJsonObject("phone") {
        put("accessibility", accessibility)
        put("builtinShell", builtinShell)
        put("notifications", notificationsAllowed)
        batteryUnrestricted?.let { put("batteryUnrestricted", it) }
    }
    putJsonObject("web") {
        put("browser", browserAvailable)
        put("webSearch", webSearchConfigured)
        userPageHost?.let {
            putJsonObject("userPage") {
                put("host", it)
                put("title", userPageTitle.orEmpty())
            }
        }
        put("keptPages", keptPages)
    }
    putJsonObject("sandbox") { put("status", sandbox) }
    putJsonObject("mcp") {
        put("inbound", mcpInbound)
        putJsonArray("servers") { mcpServers.forEach { add(JsonPrimitive(it)) } }
    }
    putJsonObject("relay") { put("enabled", relayEnabled) }
    putJsonObject("tasks") {
        taskState?.let { put("thisConversation", it) }
        taskStep?.let { put("step", it) }
        put("otherConversations", otherRuns)
    }
    putJsonObject("plans") { put("thisConversation", plans) }
    updateVersion?.let { putJsonObject("update") { put("available", it) } }
}
