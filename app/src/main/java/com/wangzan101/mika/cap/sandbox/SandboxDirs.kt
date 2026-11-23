package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sandbox's directories on the host: the root filesystem, workspaces, skills, and /proc stand-ins under `files/sandbox/`; PRoot's temporary directory is in the cache;
 * executables live only in [nativeDir] (since Android 10, files in the data directory cannot be execve'd).
 */
class SandboxDirs internal constructor(
    val root: File, val tmp: File, val nativeDir: File,
    /** Host-side reads and writes of these directories (which the sandbox can reach) all go through it; on the phone it is [AndroidFdSys.gate], while JVM tests pass the path-based one. */
    val gate: WorkspaceFileGate,
    /** Every write into this tree acquires its permission first; on the phone it is the per-process instance ([SandboxOwner]), while tests supply their own. No default: a private instance cannot lock out others. */
    val tree: SandboxTree,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "sandbox"), File(context.cacheDir, "proot-tmp"), File(context.applicationInfo.nativeLibraryDir),
        AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree,
    )

    val rootfs: File get() = File(root, "rootfs")
    val work: File get() = File(root, "work")
    val skills: File get() = File(root, "skills")
    val procStandIns: File get() = File(root, "proc")
    /** MCP package cache shared by all workspaces and writable by the sandbox (installed npm/pypi packages, not user data). */
    val mcpCache: File get() = File(root, "mcp-cache")

    /** One workspace maps to one directory: projects are `p-<ID>`, conversations are `c-<ID>` or `h-<hash>` (never colliding with `p-`). */
    fun workspace(key: WorkspaceKey): File = File(work, dirName(key))

    fun lib(name: String): File = File(nativeDir, name)

    /**
     * Deleting or replacing an entire directory tree that commands in the sandbox can write to (workspaces, runtimes) goes through here: done on an IO thread holding [tree]'s exclusive permission,
     * starting only after running commands and other writes have finished, and new commands and imports wait until it is done, so nobody writes into a tree while it is being deleted.
     * Cost: however long deleting a large directory takes, sandbox commands in all conversations wait that long. Conditions may change while waiting for the permission (e.g. a conversation gains tasks again),
     * so conditions to check should be re-checked inside [block] after the permission is acquired.
     */
    suspend fun <T> exclusiveWrite(block: suspend () -> T): T = tree.exclusive { withContext(Dispatchers.IO) { block() } }

    /** Deletes the entire workspace for [key] (deleting by handle, not following links; see [exclusiveWrite]). */
    suspend fun deleteWorkspace(key: WorkspaceKey) = deleteWorkDir(workspace(key).name)

    /**
     * Deletes the workspace named [dirName] under `work/`. Returns immediately if it does not exist (nothing was ever written there), without taking the exclusive permission:
     * taking the exclusive permission revokes all leases ([SandboxTree.lease]), stopping terminals open in other conversations and running stdio MCP servers, yet there is nothing to delete here.
     * This check happens outside the permission: a write that already holds a shared permission but has not created the directory yet (commands, attachment imports, saving screens) no longer waits for it to finish,
     * so the workspace it creates stays behind, listed as "Deleted conversations" under "Used space" in settings, and can be cleaned up.
     */
    suspend fun deleteWorkDir(dirName: String) {
        if (withContext(Dispatchers.IO) { !Files.exists(File(work, dirName).toPath(), LinkOption.NOFOLLOW_LINKS) }) return
        exclusiveWrite { gate.deleteTree(work, dirName) }
    }

    /**
     * Create every directory to be bound before binding: if a source directory does not exist, PRoot writes a warning to stderr,
     * and stderr is merged into the command output, so the model would see it. The skills directory is just an empty directory until it is synced.
     */
    fun ensureBindSources(workspace: File) {
        for (d in listOf(workspace, skills, tmp, mcpCache)) d.mkdirs()
    }

    private fun dirName(key: WorkspaceKey): String = when (key) {
        is WorkspaceKey.Project -> "p-${key.id}"
        is WorkspaceKey.Conversation -> conversationDirName(key.id)
    }

    private fun conversationDirName(id: String): String =
        if (SAFE_ID.matches(id)) "c-$id"
        else "h-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
