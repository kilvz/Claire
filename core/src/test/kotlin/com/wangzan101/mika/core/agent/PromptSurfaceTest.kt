package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.TokenEstimate
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryPersistence
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.testing.PromptSurface
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.vision.JvmTestImageOps
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.screen.vision.says
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore

/**
 * Every prompt surface the model can see: the full system prompt for each capability combination, each tool definition (name, description, parameter schema),
 * and the internal role prompts. The golden files live in core/src/test/resources/prompt-surface/; they must match exactly before and after refactors.
 * Regenerate: ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
class PromptSurfaceTest {
    private class Mem : MemoryPersistence {
        override fun load(): List<MemoryItem> = emptyList()
        override fun save(items: List<MemoryItem>) {}
    }

    private class Combo(val tools: () -> ToolDispatcher, val flags: SpeedFlags = SpeedFlags.OFF, val memory: MemoryStore? = null)

    private val bundled = KnowledgeStore(KnowledgeStore.loadBundled())
    private val LAYERS = linkedMapOf("chat" to emptyList(), "phone" to listOf("phone"), "web" to listOf("web"), "all" to listOf("phone", "web", "sandbox"))
    private val combos: Map<String, Combo> = linkedMapOf(
        "phone" to Combo({ toolDispatcher(FakeScreen()) }),
        "phone-inline-batch" to Combo({ toolDispatcher(FakeScreen(), inlineScreen = true) }, SpeedFlags(inlineScreen = true, batchSteps = true)),
        "phone-web" to Combo({ toolDispatcher(FakeScreen(), web = FakeWebPort()) }),
        "phone-sandbox-offline" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(), workspace = { "w" }) }),
        "phone-web-sandbox-online" to Combo({
            toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(SandboxStatus.Ready(network = true)), workspace = { "w" })
        }),
        "phone-sandbox-preparing" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.NotInstalled), workspace = { "w" }) }),
        // The user has enabled root commands on a rooted phone
        "phone-root" to Combo({ toolDispatcher(FakeScreen(), root = object : RootPort {
            override suspend fun run(command: String, timeoutMs: Long): RootRun = RootRun.Unavailable
        }) }),
        "everything" to MemoryStore(Mem(), clock = { 1L }, ids = { "m" }).let { mem ->
            Combo({
                toolDispatcher(
                    FakeScreen(), visionEnabled = true, skills = GuideCatalog.loadBundled(), vision = VisionService(says(), JvmTestImageOps),
                    web = FakeWebPort(), inlineScreen = true, deepLinks = true,
                    memory = mem, utterances = UserUtterances(), sandbox = FakeSandbox(SandboxStatus.Ready(network = false)), workspace = { "w" },
                    personalSkills = com.wangzan101.mika.core.learn.skills.InMemoryPersonalSkills(),
                    // Child tasks and records of previous turns: history_read is present only when a previous turn has a recorded run
                    runSpec = com.wangzan101.mika.core.run.RunSpec(com.wangzan101.mika.core.run.RunId("t"), "t", "thread",
                        priorTurns = listOf(Turn("之前", "好", "succeeded", runId = "r1"))),
                    childHost = com.wangzan101.mika.core.run.ChildRunHost { spec, _ ->
                        com.wangzan101.mika.core.run.ChildResult(spec.id, com.wangzan101.mika.core.run.ChildPhase.Completed, "") },
                    runRecords = NoRecords,
                )
            }, SpeedFlags(inlineScreen = true, batchSteps = true, deepLinks = true), mem)
        },
    )

    /** When [open], all three groups were used in the previous turn: expand them all at the start (those this task provides); otherwise keep them all collapsed, with only the entry tools. */
    private fun offered(c: Combo, open: Boolean = true): FakeRequestCapture = runBlocking {
        val m = FakeRequestCapture()
        AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory)
            .run("t", if (open) listOf(Turn("之前", "好", "succeeded", groups = listOf("phone", "web", "sandbox"))) else emptyList())
        m
    }

    /** The system prompt for each combination with all groups expanded (for the collapsed form see layeredSurfaces). */
    @Test fun systemPromptPerCombination() {
        for ((name, c) in combos) {
            val m = offered(c)
            PromptSurface.check("system/$name", PromptSurface.system(m.system!!, m.tools))
        }
    }

    @Test fun toolDefinitions() {
        // A tool with the same name may have several definitions depending on how many screens this task has (one extra surface parameter), and entry tools have a shorter description when collapsed: list every variant
        val all = combos.values.flatMap { offered(it).tools + offered(it, open = false).tools }
            .distinctBy { it.name + it.description + it.parametersJsonSchema }.sortedBy { it.name }
        PromptSurface.check("tools", PromptSurface.tools(all))
    }

    /**
     * The full picture of the first request in each scenario after layering (system prompt + the full definition of every tool), using the same combination as everything but without confirmation tools, as on the product path:
     * chat only chats (all collapsed), phone / web used that group in the previous turn (expanded at start), all expands all three groups. Sizes are written separately into layers/sizes.
     */
    @Test fun layeredSurfaces() {
        val c = combos.getValue("everything")
        val sizes = StringBuilder()
        for ((name, groups) in LAYERS) {
            val m = FakeRequestCapture()
            val prior = if (groups.isEmpty()) emptyList() else listOf(Turn("之前", "好", "succeeded", groups = groups))
            runBlocking { AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory, confirmations = null).run("t", prior) }
            PromptSurface.check("layers/$name", PromptSurface.layer(m.system!!, m.tools))
            val system = m.system!!.toByteArray().size
            val tools = m.tools.sumOf { (it.name + it.description + it.parametersJsonSchema).toByteArray().size }
            val tokens = TokenEstimate.of(listOf(com.wangzan101.mika.core.model.Message(com.wangzan101.mika.core.model.Role.User,
                listOf(Part.Text(m.system!! + m.tools.joinToString("") { it.name + it.description + it.parametersJsonSchema })), com.wangzan101.mika.core.model.Author.Mika)))
            sizes.appendLine("$name: system $system bytes, ${m.tools.size} tools $tools bytes, total ${system + tools} bytes, ~$tokens tokens")
        }
        print(sizes)
        PromptSurface.check("layers/sizes", sizes.toString())
    }

    @Test fun rolePrompts() {
        PromptSurface.check("roles", PromptSurface.roles())
    }

    /** Persona layer: the full text of the fixed header/footer and the body, so changes can be reviewed directly in the diff. Other golden files are unaffected when there is no persona. */
    @Test fun personaLayer() {
        PromptSurface.check("persona/layer", com.wangzan101.mika.core.prompt.PersonaPrompt.layer("测试人格", "先给结论,再展开;少用感叹号。")!!)
    }

    /** The segments the model sees that are added or different after renaming: the identity segment, the statement that Claire is the app's name, and the persona layer's fixed header. */
    @Test fun personaRenamed() {
        val identity = SystemPrompt.identity("辉夜").substringBefore("\n\nPrinciples:")
        val layer = com.wangzan101.mika.core.prompt.PersonaPrompt.layer("测试人格", "先给结论。", assistant = "辉夜")!!.substringBefore("\n\nWhat the persona may change")
        PromptSurface.check("persona/renamed", identity + "\n\n---\n\n" + layer)
    }

    /** A persona layer with options: fixed header/footer + sentences for the options + what the user wrote. */
    @Test fun personaLayerWithOptions() {
        val style = com.wangzan101.mika.core.prompt.PersonaStyle(
            base = com.wangzan101.mika.core.prompt.PersonaStyle.Base.Crisp,
            emoji = com.wangzan101.mika.core.prompt.PersonaStyle.Level.Less,
            length = com.wangzan101.mika.core.prompt.PersonaStyle.Level.Less,
            progress = com.wangzan101.mika.core.prompt.PersonaStyle.Level.Less,
        )
        PromptSurface.check("persona/layer-options", com.wangzan101.mika.core.prompt.PersonaPrompt.layer("测试人格", "先给结论,再展开;少用感叹号。", style)!!)
    }

    @Test fun feedbackFromTools() {
        PromptSurface.check("feedback-tools", runBlocking { PromptSurfaceFeedback.tools() })
    }

    @Test fun feedbackFromLoop() {
        PromptSurface.check("feedback-loop", PromptSurfaceFeedback.loop())
    }

    private object NoRecords : com.wangzan101.mika.core.run.record.RunRecords {
        override suspend fun summary(runId: String): com.wangzan101.mika.core.run.record.RunSummary? = null
        override suspend fun events(runId: String) = emptyList<com.wangzan101.mika.core.run.record.RunEvent>()
    }

    /** Reply with only a finish: record the system prompt and tools from the request at the start. */
    private class FakeRequestCapture : com.wangzan101.mika.core.model.ChatModel {
        override val capabilities = com.wangzan101.mika.core.model.Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        var system: String? = null
        var tools: List<com.wangzan101.mika.core.model.ToolSpec> = emptyList()
        override suspend fun complete(system: String, messages: List<com.wangzan101.mika.core.model.Message>, tools: List<com.wangzan101.mika.core.model.ToolSpec>): ModelReply {
            if (this.system == null) { this.system = system; this.tools = tools }
            return com.wangzan101.mika.core.testing.answerReply("ok")
        }
    }
}
