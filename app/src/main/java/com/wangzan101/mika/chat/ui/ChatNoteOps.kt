package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Memory and skill prompts after a task ends: undo, approve, restore, delete. */
internal class ChatNoteOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val ui get() = vm.ui
    private val scope = vm.scope
    private val learning = vm.learning
    private val memoryItems = vm.memoryItems

    /** The text of this prompt line, with content taken from the current memory; when that entry has been deleted it reads "a deleted memory". */
    fun noteText(note: MemoryNote): String = note.text(memoryItems.value.find { it.id == note.memoryId })

    /** Undo this memory change; when the token is no longer valid (after a restart) only the "Undo" is removed. */
    fun undoNote(runId: String, note: MemoryNote) {
        val token = note.undoToken ?: return
        scope.launch {
            val ok = learning?.undo(token) ?: false
            editNotes(runId) { ex -> ex.copy(memoryNotes = if (ok) ex.memoryNotes - note else ex.memoryNotes.map { if (it == note) it.copy(undoToken = null) else it }) }
        }
    }

    /** The practice: "Yes" approves it. */
    fun approveNote(runId: String, note: MemoryNote) {
        val id = note.pendingId ?: return
        scope.launch {
            learning?.approve(id)
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** The practice: "No" undoes this addition; when the token is gone the entry is deleted directly. */
    fun rejectNote(runId: String, note: MemoryNote) {
        scope.launch {
            val undone = note.undoToken?.let { learning?.undo(it) } ?: false
            if (!undone) note.pendingId?.let { learning?.forget(it) }
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** The content shown when "n memories used" is expanded; deleted ones are not listed. */
    fun usedMemoryTexts(ids: List<String>): List<String> {
        val byId = memoryItems.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.content }
    }

    fun viewSkill(note: SkillNote) { note.draftId?.let { learning?.viewSkill(it) } }

    fun restoreSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.restoreSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    fun deleteSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.deleteSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    private suspend fun editNotes(runId: String, change: (Exchange) -> Exchange) {
        var saved: Exchange? = null
        _ui.update { ui ->
            ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it).also { e -> saved = e } else it })
        }
        val ex = saved ?: return
        val conversation = _ui.value.conversationId ?: return
        learning?.save(RunNotes(conversation, runId, ex.memoryNotes, ex.usedMemory, ex.skillNotes))
    }
}
