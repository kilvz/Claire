package com.wangzan101.mika.core.screen.uistate.tools

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.uistate.PressKeys
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.sessions

/**
 * One tool in a set of screen tools: the name is `<screen>_<verb>`, and it only acts on the screen in [family] (the dispatcher records it before the call).
 * The description uses the shared template prompts/tools/<verb>.md (when this screen has its own copy, <screen>_<verb>.md), and every tool named in the template is replaced with the name from this set.
 */
internal abstract class ScreenTool(val family: ScreenFamily, val base: String) : AgentTool {
    final override val name: String = family.tool(base)
    override val capability: String get() = family.id
    /** In the phone set, text-result tools can be called from the sandbox; image-viewing ones (image results, requiring the vision model) are not exposed. */
    override val sandboxCallable: Boolean get() = family.sandboxCallable && verb !in IMAGE_VERBS
    override val promptFile: String get() = if (base in family.ownPrompts) name else base

    /** Available only when a port is attached to this screen; [usable] is each tool's additional condition. */
    final override fun available(ctx: ToolContext): Boolean = family.id in ctx.sessions && usable(ctx)

    protected open fun usable(ctx: ToolContext): Boolean = true

    /** The full description: {{verb}} placeholders in the template become the tool names in this set, and {{keys}} is the keys this screen can send. */
    protected fun describe(vararg vars: Pair<String, Any?>): String =
        Prompts.tool(promptFile, *(MIRRORED.map { it to family.tool(it) } + ("keys" to keyNames(family)) + vars).toTypedArray())

    override fun toString() = name

    companion object {
        private val IMAGE_VERBS = setOf(Verb.Screenshot, Verb.Describe, Verb.FindVisually)

        /** The verbs every screen has, in the order sent to the model. */
        val MIRRORED = listOf(
            "observe", "list_elements", "screenshot", "describe", "find_visually",
            "tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose",
        )

        /** The key names press_key can send on a screen. */
        fun keyNames(f: ScreenFamily): String =
            PressKeys.BY_NAME.filterValues { f.keys == null || it in f.keys }.keys.joinToString()

        /** The screen tools of one screen, in the order of [MIRRORED]. */
        fun of(f: ScreenFamily): List<AgentTool> = listOf(
            Observe(f), ListElements(f), Screenshot(f), Describe(f), FindVisually(f),
            Tap(f), Scroll(f), Swipe(f), TypeText(f), ClearText(f), Back(f), PressKey(f), Diagnose(f),
        ).also { tools -> check(tools.map { it.base } == MIRRORED) }
    }
}
