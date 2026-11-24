package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.util.UUID

/**
 * Staging area for web uploads: a file in the workspace is opened once through [gate], verified on the handle, and copied from that handle into [root]/<random>/.
 * [root] lives in the app's private cache directory, which the sandbox cannot reach; only this copy is handed to the page, so whatever happens to the workspace file after the check cannot affect it.
 */
class UploadStaging(
    private val root: File,
    private val gate: WorkspaceFileGate,
    private val maxFileBytes: Long = 100L shl 20,
    private val maxTotalBytes: Long = 200L shl 20,
) {
    /** Copy for a single upload: the whole [dir] directory is deleted after use. */
    class Staged(val dir: File, val files: List<File>)

    sealed interface Result {
        class Ok(val staged: Staged) : Result
        /** [error]: path_outside_workspace, file_not_found, linked_file, file_too_large. */
        class Refused(val error: String, val path: String) : Result
    }

    fun stage(workspaceDir: File, paths: List<String>): Result {
        val dir = File(root, UUID.randomUUID().toString())
        val out = mutableListOf<File>()
        var total = 0L
        try {
            for ((i, p) in paths.withIndex()) {
                val rel = SandboxPaths.normalize(p)?.takeIf(SandboxPaths::isWritable)?.removePrefix(SandboxPaths.WORKSPACE)?.trimStart('/')
                    ?.takeIf { it.isNotEmpty() } ?: return refuse(dir, "path_outside_workspace", p)
                val g = try { gate.openRead(workspaceDir, rel) } catch (e: SandboxError) { return refuse(dir, codeOf(e), p) }
                g.use {
                    if (g.size > maxFileBytes || total + g.size > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                    // One directory per file: files with the same name in different directories each keep their original name
                    val target = File(File(dir, i.toString()).apply { mkdirs() }, g.name)
                    var copied = 0L
                    target.outputStream().use { o ->
                        val input = g.input()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            // The file grew after verification: it still counts against the limits
                            if (copied > maxFileBytes || total + copied > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                            o.write(buf, 0, n)
                        }
                    }
                    total += copied
                    out += target
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return Result.Ok(Staged(dir, out))
    }

    fun delete(staged: Staged) {
        staged.dir.deleteRecursively()
    }

    /** Deletes every copy (clears what a previous process left behind at startup). */
    fun clear() {
        root.deleteRecursively()
    }

    private fun refuse(dir: File, error: String, path: String): Result {
        dir.deleteRecursively()
        return Result.Refused(error, path)
    }

    private fun codeOf(e: SandboxError) = when (e.code) {
        WorkspaceFileGate.OUTSIDE -> "path_outside_workspace"
        WorkspaceFileGate.LINKED_FILE -> WorkspaceFileGate.LINKED_FILE
        else -> "file_not_found"
    }

    companion object {
        const val FILE_TOO_LARGE = "file_too_large"
        /** Staging area under the cache directory; the FileProvider exposes only this directory (res/xml/web_files.xml). */
        const val DIR = "webupload"
    }
}
