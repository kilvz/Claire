package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.core.prompt.PersonaStyle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.root) = PersonaStore(dir)

    @Test fun addPersistsAcrossInstances() {
        val dir = tmp.newFolder("p")
        val a = store(dir)
        val p = a.add("写作助手", "先列提纲", "先给结论，再展开。").getOrThrow()
        assertEquals(listOf(p), a.personas.value)
        assertEquals(listOf(p), store(dir).personas.value)
        assertTrue(File(dir, "personas.json").readText().contains("\"version\": 1"))
    }

    @Test fun updateBumpsRevisionAndKeepsCreatedAt() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val next = s.update(p.copy(name = "Q", prompt = "b")).getOrThrow()
        assertEquals(p.createdAt, next.createdAt)
        assertEquals(p.revision + 1, next.revision)
        assertEquals("Q", store(dir).get(p.id)?.name)
    }

    @Test fun duplicateGetsNewIdAndRevision() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.add("P", "", "a").getOrThrow()
        val copy = s.duplicate(p.id).getOrThrow()
        assertNotEquals(p.id, copy.id)
        assertEquals(1, copy.revision)
        assertFalse(copy.builtin)
        assertEquals(2, store(dir).personas.value.size)
    }

    /** The options are stored along with the persona and carried through copy and rename; a persona with only options and no prompt can be saved too. */
    @Test fun styleIsStoredAndCarriedByUpdateAndDuplicate() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val style = PersonaStyle(base = PersonaStyle.Base.Crisp, emoji = PersonaStyle.Level.Less)
        val p = s.add("干练", "", "", style).getOrThrow()
        assertEquals(style, store(dir).get(p.id)?.style)
        assertTrue(File(dir, "personas.json").readText().contains("\"base\": \"crisp\""))
        val renamed = s.update(p.copy(name = "更干练")).getOrThrow()
        assertEquals(style, renamed.style)
        val changed = s.update(renamed.copy(style = style.copy(length = PersonaStyle.Level.Less))).getOrThrow()
        assertEquals(PersonaStyle.Level.Less, store(dir).get(p.id)?.style?.length)
        assertEquals(changed.style, s.duplicate(p.id).getOrThrow().style)
    }

    /** Files written before options existed have no style: they still load, with every option at its default. */
    @Test fun filesWrittenBeforeOptionsStillLoad() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText(
            """{"version":1,"personas":[{"id":"old-1","name":"写作助手","description":"","prompt":"先给结论。","revision":3,"createdAt":1,"updatedAt":2,"builtin":false}]}""",
        )
        val p = store(dir).get("old-1")!!
        assertEquals("先给结论。", p.prompt)
        assertEquals(3, p.revision)
        assertTrue(p.style.isDefault)
    }

    /** Unrecognised option values (from later versions or hand-edited files) are read as defaults; the file is not renamed to .bad and the other personas are still there. */
    @Test fun unknownOptionValuesLoadAsDefaultInsteadOfInvalidatingTheFile() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText(
            """{"version":1,"personas":[
              {"id":"a","name":"A","prompt":"x","style":{"base":"sarcastic","emoji":"more","length":"huge","future":true}},
              {"id":"b","name":"B","prompt":"y","style":{"base":"warm"}}]}""",
        )
        val s = store(dir)
        assertEquals(PersonaStyle(emoji = PersonaStyle.Level.More), s.get("a")?.style)
        assertEquals(PersonaStyle(base = PersonaStyle.Base.Warm), s.get("b")?.style)
        assertEquals(listOf("personas.json"), dir.list()!!.toList())
    }

    /** The persona chosen as active in settings: the choice persists, it may only point to one in the list, and deleting it goes back to having none active. */
    @Test fun activePersonaPersistsAndClearsWhenDeleted() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        assertNull(s.activeId.value)
        assertNull(s.active())
        val a = s.add("A", "", "a").getOrThrow()
        val b = s.add("B", "", "b").getOrThrow()
        assertNull("adding does not select", s.activeId.value)
        assertFalse(s.setActive("missing"))
        assertTrue(s.setActive(a.id))
        assertEquals(a, s.active())
        assertEquals(a.id, store(dir).activeId.value)
        // Editing the content does not touch the active selection; the active persona returns the edited copy
        val renamed = s.update(a.copy(name = "A2")).getOrThrow()
        assertEquals(renamed, store(dir).active())
        // Deleting another one has no effect; deleting the active one clears it in the same write to disk
        assertTrue(s.delete(b.id))
        assertEquals(a.id, s.activeId.value)
        assertTrue(s.delete(a.id))
        assertNull(s.activeId.value)
        assertNull(store(dir).activeId.value)
        assertTrue(s.setActive(null))
    }

    /** Old files have no active field; in hand-edited files active points at a persona that does not exist: both count as nothing active, and the file loads as usual. */
    @Test fun missingOrDanglingActiveMeansNothingInUse() {
        val old = tmp.newFolder("old")
        File(old, "personas.json").writeText("""{"version":1,"personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertNull(store(old).activeId.value)
        assertEquals("A", store(old).get("a")?.name)
        val dangling = tmp.newFolder("dangling")
        File(dangling, "personas.json").writeText("""{"version":1,"active":"gone","personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertNull(store(dangling).activeId.value)
        assertEquals(listOf("personas.json"), dangling.list()!!.toList())
    }

    /** Editing the active persona right on the persona page: when none exists yet, the first real change creates one and marks it active; if the result equals the default, nothing is created. */
    @Test fun editActiveCreatesOnTheFirstRealChangeThenUpdatesInPlace() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        // Picking "Default" when it was already selected: nothing is kept
        s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Default)) }.getOrThrow()
        assertTrue(s.personas.value.isEmpty())
        assertNull(s.activeId.value)
        assertFalse(File(dir, "personas.json").exists())

        val first = s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Crisp)) }.getOrThrow()
        assertEquals("我的风格", first.name)
        assertEquals(first.id, store(dir).activeId.value)
        assertEquals(1, first.revision)

        val second = s.editActive("不会用到") { it.copy(style = it.style.copy(emoji = PersonaStyle.Level.Less)) }.getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals("我的风格", second.name)
        assertEquals(2, second.revision)
        assertEquals(PersonaStyle(base = PersonaStyle.Base.Crisp, emoji = PersonaStyle.Level.Less), store(dir).active()?.style)
        assertEquals(1, s.personas.value.size)

        // Picking a tier that is already selected once more: no write and no revision bump
        val same = s.editActive("不会用到") { it.copy(style = it.style.copy(emoji = PersonaStyle.Level.Less)) }.getOrThrow()
        assertEquals(2, same.revision)
        assertEquals(second.updatedAt, same.updatedAt)
    }

    /** Several personas are saved but none is active (this is what upgrading from the old version that bound personas per conversation looks like): the first change creates a new one and selects it, leaving the existing ones alone. */
    @Test fun editActiveWithSavedPersonasButNoneInUseAddsANewOneAndLeavesTheRestAlone() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val old = s.add("Crisp", "", "", PersonaStyle(base = PersonaStyle.Base.Crisp)).getOrThrow()
        assertNull(s.activeId.value)
        val created = s.editActive("我的风格") { it.copy(style = it.style.copy(base = PersonaStyle.Base.Playful)) }.getOrThrow()
        assertNotEquals(old.id, created.id)
        assertEquals("我的风格", created.name)
        assertEquals(created.id, store(dir).activeId.value)
        assertEquals(old, store(dir).get(old.id))
        assertEquals(2, s.personas.value.size)
    }

    /** The nickname is stored with the persona; changing only the nickname also counts as a real change (it creates a persona and makes it active); a nickname containing symbols or one that is too long cannot be saved. */
    @Test fun nicknameIsStoredAndValidated() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val p = s.editActive("我的风格") { it.copy(nickname = "Kaguya") }.getOrThrow()
        assertEquals("Kaguya", store(dir).active()?.nickname)
        assertEquals("Kaguya", p.assistant)
        assertFalse(p.changesNothing)
        assertTrue(s.editActive("x") { it.copy(nickname = "Kaguya!") }.isFailure)
        assertTrue(s.editActive("x") { it.copy(nickname = "字".repeat(17)) }.isFailure)
        assertEquals("Kaguya", s.active()?.nickname)
        // Clearing it means the default Claire; everything else in this persona is default too, so nothing is changed
        val cleared = s.editActive("x") { it.copy(nickname = "") }.getOrThrow()
        assertEquals("Claire", cleared.assistant)
        assertTrue(cleared.changesNothing)
    }

    /** The nickname is exactly Claire and everything else is at its default: this equals no change, so nothing is created. Old files have no nickname: treated as Claire. */
    @Test fun defaultNicknameChangesNothing() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        s.editActive("我的风格") { it.copy(nickname = " Claire ") }.getOrThrow()
        assertTrue(s.personas.value.isEmpty())
        File(dir, "personas.json").writeText("""{"version":1,"personas":[{"id":"a","name":"A","prompt":"x"}]}""")
        assertEquals("Claire", store(dir).get("a")?.assistant)
    }

    /** A newly created persona is in use right away; renaming changes only the name; the copy's name is supplied by the UI and the copy is not the active one. */
    @Test fun createRenameAndDuplicate() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val a = s.create(" 工作 ").getOrThrow()
        assertEquals("工作", a.name)
        assertEquals(a.id, s.activeId.value)
        assertTrue(a.style.isDefault)
        val renamed = s.rename(a.id, "上班").getOrThrow()
        assertEquals("上班", store(dir).get(a.id)?.name)
        assertEquals(a.id, s.activeId.value)
        assertTrue(s.rename(a.id, "  ").isFailure)
        assertTrue(s.rename(a.id, "x".repeat(41)).isFailure)
        assertEquals("上班", s.get(a.id)?.name)
        val copy = s.duplicate(a.id) { "$it 副本" }.getOrThrow()
        assertEquals("上班 副本", copy.name)
        assertEquals(renamed.id, s.activeId.value)
        val long = s.create("y".repeat(40)).getOrThrow()
        assertEquals(40, s.duplicate(long.id) { "$it 副本" }.getOrThrow().name.length)
    }

    /** At most 20 personas: once the limit is reached, creating, duplicating and the first edit all stop adding. */
    @Test fun thereIsAnUpperLimitOnHowManyAreKept() {
        val s = store(tmp.newFolder("p"))
        repeat(PersonaStore.MAX_PERSONAS) { assertTrue(s.add("P$it", "", "x").isSuccess) }
        assertTrue(s.add("more", "", "x").isFailure)
        assertTrue(s.create("more").isFailure)
        assertTrue(s.duplicate(s.personas.value.first().id).isFailure)
        assertTrue(s.editActive("我的风格") { it.copy(prompt = "短一点") }.isFailure)
        assertEquals(PersonaStore.MAX_PERSONAS, s.personas.value.size)
    }

    @Test fun deleteRemovesOnlyThatPersona() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        val a = s.add("A", "", "a").getOrThrow()
        val b = s.add("B", "", "b").getOrThrow()
        assertTrue(s.delete(a.id))
        assertNull(s.get(a.id))
        assertEquals(listOf(b), store(dir).personas.value)
        assertFalse(s.delete("missing"))
    }

    @Test fun invalidInputIsRejected() {
        val dir = tmp.newFolder("p")
        val s = store(dir)
        assertTrue(s.add("", "", "a").isFailure)
        assertTrue(s.add("x".repeat(PersonaPromptLimit.NAME + 1), "", "a").isFailure)
        assertTrue(s.add("P", "x".repeat(PersonaStore.MAX_DESCRIPTION + 1), "a").isFailure)
        assertTrue(s.add("P", "", "x".repeat(PersonaPromptLimit.CHARS + 1)).isFailure)
        assertTrue(s.personas.value.isEmpty())
    }

    @Test fun corruptFileIsSetAside() {
        val dir = tmp.newFolder("p")
        File(dir, "personas.json").writeText("{not json")
        val s = store(dir)
        assertTrue(s.personas.value.isEmpty())
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("personas.json.bad-") })
    }

    @Test fun emptyPromptIsAllowed() {
        val dir = tmp.newFolder("p")
        val p = store(dir).add("P", "", "").getOrThrow()
        assertEquals("", p.prompt)
    }

    @Test fun idsAreValidated() {
        assertTrue(PersonaStore.isValidId("abc-123_X"))
        assertFalse(PersonaStore.isValidId("../escape"))
        assertFalse(PersonaStore.isValidId(""))
        assertFalse(PersonaStore.isValidId(null))
    }

    /** The limits used in tests, matched against the PersonaPrompt constants, so a rename only has to be done here. */
    private object PersonaPromptLimit {
        const val NAME = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_NAME
        const val CHARS = com.wangzan101.mika.core.prompt.PersonaPrompt.MAX_PROMPT_CHARS
    }
}
