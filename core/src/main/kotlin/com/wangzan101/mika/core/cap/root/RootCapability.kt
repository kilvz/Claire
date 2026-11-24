package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

internal const val ROOT = "root"

/** The port that runs commands as root: supplied by the app on rooted phones when the user has enabled "Allow Claire to run root commands", and not supplied otherwise (in which case the tool is not registered either). */
interface RootPort {
    /** Runs [command] as root, for at most [timeoutMs]. It blocks, so the implementation switches to an IO thread itself. */
    suspend fun run(command: String, timeoutMs: Long): RootRun
}

/** The outcome of one root command. */
sealed interface RootRun {
    /**
     * It ran. [stdout] and [stderr] each keep at most 1 MB, with the excess dropped and counted in [droppedBytes]; [timedOut]: it was ended when the time limit was reached.
     */
    data class Done(
        val exitCode: Int, val timedOut: Boolean, val stdout: String, val stderr: String, val droppedBytes: Long, val durationMs: Long,
    ) : RootRun

    /** su denied it (the user revoked the grant in the root manager). */
    data object Denied : RootRun

    /** su did not answer. */
    data object NoAnswer : RootRun

    /** su can no longer be found on the phone. */
    data object Unavailable : RootRun
}

/**
 * Root commands: a single tool, [RootRunCommand]. It is installed only when the user has enabled it and the app supplies the port; it does not touch the screen and is not collapsed away (there is only one tool).
 * Commands that change things ask the user first when asking is required (the tool declares this per argument, see [RootRunCommand.approvalRisk]).
 */
internal class RootCapability(val port: RootPort) : Capability {
    override val id = ROOT

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = RootGroup.tools

    override fun sections(env: PromptEnv): List<String> =
        if (RootGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(ROOT))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<RootCapability>(ROOT)
    }
}

internal val ToolContext.rootPort: RootPort? get() = this[RootCapability.KEY]?.port
