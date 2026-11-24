package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.learn.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.learn.knowledge.LearnedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppKnowledgeFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val facts = "- 首页顶部有搜索入口。\n- 「我的」页里有设置。"
    private fun draft(id: String, md: String = facts, lesson: Boolean = false, revises: String? = null) =
        AppKnowledgeDraft(id, "qq", "com.tencent.mobileqq", md, "run-$id", AppKnowledgeStage.Draft, 1, lesson = lesson, revises = revises)

    @Test fun draftTrialVerifiedAndArchive() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        // At most one pending draft per App
        assertFalse(files.putPending(draft("d2")))
        assertTrue(files.notes().isEmpty())
        assertTrue(File(tmp.root, "knowledge/app-drafts/d1/APP.md").isFile)
        assertTrue(files.approve("d1"))
        assertTrue(files.notes().getValue("qq").trial)
        assertEquals(facts, files.notes().getValue("qq").text)
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertFalse(files.notes().getValue("qq").trial)
        assertTrue(File(tmp.root, "knowledge/app-drafts/archive").listFiles().orEmpty().isNotEmpty())
    }

    private fun verifiedD1(): AppKnowledgeFiles {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1")); files.approve("d1"); files.recordTrial("qq", true)
        return files
    }

    @Test fun revisionReplacesTheOneInUseOnceItWorks() {
        val files = verifiedD1()
        // The one in use does not block a new revision
        assertTrue(files.putPending(draft("d2", "$facts\n- 消息页右上角的加号能建群。", revises = "d1")))
        assertEquals(facts, files.notes().getValue("qq").text)
        assertTrue(files.approve("d2"))
        // While trialing, the revision is injected and the old one is kept
        assertEquals("d2", files.inUse("qq")?.id)
        assertTrue(files.notes().getValue("qq").let { it.trial && it.text.contains("加号") })
        assertEquals(AppKnowledgeStage.Verified, files.all().single { it.id == "d1" }.stage)
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "d1" }.stage)
        assertEquals("d2", files.inUse("qq")?.id)
    }

    @Test fun badTrialOfRevisionRestoresThePreviousNotes() {
        val files = verifiedD1()
        files.putPending(draft("d2", "$facts\n- 消息页右上角的加号能建群。", revises = "d1")); files.approve("d2")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertEquals("d1", files.inUse("qq")?.id)
        assertEquals(LearnedNote(facts, trial = false), AppKnowledgeFiles(tmp.root).notes().getValue("qq"))
        // Same when the user archives the revision under trial
        files.putPending(draft("d3", "- 别的。", revises = "d1")); files.approve("d3"); files.reject("d3")
        assertEquals("d1", files.inUse("qq")?.id)
    }

    @Test fun staleRevisionIsRefused() {
        val files = verifiedD1()
        // Both runs drafted against d1; once the first accepted one is in use, the other is stale
        assertTrue(files.putPending(draft("r1", "- 一。", revises = "d1"))); assertTrue(files.approve("r1"))
        assertFalse(files.putPending(draft("r2", "- 二。", revises = "d1")))
        // A stored revision is now stale (the one it revised was archived): it cannot be accepted, so it is archived on the spot and no longer blocks new drafts for this App
        assertTrue(files.putPending(draft("r3", "- 三。", revises = "r1")))
        files.reject("r1")
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "r3" }.stage)
        assertFalse(files.approve("r3"))
        assertEquals("d1", files.inUse("qq")?.id)
        assertTrue(files.putPending(draft("r4", "- 四。", revises = "d1")))
    }

    @Test fun badTrialArchivesDraftsThatRevisedIt() {
        val files = verifiedD1()
        files.putPending(draft("r1", "- 一。", revises = "d1")); files.approve("r1")
        assertTrue(files.putPending(draft("r2", "- 二。", revises = "r1")))
        // The trial fails: r1 is archived and d1 is restored, so r2, drafted against r1, is stale
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "r2" }.stage)
        assertFalse(files.approve("r2"))
        assertTrue(files.putPending(draft("r3", "- 三。", revises = "d1")))
        assertTrue(files.approve("r3"))
        assertEquals("r3", files.inUse("qq")?.id)
    }

    @Test fun goodTrialKeepsDraftsThatStillMatch() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("a1")); files.approve("a1")
        assertTrue(files.putPending(draft("a2", revises = "a1")))
        // Promotion does not swap out the one in use: a2 stays pending and can still be accepted
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertEquals(AppKnowledgeStage.Draft, files.all().single { it.id == "a2" }.stage)
        assertTrue(files.approve("a2"))
    }

    @Test fun failedTrialIsArchivedAndNeverInjected() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        files.approve("d1")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertTrue(files.notes().isEmpty())
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeFiles(tmp.root).all().single().stage)
    }

    @Test fun lessonSurvivesOneBadTrial() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", lesson = true)); files.approve("d1")
        assertEquals(AppKnowledgeStage.Trial, files.recordTrial("qq", false)?.stage)
        assertTrue(AppKnowledgeFiles(tmp.root).all().single().lesson)
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertNull(files.inUse("qq"))
    }

    @Test fun olderFullAppMdDraftsInjectOnlyTheirBody() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", "---\nname: qq\npackages: [com.tencent.mobileqq]\n---\n## 是什么\nQQ 应用。")); files.approve("d1")
        assertEquals("## 是什么\nQQ 应用。", files.notes().getValue("qq").text)
    }
}
