package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.tool.Verb

/** What the previous step looked like, as seen when deciding which model to use next. */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** Whether the screen changed after the previous step; null for screen reads and the like that do not report it. */
    val lastChanged: Boolean?,
    /** Recovering from a replay hand-back, batch stop, review send-back, hand-over, or ask-back. */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** The context to be sent contains screenshots; a light model without vision cannot take it. */
    val hasImages: Boolean = false,
)

/**
 * Division of labor for the experimental switch lightSteps: simple steps go to the light model, the rest to the
 * primary model. When no light model is configured, or it does not support tool calls, the primary model is always
 * used. After a step errors, raises a guard hint, or leaves the screen unchanged, the next [TROUBLE_STEPS] steps are
 * forced to the primary model.
 *
 * The light side keeps the multi-level Light Waterfall Chain: when the current light model is unavailable, switch to
 * the next one in turn, and only fall back to the primary model when all are exhausted.
 * The primary and light models may both be model groups ([ModelPool]); [pick] returns the pool itself, the pool then
 * picks a member by member selection, and switches members on failure.
 */
class ModelRouter(
    private val main: ChatModel,
    private val light: ChatModel?,
    private val fallbacks: List<ChatModel> = emptyList(),
) {
    private val mainPool: ModelPool = main.asPool()
    /** The light-side failover chain; each level may be a single model or a model group. */
    private val lightChain: List<ChatModel> = listOfNotNull(light) + fallbacks
    private val lightPools: List<ModelPool> = lightChain.map { it.asPool() }
    private var lightIndex = 0
    private var forcedMain = 0
    private var disabled = false

    /** The currently active light model (may be a group). */
    val currentLight: ChatModel? get() = lightChain.getOrNull(lightIndex)
    private val currentPool: ModelPool? get() = lightPools.getOrNull(lightIndex)

    /** Whether the light model can take phone steps; when it cannot, lightSteps has no effect. One member in the group supporting tools is enough; each step is then filtered by need. */
    val active: Boolean get() = !disabled && currentPool?.members?.any { it.model.capabilities.toolCalls } == true

    /**
     * The light model hit an error that retrying cannot fix (key, quota, model name, malformed request):
     * if there is a fallback light model, switch to the next fallback; otherwise the rest use the primary model.
     */
    fun disable() {
        if (!switchToNextLight()) {
            disabled = true
        }
    }

    /** Tries to switch to the next fallback light model; returns true on success, false when there are no more fallbacks */
    fun switchToNextLight(): Boolean {
        if (lightIndex + 1 < lightChain.size) {
            lightIndex++
            return true
        }
        disabled = true
        return false
    }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        if (forcedMain > 0) {
            forcedMain--
            return main
        }
        val l = currentLight?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** Something went wrong (including a failed light-model call, or a reply reassigned to the primary model): the next [TROUBLE_STEPS] steps use the primary model. */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    /** Whether this model is a member of the light side (including fallbacks) — not by object equality, since a group whose members changed must still be recognized; the light pool itself also counts. */
    fun isLight(m: ChatModel): Boolean = lightChain.any { it === m } || lightPools.any { it.owns(m) }

    /** Which model group this member belongs to; null for a single model. Also matches when the group itself is passed in as the member. */
    fun groupOf(m: ChatModel): String? {
        val pool = if (isLight(m)) {
            lightChain.firstOrNull { it === m }?.asPool() ?: lightPools.firstOrNull { it.owns(m) }
        } else {
            mainPool.takeIf { it === m || it.owns(m) }
        }
        return pool?.groupName
    }

    companion object {
        const val TROUBLE_STEPS = 2
        /** These calls (plus do_steps) are not executed when the light model makes them; the step asks the primary model instead: giving up, confirming, hand-over, ask-back, and sandbox commands are all decided by the primary model. */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand) +
            // What runs in the sandbox is a command written by the model: only the primary model may write them
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile, Verb.ViewImage, Verb.ListFolderFiles)
        /** Calls only the primary model may make ([name] is the tool name, [verb] is the action it performs). */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** The step after these actions counts as recovery (the user was waited for). */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** These actions switch the foreground App or web page (opening a URL, switching tabs); the following step is not in the same place. */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** Used by AgentLoop: records each step's situation and assembles the next step's [StepContext]. */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && app != null && app == appBefore, hasImages)

    /** An [app] of null means this step's result reported no foreground App; the previous one is kept. */
    /** Just recovered from something like a replay hand-back or a batch stop: the next step counts as a recovery step. Call after [after]. */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}
