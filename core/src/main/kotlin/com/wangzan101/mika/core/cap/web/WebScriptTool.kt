package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.uistate.lastSnapshot

/**
 * Page script: it does not touch the page itself, and every action in the script comes back through the execution entry (guards, gates, irreversibility checks and logging as usual);
 * it does not go through the lock-screen gate, and it cannot be used as a read-only call.
 */
internal object WebScriptTool : WebTool {
    override val verb = Verb.Script
    override val risk = Risk.Write
    override val composite = true
    override val def = ToolDef("web_script",
        Prompts.tool("web_script", "max_actions" to SCRIPT_MAX_ACTIONS, "max_reads" to SCRIPT_MAX_READS, "max_seconds" to SCRIPT_MAX_TIMEOUT_MS / 1000),
        mapOf(
            "code" to buildJsonObject {
                put("type", "string"); put("minLength", 1); put("maxLength", SCRIPT_CODE_MAX_CHARS)
                put("description", "Body of an async function, e.g. `const s = await mika.snapshot(); return s.elements.filter(e => e.role === 'link').map(e => e.name);`")
            },
            "timeoutMs" to buildJsonObject {
                put("type", "integer"); put("minimum", 1000); put("maximum", SCRIPT_MAX_TIMEOUT_MS)
                put("description", "Time limit for the whole script, 1000-$SCRIPT_MAX_TIMEOUT_MS ms (default $SCRIPT_DEFAULT_TIMEOUT_MS).")
            },
        ), listOf("code"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val code = a.str("code", max = SCRIPT_CODE_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(code)?.let { throw ArgError("code", it) }
        val timeoutMs = (a.int("timeoutMs", 1000, SCRIPT_MAX_TIMEOUT_MS) ?: SCRIPT_DEFAULT_TIMEOUT_MS).toLong()
        val calls = ScriptCalls(ctx.callId, ctx.sub, ctx.tools)
        val run = try {
            // The time limit is kept by the port: only the script's own time counts, the clock does not run while an action (including waiting at the lock-screen gate) is in progress, and it is never cut off halfway
            ctx.web!!.runScript(code, timeoutMs, calls)
        } catch (e: WebTabCrashed) {
            ScriptRun.Failed("tab_crashed", FAILURE_MESSAGES.getValue("tab_crashed"))
        } catch (e: WebTabHandedOver) {
            ScriptRun.Failed("handed_over", FAILURE_MESSAGES.getValue("handed_over"))
        } catch (e: WebDialogOpen) {
            ScriptRun.Failed(DIALOG_OPEN, FAILURE_MESSAGES.getValue(DIALOG_OPEN))
        } catch (e: ScriptAbort) {
            ScriptRun.Failed(e.error, e.message.orEmpty())
        }
        val content = calls.content(run)
        val result = (content["result"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
        val seen = result?.let { webRead("页面脚本的结果(${ctx.lastSnapshot?.web?.url ?: "当前网页"})\n$it", ctx.lastSnapshot?.web?.url) }
        // The script's result comes from a web page: it is wrapped in an untrusted marker when handed to the model
        val shown = if (result == null) content else JsonObject(content +
            ("result" to JsonPrimitive(Untrusted.wrapWithin(result, EVAL_RESULT_MAX_CHARS))) +
            ("truncated" to JsonPrimitive(content["truncated"] == JsonPrimitive(true) || result.length > EVAL_RESULT_MAX_CHARS - Untrusted.OVERHEAD)))
        return ToolExecution(shown).observing(seen)
    }
}
