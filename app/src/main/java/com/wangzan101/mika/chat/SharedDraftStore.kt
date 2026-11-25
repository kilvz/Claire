package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private copy of a system-shared attachment; [fileName] is generated only by this app and retains no external path. */
data class SharedDraftAttachment(val fileName: String, val name: String, val size: Long, val photo: Boolean = true)

/** Shared content that has not been sent yet in a conversation. Only the draft is stored; it contains no execution state. */
data class SharedDraft(
    val conversationId: String,
    val text: String,
    val attachments: List<SharedDraftAttachment> = emptyList(),
) {
    constructor(conversationId: String, text: String, attachment: SharedDraftAttachment?) :
        this(conversationId, text, listOfNotNull(attachment))

    /** Compatible single-attachment read; null when there are no attachments. */
    val attachment: SharedDraftAttachment? get() = attachments.firstOrNull()
}

/**
 * Persistence point for system-shared drafts, stored per conversation. The conversation ID is decided by the conversation screen; this class is only responsible for making drafts survive across processes.
 * Metadata is written to a temporary file first and then renamed; attachments are fully copied before the metadata is written, so editing and sending can continue even after the external URI grant expires.
 */
class SharedDraftStore(private val dir: File) {
    private val metadata: File get() = File(dir, METADATA)
    private val importingAttachments = mutableMapOf<String, Int>()

    @Synchronized
    fun read(): SharedDraft? = readAll().lastOrNull()

    @Synchronized
    fun readAll(): List<SharedDraft> {
        val (drafts, pending) = readState()
        if (drafts.isEmpty() && pending == null && metadata.exists()) metadata.delete()
        return drafts
    }

    @Synchronized
    fun readPending(): SharedDraft? = readState().second

    /** Saves a background-shared draft awaiting user confirmation to replace; it can be recovered if the process is reclaimed and rebuilt while the confirmation dialog is showing. */
    @Synchronized
    fun savePending(draft: SharedDraft): Boolean {
        if (!valid(draft)) return false
        val (oldDrafts, oldPending) = readState()
        return write(oldDrafts, draft, oldDrafts, oldPending)
    }

    @Synchronized
    fun clearPending() {
        val (oldDrafts, oldPending) = readState()
        if (oldPending == null) return
        write(oldDrafts, null, oldDrafts, oldPending)
    }

    /** Keeps the file copy while importing into the workspace, even if the same conversation receives a new share. */
    @Synchronized
    fun setImporting(fileName: String?, active: Boolean) {
        if (fileName.isNullOrEmpty()) return
        setImporting(listOf(fileName), active)
    }

    /** Keeps multiple file copies while importing into the workspace, even if the same conversation receives a new share. */
    @Synchronized
    fun setImporting(fileNames: Collection<String>, active: Boolean) {
        for (fileName in fileNames) {
            if (fileName.isEmpty()) continue
            if (active) {
                importingAttachments[fileName] = (importingAttachments[fileName] ?: 0) + 1
            } else {
                val next = (importingAttachments[fileName] ?: 0) - 1
                if (next <= 0) {
                    importingAttachments.remove(fileName)
                    discardFile(fileName)
                } else {
                    importingAttachments[fileName] = next
                }
            }
        }
    }

    /** All attachments must already be complete regular files in this directory before saving; on failure the old draft is left unchanged. */
    @Synchronized
    fun save(draft: SharedDraft): Boolean {
        if (!valid(draft)) return false
        val (old, oldPending) = readState()
        val next = if (old.any { it.conversationId == draft.conversationId }) {
            old.map { if (it.conversationId == draft.conversationId) draft else it }
        } else old + draft
        val nextPending = oldPending?.takeUnless { it.conversationId == draft.conversationId }
        return write(next, nextPending, old, oldPending)
    }

    /** Sending or deleting only cleans up the owning conversation; shared attachments of other conversations remain usable. */
    @Synchronized
    fun clear(conversationId: String) {
        val (old, oldPending) = readState()
        val next = old.filterNot { it.conversationId == conversationId }
        val nextPending = oldPending?.takeUnless { it.conversationId == conversationId }
        if (next.size == old.size && nextPending == oldPending) return
        write(next, nextPending, old, oldPending)
    }

    /** Removes one attachment ([fileName] non-null) or all shared attachments of the conversation ([fileName] null). */
    @Synchronized
    fun removeAttachment(conversationId: String, fileName: String? = null) {
        val old = readState().first.firstOrNull { it.conversationId == conversationId } ?: return
        if (old.attachments.isEmpty()) return
        val remaining = if (fileName == null) emptyList() else old.attachments.filterNot { it.fileName == fileName }
        if (remaining.size == old.attachments.size) return
        if (remaining.isEmpty() && old.text.isBlank()) clear(conversationId) else save(old.copy(attachments = remaining))
    }

    /** Deletes a copy when the attachment that has not been bound to a conversation yet is cancelled; the same file must not be deleted once it has been saved successfully. */
    @Synchronized
    fun discard(attachment: SharedDraftAttachment?) {
        attachment?.let { discardFile(it.fileName) }
    }

    /** Cleans up attachment copies not yet bound to a conversation in bulk; saved files and files currently being imported are left untouched. */
    @Synchronized
    fun discard(attachments: Collection<SharedDraftAttachment>) {
        attachments.forEach { discardFile(it.fileName) }
    }

    private fun discardFile(fileName: String) {
        val (drafts, pending) = readState()
        if (fileName in importingAttachments ||
            drafts.any { d -> d.attachments.any { it.fileName == fileName } } ||
            pending?.attachments?.any { it.fileName == fileName } == true
        ) return
        attachmentFile(fileName)?.delete()
    }

    /** Clears temporary files left by interrupted copies, copies that were never bound to a conversation, and corrupted metadata. */
    @Synchronized
    fun cleanup() {
        val (drafts, pending) = readState()
        if (drafts.isEmpty() && pending == null && metadata.exists()) metadata.delete()
        val retained = retainedFiles(drafts, pending)
        dir.listFiles()?.forEach { f ->
            if (f.name == METADATA || f.name in retained) return@forEach
            if (f.isFile) f.delete()
        }
    }

    private fun retainedFiles(drafts: List<SharedDraft>, pending: SharedDraft?): Set<String> =
        (drafts.flatMap { d -> d.attachments.map(SharedDraftAttachment::fileName) } +
            pending?.attachments.orEmpty().map(SharedDraftAttachment::fileName) +
            importingAttachments.keys).toSet()

    private fun encodeAttachment(attachment: SharedDraftAttachment): JsonObject = buildJsonObject {
        put("file", attachment.fileName)
        put("name", attachment.name)
        put("size", attachment.size)
        put("photo", attachment.photo)
    }

    private fun encodeDraft(draft: SharedDraft): JsonObject = buildJsonObject {
        put("conversation", draft.conversationId)
        put("text", draft.text)
        if (draft.attachments.isNotEmpty()) {
            put("attachments", JsonArray(draft.attachments.map(::encodeAttachment)))
        }
    }

    private fun write(
        drafts: List<SharedDraft>,
        pending: SharedDraft?,
        oldDrafts: List<SharedDraft>,
        oldPending: SharedDraft?,
    ): Boolean {
        dir.mkdirs()
        if (drafts.isEmpty() && pending == null) {
            if (metadata.exists() && !metadata.delete()) return false
        } else {
            val json = buildJsonObject {
                put("drafts", JsonArray(drafts.map(::encodeDraft)))
                pending?.let { put("pending", encodeDraft(it)) }
            }.toString()
            val tmp = File(dir, "$METADATA.tmp")
            val written = runCatching {
                tmp.writeText(json)
                Files.move(tmp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
                metadata.isFile && metadata.readText() == json
            }.getOrDefault(false)
            if (!written) {
                tmp.delete()
                return false
            }
        }
        val retained = retainedFiles(drafts, pending)
        (oldDrafts.flatMap { d -> d.attachments.map(SharedDraftAttachment::fileName) } +
            oldPending?.attachments.orEmpty().map(SharedDraftAttachment::fileName))
            .filterNot { it in retained }
            .forEach { attachmentFile(it)?.delete() }
        return true
    }

    fun attachmentFile(fileName: String): File? {
        if (!fileName.matches(FILE)) return null
        val f = File(dir, fileName)
        return f.takeIf { runCatching { it.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false) }
    }

    private fun decodeAttachment(fields: JsonObject, requirePhoto: Boolean): SharedDraftAttachment? {
        fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val photo = if (requirePhoto) {
            string(fields, "photo")?.toBooleanStrictOrNull() ?: return null
        } else true
        return SharedDraftAttachment(
            string(fields, "file") ?: return null,
            string(fields, "name") ?: return null,
            string(fields, "size")?.toLongOrNull() ?: return null,
            photo,
        )
    }

    private fun decodeDraft(o: JsonObject): SharedDraft? {
        fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val attachments = when {
            "attachments" in o -> {
                val arr = o["attachments"] as? JsonArray ?: return null
                arr.map { element ->
                    val fields = element as? JsonObject ?: return null
                    decodeAttachment(fields, requirePhoto = true) ?: return null
                }
            }
            // Compatible with the old single-attachment format (attachment) and the even older single-image format (image, no photo flag).
            "attachment" in o || "image" in o -> {
                val encoded = o["attachment"] ?: o["image"]
                val fields = encoded as? JsonObject ?: return null
                listOf(decodeAttachment(fields, requirePhoto = "attachment" in o) ?: return null)
            }
            else -> emptyList()
        }
        return SharedDraft(
            string(o, "conversation") ?: return null,
            string(o, "text") ?: return null,
            attachments,
        ).takeIf(::valid)
    }

    private fun readState(): Pair<List<SharedDraft>, SharedDraft?> = runCatching {
        val root = Json.parseToJsonElement(metadata.readText()) as? JsonObject ?: return emptyList<SharedDraft>() to null
        // Compatible with the original file that held only a single shared draft.
        val entries = (root["drafts"] as? JsonArray)?.toList() ?: listOf(root)
        val drafts = entries.mapNotNull { (it as? JsonObject)?.let(::decodeDraft) }
        val pending = (root["pending"] as? JsonObject)?.let(::decodeDraft)
        drafts to pending
    }.getOrDefault(emptyList<SharedDraft>() to null)

    private fun valid(draft: SharedDraft): Boolean {
        if (!draft.conversationId.matches(ID) || draft.text.length > MAX_TEXT_CHARS) return false
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return false
        if (draft.attachments.size > MAX_ATTACHMENTS) return false
        val seen = mutableSetOf<String>()
        return draft.attachments.all { attachment ->
            if (!seen.add(attachment.fileName)) return@all false
            val file = attachmentFile(attachment.fileName)?.takeIf { it.isFile } ?: return@all false
            attachment.name.isNotBlank() && attachment.name.length <= 240 && attachment.size >= 0 &&
                attachment.size <= MAX_ATTACHMENT_BYTES && file.length() == attachment.size
        }
    }

    companion object {
        // 新导入按当前档位检查;已保存草稿按支持的最高档位校验,调低设置后也不丢附件。
        private val MAX_ATTACHMENT_BYTES = FeatureFlags.ATTACHMENT_FILE_LIMIT_MB.max().toLong() shl 20
        const val MAX_TEXT_CHARS = 100_000
        const val MAX_ATTACHMENTS = 9
        private const val METADATA = "draft.json"
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
    }
}
