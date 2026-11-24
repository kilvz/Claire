package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxProcess
import com.wangzan101.mika.core.cap.sandbox.SandboxShell
import com.wangzan101.mika.core.cap.sandbox.PhoneCommandHandler
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.learn.knowledge.ScriptEntry
import com.wangzan101.mika.core.learn.knowledge.ScriptManifest
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXIT_TIMEOUT = 124
private const val EXIT_LAUNCHER_ERROR = 125
private const val EXIT_SIGKILL = 128 + 9
/** The launcher has its own timeout; if it still has not ended after waiting this much longer on the Kotlin side, kill it. */
private const val FALLBACK_GRACE_MS = 10_000L
private const val DESTROY_GRACE_MS = 3_000L
private const val READER_GRACE_MS = 2_000L
private const val TIMEOUT_SLACK_MS = 500L
private const val SIZE_CACHE_MS = 60_000L
internal val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
private const val TAG = "AndroidSandbox"
internal const val SHARE_MAX_FILES = 20
internal const val SHARE_MAX_BYTES = 500L shl 20
/** Minimum free space required before opening the user's terminal (same as run_command). */
private const val MIN_FREE_BYTES = 500L shl 20

/**
 * The export quota of one task (one AndroidSandbox), shared by share_file and `mika share`.
 * Reserved by file size before copying and settled by the actual bytes afterwards; a failed export uses no quota.
 */
internal class ShareQuota(private val maxFiles: Int = SHARE_MAX_FILES, private val maxBytes: Long = SHARE_MAX_BYTES) {
    private var files = 0
    private var bytes = 0L

    @Synchronized fun reserve(size: Long) {
        if (files + 1 > maxFiles || bytes + size > maxBytes) {
            throw SandboxError("share_limit", "One task can share at most $maxFiles files and ${maxBytes shr 20} MB in total.", recoverable = false)
        }
        files++
        bytes += size
    }

    @Synchronized fun settle(reserved: Long, actual: Long) { bytes += actual - reserved }

    @Synchronized fun release(reserved: Long) {
        files--
        bytes -= reserved
    }
}

/**
 * A dedicated thread for starting sandbox processes, alive as long as they are. The launcher sets PR_SET_PDEATHSIG,
 * which follows the thread that forked it: when a thread pool retires an idle thread, commands started by that thread
 * would be killed as if "Claire died". The coroutine dispatcher hands start() exceptions back to the caller, and the
 * thread itself does not exit because of them.
 */
private val launcherThread = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-sandbox-launcher").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/** The Android implementation of [SandboxPort]: runs commands with a launcher + PRoot, file operations read and write host directories directly. */
class AndroidSandbox internal constructor(
    private val dirs: SandboxDirs,
    private val currentStatus: () -> SandboxStatus,
    private val dnsServers: () -> List<String>,
    /** Opens a bridge session per run; JVM tests have no Android socket, so pass false. */
    private val bridges: Boolean = false,
    private val version: String = "",
    /** Saves to "Downloads/Claire": (a file verified through the gate, save name) → the exported file; null disables export. */
    private val exporter: (suspend (GatedFile, String) -> SharedFile)? = null,
    /** Tells the task (file card) after a successful export. */
    private val onShared: (SharedFile) -> Unit = {},
    /** Preparation before each run (reconciling the skills directory with the bundled copy). */
    private val sync: suspend () -> Unit = {},
    /** Skill scripts bundled with the app (relative path under the skills directory → content); both the manifest and the scripts take it as the source of truth. */
    private val skillFiles: () -> Map<String, ByteArray> = { emptyMap() },
    /** Starts (or obtains the in-progress) runtime preparation; null when no preparation is needed. */
    private val startPreparing: () -> Job? = { null },
    /** The user's network setting; when not given, the one in the ready status is used. */
    private val allowNetwork: (() -> Boolean)? = null,
    /** Network proxy (Settings › Network proxy, see [NetProxyStore]); an empty string means none. Only given to commands allowed to use the network. */
    private val proxy: () -> String = { "" },
    /** The bridge client inside the APK; an existing runtime must be updated along with the APK as well. */
    private val clientScript: () -> ByteArray? = { null },
    override val folderFiles: com.wangzan101.mika.core.cap.sandbox.FolderFiles? = null,
) : SandboxPort {
    override val tree get() = dirs.tree

    constructor(context: Context, state: SandboxState, onShared: (SharedFile) -> Unit = {}) : this(
        SandboxDirs(context), { state.status.value }, { dns(context) }, bridges = true, version = versionOf(context),
        exporter = ShareExporter(context)::export, onShared = onShared, sync = { SkillsSync.sync(SandboxDirs(context)) { state.status.value is SandboxStatus.Ready } },
        skillFiles = { SkillsSync.bundled },
        startPreparing = { RuntimePreparer.ensure(context, retryFailed = true) },
        allowNetwork = { state.networkAllowed },
        proxy = { NetProxyStore.get(context).get() },
        clientScript = { context.assets.open("sandbox/mika").use { it.readBytes() } },
        folderFiles = GrantedFolderFiles(context.applicationContext),
    )

    private val quota = ShareQuota()

    private class SizeEntry(val bytes: Long, val at: Long)
    private val sizes = ConcurrentHashMap<String, SizeEntry>()

    private fun dir(workspace: String): File = dirs.workspace(WorkspaceKey.parse(workspace))

    override fun status(): SandboxStatus = currentStatus()

    override fun networkAllowed(): Boolean = allowNetwork?.invoke() ?: super.networkAllowed()

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        val s = status()
        if (s is SandboxStatus.Ready || s is SandboxStatus.Unsupported) return s
        val job = startPreparing() ?: return status()
        withTimeoutOrNull(timeoutMs) { job.join() }
        return status()
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit): CommandResult = runWithPhone(workspace, argv, cwd, timeoutMs, onProgress, null)

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit,
        phone: PhoneCommandHandler?): CommandResult {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        // Check the caller's cwd once more: after normalization it must still be inside the workspace
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        // A failed skill sync does not block commands: when a script cannot run, run_bundled_script reports it truthfully
        prepare()
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 3600).toInt()
        // From directory creation until the command ends we hold the permit: commands write into the workspace too, and a reset must wait for it to finish before deleting
        val o = tree.shared {
            val ws = withContext(Dispatchers.IO) {
                dir(workspace).also {
                    dirs.ensureBindSources(it)
                    syncClientScript()
                    // Create the cwd inside the workspace when missing (segment by segment, following no symlinks); whatever cannot be created is left for PRoot to report
                    rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                    if (network) writeResolvConf()
                }
            }
            val cmd = ProcRunner.argv(dirs, ws, rel, timeoutSec, network, standIns(), argv)
            // Close the bridge when the command ends (timeout, kill or cancellation included): the old name and token no longer work
            val session = if (bridges) bridgeFor(workspace, network, onProgress, phone).open() else null
            try {
                launch(cmd, ProcRunner.env(dirs, session?.name, session?.token, workspace, if (network) proxy() else ""), timeoutSec * 1000L + FALLBACK_GRACE_MS)
            } finally {
                session?.close()
            }
        }
        sizes.remove(dir(workspace).name)
        val text = String(o.output, Charsets.UTF_8)
        if (o.exitCode == EXIT_LAUNCHER_ERROR) {
            val reason = text.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (reason.startsWith("mikarun: network isolation unavailable")) {
                throw SandboxError("sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so commands are refused while network access is off. ($reason)")
            }
        }
        val timedOut = o.timedOut || launcherTimedOut(o.exitCode, o.durationMs, timeoutSec)
        return CommandResult(
            exitCode = if (o.timedOut) EXIT_TIMEOUT else o.exitCode, timedOut = timedOut,
            killedBySystem = !timedOut && o.exitCode == EXIT_SIGKILL, durationMs = o.durationMs,
            output = text, droppedBytes = o.droppedBytes, network = network,
        )
    }

    /**
     * Starts a long-lived stdio process in the sandbox. Like a one-shot command it first prepares the runtime, creates
     * the workspace and writes resolv.conf; it provides no phone tool bridge (a stdio server only gets its own
     * stdin/stdout).
     *
     * While the process is alive it holds the tree's lease ([SandboxTree.lease]), not an ordinary shared lock: anything
     * that needs exclusivity (swapping the root filesystem, a reset, clearing the workspace) stops the process and
     * revokes the permit as soon as it arrives, instead of being blocked for an hour by a server left open in a task;
     * calling this process afterwards reports the connection as dropped. Directory creation through startup takes a
     * short shared lock of its own, so an exclusive one waits for it to finish before deleting, and after startup it is
     * checked once more whether the permit was revoked.
     */
    override suspend fun openProcess(workspace: String, argv: List<String>, cwd: String?, environment: Map<String, String>): SandboxProcess {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        prepare()
        val started = java.util.concurrent.atomic.AtomicReference<Process?>(null)
        val lease = tree.lease(onRevoke = { started.get()?.let(::stopNow) })
        try {
            val process = tree.shared {
                val ws = withContext(Dispatchers.IO) {
                    dir(workspace).also {
                        dirs.ensureBindSources(it)
                        syncClientScript()
                        rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                        if (network) writeResolvConf()
                    }
                }
                val cmd = ProcRunner.argv(dirs, ws, rel, 3600, network, standIns(), argv)
                val env = ProcRunner.env(dirs, null, null, workspace, if (network) proxy() else "", extra = environment)
                val p = withContext(launcherThread + NonCancellable) {
                    ProcessBuilder(cmd).redirectErrorStream(false).apply {
                        environment().clear()
                        environment().putAll(env)
                    }.start()
                }
                started.set(p)
                // A revocation that falls between taking the lease and registering the process was not seen by onRevoke: stop it here
                if (lease.revoked) {
                    stopNow(p)
                    throw SandboxError("sandbox_busy", "The sandbox is being reset; try again later.", recoverable = true)
                }
                p
            }
            return SandboxProcessHandle(process, lease)
        } catch (e: Throwable) {
            started.get()?.let(::stopNow)
            lease.close()
            throw e
        }
    }

    suspend fun openShell(key: WorkspaceKey, cols: Int = 80, rows: Int = 24): SandboxShell = openShell(key.wire, cols, rows)

    /**
     * The terminal the user opens: starts a `/bin/sh` with a pseudo-terminal in [workspace]. Same launcher, same
     * workspace and same network state as one-shot commands; no phone bridge and no time limit, ended when the user
     * closes it or the tree lease is revoked.
     */
    override suspend fun openShell(workspace: String, cols: Int, rows: Int): SandboxShell {
        val ready = status() as? SandboxStatus.Ready
            ?: throw SandboxError("sandbox_unavailable", "The Linux environment is not ready yet.", recoverable = true)
        if (freeBytes() < MIN_FREE_BYTES) {
            throw SandboxError("storage_low", "The phone has less than 500 MB free, so the terminal cannot open.", recoverable = false)
        }
        val network = ready.network
        val c = cols.coerceIn(1, 1000)
        val r = rows.coerceIn(1, 1000)
        prepare()
        val sizeFile = File(dirs.tmp, "pty-${java.util.UUID.randomUUID()}.size")
        val started = java.util.concurrent.atomic.AtomicReference<Process?>(null)
        val revoked = MutableStateFlow(false)
        val exited = CompletableDeferred<Int>()
        val lease = tree.lease(onRevoke = {
            revoked.value = true
            started.get()?.let(::stopNow)
        })
        try {
            val process = tree.shared {
                val ws = withContext(Dispatchers.IO) {
                    sizeFile.parentFile?.mkdirs()
                    sizeFile.writeText("$c $r")
                    dir(workspace).also {
                        dirs.ensureBindSources(it)
                        syncClientScript()
                        if (network) writeResolvConf()
                    }
                }
                val cmd = ProcRunner.argv(
                    dirs, ws, "", 0, network, standIns(), listOf("/bin/sh"),
                    pty = true, ptyCols = c, ptyRows = r, ptySizePath = sizeFile,
                )
                val env = ProcRunner.env(dirs, null, null, workspace, if (network) proxy() else "", pty = true)
                val p = withContext(launcherThread + NonCancellable) {
                    ProcessBuilder(cmd).redirectErrorStream(true).apply {
                        environment().clear()
                        environment().putAll(env)
                    }.start().also(started::set)
                }
                if (lease.revoked) {
                    stopNow(p)
                    throw SandboxError("sandbox_busy", "The sandbox is being reset; try again later.", recoverable = true)
                }
                p
            }
            thread(isDaemon = true, name = "mika-terminal-wait") {
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                lease.close()
                synchronized(sizeFile) { runCatching { sizeFile.delete() } }
                exited.complete(code)
            }
            return SandboxShellHandle(process, exited, revoked.asStateFlow(), sizeFile)
        } catch (e: Throwable) {
            started.get()?.let(::stopNow)
            lease.close()
            runCatching { sizeFile.delete() }
            throw e
        }
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(dir(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(dirs.skills, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files: WorkspaceFileGate get() = dirs.gate

    // Create the workspace directory only for writes (the caller holds a SandboxTree permit): reads and path lookups hold no permit, and creating it there would race with a reset's deletion
    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> dir(workspace).also { if (forWrite) it.mkdirs() } to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> dirs.skills to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    fun uploadCheck(key: WorkspaceKey, sandboxPath: String): WorkspacePathCheck = uploadCheck(key.wire, sandboxPath)

    /** Path check for web_upload: only regular files in this workspace that pass the gate count; the skills directory and anything outside the workspace are out of bounds. */
    fun uploadCheck(workspace: String, sandboxPath: String): WorkspacePathCheck {
        hostPath(workspace, sandboxPath, forWrite = true) ?: return WorkspacePathCheck.Outside
        val n = SandboxPaths.normalize(sandboxPath) ?: return WorkspacePathCheck.Outside
        val (root, rel) = hostRoot(workspace, n, forWrite = false) ?: return WorkspacePathCheck.Outside
        return try {
            files.openRead(root, rel).close()
            WorkspacePathCheck.Ok
        } catch (e: SandboxError) {
            if (e.code == WorkspaceFileGate.OUTSIDE) WorkspacePathCheck.Outside else WorkspacePathCheck.NotFound
        }
    }

    /**
     * [file] has already been checked against the workspace by ShareRules; export opens it once through the gate (with
     * the workspace it lives in as the root), the quota is based on the size from the handle, and the copy reads from
     * that same handle. Unexpected errors are logged only on Claire's side; only an error code and one sentence go out,
     * without Claire's private paths.
     */
    override suspend fun share(file: File, name: String): String {
        val export = exporter ?: throw SandboxError("share_unavailable", "Saving files to Downloads is not available in this version.")
        val (within, rel) = withContext(Dispatchers.IO) { workspaceOf(file) }
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val opened = withContext(Dispatchers.IO) { files.openRead(within, rel) }
        return opened.use { g ->
            val reserved = g.size
            quota.reserve(reserved)
            val shared = try {
                export(g, name)
            } catch (e: Throwable) {
                quota.release(reserved)
                throw mapExportError(e)
            }
            quota.settle(reserved, shared.size)
            onShared(shared)
            shared.savedAs
        }
    }

    private fun mapExportError(e: Throwable): Throwable = when (e) {
        is CancellationException, is SandboxError -> e
        is FileTooLarge -> SandboxError("file_too_large", "At most ${ShareRules.MAX_BYTES shr 20} MB can be shared.", recoverable = true)
        is Exception -> {
            Log.w(TAG, "export to Downloads failed", e)
            SandboxError("share_failed", "Saving to Downloads failed.", recoverable = true)
        }
        else -> e
    }

    /** The workspace directory the file lives in (the first level under work/) and the path relative to the workspace; null when it is in no workspace. */
    private fun workspaceOf(file: File): Pair<File, String>? {
        val work = dirs.work.canonicalFile
        val rel = file.canonicalFile.toRelativeString(work).takeIf { !it.startsWith("..") && it.isNotEmpty() && !File(it).isAbsolute } ?: return null
        val first = rel.substringBefore(File.separatorChar)
        return if (first == rel) null else File(work, first) to rel.substringAfter(File.separatorChar).replace(File.separatorChar, '/')
    }

    override fun freeBytes(): Long = dirs.root.takeIf { it.exists() }?.usableSpace ?: dirs.root.parentFile?.usableSpace ?: 0L

    fun workspaceBytes(key: WorkspaceKey): Long = workspaceBytes(key.wire)

    /** Sums without following symlinks; cached for a minute and invalidated after a command ran in this workspace. */
    override fun workspaceBytes(workspace: String): Long {
        val ws = dir(workspace)
        val cacheKey = ws.name
        val now = System.currentTimeMillis()
        sizes[cacheKey]?.takeIf { now - it.at < SIZE_CACHE_MS }?.let { return it.bytes }
        val bytes = treeBytes(ws.toPath())
        sizes[cacheKey] = SizeEntry(bytes, now)
        return bytes
    }

    /** Creates a new file in .mika/ through the gate (never overwriting an existing one); throws without writing anything when .mika has been swapped for a symlink or the like. */
    @Synchronized
    override fun saveOutput(workspace: String, text: String): String {
        val root = dir(workspace).apply { mkdirs() }
        var next = (files.list(root, ".mika").mapNotNull { OUT_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        repeat(100) {
            val rel = ".mika/out-$next.txt"
            try {
                files.createNew(root, rel, under = ".mika").use { it.write(text.toByteArray(Charsets.UTF_8)) }
                return "${SandboxPaths.WORKSPACE}/$rel"
            } catch (e: SandboxError) {
                if (e.code != WorkspaceFileGate.FILE_EXISTS) throw IOException("cannot save output: ${e.code}")
                next++
            }
        }
        throw IOException("cannot save output: too many files")
    }

    /** Reads the manifest bundled with the app: the one in the sandbox can be modified by commands and cannot be trusted. */
    override fun scriptManifest(skillPath: String): List<ScriptEntry> {
        val bytes = skillFiles()["$skillPath/scripts/manifest.json"] ?: return emptyList()
        return ScriptManifest.parse(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun prepare() {
        try { sync() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "skill sync failed", e) }
    }

    private val bundledClient: ByteArray? by lazy(clientScript)

    /** The installed root filesystem version may be unchanged, but the bridge client in the APK has been updated. */
    private fun syncClientScript() {
        val expected = bundledClient ?: return
        val rel = "usr/local/bin/mika"
        val matches = runCatching {
            dirs.gate.openRead(dirs.rootfs, rel).use { it.size == expected.size.toLong() && it.read(0, expected.size).contentEquals(expected) }
        }.getOrDefault(false)
        if (!matches || !File(dirs.rootfs, rel).canExecute()) dirs.gate.replace(dirs.rootfs, rel, expected, mode = 493 /* 0755 */)
    }

    override suspend fun verifyScript(skillPath: String, file: String): Boolean {
        val rel = "$skillPath/$file"
        val expected = skillFiles()[rel] ?: return false
        if (withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }) return true
        prepare()
        return withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }
    }

    /** `mika share` and share_file follow the same path rules. */
    private fun bridgeFor(workspace: String, network: Boolean, onProgress: (String) -> Unit, phone: PhoneCommandHandler?): SandboxBridge {
        return SandboxBridge(
        onProgress = onProgress,
        onShare = { path, name ->
            val (f, n) = withContext(Dispatchers.IO) { ShareRules.check(this@AndroidSandbox, workspace, path, name) }
            share(f, n)
        },
        info = {
            buildJsonObject { put("version", version); put("network", network); put("workspace", SandboxPaths.WORKSPACE) }
        },
        onPhone = phone?.let { handler -> { tool, args -> handler.call(tool, args) } },
        )
    }

    /**
     * The launcher's timeout exit code is 124, but the command itself may also exit with 124 and the launcher adds no
     * separate mark. So only a 124 after running the full limit (with a [TIMEOUT_SLACK_MS] margin) counts as a timeout;
     * an early 124 counts as the command's own exit code.
     */
    internal fun launcherTimedOut(exitCode: Int, durationMs: Long, timeoutSec: Int): Boolean =
        exitCode == EXIT_TIMEOUT && durationMs >= timeoutSec * 1000L - TIMEOUT_SLACK_MS

    private fun standIns(): List<String> = dirs.procStandIns.list()?.sorted().orEmpty()

    /** With the network on, writes the current network's DNS into the root filesystem: replaced as a whole through the gate (things in the root filesystem are writable by commands, so an existing symlink is simply replaced). */
    private fun writeResolvConf() {
        val servers = runCatching(dnsServers).getOrNull().orEmpty().ifEmpty { FALLBACK_DNS }
        files.replace(dirs.rootfs, "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" }.toByteArray())
    }

    internal companion object {
        private val OUT_NAME = Regex("^out-(\\d+)\\.txt$")

        private fun versionOf(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

        internal fun dns(context: Context): List<String> {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val lp = cm.getLinkProperties(cm.activeNetwork ?: return emptyList()) ?: return emptyList()
            return lp.dnsServers.mapNotNull { it.hostAddress }
        }

        internal fun treeBytes(root: Path): Long {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
            var total = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
            return total
        }

        /**
         * start() runs on the dedicated thread, everything else on IO threads: read output (capped at 1 MB) and wait for
         * the end. Kill it forcibly if it still has not ended at [limitMs]. On coroutine cancellation send SIGTERM first
         * (the launcher clears the whole process tree on receipt), then SIGKILL if it is still alive after
         * [DESTROY_GRACE_MS].
         */
        internal suspend fun launch(
            cmd: List<String>, env: Map<String, String>, limitMs: Long,
            start: (ProcessBuilder) -> Process = ProcessBuilder::start,
        ): ProcOutcome {
            val begin = System.nanoTime()
            // The start itself is not cancellable. withContext discards the result if the original coroutine is already
            // cancelled when handing it back, so the process is recorded inside the block: even a lost result shows what to kill
            var started: Process? = null
            try {
                withContext(launcherThread + NonCancellable) {
                    started = start(ProcessBuilder(cmd).redirectErrorStream(true).apply {
                        environment().clear()
                        environment().putAll(env)
                    })
                }
            } catch (e: CancellationException) {
                started?.let(::abandon)
                throw e
            }
            val p = started!!
            // Output is read on a plain thread: a blocking read cannot be cancelled, so putting it in a coroutine would make cancellation wait indefinitely
            val collector = OutputCollector()
            val reader = thread(isDaemon = true, name = "mika-sandbox-output") { runCatching { p.inputStream.use(collector::drain) } }
            try {
                // Cancelled during startup: throw here and the catch below kills the process just started
                currentCoroutineContext().ensureActive()
                p.outputStream.close()
                val finished = runInterruptible(Dispatchers.IO) { p.waitFor(limitMs, TimeUnit.MILLISECONDS) }
                if (!finished) p.destroyForcibly()
                val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
                // Normally the launcher has cleared the whole process tree before exiting and the pipe closes with it; should a descendant still hold the pipe, do not wait forever
                runInterruptible(Dispatchers.IO) { reader.join(READER_GRACE_MS) }
                // A descendant still holds the pipe: close our end and the reader thread ends with it
                if (reader.isAlive) runCatching { p.inputStream.close() }
                val (bytes, dropped) = collector.snapshot()
                return ProcOutcome(code, bytes, dropped, !finished, (System.nanoTime() - begin) / 1_000_000)
            } catch (e: CancellationException) {
                abandon(p)
                throw e
            }
        }

        /** SIGTERM first (the launcher clears the whole process tree on receipt), then SIGKILL if it is still alive after [DESTROY_GRACE_MS]. */
        private fun abandon(p: Process) {
            p.destroy()
            thread(isDaemon = true, name = "mika-sandbox-reaper") { if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
    }
}

/** SIGTERM the launcher first (it clears the whole process tree), SIGKILL if it has not exited within [DESTROY_GRACE_MS]; blocks until the process is gone. */
private fun stopNow(p: Process) {
    runCatching { p.outputStream.close() }
    p.destroy()
    if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
    }
}

/** The long-lived process of [AndroidSandbox.openProcess]: on close, SIGTERM the launcher first (it clears the whole process tree), then SIGKILL as a fallback, and return the lease. */
private class SandboxProcessHandle(
    private val process: Process,
    private val lease: com.wangzan101.mika.core.workspace.SandboxTree.Lease,
) : SandboxProcess {
    override val stdin: java.io.OutputStream get() = process.outputStream
    override val stdout: java.io.InputStream get() = process.inputStream
    override val stderr: java.io.InputStream get() = process.errorStream

    override fun close() {
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        // The lease is returned only after the process tree is gone: a process still writing the workspace must not collide with a reset (a reset revokes first and stops processes itself, without waiting here)
        thread(isDaemon = true, name = "mika-sandbox-stdio-reaper") {
            try {
                if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } finally {
                lease.close()
            }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

/** On close, SIGTERM the launcher first, then SIGKILL as a fallback. The waiting thread returns the lease after the process exits. */
private class SandboxShellHandle(
    private val process: Process,
    override val exited: Deferred<Int>,
    override val revoked: StateFlow<Boolean>,
    private val sizeFile: File,
) : SandboxShell {
    override val input: java.io.OutputStream get() = process.outputStream
    override val output: java.io.InputStream get() = process.inputStream

    override fun resize(cols: Int, rows: Int) {
        if (cols !in 1..1000 || rows !in 1..1000) return
        synchronized(sizeFile) {
            if (process.isAlive) runCatching { sizeFile.writeText("$cols $rows") }
        }
    }

    override fun close() {
        // Android's destroy() signals the process before closing its pipes, but it can
        // still wait on a writer during stream cleanup. Arm the watchdog before calling it.
        thread(isDaemon = true, name = "mika-terminal-reaper") {
            if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        runCatching { process.destroy() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
    }
}
