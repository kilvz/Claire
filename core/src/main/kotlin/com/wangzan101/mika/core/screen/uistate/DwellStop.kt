package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The pages viewed on this site, or the screens changed in this app, within this task have reached the cap: no more page or screen changes (see [PageLimitReached]). */
const val PAGE_LIMIT = "page_limit"

/** The pause before a page or screen change kept this action from being done: the caller skips it and reports by [code] (see [dwellStopped]). Shared by the web ([com.wangzan101.mika.core.cap.web.PageDwell]) and apps (the phone capability's pause). */
internal sealed class DwellStop(message: String) : Exception(message) {
    abstract val code: String
    /** Details appended after the explanation; null when there are none. */
    open val detail: String? get() = null
}

/** The pages from this site, or the screens this app changed, within this task have reached the cap that limits browsing speed: no more page or screen changes; answer or ask the user first. [site] is the site host name or the app package name. */
internal class PageLimitReached(val site: String, val pages: Int, val cap: Int, val scope: Scope) :
    DwellStop("Claire reached its per-task ${scope.label} budget for $site ($pages/$cap).") {
    enum class Scope(val wireName: String, val label: String) {
        APP_SCREENS("app_screens", "app screen"),
        WEB_PAGES("web_pages", "web page"),
    }

    override val code get() = PAGE_LIMIT
    override val detail get() = message
}

/** The user spoke up during the pause: skip this one and read that message first. */
internal class DwellInterrupted : DwellStop("The pause before this action was cut short by a new message from the user.") {
    override val code get() = SKIPPED_FOR_NEW_MESSAGE
}

/** The tool result for not doing this action: explains why it stopped and what to do next (at the cap it includes how many pages of this site or app were viewed). */
internal fun dwellStopped(e: DwellStop): ToolExecution {
    val failure = fail(e.code, listOfNotNull(FAILURE_MESSAGES.getValue(e.code), e.detail).joinToString(" "))
    if (e !is PageLimitReached) return failure
    val budget = buildJsonObject {
        put("scope", e.scope.wireName)
        put("target", e.site)
        put("used", e.pages)
        put("limit", e.cap)
    }
    return failure.with(content = JsonObject(failure.content + ("navigationBudget" to budget)))
}
