package com.wangzan101.mika.files.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.SharedDraftCopy
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.wangzan101.mika.chat.ui.AttachFiles
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.PendingAttachment
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.voice.ui.nameOf

/** A file library source; workspace paths must be paths under /workspace/. */
sealed interface LibraryFileHandle {
    data class Workspace(val conversationId: String, val path: String) : LibraryFileHandle
    data class Exported(val file: SharedFile) : LibraryFileHandle
}

enum class LibraryAttachmentResult { Added, Full, Unavailable, Failed }

/** Plan made before a batch add; [accepted] already has duplicate sources removed and is trimmed to the remaining slots of the target draft. */
data class LibraryAddPlan(
    val accepted: List<LibraryFileHandle>,
    val duplicates: Int,
    val overLimit: Int,
)

/** An item that has been copied and is waiting to be merged; [release] frees the copy when it is not accepted. */
data class LibraryStaged(
    val attachment: PendingAttachment,
    val source: LibraryFileHandle,
    val release: () -> Unit,
)

/** Result of merging a batch of files into a draft; [unavailable] means the target draft cannot take the batch at all. */
data class LibraryMergeResult(
    val added: Int,
    val duplicates: Int,
    val full: Int,
    val unavailable: Boolean = false,
)

/** Final result of a file library batch add, used by the UI to summarize the outcome. */
data class LibraryBatchOutcome(
    val added: Int,
    val duplicates: Int,
    val overLimit: Int,
    val failed: List<String>,
    val unavailable: Boolean = false,
) {
    val accepted: Int get() = added + duplicates
}

/** Result of a file library multi-select share; [shared] is the number of files handed to the system share sheet. */
data class LibraryShareOutcome(val shared: Int, val failed: List<String>)

/** File library operations. Workspace files are copied through the gate; exported files are previewed and shared using their original MediaStore URI. */
object LibraryFileActions {
    private val startupLock = Any()
    private var orphanCleanupDone = false

    /** Plain attachment drafts are not restored across processes; before this process creates its first copy, copies left behind by the previous process are cleaned up. */
    private fun cleanupOrphanedDraftCopies(context: Context) = synchronized(startupLock) {
        if (!orphanCleanupDone) {
            File(context.filesDir, "library-attachments").listFiles()?.forEach { it.deleteRecursively() }
            orphanCleanupDone = true
        }
    }

    /** An immediate check that only reflects readability at check time; later operations still reopen the source. */
    suspend fun isAvailable(context: Context, source: LibraryFileHandle): Boolean = withContext(Dispatchers.IO) {
        try {
            when (source) {
                is LibraryFileHandle.Workspace -> {
                    require(source.conversationId.isNotBlank())
                    val rel = requireNotNull(AttachFiles.relative(source.path))
                    val dirs = SandboxDirs(context)
                    dirs.gate.openRead(dirs.workspace(WorkspaceResolver.resolveFor(context, source.conversationId)), rel).use { it.input().read() }
                }
                is LibraryFileHandle.Exported -> {
                    val uri = Uri.parse(source.file.uri)
                    require(uri.scheme == "content" && uri.authority == "media")
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { it.read() }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun preview(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.open(context, uri, name) }

    suspend fun share(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.share(context, uri, name) }

    /**
     * Multi-select share: workspace files are first copied into the cache and converted to FileProvider URIs, while exported files use their original URI directly.
     * When some files fail to prepare, the remaining files are still shared and the failures are left to the UI to summarize.
     */
    suspend fun shareAll(
        context: Context,
        sources: List<LibraryFileHandle>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryShareOutcome {
        val caller = currentCoroutineContext()
        val staged = mutableListOf<File>()
        var opened = false
        return withContext(NonCancellable) {
            try {
                val uris = ArrayList<Uri>(sources.size)
                val failed = mutableListOf<String>()
                withContext(Dispatchers.Main.immediate) { onProgress(0, sources.size) }
                sources.forEachIndexed { index, source ->
                    caller.ensureActive()
                    val uri = prepareShare(context, source, staged)
                    if (uri == null) failed += nameOf(source) else uris += uri
                    withContext(Dispatchers.Main.immediate) { onProgress(index + 1, sources.size) }
                }
                if (uris.isEmpty()) return@withContext LibraryShareOutcome(0, failed)
                val started = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    startShare(context, uris)
                }
                if (!started) {
                    staged.forEach { runCatching { it.parentFile?.deleteRecursively() } }
                    return@withContext LibraryShareOutcome(0, sources.map(::nameOf))
                }
                opened = true
                LibraryShareOutcome(uris.size, failed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!opened) staged.forEach { runCatching { it.parentFile?.deleteRecursively() } }
                throw e
            } catch (_: Exception) {
                if (!opened) staged.forEach { runCatching { it.parentFile?.deleteRecursively() } }
                LibraryShareOutcome(0, sources.map(::nameOf))
            }
        }
    }

    private suspend fun prepareShare(
        context: Context,
        source: LibraryFileHandle,
        staged: MutableList<File>,
    ): Uri? = when (source) {
        is LibraryFileHandle.Exported ->
            if (isAvailable(context, source)) Uri.parse(source.file.uri) else null
        is LibraryFileHandle.Workspace -> {
            val copy = stage(context, source, forDraft = false) ?: return null
            staged += copy.file
            Uri.parse(copy.attachment.uri)
        }
    }

    private fun startShare(context: Context, uris: List<Uri>): Boolean = try {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.setType(shareMime(uris.map { runCatching { context.contentResolver.getType(it) }.getOrNull() }))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData makes the grant cover all URIs; some receivers also rely on it to obtain file names and types.
        val clip = runCatching { ClipData.newUri(context.contentResolver, "", uris.first()) }
            .getOrElse { ClipData.newRawUri("", uris.first()) }
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.file_library_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Copy the source first, then merge into the target draft on the main thread; switching conversations during the copy does not change the target. */
    suspend fun addToDraft(context: Context, viewModel: ChatViewModel, targetConversationId: String,
                           source: LibraryFileHandle): LibraryAttachmentResult {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = true) ?: return@withContext LibraryAttachmentResult.Failed
            var accepted = false
            try {
                val result = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    viewModel.addLibraryAttachment(targetConversationId, copy.attachment, source) { copy.file.parentFile?.deleteRecursively() }
                }
                accepted = result == LibraryAttachmentResult.Added
                result
            } finally {
                if (!accepted) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    /**
     * Batch add: plan against the target draft first, then copy one by one, and merge once at the end.
     * If cancelled during copying, it stops once the current file has finished copying and releases every copy that was not merged.
     */
    suspend fun addAllToDraft(
        context: Context,
        viewModel: ChatViewModel,
        targetConversationId: String,
        sources: List<LibraryFileHandle>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryBatchOutcome {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val plan = withContext(Dispatchers.Main.immediate) {
                caller.ensureActive()
                viewModel.planLibraryAttachments(targetConversationId, sources)
            } ?: return@withContext LibraryBatchOutcome(0, 0, 0, emptyList(), unavailable = true)
            val staged = mutableListOf<LibraryStaged>()
            val failed = mutableListOf<String>()
            // Once the merge call returns, the copies belong to ChatViewModel; only the ones not yet merged are released on cancellation or exception.
            var committed = false
            try {
                withContext(Dispatchers.Main.immediate) { onProgress(0, plan.accepted.size) }
                plan.accepted.forEachIndexed { index, source ->
                    caller.ensureActive()
                    val copy = stage(context, source, forDraft = true)
                    if (copy == null) failed += nameOf(source)
                    else staged += LibraryStaged(copy.attachment, source) { copy.file.parentFile?.deleteRecursively() }
                    withContext(Dispatchers.Main.immediate) { onProgress(index + 1, plan.accepted.size) }
                }
                val merged = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    viewModel.addLibraryAttachments(targetConversationId, staged, plan.overLimit).also { committed = true }
                }
                LibraryBatchOutcome(
                    added = merged.added,
                    duplicates = plan.duplicates + merged.duplicates,
                    overLimit = plan.overLimit + merged.full,
                    failed = failed,
                    unavailable = merged.unavailable,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!committed) staged.forEach { runCatching { it.release() } }
                throw e
            } catch (_: Exception) {
                if (!committed) staged.forEach { runCatching { it.release() } }
                LibraryBatchOutcome(0, plan.duplicates, plan.overLimit, failed, unavailable = false)
            }
        }
    }

    private fun nameOf(source: LibraryFileHandle): String = when (source) {
        is LibraryFileHandle.Workspace -> source.path.substringAfterLast('/')
        is LibraryFileHandle.Exported -> source.file.name
    }

    private suspend fun show(context: Context, source: LibraryFileHandle, open: (Uri, String) -> Unit): Boolean {
        if (source is LibraryFileHandle.Exported) {
            if (!isAvailable(context, source)) return false
            return try {
                withContext(Dispatchers.Main.immediate) {
                    open(Uri.parse(source.file.uri), source.file.name)
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = false) ?: return@withContext false
            var opened = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    open(Uri.parse(copy.attachment.uri), copy.attachment.name)
                }
                opened = true
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                if (!opened) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    /**
     * File name of the copy: path separators and control characters become `_`, and it is truncated by UTF-8 bytes (the file name limit is 255 bytes, and `.tmp` is appended during the copy),
     * keeping the extension and never splitting a character (both draft insertion and preview identify the type by extension); this is the same function used for export and imported attachments.
     */
    internal fun copyName(raw: String): String =
        ShareRules.fitName(raw.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_"))
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

    private data class Copy(val file: File, val attachment: PendingAttachment)

    private suspend fun stage(context: Context, source: LibraryFileHandle, forDraft: Boolean): Copy? =
        withContext(Dispatchers.IO + NonCancellable) {
            cleanupOrphanedDraftCopies(context)
            val name = copyName(when (source) {
                is LibraryFileHandle.Workspace -> source.path.substringAfterLast('/')
                is LibraryFileHandle.Exported -> source.file.name
            })
            val root = if (forDraft) File(context.filesDir, "library-attachments") else File(context.cacheDir, "library-viewing")
            if (!forDraft) {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            }
            val dir = File(root, UUID.randomUUID().toString())
            try {
                check(dir.mkdirs())
                val file = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                when (source) {
                    is LibraryFileHandle.Workspace -> {
                        require(source.conversationId.isNotBlank())
                        val rel = requireNotNull(AttachFiles.relative(source.path))
                        val dirs = SandboxDirs(context)
                        dirs.gate.openRead(dirs.workspace(WorkspaceResolver.resolveFor(context, source.conversationId)), rel).use { gated ->
                            SharedDraftCopy.copy(gated.input(), tmp, file) { dir.usableSpace }
                        }
                    }
                    is LibraryFileHandle.Exported -> {
                        val uri = Uri.parse(source.file.uri)
                        require(uri.scheme == "content" && uri.authority == "media")
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            SharedDraftCopy.copy(input, tmp, file) { dir.usableSpace }
                        }
                    }
                }
                dir.setLastModified(System.currentTimeMillis())
                val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file)
                Copy(file, PendingAttachment(uri.toString(), name, file.length()))
            } catch (_: Exception) {
                dir.deleteRecursively()
                null
            }
        }
}

/** MIME used when sharing: image/* when all are images, the concrete type when they are all the same, and */* when mixed or unknown. */
internal fun shareMime(types: List<String?>): String {
    if (types.isEmpty() || types.any { it.isNullOrBlank() }) return "*/*"
    val known = types.filterNotNull()
    return when {
        known.size == 1 -> known.first()
        known.all { it.startsWith("image/") } -> "image/*"
        known.distinct().size == 1 -> known.first()
        else -> "*/*"
    }
}
