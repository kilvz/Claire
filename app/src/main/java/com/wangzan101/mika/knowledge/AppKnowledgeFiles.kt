package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.core.learn.knowledge.LearnedNote
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Ledger of versions and stages of app knowledge supplements; pending ones (drafts) are not read by the knowledge base. Each app has at most one pending entry and only one is injected
 * ([AppKnowledgeTrial.inUse]): an accepted revision takes the place of the old one during the trial, the old one is archived only when the revision becomes official, and it switches back to the old one if archived
 * (all files from before a change are kept in archive).
 */
class AppKnowledgeFiles(dir: File) : AppKnowledgeDraftStore {
    private val root = File(dir, "knowledge/app-drafts")
    private val archive = File(root, "archive")
    private val journal = File(root, "changes.jsonl")
    private val idPattern = Regex("[A-Za-z0-9_-]+")

    @Synchronized override fun all(): List<AppKnowledgeDraft> =
        root.listFiles { f -> f.isDirectory && f.name != "archive" && idPattern.matches(f.name) }.orEmpty()
            .mapNotNull { d ->
                runCatching {
                    val row = Json.decodeFromString(AppKnowledgeDraft.serializer(), File(d, "meta.json").readText())
                    row.copy(markdown = File(d, "APP.md").readText())
                }.getOrNull()
            }

    @Synchronized override fun putPending(draft: AppKnowledgeDraft): Boolean {
        val rows = all()
        if (!idPattern.matches(draft.id) || !idPattern.matches(draft.appName) ||
            draft.stage != AppKnowledgeStage.Draft ||
            rows.any { it.appName == draft.appName && it.stage == AppKnowledgeStage.Draft } || stale(draft, rows)) return false
        save(draft)
        return true
    }

    @Synchronized override fun approve(id: String): Boolean {
        val rows = all()
        val old = rows.firstOrNull { it.id == id && it.stage == AppKnowledgeStage.Draft } ?: return false
        if (stale(old, rows)) {
            save(old.copy(stage = AppKnowledgeStage.Archived))
            return false
        }
        save(old.copy(stage = AppKnowledgeStage.Trial))
        archiveStale(old.appName)
        return true
    }

    @Synchronized override fun reject(id: String): Boolean {
        val old = all().firstOrNull { it.id == id && it.stage != AppKnowledgeStage.Archived } ?: return false
        save(old.copy(stage = AppKnowledgeStage.Archived))
        archiveStale(old.appName)
        return true
    }

    /** The trial applies to the injected entry; when it becomes official, the older versions it replaced are archived with it, and archiving naturally brings the old version back. */
    @Synchronized override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? {
        val rows = all()
        val old = AppKnowledgeTrial.inUse(rows, appName)?.takeIf { it.stage == AppKnowledgeStage.Trial } ?: return null
        val next = AppKnowledgeTrial.next(old, good)
        if (next.stage == AppKnowledgeStage.Verified) rows.filter {
            it.appName == appName && it.id != old.id && (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified)
        }.forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
        save(next)
        archiveStale(appName)
        return next
    }

    /** The entry injected for each app; older versions were drafted as a whole APP.md with front matter, so only the body is taken. */
    @Synchronized override fun notes(): Map<String, LearnedNote> {
        val rows = all()
        return rows.map { it.appName }.distinct().mapNotNull { app -> AppKnowledgeTrial.inUse(rows, app) }
            .associate { row -> row.appName to LearnedNote(FrontMatter.parse(row.markdown).second.trim(), trial = row.stage == AppKnowledgeStage.Trial) }
    }

    /** The entry the revision was drafted against is no longer the injected one (another revision was accepted first): accepting it would lose that entry. */
    private fun stale(draft: AppKnowledgeDraft, rows: List<AppKnowledgeDraft>) =
        draft.revises != AppKnowledgeTrial.inUse(rows, draft.appName)?.id

    /**
     * After the entry in use has changed (accepted, archived, or the trial stage changed), a pending revision drafted against another entry can no longer be accepted: archive it,
     * so it does not keep occupying the "at most one pending entry per app" slot and block this app's later drafts.
     */
    private fun archiveStale(appName: String) {
        val rows = all()
        rows.filter { it.appName == appName && it.stage == AppKnowledgeStage.Draft && stale(it, rows) }
            .forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
    }

    private fun save(row: AppKnowledgeDraft) {
        val d = File(root, row.id)
        if (d.exists()) {
            val revision = UUID.randomUUID().toString()
            d.copyRecursively(File(archive, revision), overwrite = true)
            journal.parentFile.mkdirs()
            journal.appendText(revision + " " + row.id + " " + row.stage + " " + System.currentTimeMillis() + "\n")
        }
        d.mkdirs()
        atomic(File(d, "APP.md"), row.markdown)
        atomic(File(d, "meta.json"), Json.encodeToString(AppKnowledgeDraft.serializer(), row.copy(markdown = "")))
    }

    private fun atomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
