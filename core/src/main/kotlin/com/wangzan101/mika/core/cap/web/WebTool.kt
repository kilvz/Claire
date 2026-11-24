package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.screen.uistate.tools.ScreenTool
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** Web-only tools: offered only when a web port is attached. */
internal interface WebTool : AgentTool {
    override val capability: String get() = WEB
    override fun available(ctx: ToolContext) = ctx.web != null
}

/** The id of the web capability, and also the prefix of the web tool names. */
const val WEB = "web"

/** On a web page press_key accepts only these keys: Enter, Backspace, Delete, Tab, arrow keys and page keys. */
private val KEYS = setOf(66, 160, 67, 112, 61, 19, 20, 21, 22, 92, 93)
private const val KEYS_HINT = "on a web page only Enter, Backspace, Delete, Tab, arrow and page keys are allowed"

/** The web version of the screen tools: snapshot refs start with w, the "where" question reports the site, and the experience trail does not record how things are done on the web. */
internal val WEB_FAMILY = ScreenFamily(
    WEB, snapshotPrefix = "w", placeKey = ScreenFamily.SITE_PLACE, keys = KEYS, keysHint = KEYS_HINT,
    ownPrompts = setOf("observe", "back"), emptyHint = "empty_page",
)

/** The web group: those screen tools plus the web-only navigation, page reading, tabs, downloads and uploads, scripts, waiting and keeping the page after the task. In the order they are sent to the model. */
internal object WebGroup {
    val tools: List<AgentTool> = ScreenTool.of(WEB_FAMILY) + listOf(
        Navigate, ReadPage, EvaluateJs, RunSiteCommand, Tabs, Downloads, Upload, SiteMode, WebScriptTool, Interact, Capture, WebWaitFor, KeepOpen,
    )
}
