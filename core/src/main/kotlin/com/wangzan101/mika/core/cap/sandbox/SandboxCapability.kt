package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.safety.guard.OffScreenGuard
import com.wangzan101.mika.core.learn.knowledge.ScriptManifest
import com.wangzan101.mika.core.learn.knowledge.ScriptState
import com.wangzan101.mika.core.learn.knowledge.BundledScripts
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.workspace.SandboxPaths

/**
 * The Linux sandbox: commands, skill scripts, workspace files. Tools are provided when the device supports it (a tool call waits for the runtime when it is not ready yet);
 * this is not screen operation, so it does not go through the lock-screen gate and does not enter the screen guard, being managed separately by [SandboxGuard].
 */
internal class SandboxCapability(
    private val port: SandboxPort?,
    /** This task's workspace (a conversation ID, or `p:<projectId>`). */
    private val workspace: () -> String,
    visionEnabled: Boolean,
    onCommand: (String) -> Unit,
    onProgress: (String) -> Unit,
    onPreparing: () -> Unit,
    onScript: ((String) -> Unit)?,
    folderAccess: Boolean = false,
    /** How long sandbox commands may accumulate in this task (settings › the working tier); 30 minutes by default. */
    private val budgetMs: Long = SANDBOX_BUDGET_MS,
    folderFileImporter: FolderFileImporter? = null,
    private val folderTools: List<AgentTool> = emptyList(),
) : Capability {
    override val id = SANDBOX

    val runner = port?.let { SandboxTools(it, workspace, visionEnabled, onCommand = onCommand, onProgress = onProgress, onPreparing = onPreparing, onScript = onScript, folderAccess = folderAccess, folderFileImporter = folderFileImporter) }

    /** The device supports the sandbox (tools are already provided, and it may still be preparing). */
    fun available(): Boolean = runner?.available() == true

    override val folds = true

    override fun status(): CapabilityStatus = when (val s = port?.status()) {
        null -> CapabilityStatus.Unavailable("no sandbox")
        is SandboxStatus.Ready -> CapabilityStatus.Available
        is SandboxStatus.NotInstalled -> CapabilityStatus.Degraded("not installed")
        is SandboxStatus.Installing -> CapabilityStatus.Degraded(s.step)
        is SandboxStatus.Unsupported -> CapabilityStatus.Unavailable(s.reason)
        is SandboxStatus.Broken -> CapabilityStatus.Unavailable(s.reason)
    }

    override fun tools(): List<AgentTool> = if (runner?.foldersAvailable == true) SandboxGroup.tools + folderTools else SandboxGroup.tools

    /** This message carries files from the workspace (the attachment line gives a path under /workspace/): expand the sandbox group right away. */
    override fun preload(task: String): Boolean = available() && "${SandboxPaths.WORKSPACE}/" in task

    /** Not offered while the sandbox group is collapsed. */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || SandboxGroup.tools.none { it.name in env.offered }) return emptyList()
        val p = port?.takeIf { available() }
        val shared = workspace().startsWith(PROJECT_WORKSPACE_PREFIX)
        return listOf(env.gate(section(p?.status() ?: SandboxStatus.NotInstalled, p?.networkAllowed() == true, sharedWorkspace = shared)))
    }

    override val scripts = object : BundledScripts {
        override fun offered() = available()

        override suspend fun ready(): Boolean {
            val p = port ?: return false
            if (p.status() !is SandboxStatus.Ready) return false
            p.prepare()
            return true
        }

        override fun scripts(skillDir: String): List<ScriptState> {
            val network = (port?.status() as? SandboxStatus.Ready)?.network == true
            return port?.scriptManifest(skillDir).orEmpty().map { e ->
                val (run, reason) = ScriptManifest.effectiveRunnable(e, network)
                ScriptState(e.file, run, reason)
            }
        }
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    override fun guard(): OffScreenGuard = SandboxGuard(budgetMs)

    companion object {
        val KEY = CapabilityKey<SandboxCapability>(SANDBOX)

        /** The sandbox section: states whether the network is available right now ([network]: the user's network setting); adds a line each for a project-shared workspace and for a runtime that is not ready yet. */
        fun section(status: SandboxStatus, network: Boolean, sharedWorkspace: Boolean = false): String = buildString {
            append(Prompts.capability(SANDBOX).replace("{{network}}", if (network) NETWORK_ON else NETWORK_OFF))
            if (sharedWorkspace) append("\n- ").append(SHARED_WORKSPACE)
            if (status !is SandboxStatus.Ready) append("\n- ").append(PREPARING)
        }

        private const val PROJECT_WORKSPACE_PREFIX = "p:"

        const val SHARED_WORKSPACE = "This conversation belongs to a project: all conversations in the same project share /workspace, so files created or updated by other conversations in this project may already be present."
        const val PREPARING = "The environment is still being set up in the background. The first tool call waits for it (up to about two minutes); if it is still not ready, the tool says so."

        const val NETWORK_ON = "on. The user allowed the sandbox to use the network; anything you send out leaves the phone, so only fetch what the task needs."
        const val NETWORK_OFF = "off. Commands cannot reach the internet (no pip/npm/apk installs, no downloads). If the task really needs the network, tell the user they can turn on \"Allow scripts to go online\" (允许脚本联网) in Claire's settings."
    }
}

/** Sandbox execution for this task; null when there is no sandbox. */
internal val ToolContext.sandboxRunner: SandboxTools? get() = this[SandboxCapability.KEY]?.runner

internal fun ToolContext.sandboxAvailable(): Boolean = this[SandboxCapability.KEY]?.available() == true
