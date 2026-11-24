package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.screen.uistate.Surface
import com.wangzan101.mika.core.screen.observation.Look
import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.screen.uistate.sessions
import com.wangzan101.mika.core.screen.uistate.DwellStop
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.screen.uistate.PlacePace

private const val EVIDENCE_PAGE_CHARS = 3000
private val HOST_RE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

/**
 * The built-in browser: the web version of the screen tools, plus opening pages, reading pages, page scripts, downloads and uploads; the tools are offered only when a web port is attached ([port]).
 * [workspacePath] is the workspace path check used for uploads (normalization and symlink resolution are done by the app side).
 * [pacing] is the rhythm of looking page by page (see [PagePacing]): it tops up this page's dwell before actions that change the page; [sitePace] gives, per host name, what site knowledge says about how fast a site may be browsed;
 * when [idle] is given, the page is scrolled lightly a few times during the dwell (see [IdleScroll]).
 */
internal class WebCapability(
    val port: WebPort?,
    val workspacePath: ((String) -> WorkspacePathCheck)?,
    pacing: PagePacing = PagePacing.Off,
    sitePace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    override val id = WEB

    val dwell = PageDwell(pacing, sitePace, idle)

    override val folds = true

    override fun status(): CapabilityStatus = if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no browser")

    override fun tools(): List<AgentTool> = WebGroup.tools

    /** The message this time was asked on a browser page about one page (it carries the [AttachedPage] note): that page is already the current tab, so the web group is expanded from the start. */
    override fun preload(task: String): Boolean = port != null && AttachedPage.has(task)

    /** The title, URL and selected text in the page note come from the web page; what is left after removing them is what the user typed. */
    override fun userWords(text: String): String = AttachedPage.words(text)

    override val family = WEB_FAMILY

    override val surface = port?.let { p ->
        Surface(WEB_FAMILY, WebScreen(p, dwell), declaredTarget = ::declaredHost, targetNote = "the site host shown as currentSite (e.g. shop.example.com)",
            mode = { siteMode(p) })
    }

    /** Not supplied while the web group is collapsed. */
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && WebGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(WEB))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onWait = { ctx.pacedWaitMs += it }
        dwell.onIdleScroll = { ctx.idleScrolls += it }
        dwell.afterIdle = { ctx.sessions[id]?.reprobe() }
    }

    /** The user has answered: the page cap for the same site is recomputed (the user agreed to keep looking). */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** An outward action on the web: this one is not blocked by the page cap; once it succeeds, the page cap no longer applies to this site (bulk outreach and submissions go on up to the site's own limits). */
    override fun onOutwardStart(ctx: ToolContext, site: String) = dwell.outwardStart(site)

    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /** Another look: the current interface state of this page, with the beginning of the whole-page body text attached (the interface state only holds elements inside the viewport). */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? {
        val p = port ?: return null
        return ctx.sessions[id]?.freshLook(withImage) { pageStart(p)?.let { webRead(it, null) } }
    }

    /** Confirms that the declared app on the web surface is a site host name (an internationalized domain in ASCII form), matching the packageName of the web screen read. */
    private fun declaredHost(app: String): String {
        val host = app.takeIf { it.isNotEmpty() && it.none { c -> c in "/:?#@\\ " } }?.let { UrlHost.of("https://$it") }
        if (host == null || !HOST_RE.matches(host))
            throw ArgError("app", "on the web surface, must be the site host shown as currentSite, e.g. shop.example.com")
        return host
    }

    /** The beginning of the whole-page body text. */
    private suspend fun pageStart(p: WebPort): String? {
        val text = try { p.readPage(EVIDENCE_PAGE_CHARS, 0, scrollLazy = false).text } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return text?.takeIf { it.isNotBlank() }?.let { "Page text (beginning):\n$it" }
    }

    /** The version the current tab is using right now (mobile / desktop); null when it cannot be read. */
    private suspend fun siteMode(p: WebPort): String? = try { p.siteMode() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * The web version of the screen tools' snapshots and actions goes through this: the back key is the page's own back; the snapshot's package name is replaced by the site host name
     * (the confirmation gate checks by it whether what was declared is what is in front of us). On a snapshot the URL is handed to [dwell] so it can recognize a new page;
     * before actions that may change the page (click, back, key press, scroll down, swipe) this page's dwell is topped up first, while typing, clearing and scrolling up do not wait.
     * Tapping by ref, going back, pressing keys and scrolling down are not done by screen coordinates, so the page may be scrolled lightly during the dwell; tapping by coordinates and swiping do not scroll during the dwell (the coordinates would no longer match).
     * When pages from this site have reached the task's cap, all these actions except scrolling down are not performed, and page_limit is reported.
     */
    private class WebScreen(private val port: WebPort, private val dwell: PageDwell) : ScreenPort by port {
        override suspend fun back(): ActionResult = paced(idle = true) { port.historyBack() }
        override suspend fun snapshot() = port.snapshot().withSite()
        override suspend fun peek() = port.peek().withSite()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.tapRef(ref, snapshotId) }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.tapXY(x, y) }
        override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.doubleTapRef(ref, snapshotId) }
        override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.doubleTapXY(x, y) }
        override suspend fun pressKey(keyCode: Int): ActionResult = paced(idle = true) { port.pressKey(keyCode) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
            paced(idle = false) { port.swipe(x1, y1, x2, y2, durationMs) }

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            if (!down) return port.scroll(ref, snapshotId, false)
            // Scrolling down means reading the rest of this page: it happens even at the page cap; if the user interjects during the dwell, it does not happen
            try { dwell.before(port = port, gate = false) } catch (e: DwellStop) { return ActionResult.Failed(e.code, e.detail) }
            val mark = dwell.scrollMark(port)
            return port.scroll(ref, snapshotId, true).also { dwell.afterScroll(port, mark) }
        }

        /** Top up the dwell first (skipped at the page cap or when the user interjects during it, reporting page_limit or skipped_for_new_message), then perform [action]. */
        private suspend fun paced(idle: Boolean, action: suspend () -> ActionResult): ActionResult {
            try {
                dwell.before(port = port.takeIf { idle })
            } catch (e: DwellStop) {
                return ActionResult.Failed(e.code, e.detail)
            }
            return action()
        }

        private fun ScreenSnapshot.withSite() = web?.let { w ->
            dwell.seen(w.url)
            copy(packageName = UrlHost.of(w.url) ?: packageName)
        } ?: this
    }

    companion object {
        val KEY = CapabilityKey<WebCapability>(WEB)
    }
}

/** This task's web port; null when there is no web capability. */
internal val ToolContext.web: WebPort? get() = this[WebCapability.KEY]?.port

/** This task's page-by-page dwell; null when there is no web capability. */
internal val ToolContext.dwell: PageDwell? get() = this[WebCapability.KEY]?.dwell

internal val ToolContext.workspacePath: ((String) -> WorkspacePathCheck)? get() = this[WebCapability.KEY]?.workspacePath
