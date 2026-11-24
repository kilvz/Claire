package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.Project
import java.nio.file.Files

/**
 * How much a workspace (conversation or project) occupies: [dirName] is its directory name under `work/`; [conversationId], [projectId] and [title]
 * identify the conversation or project it belongs to (null when they have been deleted and can no longer be recognized); when [busy] is true a task is running or queued, so it must not be cleared.
 */
data class WorkspaceUsage(
    val dirName: String,
    val conversationId: String?,
    val title: String?,
    val bytes: Long,
    val busy: Boolean,
    val projectId: String? = null,
)

/** Space taken by files and the compute environment: runtime, skills, and each workspace (largest first). */
data class SandboxUsage(val runtime: Long, val skills: Long, val workspaces: List<WorkspaceUsage>) {
    val workspacesTotal: Long get() = workspaces.sumOf { it.bytes }
    val total: Long get() = runtime + skills + workspacesTotal
}

/**
 * Reads and clears the workspaces of each conversation/project. Sizes are computed from regular files in the directory tree and do not follow links (commands inside a workspace can create links, and whatever they point to is never counted);
 * clearing goes through the file gate, deleting by handle without following links, holding the tree lock's exclusive permit ([SandboxDirs.exclusiveWrite]) so it is mutually exclusive with commands and other writes;
 * a workspace is not cleared while its conversation or project has a task running or queued ([active]), so files that a task is using are never deleted. The runtime and skills ship with the App and are not cleared here.
 */
class SandboxUsageReader(
    private val dirs: SandboxDirs,
    /** Stored conversations (ID, title and owning project). */
    private val conversations: () -> List<ConversationSummary>,
    /** Conversations that have a task running or queued. */
    private val active: () -> Collection<String>,
    /** Stored project list (ID and name). */
    private val projects: () -> List<Project> = { emptyList() },
    private val resolveWorkspace: ((String) -> WorkspaceKey)? = null,
) {
    /** Scan the disk once; workspaces may contain many files, so call this on a background thread. */
    fun read(): SandboxUsage {
        val projList = projects()
        val projIds = projList.map { it.id }.toSet()
        val projByDir = projList.associateBy { dirs.workspace(WorkspaceKey.Project(it.id)).name }
        val convList = conversations()
        val convByDir = convList
            .filter { it.projectId == null || it.projectId !in projIds }
            .associateBy { dirs.workspace(WorkspaceKey.Conversation(it.id)).name }
        val busy = busyDirs(convList, projIds)
        val workspaces = dirs.work.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }.map { dir ->
            val p = projByDir[dir.name]
            if (p != null) {
                WorkspaceUsage(dir.name, conversationId = null, p.name, AndroidSandbox.treeBytes(dir.toPath()), dir.name in busy, projectId = p.id)
            } else {
                val c = convByDir[dir.name]
                WorkspaceUsage(dir.name, c?.id, c?.title, AndroidSandbox.treeBytes(dir.toPath()), dir.name in busy)
            }
        }.sortedByDescending { it.bytes }
        return SandboxUsage(AndroidSandbox.treeBytes(dirs.rootfs.toPath()), AndroidSandbox.treeBytes(dirs.skills.toPath()), workspaces)
    }

    /** Clear the workspace named [dirName] under `work/`; if a task is running it is not cleared and false is returned. */
    suspend fun clear(dirName: String): Boolean = dirs.exclusiveWrite {
        // Waiting for the tree permit and switching to IO can both suspend: re-check after acquiring it rather than using the busy snapshot from before the wait.
        if (dirName in busyDirs()) return@exclusiveWrite false
        dirs.deleteWorkDir(dirName)
        true
    }

    /** Clear every workspace that is not in use; returns how many were left behind because a task was running. */
    suspend fun clearAll(): Int = dirs.exclusiveWrite {
        val names = dirs.work.listFiles().orEmpty().map { it.name }
        var busy = 0
        for (name in names) {
            // Deleting earlier workspaces can take a long time: re-check before deleting each workspace.
            if (name in busyDirs()) busy++
            else runCatching { dirs.deleteWorkDir(name) }
        }
        busy
    }

    private fun busyDirs(
        convList: List<ConversationSummary> = conversations(),
        projIds: Set<String> = projects().map { it.id }.toSet(),
    ): Set<String> {
        val resolver = resolveWorkspace
        if (resolver != null) {
            return active().map { dirs.workspace(resolver(it)).name }.toSet()
        }
        val projectOfConv = convList.associate { it.id to it.projectId }
        return active().map { convId ->
            val key = projectOfConv[convId]?.takeIf { it in projIds }?.let(WorkspaceKey::Project)
                ?: WorkspaceKey.Conversation(convId)
            dirs.workspace(key).name
        }.toSet()
    }
}
