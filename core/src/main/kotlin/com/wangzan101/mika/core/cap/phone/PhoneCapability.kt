package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.Paced
import com.wangzan101.mika.core.screen.uistate.DwellStop
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.screen.uistate.PlacePace
import com.wangzan101.mika.core.screen.uistate.dwellStopped
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.screen.uistate.Surface
import com.wangzan101.mika.core.screen.observation.Look
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.EffectKind
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Phone: the phone's set of screen tools, plus opening apps and links.
 * [pacing] is the pace of operating step by step inside an app (see [AppPacing]): top up the dwell before actions that change the screen, and apps with limited browsing speed have a screen cap;
 * [appTempo] gives this app's effective pace by package name ([AppTempo]), [appCapped] says which apps limit browsing speed (counting screens, subject to the cap);
 * when [idle] is provided, gently scroll a few times during the dwell on a new screen (see [IdleScroll]).
 */
internal class PhoneCapability(
    val screen: ScreenPort,
    /** Speed-up toggle deepLinks: provides the tool for opening links. */
    val deepLinks: Boolean,
    pacing: AppPacing = AppPacing.Off,
    appTempo: (String) -> AppTempo = { AppTempo.NORMAL },
    appCapped: (String) -> Boolean = { false },
    idle: IdleScroll? = null,
    val smsLogin: com.wangzan101.mika.core.cap.phone.sms.SmsLoginPort? = null,
) : Capability {
    val dwell = AppDwell(pacing, appTempo, appCapped, idle)

    override val id = PHONE
    private val tools = PhoneGroup.tools

    override val folds = true

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = tools

    override val family = PHONE_FAMILY

    override val surface = Surface(PHONE_FAMILY, screen, declaredTarget = { app ->
        if (!PACKAGE_NAME_RE.matches(app)) throw ArgError("app", "must be an Android package name")
        app
    }, targetNote = "the exact package name of the app")

    /**
     * The phone section goes along with the tools for opening and listing apps; it is not offered when the phone group is collapsed.
     * The app access rules are appended only when the user has disabled apps (or the settings cannot be read): with an empty list the phone tools are never rejected because of it, so the model does not need this section.
     */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || tools.none { it.verb in SECTION_VERBS && it.name in env.offered }) return emptyList()
        val access = screen.appAccess.current()
        return listOfNotNull(
            env.gate(Prompts.capability(PHONE)),
            Prompts.capability(APP_ACCESS).takeIf { !access.available || access.blocked.isNotEmpty() },
        )
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onIdleScroll = { ctx.sessions[id]?.movedByMika() }
    }

    /** The user has answered: the screen count for apps with limited browsing speed is recomputed (the user agreed to keep looking). */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** Once an outward action inside the app has been done, this app is no longer capped by screen count (bulk contact and delivery run up to the app's own limit). */
    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /**
     * Before an action that changes the screen, pause at the pace inside the app: which app it is right now and how many characters this screen has are taken from the screen the model most recently read.
     * Actions that switch screens inside an app (tap, swipe, back, key press, gesture) are not performed once the screen cap is reached in an app with limited browsing speed, and report page_limit; typing, clearing, scrolling,
     * turning the wheel, opening another app and outward actions themselves are not blocked. When the upcoming actions do not work by on-screen position (back, key press, direction-based scroll), a few gentle scrolls may happen during the dwell.
     */
    override suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced {
        if (!dwell.paced) return Paced.NONE
        val shown = ctx.sessions[id]?.lastSnapshot
        return try {
            val d = dwell.before(shown?.packageName, shown?.let(::textChars) ?: 0, gate = !outward && verb in BROWSES,
                port = screen.takeIf { verb in IDLE_BEFORE })
            Paced(d.waitMs, d.idleScrolls)
        } catch (e: DwellStop) {
            Paced(stop = dwellStopped(e))
        }
    }

    /** After the action: a major UI change or switching apps counts as moving to a new screen (scrolling down to read the rest of the same page does not count toward the screen count). */
    override fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {
        if (!dwell.paced) return
        val effect = result["effect"] as? JsonObject
        val kind = EffectKind.of((effect?.get("kind") as? JsonPrimitive)?.contentOrNull)
        val newScreen = kind == EffectKind.Done || kind == EffectKind.PlaceChanged
        val app = (effect?.get("to") as? JsonPrimitive)?.contentOrNull ?: ctx.sessions[id]?.lastSnapshot?.packageName
        dwell.after(app, newScreen, counts = verb != Verb.Scroll)
    }

    /** Look again: the UI state of the app currently on the phone. */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = ctx.sessions[id]?.freshLook(withImage)

    /** How many times system commands have failed in a row; reset to zero by one success. */
    var commandFailures = 0

    /** This task has already been reminded to switch system commands back to the dedicated tools: commands that do those kinds of things afterwards are not executed. */
    var commandSteered = false

    companion object {
        val KEY = CapabilityKey<PhoneCapability>(PHONE)
        private val SECTION_VERBS = setOf(Verb.LaunchApp, Verb.ListApps)

        /** The app access rules section (capabilities/app-access.md). */
        private const val APP_ACCESS = "app-access"

        /** Actions that switch screens inside an app: blocked by the screen cap of apps with limited browsing speed. */
        private val BROWSES = setOf(Verb.Tap, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.PhoneGesture)

        /** Actions that do not work by on-screen position: a few gentle scrolls during the preceding dwell will not make them tap off-target. */
        private val IDLE_BEFORE = setOf(Verb.Back, Verb.PressKey, Verb.Scroll)

        /** Number of characters shown to a person on one screen (text in input fields and hint text do not count). */
        internal fun textChars(s: ScreenSnapshot): Int =
            s.elements.filter { !it.editable && !it.hint }.sumOf { it.name.codePointCount(0, it.name.length) }

        /** All tools the phone group may offer. */
        fun tools(): List<AgentTool> = PhoneGroup.tools
    }
}

/** The phone capability for this task. */
internal val ToolContext.phone: PhoneCapability get() = this[PhoneCapability.KEY]!!
