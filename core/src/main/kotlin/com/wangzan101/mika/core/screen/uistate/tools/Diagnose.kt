package com.wangzan101.mika.core.screen.uistate.tools

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenState
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.tool.HINT_UNSUPPORTED_BROWSER
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.ok
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.uistate.screen
import com.wangzan101.mika.core.screen.uistate.session

/** Diagnose a blank, loading, locked, or protected screen; passes the lock-screen gate (nothing can be diagnosed while locked). */
internal class Diagnose(f: ScreenFamily) : ScreenTool(f, "diagnose") {
    override val verb = Verb.Diagnose
    override val label = "Inspect screen"
    override val risk = Risk.Write
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val place = family.placeKey
        ctx.screen.checkScreen()?.let { c ->
            return ok {
                put(place, c.currentApp)
                put("state", c.state.wire)
                put("usable", c.state == ScreenState.NORMAL)
                put("needsHuman", c.needsHuman)
                c.takeoverReason?.let { put("takeoverReason", it) }
                c.cause?.let { put("cause", it) }
                when (c.state) {
                    ScreenState.NORMAL -> if (c.takeoverReason == ScreenDiagnosis.UNSUPPORTED_BROWSER) put("hint", HINT_UNSUPPORTED_BROWSER)
                    ScreenState.BLANK -> put("hint", when {
                        c.currentApp == null && c.cause == ScreenDiagnosis.NO_WINDOW -> Feedback[family.emptyHint]
                        // The foreground App is there, only the window is unreadable: this is not "not opened", so do not start it again
                        c.currentApp != null && c.cause == ScreenDiagnosis.UNREADABLE_WINDOW -> Feedback.format("unreadable_window", "app" to c.currentApp)
                        else -> Feedback["screen.blank"]
                    })
                    ScreenState.LOADING -> put("hint", Feedback["screen.loading"])
                    ScreenState.PROTECTED -> put("hint", HINT_PROTECTED)
                    ScreenState.CAPTURE_UNAVAILABLE -> put("hint", HINT_DISPLAY_CAPTURE)
                    // Access restricted: besides handing over to the user, do not retry immediately
                    ScreenState.NEEDS_HUMAN -> put("hint", if (c.takeoverReason == "access_blocked") Feedback["barrier.access_blocked"]
                        else Feedback.format("screen.needs_human", "reason" to c.takeoverReason))
                }
            }
        }
        val s = ctx.session.snapshot()
        val usable = s.elements.any { it.meaningful }
        return ok {
            put(place, s.packageName ?: s.unreadableApp)
            put("usable", usable)
            put("needsHuman", false)
            if (!usable) put("hint", s.unreadableApp?.let { Feedback.format("unreadable_window", "app" to it) } ?: Feedback["screen.no_elements"])
        }
    }
}
