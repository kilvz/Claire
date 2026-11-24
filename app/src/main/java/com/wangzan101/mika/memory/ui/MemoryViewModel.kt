package com.wangzan101.mika.memory.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemoryRejected
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStatus
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillImport
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.voice.ui.nameOf

/** The three page segments: recorded, personal skills, waiting for your confirmation (practices awaiting approval + skill and app knowledge drafts). */
enum class Segment { Memories, Skills, Pending }

/** One memory in the list; [source] is "You said on October 23: '…'" or "you wrote it by hand". */
data class Row(
    val id: String, val kind: MemoryKind, val content: String, val keywords: List<String>, val source: String,
    val pinned: Boolean, val pending: Boolean, val replaces: String?,
)

/** [clearStage]: 0 = not clearing, 1 = first confirmation, 2 = second confirmation. [message] is a one-off notice (e.g. "I won't remember this kind of information"). */
data class MemoryUi(
    val segment: Segment = Segment.Memories,
    val query: String = "",
    val pending: List<Row> = emptyList(),
    val rows: List<Row> = emptyList(),
    val brokenNotice: String? = null,
    val switches: MemorySwitches = MemorySwitches(),
    val message: String? = null,
    val clearStage: Int = 0,
    val skills: SkillsUi = SkillsUi(),
) {
    /** Number of items in the "pending" segment: memories awaiting approval + skill drafts + app knowledge drafts not yet accepted. */
    val pendingCount: Int get() = pending.size + skills.drafts.size + skills.appKnowledge.count { it.stage == AppKnowledgeStage.Draft }
}

/**
 * State of the "Memory & Skills" page: segments, search (an empty query sorts by update time descending), approved
 * practices pinned on top, edit, pin, delete, manual add,
 * clear all (two confirmations) and the three switches. See [SkillsUi] for the skills segment.
 */
class MemoryViewModel(
    private val store: MemoryStore,
    private val settings: MemorySettings,
    private val skills: PersonalSkills?,
    scope: CoroutineScope,
    brokenNotice: StateFlow<String?> = MutableStateFlow(null),
    /** Clear all: deletes the memory file, corrupt files moved aside and personal skills, and rebuilds the key. */
    private val wipe: () -> Unit = { store.clearAll() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** A memory was deleted: hints in conversations that point to it are removed too. */
    private val onForgotten: (String) -> Unit = {},
    /** Validates the tool names mentioned when accepting an edited skill. */
    private val toolNames: Set<String> = knownToolNames(),
    /** Tool name for running skill scripts. */
    private val scriptTool: String? = knownTools().nameOf(Verb.RunBundledScript),
    /** When accepting an edited skill: if it mentions the script-running tool, the script must be marked runnable in the manifest. */
    private val runnableScripts: Set<String> = emptySet(),
    private val appDrafts: AppKnowledgeDraftStore? = null,
    /** Names of built-in skills: imported skills cannot use them. */
    private val builtinSkillNames: Set<String> = emptySet(),
    /** Clears local traces after a demo draft is accepted, rejected or deleted. */
    private val onSkillRetired: (PersonalSkill) -> Unit = {},
    /** Cross-session task summaries; null when the switch is off or none is provided. */
    private val taskMemory: TaskMemoryStore? = null,
) {
    private val local = MutableStateFlow(MemoryUi())
    private val skillsVersion = MutableStateFlow(0)

    /** Cross-session task summaries (newest first); always empty when the switch is off. */
    val tasks: StateFlow<List<TaskMemory>> = taskMemory?.items ?: MutableStateFlow(emptyList())

    val ui: StateFlow<MemoryUi> = combine(local, store.items, settings.switches, brokenNotice, skillsVersion) { l, items, sw, broken, _ ->
        val all = filtered(items, l.query)
        l.copy(
            pending = all.filter { it.status == MemoryStatus.pending }.map(::row),
            rows = all.filter { it.status == MemoryStatus.active }.map(::row),
            brokenNotice = broken, switches = sw,
            skills = skillsUi(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, MemoryUi())

    fun select(s: Segment) = local.update { it.copy(segment = s) }
    fun search(q: String) = local.update { it.copy(query = q) }
    fun dismissMessage() = local.update { it.copy(message = null) }

    fun edit(id: String, content: String, keywords: List<String>) =
        report(store.update(id, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())
    fun setPinned(id: String, pinned: Boolean) = store.setPinned(id, pinned)
    fun delete(id: String) {
        if (store.forget(id).isSuccess) onForgotten(id)
    }
    fun approve(id: String) { store.approve(id) }
    /** Clicking "No" on a practice awaiting approval = delete. */
    fun reject(id: String) = delete(id)

    fun add(kind: MemoryKind, content: String, keywords: List<String>) =
        report(store.add(kind, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())

    fun requestClear() = local.update { it.copy(clearStage = 1) }
    fun cancelClear() = local.update { it.copy(clearStage = 0) }
    fun confirmClear() {
        val stage = local.value.clearStage
        if (stage == 2) wipe()
        local.update { it.copy(clearStage = if (stage == 1) 2 else 0) }
        skillsVersion.update { it + 1 }
    }

    fun setSwitches(transform: (MemorySwitches) -> MemorySwitches) = settings.update(transform)

    /** Deletes a task summary (the experience cache used for cross-session retrieval). */
    fun deleteTask(id: String) { taskMemory?.forget(id) }

    private fun report(e: Throwable?) {
        val msg = when ((e as? MemoryRejected)?.code) {
            null -> null
            "sensitive" -> tr(R.string.memory_sensitive)
            "too_long" -> tr(R.string.memory_too_long)
            "memory_full" -> tr(R.string.memory_full)
            "unavailable" -> tr(R.string.memory_unavailable)
            else -> tr(R.string.memory_not_changed)
        }
        if (msg != null) local.update { it.copy(message = msg) }
    }

    private fun filtered(items: List<MemoryItem>, query: String): List<MemoryItem> =
        if (query.isBlank()) items.sortedByDescending { it.updatedAt }
        else MemoryIndex(items).search(query, items.size.coerceAtLeast(1), includePending = true).map { it.first }

    private fun row(m: MemoryItem) = Row(m.id, m.kind, m.content, m.keywords, sourceLine(m.source), m.pinned, m.status == MemoryStatus.pending, m.replaces)

    // ---- Skills segment ----
    private fun skillsUi(): SkillsUi = skills?.let { SkillsUi.of(it, appDrafts) } ?: SkillsUi()
    private fun skillsChanged() = skillsVersion.update { it + 1 }

    /**
     * Accepts a draft; [edited] is the full text the user edited, which, like a freshly drafted one, must pass format
     * and safety validation — if it fails, do not accept it and explain.
     * Imported drafts are written by someone else, so validate them again even if unedited; self-learned drafts were
     * already validated against that run's tools when drafted, so skip validation if they are unedited.
     */
    fun approveSkill(draftId: String, edited: String? = null) {
        val p = skills ?: return
        val checked = edited ?: p.drafts()[draftId]?.takeIf { it.imported }?.markdown
        val problem = checked?.let { SkillDrafter.validate(it, toolNames, scriptTool, runnableScripts) }
        if (problem != null) {
            local.update { it.copy(message = SKILL_PROBLEMS[problem] ?: tr(R.string.skill_bad_format)) }
            return
        }
        val before = p.drafts()[draftId]
        if (p.approve(draftId, edited) == null) {
            local.update { it.copy(message = tr(R.string.skill_changed)) }
        } else before?.let(onSkillRetired)
        skillsChanged()
    }

    fun rejectSkill(draftId: String) {
        val before = skills?.drafts()?.get(draftId)
        skills?.reject(draftId)
        before?.let(onSkillRetired)
        skillsChanged()
    }

    /**
     * Imports the full text of a skill shared by someone else: if it passes validation, put it into "pending" and switch
     * there, returning null; if it fails, return the reason and let the import box display it (the box stays open so the
     * pasted text is not lost).
     */
    fun importSkill(text: String): String? {
        val p = skills ?: return tr(R.string.skill_bad_format)
        return when (val r = SkillImport.prepare(text, p, builtinSkillNames, toolNames, scriptTool, runnableScripts, UUID.randomUUID().toString(), clock())) {
            is SkillImport.Result.Ready -> {
                p.putPending(r.id, r.skill)
                skillsChanged()
                local.update { it.copy(segment = Segment.Pending, message = tr(R.string.skill_imported)) }
                null
            }
            is SkillImport.Result.Rejected -> SKILL_PROBLEMS[r.problem] ?: tr(R.string.skill_bad_format)
        }
    }

    fun approveAppDraft(id: String) { appDrafts?.approve(id); skillsChanged() }
    fun rejectAppDraft(id: String) { appDrafts?.reject(id); skillsChanged() }

    fun suspendSkill(name: String) = changeSkill(name) { it.copy(status = SkillStatus.suspended) }

    /** Clear old outcomes when restoring, so it is not immediately suspended again on the next hiccup. */
    fun restoreSkill(name: String) = changeSkill(name) {
        it.copy(status = it.restoredStatus(), outcomes = emptyList())
    }

    fun deleteSkill(name: String) {
        val before = skills?.get(name)
        skills?.remove(name)
        before?.let(onSkillRetired)
        skillsChanged()
    }

    fun restorePreviousSkill(name: String) {
        val p = skills ?: return
        val revision = p.revisions(name).firstOrNull() ?: return
        if (!p.restore(revision.id)) local.update { it.copy(message = tr(R.string.skill_restore_failed)) }
        skillsChanged()
    }

    private fun changeSkill(name: String, change: (PersonalSkill) -> PersonalSkill) {
        val p = skills ?: return
        p.get(name)?.let { p.put(change(it)) }
        skillsChanged()
    }

    companion object {
        private val SKILL_PROBLEMS get() = mapOf(
            "sensitive" to tr(R.string.memory_sensitive),
            "invalid_name" to tr(R.string.skill_invalid_name),
            "missing_description" to tr(R.string.skill_missing_description),
            "missing_section" to tr(R.string.skill_missing_section),
            "too_long" to tr(R.string.skill_too_long),
            "unknown_tool" to tr(R.string.skill_unknown_tool),
            "builtin_name" to tr(R.string.skill_builtin_name),
            "draft_pending" to tr(R.string.skill_draft_pending),
        )

        fun sourceLine(s: MemorySource): String =
            if (s.type == "manual") tr(R.string.memory_manual)
            else SimpleDateFormat(tr(R.string.date_pattern_md), uiLocale()).format(Date(s.atMs)).let { day ->
                // Summarized by Claire itself: it quotes the sentence where the user made the request, so it cannot be phrased as "what you said"
                if (s.type == com.wangzan101.mika.core.learn.memory.MemoryContext.INFERRED) tr(R.string.memory_inferred, day, s.quote.orEmpty())
                else if (s.channel != null && s.channel != "Ui") tr(R.string.memory_said_via, day, s.channel, s.quote.orEmpty())
                else tr(R.string.memory_said, day, s.quote.orEmpty())
            }
    }
}

/**
 * One piece of app knowledge supplement. Pending: [revision] true means it is a revision of the in-use one, otherwise
 * it is the first supplement;
 * [changedLines] are the lines added or removed relative to the in-use one (relative to blank for a first supplement).
 * [replaced]: an accepted revision is being trialed and temporarily supersedes this one (not injected; switched back if
 * the revision is archived).
 */
data class AppKnowledgeRow(val id: String, val name: String, val markdown: String, val stage: AppKnowledgeStage,
    val revision: Boolean = false, val changedLines: List<String> = emptyList(), val replaced: Boolean = false)

/** Contents of the skills segment: drafts awaiting acceptance and accepted personal skills (in-use ones first). */

data class SkillsUi(val drafts: List<SkillRow> = emptyList(), val installed: List<SkillRow> = emptyList(),
    val appKnowledge: List<AppKnowledgeRow> = emptyList()) {
    companion object {
        fun of(skills: PersonalSkills, appDrafts: AppKnowledgeDraftStore? = null): SkillsUi {
            val installed = skills.list()
            val byName = installed.associateBy { it.name }
            val drafts = skills.drafts().map { (id, d) ->
                row(id, d).copy(changedLines = d.replacesDraftOf?.let { byName[it] }?.let { changedLines(it.markdown, d.markdown) }.orEmpty())
            }
            val all = appDrafts?.all().orEmpty()
            val byId = all.associateBy { it.id }
            val appRows = all.filter { it.stage != AppKnowledgeStage.Archived }.map { d ->
                val row = AppKnowledgeRow(d.id, d.display.ifBlank { d.appName }, d.markdown, d.stage)
                if (d.stage != AppKnowledgeStage.Draft) row.copy(replaced = AppKnowledgeTrial.inUse(all, d.appName)?.id != d.id)
                else row.copy(revision = d.revises != null,
                    changedLines = changedLines(d.revises?.let { byId[it] }?.markdown?.let(AppKnowledgeDrafter::facts).orEmpty(), d.markdown))
            }
            return SkillsUi(drafts, installed.sortedBy { it.status == SkillStatus.suspended }.map { row(it.name, it, skills) }, appRows)
        }

        private fun row(id: String, s: PersonalSkill, skills: PersonalSkills? = null): SkillRow {
            val meta = FrontMatter.parse(s.markdown).first
            return SkillRow(
                id, s.name, (meta["display"] as? String)?.takeIf { it.isNotBlank() } ?: s.name, meta["description"] as? String ?: "",
                s.markdown, suspended = s.status == SkillStatus.suspended, trial = s.status == SkillStatus.trial,
                canRestore = skills?.revisions(s.name)?.isNotEmpty() == true,
                recent = s.outcomes.takeLast(3).map(::outcomeLabel),
                userCreated = s.createdByUser,
                imported = s.imported,
            )
        }

        /** Lines changed in an updated draft relative to the original (skill or app knowledge supplement): lines added as "+ " and lines removed as "- " (blank lines not compared). */
        fun changedLines(old: String, new: String): List<String> {
            val before = old.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val after = new.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            return after.filter { it !in before }.map { "+ $it" } + before.filter { it !in after }.map { "- $it" }
        }

        private fun outcomeLabel(o: String) = when (o) {
            "verified" -> tr(R.string.verify_passed)
            "unverified" -> tr(R.string.verify_unverified)
            "verifier_rejected" -> tr(R.string.verify_returned)
            else -> tr(R.string.capsule_failed)
        }
    }
}

/** [id] is the draft id (the skill name once accepted); [changedLines] are the lines changed in an updated draft relative to the original skill. */
data class SkillRow(
    val id: String, val name: String, val display: String, val description: String, val markdown: String,
    val suspended: Boolean = false, val trial: Boolean = false, val canRestore: Boolean = false,
    val recent: List<String> = emptyList(), val changedLines: List<String> = emptyList(),
    /** The user asked for this to be written down during a task (not learned from a completed task). */
    val userCreated: Boolean = false,
    /** Imported from the full text shared by someone else. */
    val imported: Boolean = false,
)
