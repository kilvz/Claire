package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.MemoryExtractor
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

class RunLearningMemoryTest {
    private val catalog = GuideCatalog.loadBundled()
    private val extractJson = """{"ops":[{"op":"add","kind":"preference","content":"用户点外卖不要香菜","quote":"以后都不要香菜"}]}"""

    private fun aftermath(utterances: List<String>, saved: List<com.wangzan101.mika.core.learn.memory.Change> = emptyList()) = RunAftermath(
        "conv-1", "run-1", "点外卖", TaskState.Succeeded("ok"), utterances, saved, listOf("x"), emptySet(), knownTools(), events = { emptyList() },
    )

    @Test fun extractsFromUserWordsWithConversationSource() {
        val store = memoryStore()
        val model = TextModel(extractJson)
        val notes = runBlocking {
            RunLearning(MemorySwitches(), store, MapSkills(), catalog, MemoryExtractor(model), null, { "d" }).learn(aftermath(listOf("点外卖,以后都不要香菜")))
        }
        assertEquals(listOf("记住了:用户点外卖不要香菜"), notes.memoryNotes.map { n -> n.text(store.items.value.find { it.id == n.memoryId }) })
        assertEquals(listOf("x"), notes.usedMemory)
        assertEquals("conv-1", store.items.value.single().source.conversationId)
        assertEquals("Ui", store.items.value.single().source.channel)
        assertEquals("run-1", notes.runId)
    }

    @Test fun autoRememberOffSkipsExtractionButKeepsSavedNotes() {
        val store = memoryStore()
        val saved = store.add(MemoryKind.fact, "用户住在张江", emptyList(), MemorySource("user_said", "我住在张江", atMs = 1)).getOrThrow()
        val model = TextModel(extractJson)
        val notes = runBlocking {
            RunLearning(MemorySwitches(autoRemember = false), store, MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("以后都不要香菜"), listOf(saved)))
        }
        assertTrue(model.requests.isEmpty())
        assertEquals(listOf("记住了:用户住在张江"), notes.memoryNotes.map { n -> n.text(store.items.value.find { it.id == n.memoryId }) })
    }

    @Test fun noUtterancesNoCall() {
        val model = TextModel(extractJson)
        runBlocking { RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" }).learn(aftermath(emptyList())) }
        assertTrue(model.requests.isEmpty())
    }

    @Test fun noRunIdNoExtraction() {
        val model = TextModel(extractJson)
        runBlocking {
            RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("以后都不要香菜")).copy(runId = null))
        }
        assertTrue(model.requests.isEmpty())
    }

    // The conversation contains notification notices (text written by others): what the user says here is not automatically extracted into memory; things explicitly requested to be remembered in the task (written via memory_save) still are
    @Test fun conversationWithNoticesIsNotMinedForMemory() {
        val store = memoryStore()
        val saved = store.add(MemoryKind.fact, "用户住在张江", emptyList(), MemorySource("user_said", "我住在张江", atMs = 1)).getOrThrow()
        val model = TextModel(extractJson)
        val notes = runBlocking {
            RunLearning(MemorySwitches(), store, MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("好,就按他说的,以后都不要香菜"), listOf(saved)).copy(noticeTainted = true))
        }
        assertTrue(model.requests.isEmpty())
        assertEquals(listOf("记住了:用户住在张江"), notes.memoryNotes.map { n -> n.text(store.items.value.find { it.id == n.memoryId }) })
    }

    // This run read notifications with a tool: as with reading web pages, memory is not automatically extracted from what the user said this time
    @Test fun aRunThatReadNotificationsIsNotMinedForMemory() {
        val model = TextModel(extractJson)
        val read = com.wangzan101.mika.core.run.record.RunEvent.Activity(0, 1, "observe", com.wangzan101.mika.core.general.NOTICES_READ)
        runBlocking {
            RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("好,就按他说的,以后都不要香菜")).copy(events = { listOf(read) }))
        }
        assertTrue(model.requests.isEmpty())
        // Other read-only tools are unaffected
        val other = com.wangzan101.mika.core.run.record.RunEvent.Activity(0, 1, "observe", "history_read")
        runBlocking {
            RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("点外卖,以后都不要香菜")).copy(events = { listOf(other) }))
        }
        assertEquals(1, model.requests.size)
    }

    // This run started out carrying the excerpt derived from notifications: what the user says this time is most likely responding to things others wrote in it, so memory is not automatically extracted.
    // Start from actually running a task once: AgentLoop runs through with that excerpt, the run record contains that entry, and the post-run learning skips extraction based on it
    @Test fun aRunGivenNotificationContextIsNotMinedForMemory() = runBlocking {
        fun run(observed: String?): Int {
            val store = memoryStore()
            val utterances = com.wangzan101.mika.core.learn.memory.UserUtterances()
            val events = mutableListOf<com.wangzan101.mika.core.run.record.RunEvent>()
            val screen = java.lang.reflect.Proxy.newProxyInstance(
                com.wangzan101.mika.core.screen.uistate.ScreenPort::class.java.classLoader, arrayOf(com.wangzan101.mika.core.screen.uistate.ScreenPort::class.java),
            ) { _, m, _ ->
                when {
                    m.name == "getAppAccess" -> com.wangzan101.mika.core.device.access.AppAccessPolicy.ALLOW
                    m.returnType == Boolean::class.javaPrimitiveType -> false
                    else -> error("Unexpected screen access: ${m.name}")
                }
            } as com.wangzan101.mika.core.screen.uistate.ScreenPort
            val dispatcher = com.wangzan101.mika.core.assembly.toolDispatcher(screen, memory = store, utterances = utterances)
            val agent = com.wangzan101.mika.core.agent.AgentLoop(TextModel("好的"), dispatcher, "base", memory = store, observed = observed,
                sink = object : com.wangzan101.mika.core.run.record.RunSink { override fun event(e: com.wangzan101.mika.core.run.record.RunEvent) { events += e } })
            val goal = "好,就按他说的,以后都不要香菜"
            val end = runBlocking { agent.run(goal, emptyList()) }
            assertTrue(end is TaskState.Succeeded)
            assertEquals(observed != null, agent.carriedObserved)
            val extraction = TextModel(extractJson)
            // The conversation has no notification notices (noticeTainted is false): only the entry in the run record applies
            val aftermath = RunAftermath("conv", "run", goal, end, utterances.all, emptyList(), emptyList(),
                dispatcher.definitions().map { it.name }.toSet(), dispatcher.index, events = { events })
            runBlocking { RunLearning(MemorySwitches(), store, MapSkills(), catalog, MemoryExtractor(extraction), null, { "id" }).learn(aftermath) }
            return extraction.requests.size
        }
        val observed = com.wangzan101.mika.core.proactive.ObservedContext.render(listOf("群里的人点餐时说以后都不要香菜"), emptyList(), emptyList(), emptyList())!!
        assertEquals(0, run(observed))
        // The same task without that excerpt still extracts as usual
        assertEquals(1, run(null))
    }
}
