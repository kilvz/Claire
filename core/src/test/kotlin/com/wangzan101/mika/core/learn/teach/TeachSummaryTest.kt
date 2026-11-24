package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachSummaryTest {
    private fun skill(name: String = "a") = PersonalSkill(
        name = name,
        markdown = "---\nname: $name\n---\n正文",
        status = SkillStatus.pending,
        learnedFrom = "teach:t-1",
        replacesDraftOf = null,
        createdAt = 0L,
        outcomes = emptyList(),
        source = TeachSkill.SOURCE,
    )

    @Test fun aDiscardedSessionIsNeverStoredAfterTheModelWrites() = runBlocking {
        var live = true
        var stored = false
        val outcome = TeachSummary.run(
            current = { live },
            build = { live = false; TeachDraft.Result(skill(), byModel = true) },
            store = { _, _ -> stored = true; true },
            newId = { "d1" },
        )
        assertEquals(TeachSummary.Outcome.Abandoned, outcome)
        assertFalse("after discarding, no unclaimed draft may be stored", stored)
    }

    @Test fun aFailToBuildIsReportedNotThrown() = runBlocking {
        val outcome = TeachSummary.run(
            current = { true },
            build = { error("the model service is down") },
            store = { _, _ -> error("should not write to disk") },
            newId = { "d1" },
        )
        assertEquals(TeachSummary.Outcome.Failed, outcome)
    }

    @Test fun aFailureAfterTheSessionWasDiscardedIsNotReportedAsAFailedDraft() = runBlocking {
        var live = true
        val outcome = TeachSummary.run(
            current = { live },
            build = { live = false; error("the model service is down") },
            store = { _, _ -> error("should not write to disk") },
            newId = { "d1" },
        )
        assertEquals(TeachSummary.Outcome.Abandoned, outcome)
    }

    @Test fun theDraftIdAndModelUseReachTheCaller() = runBlocking {
        val outcome = TeachSummary.run(
            current = { true },
            build = { TeachDraft.Result(skill(), byModel = false) },
            store = { id, _ -> id == "d1" },
            newId = { "d1" },
        )
        val drafted = outcome as TeachSummary.Outcome.Drafted
        assertEquals("d1", drafted.draftId)
        assertFalse("a draft prepared locally must not be marked as written by the model", drafted.byModel)
    }

    @Test fun theStoreSeesTheWholeDraft() = runBlocking {
        var got: TeachDraft.Result? = null
        TeachSummary.run(
            current = { true },
            build = { TeachDraft.Result(skill("x"), byModel = true) },
            store = { _, result -> got = result; true },
            newId = { "d1" },
        )
        assertEquals("x", got?.skill?.name)
        assertTrue(got?.byModel == true)
    }

    @Test fun aStoreThatRefusesBecauseTheSessionWasDiscardedAbandonsTheDraft() = runBlocking {
        var live = true
        val outcome = TeachSummary.run(
            current = { live },
            build = { TeachDraft.Result(skill(), byModel = true) },
            store = { _, _ -> live = false; false },
            newId = { "d1" },
        )
        assertEquals(TeachSummary.Outcome.Abandoned, outcome)
    }

    @Test fun aStoreThatFailsWhileTheSessionIsAliveReportsAFailedDraft() = runBlocking {
        val outcome = TeachSummary.run(
            current = { true },
            build = { TeachDraft.Result(skill(), byModel = true) },
            store = { _, _ -> false },
            newId = { "d1" },
        )
        assertEquals(TeachSummary.Outcome.Failed, outcome)
    }
}
