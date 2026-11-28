package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Result of copying a branch workspace: [skipped] lists rejected items (symlinks, out-of-bounds paths, non-regular files); when [truncated] is true some directory exceeded its limit and none of its files were copied. */
data class BranchCopyResult(
    val copied: Int,
    val skipped: Int = 0,
    /** The limit was exceeded and the copy was aborted. */
    val truncated: Boolean = false,
)

/** When retrying, the outcome of each requested attachment in this copy. */
enum class RetryAttachmentStatus { COPIED, MISSING, OVER_LIMIT, FAILED }

data class RetryAttachmentResult(val path: String, val status: RetryAttachmentStatus, val sizeBytes: Long? = null)

/** Copies the source conversation workspace when branching (user attachments in inbox/ and generated audio, images and video in out/audio, out/images, out/video); injected by the chat view at stage 2. */
interface BranchWorkspace {
    /**
     * Copies the source conversation workspace's inbox/ and generated audio, images and video (out/audio, out/images, out/video); creates the target workspace if absent.
     * The four directories are copied independently with their own [maxBytes]; absent source directories and failed copies are left out without affecting the rest, and the result sums the remaining directories.
     * Returns null when source and target are the same workspace, the source workspace/sandbox is unavailable, or none of the four directories produced a result; existing target directories are not overwritten and each counts as skipped = 1.
     */
    suspend fun copyInbox(
        sourceId: String,
        targetId: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): BranchCopyResult?

    /** Copies the inbox once and returns the result for each sandbox path in input order; only files actually committed this time are COPIED. */
    suspend fun copyInboxForRetry(
        sourceId: String,
        targetId: String,
        paths: List<String>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): List<RetryAttachmentResult> = paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }

    /** Checks before sending whether the files in the target workspace can still be read safely. COPIED means currently readable. */
    suspend fun checkInboxAttachments(targetId: String, paths: List<String>): List<RetryAttachmentResult> =
        paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }

    companion object {
        /** Default byte limit for branching (per directory) and for re-asking (total for one copy). */
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
    }
}

/** The phone implementation: both workspaces live under files/sandbox/, and copying takes the same tree lock, so it is mutually exclusive with resetting and with tasks sharing the workspace. */
class AndroidBranchWorkspace(
    private val dirs: SandboxDirs,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::Conversation,
) : BranchWorkspace {
    constructor(context: Context) : this(SandboxDirs(context), WorkspaceResolver.resolver(context))

    override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? =
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                val src = resolveWorkspace(sourceId)
                val dst = resolveWorkspace(targetId)
                if (src == dst) return@withContext null
                val srcDir = dirs.workspace(src)
                val dstDir = dirs.workspace(dst)
                // Copy the four directories independently: one that is absent or fails returns null without affecting the others
                val results = listOfNotNull(
                    copyInboxTree(srcDir, dstDir, maxBytes),
                    copyGeneratedAudioTree(srcDir, dstDir, maxBytes),
                    copyGeneratedImagesTree(srcDir, dstDir, maxBytes),
                    copyGeneratedVideoTree(srcDir, dstDir, maxBytes),
                )
                if (results.isEmpty()) return@withContext null
                BranchCopyResult(
                    copied = results.sumOf { it.copied },
                    skipped = results.sumOf { it.skipped },
                    truncated = results.any { it.truncated },
                )
            }
        }

    override suspend fun copyInboxForRetry(
        sourceId: String, targetId: String, paths: List<String>, maxBytes: Long,
    ): List<RetryAttachmentResult> = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val src = resolveWorkspace(sourceId)
            val dst = resolveWorkspace(targetId)
            if (src == dst) return@withContext checkRetryAttachments(dirs.workspace(dst), paths, dirs.gate)
            val context = currentCoroutineContext()
            copyRetryAttachments(dirs.workspace(src), dirs.workspace(dst), paths, maxBytes, dirs.gate) { context.ensureActive() }
        }
    }

    override suspend fun checkInboxAttachments(targetId: String, paths: List<String>): List<RetryAttachmentResult> =
        dirs.tree.shared { withContext(Dispatchers.IO) { checkRetryAttachments(dirs.workspace(resolveWorkspace(targetId)), paths, dirs.gate) } }
}

private const val INBOX = "inbox"
private const val INBOX_PREFIX = "${SandboxPaths.WORKSPACE}/inbox/"

private fun retryRelative(path: String): String? {
    if (!path.startsWith(INBOX_PREFIX)) return null
    val parts = path.removePrefix(INBOX_PREFIX).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return null
    return "inbox/" + parts.joinToString("/")
}

private fun failureStatus(e: Exception): RetryAttachmentStatus =
    if (e is SandboxError && e.code == WorkspaceFileGate.FILE_NOT_FOUND) RetryAttachmentStatus.MISSING
    else RetryAttachmentStatus.FAILED

internal fun checkRetryAttachments(workspace: File, paths: List<String>, gate: WorkspaceFileGate): List<RetryAttachmentResult> =
    paths.map { path ->
        val rel = retryRelative(path)
        if (rel == null) RetryAttachmentResult(path, RetryAttachmentStatus.FAILED)
        else try {
            gate.openRead(workspace, rel).use { RetryAttachmentResult(path, RetryAttachmentStatus.COPIED, it.currentSize()) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            RetryAttachmentResult(path, failureStatus(e))
        }
    }

/** Copies the requested items first, then the remaining historical attachments; each target file is committed independently, and a failure deletes only the files created this time. */
internal fun copyRetryAttachments(
    source: File, target: File, paths: List<String>, maxBytes: Long, gate: WorkspaceFileGate,
    ensureActive: () -> Unit = {},
): List<RetryAttachmentResult> {
    val requested = paths.distinct()
    val all = LinkedHashSet(requested)
    val inbox = File(source, INBOX)
    fun collect(dir: File, prefix: String) {
        ensureActive()
        if (Files.isSymbolicLink(dir.toPath())) return
        for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (Files.isSymbolicLink(child.toPath())) continue
            val path = "$prefix${child.name}"
            if (child.isDirectory) collect(child, "$path/")
            else if (child.isFile && retryRelative(path) != null) all.add(path)
        }
    }
    collect(inbox, INBOX_PREFIX)
    var remaining = maxBytes.coerceAtLeast(0)
    val results = mutableMapOf<String, RetryAttachmentResult>()
    for (path in all) {
        ensureActive()
        val rel = retryRelative(path)
        if (rel == null) {
            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.FAILED)
            continue
        }
        var sourceOpened = false
        try {
            gate.openRead(source, rel).use { input ->
                sourceOpened = true
                if (input.currentSize() > remaining) {
                    results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.OVER_LIMIT)
                } else {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create target workspace")
                    gate.createNew(target, rel).use { output ->
                        try {
                            var written = 0L
                            val buffer = ByteArray(64 * 1024)
                            val stream = input.input()
                            while (true) {
                                ensureActive()
                                val n = stream.read(buffer)
                                if (n < 0) break
                                if (n.toLong() > remaining - written) throw RetryLimitException()
                                output.write(if (n == buffer.size) buffer else buffer.copyOf(n))
                                written += n
                            }
                            remaining -= written
                            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.COPIED, written)
                        } catch (e: Throwable) {
                            output.delete()
                            throw e
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: RetryLimitException) {
            results[path] = RetryAttachmentResult(path, RetryAttachmentStatus.OVER_LIMIT)
        } catch (e: Exception) {
            results[path] = RetryAttachmentResult(path, if (sourceOpened) RetryAttachmentStatus.FAILED else failureStatus(e))
        }
    }
    return paths.map { results.getValue(it) }
}

private class RetryLimitException : IOException()

/**
 * Copies [source]/inbox into [target]/inbox: only regular files, symlinks are not followed, and canonical paths must still be inside their own directories;
 * it copies into target/.inbox-tmp-<uuid> first and renames only after everything succeeded. Exceeding [maxBytes] aborts the copy and deletes the temporary directory,
 * returning `BranchCopyResult(copied = 0, truncated = true)`, leaving no partial work behind.
 * Returns null when the source has no inbox; an existing target inbox is not overwritten and `BranchCopyResult(copied = 0, skipped = 1)` is returned;
 * an error during copying also returns null. Does not take the tree lock; the phone implementation calls it inside the lock.
 */
internal fun copyInboxTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyTree(source, target, INBOX, maxBytes, ".inbox-tmp-")

/** Also carries over the images generated by this turn when branching: copies [source]/out/images to [target]/out/images (same rules as inbox). */
internal fun copyGeneratedImagesTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyTree(source, target, "out/images", maxBytes, ".images-tmp-")

private fun copyTree(source: File, target: File, sub: String, maxBytes: Long, tmpPrefix: String): BranchCopyResult? {
    val sourceDir = File(source, sub)
    if (!sourceDir.isDirectory || Files.isSymbolicLink(sourceDir.toPath())) return null
    val sourceRoot = sourceDir.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, sub)
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    dest.parentFile?.let { mkdirs(it.toPath()) }
    val tmp = File(target, "$tmpPrefix${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // Pipes, sockets and anything else user attachments never contain are not copied
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceDir, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

internal fun copyGeneratedAudioTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyGeneratedTree(source, target, "out/audio", "audio", maxBytes)

internal fun copyGeneratedVideoTree(source: File, target: File, maxBytes: Long): BranchCopyResult? =
    copyGeneratedTree(source, target, "out/video", "video", maxBytes)

private fun copyGeneratedTree(source: File, target: File, outDir: String, tag: String, maxBytes: Long): BranchCopyResult? {
    val sourceInbox = File(source, outDir)
    if (!sourceInbox.isDirectory || Files.isSymbolicLink(sourceInbox.toPath())) return null
    val sourceRoot = sourceInbox.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, outDir)
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    // A new branch workspace has no out/ yet; without creating it first the final rename fails
    dest.parentFile?.let { mkdirs(it.toPath()) }
    val tmp = File(target, ".$tag-tmp-${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // Pipes, sockets and anything else user attachments never contain are not copied
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceInbox, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

/** Copies one regular file, writing at most [max] bytes; returns null when exceeded (the caller deletes the whole temporary directory). */
private fun copyFile(source: File, dest: Path, max: Long): Long? {
    dest.parent?.let(::mkdirs)
    var total = 0L
    Files.newInputStream(source.toPath()).use { input ->
        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                output.write(buf, 0, n)
            }
        }
    }
    return total
}

private fun mkdirs(path: Path) {
    val f = path.toFile()
    if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create directory: $path")
}
