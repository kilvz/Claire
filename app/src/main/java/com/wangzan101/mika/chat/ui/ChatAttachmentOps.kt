package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.files.ui.LibraryAddPlan
import com.wangzan101.mika.files.ui.LibraryAttachmentResult
import com.wangzan101.mika.files.ui.LibraryFileHandle
import com.wangzan101.mika.files.ui.LibraryMergeResult
import com.wangzan101.mika.files.ui.LibraryStaged

/** Attachments above the input box: camera, album, and file picker; device files; file library. */
internal class ChatAttachmentOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val drafts = session.drafts
    private var draftStateChanged by session::draftStateChanged
    private val libraryCopies = session.libraryCopies
    private val librarySources = session.librarySources
    private val libraryImports = session.libraryImports
    private val deviceImports = session.deviceImports
    private var sharedDraftSerial by session::sharedDraftSerial
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private val scope = vm.scope
    private val activeConversations = vm.activeConversations
    private val sandboxReady = vm.sandboxReady
    private val describeAttachments = vm.describeAttachments
    private val sharedDrafts = vm.sharedDrafts

    /** A file from the camera, gallery, or file picker: accepted only when attachments are allowed (see [ChatSession.canAttach]) and the sandbox is ready; once its name and size are read it sits above the input bar and goes out with the next message. */
    fun attach(uris: List<String>, conversationId: String? = _ui.value.conversationId) {
        if (uris.isEmpty() || conversationId != _ui.value.conversationId || !session.canAttach(_ui.value)) return
        val id = session.draftConversation()
        val sharedBefore = _ui.value.sharedDraft
        val serialBefore = sharedDraftSerial
        // With no front desk (no front desk attached, or a subtask conversation) the attachment starts a new task directly: if the conversation starts a task while the file is being read it is not accepted; with a front desk it is accepted whether or not a task is running
        val wasIdle = vm.deskFor(id) == null && _ui.value.composer == ComposerMode.Idle
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (wasIdle && _ui.value.composer != ComposerMode.Idle) return@launch
            if (_ui.value.conversationId == id && (!session.canAttach(_ui.value) || sharedDraftSerial != serialBefore)) return@launch
            if (_ui.value.conversationId != id && drafts.value[id]?.sharedDraft != sharedBefore) return@launch
            session.updateDraft(id) { draft ->
                val (all, dropped) = AttachTray.add(draft.attachments, items)
                draft.copy(attachments = all, overLimit = dropped, rejected = emptyList())
            }
        }
    }

    /** Copy selected device files into the conversation that opened the browser. */
    internal fun importDeviceAttachments(conversationId: String, paths: List<String>,
        copy: suspend (String, String) -> com.wangzan101.mika.core.workspace.DeviceFileImport) {
        if (_ui.value.conversationId != conversationId || !session.canAttach(_ui.value) || paths.isEmpty()) return
        val slots = (AttachTray.LIMIT - _ui.value.attachments.size).coerceAtLeast(0)
        val selected = paths.distinct().take(slots)
        val dropped = (paths.distinct().size - selected.size).coerceAtLeast(0)
        if (selected.isEmpty()) {
            session.updateDraft(conversationId) { it.copy(overLimit = dropped) }
            return
        }
        session.updateDraft(conversationId) { it.copy(importing = true, rejected = emptyList(), overLimit = dropped) }
        val job = scope.launch {
            val added = mutableListOf<PendingAttachment>()
            val failures = mutableListOf<AttachmentFailure>()
            try {
                for (path in selected) {
                    if (conversationId in _deleted.value || conversationId in deleting) break
                    try {
                        val result = copy(path, conversationId)
                        added += PendingAttachment("workspace:$conversationId:${result.path}", result.path.substringAfterLast('/'), result.size,
                            workspaceConversationId = conversationId, workspacePath = result.path)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        val reason = when ((e as? com.wangzan101.mika.core.workspace.SandboxError)?.code) {
                            "too_large" -> AttachmentFailureReason.TOO_LARGE
                            "low_device_space" -> AttachmentFailureReason.LOW_DEVICE_SPACE
                            "workspace_limit" -> AttachmentFailureReason.WORKSPACE_LIMIT
                            else -> AttachmentFailureReason.IO_ERROR
                        }
                        failures += AttachmentFailure(path.substringAfterLast('/'), reason)
                    }
                }
            } finally {
                session.updateDraft(conversationId) { draft ->
                    val (merged, overflow) = AttachTray.add(draft.attachments, added)
                    draft.copy(importing = false, attachments = merged, rejected = failures, overLimit = draft.overLimit + overflow)
                }
            }
        }
        deviceImports[conversationId] = job
        job.invokeOnCompletion { deviceImports.remove(conversationId, job) }
    }

    /** Allocates a draft ID for the current new conversation; the caller invokes this on the main thread. */
    fun libraryTargetConversation(): String = session.draftConversation()

    /** Whether the target draft can accept file library files right now; returns null when it cannot. The caller invokes this on the main thread. */
    private fun libraryDraft(conversationId: String): ComposerDraft? {
        if (conversationId.isBlank() || conversationId in _deleted.value || conversationId in deleting || !sandboxReady.value)
            return null
        val current = _ui.value.takeIf { it.conversationId == conversationId }
        if (current != null && !session.canAttach(current)) return null
        val draft = current?.draft() ?: drafts.value[conversationId] ?: ComposerDraft()
        if (draft.importing || (current == null && conversationId in activeConversations.value)) return null
        return draft
    }

    /**
     * Planning before a batch add: drop sources already present in the target draft, in the order passed in, then truncate to the remaining slots.
     * No files are copied; the merge validates again against the target state at that time. The caller invokes this on the main thread.
     */
    fun planLibraryAttachments(conversationId: String, sources: List<LibraryFileHandle>): LibraryAddPlan? {
        val draft = libraryDraft(conversationId) ?: return null
        var slots = (AttachTray.LIMIT - draft.attachments.size).coerceAtLeast(0)
        var duplicates = 0
        var overLimit = 0
        val accepted = mutableListOf<LibraryFileHandle>()
        val seen = mutableSetOf<LibraryFileHandle>()
        for (source in sources) {
            val duplicate = !seen.add(source) || draft.attachments.any { librarySources[it.uri] == source }
            when {
                duplicate -> duplicates++
                slots <= 0 -> overLimit++
                else -> {
                    accepted += source
                    slots--
                }
            }
        }
        return LibraryAddPlan(accepted, duplicates, overLimit)
    }

    /**
     * Merges a batch of already-copied files into the target draft; the draft is updated only once.
     * Accepted copies are registered in [libraryCopies], and those not accepted are released on the spot. The caller invokes this on the main thread.
     */
    fun addLibraryAttachments(conversationId: String, items: List<LibraryStaged>, overLimit: Int = 0): LibraryMergeResult {
        val draft = libraryDraft(conversationId)
        if (draft == null) {
            items.forEach { runCatching { it.release() } }
            return LibraryMergeResult(0, 0, 0, unavailable = true)
        }
        var attachments = draft.attachments
        var added = 0
        var duplicates = 0
        var full = 0
        val accepted = mutableListOf<LibraryStaged>()
        for (item in items) {
            if (attachments.any { librarySources[it.uri] == item.source }) {
                duplicates++
                runCatching { item.release() }
                continue
            }
            val (merged, dropped) = AttachTray.add(attachments, listOf(item.attachment))
            if (dropped != 0) {
                full++
                runCatching { item.release() }
                continue
            }
            attachments = merged
            added++
            accepted += item
        }
        if (added > 0) {
            draftStateChanged = true
            accepted.forEach { item ->
                libraryCopies.putIfAbsent(item.attachment.uri, item.release)
                librarySources.putIfAbsent(item.attachment.uri, item.source)
            }
            session.updateDraft(conversationId) {
                it.copy(attachments = attachments, rejected = emptyList(), overLimit = overLimit + full)
            }
        }
        return LibraryMergeResult(added, duplicates, full, unavailable = false)
    }

    /** Merges a file library copy into the draft of the given conversation without switching conversations or overwriting existing content. The caller invokes this on the main thread. */
    fun addLibraryAttachment(conversationId: String, item: PendingAttachment, source: LibraryFileHandle? = null,
                             release: () -> Unit = {}): LibraryAttachmentResult {
        val draft = libraryDraft(conversationId) ?: return LibraryAttachmentResult.Unavailable
        val duplicate = source != null && draft.attachments.any { librarySources[it.uri] == source }
        if (duplicate) {
            val referenced = item.uri in libraryImports || item.uri in libraryCopies ||
                _ui.value.attachments.any { it.uri == item.uri } ||
                drafts.value.values.any { saved -> saved.attachments.any { it.uri == item.uri } }
            if (!referenced) runCatching { release() }
            return LibraryAttachmentResult.Added
        }
        val (items, dropped) = AttachTray.add(draft.attachments, listOf(item))
        if (dropped != 0) return LibraryAttachmentResult.Full
        draftStateChanged = true
        libraryCopies.putIfAbsent(item.uri, release)
        if (source != null) librarySources.putIfAbsent(item.uri, source)
        session.updateDraft(conversationId) { it.copy(attachments = items, rejected = emptyList(), overLimit = 0) }
        return LibraryAttachmentResult.Added
    }

    /** Tapping a recent photo in the panel: select or deselect it; returns false when the tray is already full. */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!session.canAttach(ui)) return true
        session.draftConversation()
        val t = AttachTray.toggle(ui.attachments, item)
        if (ui.attachments.any { it.uri == item.uri } && t.items.none { it.uri == item.uri }) removeAttachment(item.uri)
        else _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    private fun removeSharedAttachment(draft: SharedDraft, target: SharedDraftAttachment) {
        val currentText = _ui.value.input
        val remaining = draft.attachments.filterNot { it.fileName == target.fileName }
        val nextShared = draft.copy(text = currentText, attachments = remaining)
            .takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (nextShared == null) {
            sharedDrafts?.clear(draft.conversationId)
        } else if (sharedDrafts?.save(nextShared) != true) {
            sharedDrafts?.removeAttachment(draft.conversationId, target.fileName)
        }
        _ui.update { current ->
            if (current.sharedDraft == draft) {
                current.copy(sharedDraft = draft.copy(text = current.input, attachments = remaining)
                    .takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() })
            } else current
        }
    }

    fun removeAttachment(uri: String) {
        val draft = _ui.value.sharedDraft
        val matched = draft?.attachments?.firstOrNull { session.attachmentUri(it) == uri }
        if (draft != null && matched != null) {
            removeSharedAttachment(draft, matched)
        }
        _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }
        session.releaseUnusedLibraryCopies()
    }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }
}
