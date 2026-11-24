package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage

/** File records from saved messages. Reads conversations only; does not check whether the files can still be opened. */
class FileLibrary(private val conversations: ConversationStore) {
    /** Re-reads every time; callers can combine it with ConversationStore.changes to refresh in the background. */
    fun list(): List<LibraryFile> = snapshot().files

    /** Returns the files that could be read, flagging whether the directory or any conversation failed to read; calling again retries. */
    fun snapshot(): FileLibrarySnapshot {
        val recordsSnapshot = conversations.allRecords()
        val result = mutableListOf<LibraryFile>()
        val outputsByUri = mutableMapOf<String, Int>()
        val inputsByPath = mutableMapOf<Pair<String, String>, Int>()
        for ((conversationId, records) in recordsSnapshot.conversations) {
            records.forEachIndexed { turnIndex, (turn, files) ->
                val source = FileSource(conversationId, turnIndex, turn.runId, turn.atMs)
                val messages = listOf(turn.userText) + turn.steers
                messages.forEachIndexed { messageIndex, text ->
                    val messageSource = source.copy(steerIndex = if (messageIndex == 0) null else messageIndex - 1)
                    AttachmentLines.parse(QuoteMessage.words(AttachedPage.words(text))).files.distinct().forEach { (path, size) ->
                        val occurrence = InputFileSource(messageSource, size)
                        val key = conversationId to path
                        val index = inputsByPath[key]
                        if (index == null) {
                            inputsByPath[key] = result.size
                            result += LibraryFile.Input(path, listOf(occurrence))
                        } else {
                            val existing = result[index] as LibraryFile.Input
                            result[index] = existing.copy(sources = existing.sources + occurrence)
                        }
                    }
                }
                files.forEach { file ->
                    val occurrence = SharedFileSource(source, file)
                    // An empty URI cannot prove it is the same file; the file name, path and digest are not used to infer identity either.
                    val uri = file.uri.takeIf { it.isNotBlank() }
                    val index = uri?.let { outputsByUri[it] }
                    if (index == null) {
                        if (uri != null) outputsByUri[uri] = result.size
                        result += LibraryFile.Output(listOf(occurrence))
                    } else {
                        val existing = result[index] as LibraryFile.Output
                        result[index] = existing.copy(sources = existing.sources + occurrence)
                    }
                }
            }
        }
        return FileLibrarySnapshot(result, recordsSnapshot.hasReadFailures)
    }
}

data class FileLibrarySnapshot(val files: List<LibraryFile>, val hasReadFailures: Boolean)

/** turnIndex and steerIndex start at zero; steerIndex null means the turn's first user message. */
data class FileSource(
    val conversationId: String,
    val turnIndex: Int,
    val runId: String?,
    val atMs: Long?,
    val steerIndex: Int? = null,
)

data class InputFileSource(val source: FileSource, val sizeLabel: String)
data class SharedFileSource(val source: FileSource, val file: SharedFile)

sealed interface LibraryFile {
    /** Entries with the same path in the same conversation are merged; a path may be overwritten, so this does not mean the contents were the same each time. */
    data class Input(val path: String, val sources: List<InputFileSource>) : LibraryFile {
        val name: String get() = path.substringAfterLast('/')
        val latestAtMs: Long? get() = sources.mapNotNull { it.source.atMs }.maxOrNull()
    }

    /** Outputs with the same non-empty URI are merged; each source keeps the full metadata recorded at that time. */
    data class Output(val sources: List<SharedFileSource>) : LibraryFile {
        val latestAtMs: Long? get() = sources.mapNotNull { it.source.atMs }.maxOrNull()
    }
}
