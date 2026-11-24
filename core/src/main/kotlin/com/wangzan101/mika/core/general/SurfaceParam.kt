package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.screen.uistate.focus

private const val SURFACE = "surface"

/**
 * Shared by tools that say which screen they mean (confirm, take over, report an obstacle): when this task has more than one screen, an extra surface parameter is added,
 * and when it is omitted, the screen the latest screen tool acted on. Before the call, the dispatcher records from it which screen is being operated on, and the tool still uses that screen as of that moment.
 */
internal interface SurfaceAware : AgentTool {
    override fun def(ctx: ToolContext): ToolDef {
        val ids = ctx.focus.surfaces.map { it.id }
        if (ids.size < 2) return def
        return ToolDef(def.name, def.description, def.props + (SURFACE to str(SURFACE_NOTE, ids)), def.required)
    }

    override fun aimAt(o: JsonObject): String? = o.argStr(SURFACE)

    /** When surface is given, check that it is one of this task's screens. */
    fun checkSurface(a: Args, ctx: ToolContext) {
        val id = a.str(SURFACE) ?: return
        if (ctx.focus[id] == null) throw ArgError(SURFACE, "must be one of ${ctx.focus.surfaces.joinToString("|") { it.id }}")
    }

    companion object {
        const val SURFACE_NOTE = "Which screen this is about. Default: the screen your latest screen tool acted on."
    }
}
