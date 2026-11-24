package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.action
import com.wangzan101.mika.core.screen.uistate.session

/** List and open Apps on the phone: always acts on the phone. */
internal object ListApps : PhoneTool {
    override val verb = Verb.ListApps
    override val label = "查看已装的 App"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("phone_list_apps", Prompts.tool("phone_list_apps"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val apps = ctx.phone.screen.listApps()
        // Also installed in another user such as a clone or work profile: list each copy as its own entry, carrying the user needed to open it
        val copies = ctx.phone.screen.appCopies().groupBy { it.packageName }
        val access = ctx.phone.screen.appAccess.current()
        return ok {
            put("apps", JsonArray(apps.flatMap { (p, l) ->
                listOf(buildJsonObject {
                    put("package", p); put("label", l)
                    // false is the default state, so it is not repeated for every App; this result stays in the history of every later step.
                    if (p in access.blocked) put("blocked", true)
                    if (p in access.shareAllowed) put("screenShareAllowed", true)
                }) +
                    copies[p].orEmpty().map { c -> buildJsonObject { put("package", p); put("label", c.label(l)); put("user", c.userId) } }
            }))
        }
    }

    /** Listed App names are not evidence on their own; they are recorded for the fact of opening an App later (names from another copy are not recorded, so they do not overwrite the original one's). */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        (r.content["apps"] as? JsonArray)?.forEach { a ->
            val o2 = a as? JsonObject ?: return@forEach
            if ("user" in o2) return@forEach
            val p = (o2["package"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (o2["label"] as? JsonPrimitive)?.contentOrNull?.let { log.appLabels[p] = it }
        }
        return emptyList()
    }
}

internal object LaunchApp : PhoneTool {
    override val verb = Verb.LaunchApp
    override val batchable = true
    override val routable = true
    override val label = "打开 App"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_launch_app", Prompts.tool("phone_launch_app"),
        mapOf(
            "package" to str("Exact package name, e.g. com.example.app."),
            "cold" to bool("Restart the app first (default false)."),
            "user" to int("Only to open another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
            "wait" to int("Only when another task is using this app: seconds to wait for it to be free, then open it (at most $MAX_WAIT_S). Leave out to get the in-use result at once.", min = 1, max = MAX_WAIT_S),
        ),
        listOf("package"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val user = a.int("user", 0, MAX_USER)
        val cold = a.bool("cold", false)
        val wait = a.int("wait", 1, MAX_WAIT_S)
        // Only open the copies that were listed: anything not in an available other user (private space, someone else's user, not installed) is left alone
        if (user != null && ctx.phone.screen.appCopies().none { it.packageName == p && it.userId == user }) {
            return ctx.action(ActionResult.Failed(COPY_NOT_FOUND))
        }
        // Switched Apps, so a payment declaration refused earlier no longer applies to this one
        ctx.refusedPay = null
        suspend fun open() = if (user != null) ctx.phone.screen.openAppCopy(p, user, cold) else ctx.phone.screen.openApp(p, cold)
        var opened = open()
        // Another task is using it and the model asked to wait: wait for it to be released, then try opening again; when tasks wait on each other (nobody can get it) or it is still occupied at the deadline, say so plainly
        if (wait != null && opened is ActionResult.Failed && opened.reason == APP_IN_USE_BY_OTHER_TASK) {
            when (ctx.phone.screen.awaitAppFree(p, user, wait * 1_000L)) {
                AppWait.Free -> opened = open()
                AppWait.WouldDeadlock -> return failCode(WAIT_WOULD_DEADLOCK)
                AppWait.TimedOut -> return failCode(WAIT_TIMED_OUT)
                AppWait.Unsupported -> Unit
            }
        }
        val r = ctx.action(opened)
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return unreadable(ctx, p, r)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        o.argStr("package")?.let { p -> (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "$p (user $it)" } ?: p }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            val copy = (o["user"] as? JsonPrimitive)?.contentOrNull?.let { " (other copy, user $it)" }.orEmpty()
            Observation(ObsKind.Fact, "$name opened $p$copy" + (log.appLabels[p]?.let { " (app name \"$it\")" } ?: ""), source = PHONE)
        })
    }

    /** Upper bound on Android's user number (within UserHandle.PER_USER_RANGE). */
    private const val MAX_USER = 99_999
    /** How many seconds at most to wait for another task to release the App: beyond that it is better to do something else first or tell the user. */
    const val MAX_WAIT_S = 180
    const val COPY_NOT_FOUND = "copy_not_found"
    const val WAIT_WOULD_DEADLOCK = "app_wait_would_deadlock"
    const val WAIT_TIMED_OUT = "app_wait_timed_out"
}

/**
 * Whether to keep this App around or close it when the task ends. Kept by default (sent back to the background,
 * left on the page it finished on, to be continued next time), except that anything still playing sound at the end
 * is closed; mark it here when the user said it should keep playing, or should be closed when done (a one-time
 * sign-in and the like). This only records it and changes nothing right now; marking the same App again takes the
 * last mark.
 */
internal object AppAfterTask : PhoneTool {
    override val verb = Verb.AppAfterTask
    override val label = "定下 App 做完后留着还是关掉"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("phone_app_after_task", Prompts.tool("phone_app_after_task"),
        mapOf(
            "package" to str("Exact package name of the app, e.g. com.example.app."),
            "action" to str("keep: leave it running in the background after the task (even if it is playing sound). close: close it when the task ends.", listOf(KEEP, CLOSE)),
            "reason" to str("Why, in a few words (e.g. the user wants the music to keep playing; a one-time sign-in)."),
            "user" to int("Only for another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
        ),
        listOf("package", "action", "reason"))

    /** Only records it; does not switch the screen being operated on. */
    override fun aimAt(o: JsonObject): String? = null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val action = a.str("action")!!
        if (action != KEEP && action != CLOSE) throw ArgError("action", "must be keep or close")
        val reason = a.str("reason", max = 200)!!.trim()
        val user = a.int("user", 0, MAX_USER)
        val marks = ctx.phone.screen.afterTask ?: return failCode(AFTER_TASK_UNAVAILABLE)
        val previous = marks.mark(p, user, action == KEEP, reason)
        return ok {
            put("package", p)
            user?.let { put("user", it) }
            put("action", action)
            previous?.takeIf { it.keep != (action == KEEP) }?.let { put("replaces", if (it.keep) KEEP else CLOSE) }
            put("hint", Feedback[if (action == KEEP) "after_task.keep" else "after_task.close"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("package"), o.argStr("action")).joinToString(" ").ifEmpty { null }

    const val KEEP = "keep"
    const val CLOSE = "close"
    const val AFTER_TASK_UNAVAILABLE = "after_task_unavailable"
    private const val MAX_USER = 99_999
}

/**
 * Share a piece of text to an App (ACTION_SEND text/plain), opening its share sheet on Claire's screen: picking the
 * recipient and sending both happen on that page, and nothing is sent from here. When text cannot be typed into a
 * UI that cannot be read, bring it in via sharing.
 */
internal object ShareText : PhoneTool {
    override val verb = Verb.ShareText
    override val label = "分享文字到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_text", Prompts.tool("phone_share_text"),
        mapOf(
            "package" to str("Exact package name of the app to share the text to, e.g. com.example.app."),
            "text" to buildJsonObject { put("type", "string"); put("description", "The text to share, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "reason" to str("Optional: why you share it, e.g. the message the user asked to send to a contact."),
        ),
        listOf("package", "text"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val text = a.str("text", max = 10000)!!
        // Switched Apps, so a payment declaration refused earlier no longer applies to this one
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareText(p, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        // The system first asks which one to use (two copies installed, more than one share target): the hint already explains how to choose; say the next step once on the share page
        val opened = if (r.content["chooser"] != null) r else withHint(r, Feedback["share.opened"])
        return unreadable(ctx, p, opened)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name opened the share page of $p (not sent yet)" + (log.appLabels[p]?.let { " (app name \"$it\")" } ?: ""), source = PHONE)
        })
    }
}

private fun withHint(r: ToolExecution, hint: String): ToolExecution {
    val all = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, hint).joinToString(" ")
    return ToolExecution(JsonObject(r.content + ("hint" to JsonPrimitive(all))), changed = r.changed)
}

/** Came to the foreground but the window cannot be read (secure window, splash screen, ...): say so plainly, so the model does not think it failed to open and launch it again. */
private suspend fun unreadable(ctx: ToolContext, p: String, r: ToolExecution): ToolExecution {
    val s = ctx.session.peek()
    if (s?.unreadableApp != p || s.elements.any { it.meaningful }) return r
    return withHint(ToolExecution(JsonObject(r.content + ("readable" to JsonPrimitive(false))), changed = r.changed), Feedback.format("unreadable_window", "app" to p))
}
