package com.wangzan101.mika.core.capability

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.screen.uistate.Surface
import com.wangzan101.mika.core.safety.guard.OffScreenGuard
import com.wangzan101.mika.core.learn.knowledge.BundledScripts
import com.wangzan101.mika.core.screen.observation.Look
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject

/** Whether a capability is usable right now. */
sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    /** Usable, but missing something (the runtime is still preparing, part of the functionality is absent). */
    data class Degraded(val reason: String) : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}

/**
 * What each group's sections can see when the system prompt is assembled: the tool names and switches sent to the model for this task;
 * [screens] is how many kinds of screens are among this task's offered tools (phone, web, …, folded ones count as long as only their entry point is offered).
 */
class PromptEnv(
    val offered: Set<String>, val inlineScreen: Boolean, val screens: Int = 1,
    /** Expanded capabilities (including ones that do not fold); null means treat all as expanded. Folded capabilities do not provide their own sections. */
    val open: Set<String>? = null,
    /** The screens out of [screens] that are expanded. */
    val openScreens: Int = screens,
) {
    /** Capability [id] is expanded (or does not fold). */
    fun isOpen(id: String): Boolean = open == null || id in open

    /**
     * A line in a section that only applies when certain tools are offered starts with `<!-- needs: tool names, … -->`: it is kept (with the marker removed) only when all those tools are in this task's tool list,
     * otherwise the whole line is dropped. Lines without the marker are kept as-is.
     */
    fun gate(text: String): String = text.lines().mapNotNull { line ->
        val m = NEEDS.find(line) ?: return@mapNotNull line
        val tools = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tools.all { it in offered }) m.groupValues[1] + m.groupValues[3] else null
    }.joinToString("\n")

    private companion object {
        val NEEDS = Regex("^(\\s*)<!-- needs: ([a-z0-9_, ]+) -->\\s?(.*)$")
    }
}

/** A group of sections in the system prompt; each capability provides one group, and the core has its own. */
internal fun interface PromptSections {
    fun sections(env: PromptEnv): List<String>
}

/**
 * A capability (phone, web, sandbox, …): its own tools, its sections in the system prompt, screens, and guards.
 * The core knows capabilities only through this interface; there is one instance per task, and ports are supplied at construction.
 */
internal interface Capability : PromptSections {
    val id: String

    fun status(): CapabilityStatus

    /** This tool group expands only when used (see [com.wangzan101.mika.core.tool.ToolGroups]): at start-up only the entry-point tool is present; groups that do not fold are always fully present. */
    val folds: Boolean get() = false

    /** When port availability changes with external selection, the catalog and execution are re-checked every time; ordinary capabilities still freeze the catalog from start-up. */
    val dynamicAvailability: Boolean get() = false

    /** Expand this group at start-up based on this run's utterance ([task]) (when it will certainly be used); does not expand by default. */
    fun preload(task: String): Boolean = false

    /** What the user typed themselves after removing capability-specific annotations (such as the browser page description) from the user's utterance ([text]); returned as-is when there is no such annotation. */
    fun userWords(text: String): String = text

    /** All tools this capability may offer; whether each is usable in this task is decided by the tool itself from the context. */
    fun tools(): List<AgentTool>

    /** A capability with screens provides the shape of its screen tool set (also provided when no port is connected). */
    val family: ScreenFamily? get() = null

    /** The screen whose port is connected; null when there is none. This capability's screen tools operate only on it. */
    val surface: Surface? get() = null

    /** Provided when skill scripts can be run. */
    val scripts: BundledScripts? get() = null

    /** Task start: puts the ports and state the tools need into the tool context. */
    fun onTaskStart(ctx: ToolContext) {}

    /** The user has answered during the task (answered a follow-up question, handed back a takeover). */
    fun onUserAnswered(ctx: ToolContext) {}

    /**
     * An outward action (send, submit, see [com.wangzan101.mika.core.action.OutwardPacing]) is about to be performed on this capability's screen; [site] is the website or app it is on.
     * [onOutwardEnd] is always called afterwards ([done]: whether it succeeded).
     */
    fun onOutwardStart(ctx: ToolContext, site: String) {}

    fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) {}

    /**
     * An action that changes the screen ([verb]) is about to be performed on this capability's screen: pause first according to this capability's pacing (see [com.wangzan101.mika.core.action.ActionExecutor],
     * both for single steps and for each step within a batch). [outward]: this step is an outward action (send, submit). No pause by default.
     */
    suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced = Paced.NONE

    /** That action has finished ([result] is its result); not called when [beforeWrite] stopped it and it was not performed. */
    fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {}

    /** Guard for tools that do not touch the screen; one per task; null when there is none. */
    fun guard(): OffScreenGuard? = null

    /**
     * Look once more at this capability's current state before verification (without changing anything the model holds); with [withImage], include a frame as well. Null when there is nothing to look at.
     */
    suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = null
}

/**
 * Result of pausing according to the capability's pacing before a screen-changing action: paused [waitMs] (counted into this call's wait) and scrolled gently [idleScrolls] times during the dwell (recorded only in the run record);
 * when [stop] is non-null the action is not performed and [stop] is this call's result (screen limit reached, user interjected during the dwell).
 */
internal class Paced(val waitMs: Long = 0, val idleScrolls: Int = 0, val stop: ToolExecution? = null) {
    companion object {
        val NONE = Paced()
    }
}

/** Key for something a capability puts into the tool context; the core does not know what is inside. */
class CapabilityKey<T : Any>(val name: String)
