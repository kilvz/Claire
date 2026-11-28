package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.action
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shares a workspace file to an app (ACTION_SEND + EXTRA_STREAM), opening its share sheet on Claire's screen:
 * it only opens, it never sends; the model picks the recipient and taps "send" on that page with screen tools, and sending still goes through the existing confirmation gate.
 * The audio, image and video tools differ only in the file kind, the wording and the one call on the screen.
 *
 * @param kind the kind word (audio / image / video), spliced into the argument descriptions and errors
 * @param pathExample example path in the path argument description
 */
internal abstract class ShareFileTool(
    toolName: String,
    private val kind: String,
    pathExample: String,
) : PhoneTool {
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef(toolName, Prompts.tool(toolName),
        mapOf(
            "package" to str("Exact package name of the app to share the $kind to, e.g. com.example.app."),
            "path" to str("Workspace $kind path, e.g. $pathExample."),
            "text" to str("Optional caption to share with the $kind, at most 2000 characters."),
        ),
        listOf("package", "path"))

    /** The step that actually opens the share page on the screen. */
    protected abstract suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?): ActionResult

    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace $kind path under $prefix")
        }
        val text = a.str("text", max = 2000)
        // A different app was chosen, so a previously refused payment declaration no longer belongs to this run
        ctx.refusedPay = null
        val r = ctx.action(share(ctx, p, path, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return r
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["package"] as? JsonPrimitive)?.content

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((o["package"] as? JsonPrimitive)?.content?.let { p ->
            Observation(ObsKind.Fact, "$name opened the $kind share page of $p (not sent yet)", source = PHONE)
        })
    }
}
