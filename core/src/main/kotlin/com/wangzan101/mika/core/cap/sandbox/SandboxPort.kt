package com.wangzan101.mika.core.cap.sandbox

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import com.wangzan101.mika.core.learn.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Entry point this task's action executor is handed back to when a sandbox command requests a phone tool. */
fun interface PhoneCommandHandler {
    suspend fun call(tool: String, args: JsonObject): JsonObject
}

sealed interface SandboxStatus {
    data class Unsupported(val reason: String) : SandboxStatus
    data object NotInstalled : SandboxStatus
    data class Installing(val percent: Int, val step: String) : SandboxStatus
    data class Ready(val network: Boolean) : SandboxStatus
    data class Broken(val reason: String) : SandboxStatus
}

/** The only entry point through which the Agent touches the Linux sandbox; all paths are paths inside the sandbox ([SandboxPaths]). */
interface SandboxPort {
    val folderFiles: FolderFiles? get() = null

    /** Tree lock of the sandbox directory: everything written into the workspace is written under its permit. */
    val tree: SandboxTree

    fun status(): SandboxStatus

    /** Whether the user allows the sandbox to access the network (known even when the runtime is not ready yet); by default taken from the ready status. */
    fun networkAllowed(): Boolean = (status() as? SandboxStatus.Ready)?.network == true

    /** [cwd]: relative path under `/workspace`, null means `/workspace` itself. */
    suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult

    /**
     * Starts a long-running command (a stdio MCP server); stdin/stdout/stderr are wired directly to the process. Unsupported by default;
     * an implementation must lock the process inside the sandbox and must not let it obtain the App's private directories. [environment] is the explicitly appended
     * environment (secrets enter the process through here, not via argv); implementations merge it against an allowlist and do not inherit the host/App environment.
     */
    suspend fun openProcess(workspace: String, argv: List<String>, cwd: String? = null, environment: Map<String, String> = emptyMap()): SandboxProcess =
        throw SandboxError("sandbox_stdio_unsupported", "This device cannot run stdio MCP servers.", recoverable = true)

    /**
     * A terminal the user opens: starts a shell with a pseudo-terminal in [workspace]. It belongs to no task, does not go through the model,
     * and writes no run record; like [run], it executes under the network setting in effect at the time. Unsupported by default.
     */
    suspend fun openShell(workspace: String, cols: Int = 80, rows: Int = 24): SandboxShell =
        throw SandboxError("sandbox_shell_unsupported", "This device cannot open a terminal.", recoverable = true)

    /** When the current task provides a phone-tool entry, commands can call it through the bridge; older implementations may still implement only [run]. */
    suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult =
        run(workspace, argv, cwd, timeoutMs, onProgress)

    /** The host file corresponding to a sandbox path; returns null when the path escapes the boundary (including symlinks pointing outside) or, when [forWrite] is set, is not under the workspace. Used only for checking; reads and writes go through [files]. */
    fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File?

    /** All host-side reads and writes of files in the sandbox directory pass through this gate: open once, verify on the handle, then read and write on that same handle. */
    val files: WorkspaceFileGate

    /** Normalized sandbox path → (host root directory, path relative to that root), handed to [files]; skill directories are read-only, out-of-bounds is null. Creates the workspace directory if it does not exist. */
    fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>?

    /** Saves to "Downloads/Claire" on the phone and returns "Download/Claire/<name>". */
    suspend fun share(file: File, name: String): String

    fun freeBytes(): Long
    fun workspaceBytes(workspace: String): Long

    /**
     * Saves the complete output to /workspace/.mika/out-<n>.txt and returns the sandbox path. Created through [files] (throws and writes nothing if .mika has been replaced by a link, etc.).
     */
    fun saveOutput(workspace: String, text: String): String

    /** [skillPath]: a skill directory under `/opt/mika/skills` (relative path); reads the manifest bundled with the App, empty if there is none. */
    fun scriptManifest(skillPath: String): List<ScriptEntry>

    /**
     * While the runtime is not ready yet (first-launch unpacking, or not started yet): begin preparing and wait for it, at most [timeoutMs]; returns the status after the wait.
     * Returns as soon as possible on unsupported devices or when preparation fails. The default implementation does not wait.
     */
    suspend fun awaitReady(timeoutMs: Long): SandboxStatus = status()

    /** Reconciles `/opt/mika/skills` against the copy bundled with the App: extra, missing and modified entries are all re-synced. Failures are only recorded, never thrown. */
    suspend fun prepare() {}

    /** [file] under [skillPath] is byte-identical to the bundled copy; if it differs, re-sync once and check again, returning false if it still differs. */
    suspend fun verifyScript(skillPath: String, file: String): Boolean = true
}

/** The long-running process returned by [SandboxPort.openProcess]; closing it terminates the entire process tree. */
interface SandboxProcess : Closeable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
}

/**
 * A user terminal session: [input] is written to the shell's stdin, [output] is the merged output of stdout and stderr.
 * Closing it terminates the entire process tree; [revoked] becomes true once the tree lock is reclaimed by a reset/cleanup.
 */
interface SandboxShell : Closeable {
    val input: OutputStream
    val output: InputStream

    /** Exit code when the shell exits; when killed by a signal it is 128 + the signal. */
    val exited: Deferred<Int>

    /** True after being reclaimed by the exclusive permit of [SandboxTree] (reset, clearing the workspace). */
    val revoked: StateFlow<Boolean>

    /** Resizes the window; does nothing when the implementation does not support it. */
    fun resize(cols: Int, rows: Int) {}
}
