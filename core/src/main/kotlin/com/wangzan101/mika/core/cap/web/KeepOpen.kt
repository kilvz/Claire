package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Why the page that was kept open got closed; [wire] is recorded in the conversation. Timed out, reclaimed by the system for lack of memory, renderer process crashed, closed by the user, another page kept instead, Claire quit.
 */
enum class KeptPageClose(val wire: String) {
    Expired("expired"), Memory("memory"), Crashed("crashed"), User("user"), Replaced("replaced"), AppExit("app_exit");

    companion object {
        fun of(wire: String?): KeptPageClose? = entries.firstOrNull { it.wire == wire }
    }
}

/** A page left open after the task ends stays for at most this many hours (closed automatically when it expires). */
const val KEEP_OPEN_HOURS = 2

/**
 * When the task ends, all web pages of the task are closed by default; when a page must keep running after the answer (keep playing, stay idle, finish on its own), use this to leave the current page open.
 * This only records "leave open at the end" and touches nothing right now; only one page is kept globally, and keeping a new one closes the old one; undo with keep=false.
 */
internal object KeepOpen : WebTool {
    override val verb = Verb.KeepOpen
    override val label = "Keep page open"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_keep_open",
        Prompts.tool("web_keep_open", "hours" to KEEP_OPEN_HOURS),
        mapOf(
            "reason" to str("Why this page must keep running after the task, in a few words (e.g. the user wants the video to keep playing)."),
            "keep" to buildJsonObject { put("type", "boolean"); put("description", "false takes back an earlier keep in this task (default true).") },
        ), listOf("reason"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val reason = a.str("reason", max = 200)!!.trim()
        val keep = a.bool("keep", true)
        val r = try { ctx.web!!.keepOpen(keep, reason) } catch (e: WebTabCrashed) { return failCode("tab_crashed") }
            catch (e: WebTabIsUsers) { return failCode("users_page") }
            catch (e: UnsupportedOperationException) { return failCode("keep_open_unavailable") }
            ?: return failCode("no_tab_open")
        return ok {
            put("keep", r.keep)
            r.tabId?.let { put("tabId", it) }
            r.host?.let { put("site", it) }
            r.title?.let { put("title", it) }
            if (r.keep) {
                put("hours", r.hours)
                r.replaces?.let { put("replaces", it) }
                put("hint", if (r.replaces != null) Feedback.format("keep_open.replaces", "site" to r.replaces, "hours" to r.hours.toString())
                    else Feedback.format("keep_open.kept", "hours" to r.hours.toString()))
            } else put("hint", Feedback["keep_open.taken_back"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("reason")
}
