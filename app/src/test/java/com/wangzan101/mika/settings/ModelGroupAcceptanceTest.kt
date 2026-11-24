package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * End-to-end acceptance for model groups (no device required): ProviderStore creates two providers and a group → the primary member fails authentication → the second member takes over →
 * usage is recorded per member → the bad member enters cooldown → the same conversation keeps using the member that took over.
 * The same flow still has to be run once on a real device (see MODEL-GROUP-PLAN.md §18).
 */
class ModelGroupAcceptanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val seen = mutableListOf<Pair<UsageSource, Usage?>>()
    private val calls = mutableMapOf<String, Int>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }, userAgent = "Claire/test") { cfg, _, _, _ ->
        ScriptedModel(cfg.model, cfg.capabilities) {
            val n = (calls[cfg.model] ?: 0) + 1
            calls[cfg.model] = n
            if (cfg.model == "m1") throw ModelError.Auth()
            ModelReply(listOf(Part.Text("from $n")), StopReason.EndTurn, Usage(10, 5))
        }
    }

    private fun caps() = Capabilities(toolCalls = true, vision = false, contextTokens = 100_000, reasoning = false)

    private fun ProviderStore.twoProviderGroup(): Pair<Provider, Provider> {
        val a = connect("A", ProviderConfig.Kind.OpenAiCompat, "https://a.test/v1", "ka", listOf(ListedModel("m1")))
        val b = connect("B", ProviderConfig.Kind.OpenAiCompat, "https://b.test/v1", "kb", listOf(ListedModel("m2")))
        val a1 = ensureConfig(a.id, "m1", caps())!!
        val b1 = ensureConfig(b.id, "m2", caps())!!
        assertTrue(saveGroup(ModelGroup("g", "主力兜底", members = listOf(GroupMember(a1, 0), GroupMember(b1, 1)))))
        assertTrue(choose(ModelRole.Main, RoleChoice.Group("g")))
        return a to b
    }

    @Test
    fun failedPrimaryFailsOverThenSticksAndCoolsDown() = runTest {
        val s = store()
        val (a, b) = s.twoProviderGroup()

        val pool = s.modelFor(ModelRole.Main, ModelSession.of("conv")) as GroupPool
        val first = pool.complete("s", listOf(userMessage("hi")), emptyList())
        assertEquals(listOf(Part.Text("from 1")), first.parts)

        // m1 throwing an auth error is still recorded (count only); m2 records tokens normally
        assertEquals(
            listOf(
                UsageSource(a.id, "A", "m1", UsagePurpose.Main) to null,
                UsageSource(b.id, "B", "m2", UsagePurpose.Main) to Usage(10, 5),
            ),
            seen,
        )
        assertEquals(1, calls["m1"])
        assertEquals(1, calls["m2"])

        // Another round on the same pool: m1 is cooling down, so it goes straight to m2; stickiness also puts m2 first
        val second = pool.complete("s", listOf(userMessage("again")), emptyList())
        assertEquals(listOf(Part.Text("from 2")), second.parts)
        assertEquals(1, calls["m1"])
        assertEquals(2, calls["m2"])

        // A new task (new pool) on the same conversation also skips m1 while it is cooling down
        val fresh = s.modelFor(ModelRole.Main, ModelSession.of("conv")) as GroupPool
        fresh.complete("s", listOf(userMessage("third")), emptyList())
        assertEquals(1, calls["m1"])
        assertEquals(3, calls["m2"])
        assertEquals("B · m2", fresh.ordered(com.wangzan101.mika.core.model.ModelNeed()).first().label)
    }

    private class ScriptedModel(
        override val name: String,
        override val capabilities: Capabilities,
        private val reply: () -> ModelReply,
    ) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = reply()
    }
}

/** Shorthand for a user message in tests; Author has to be explicit, so User is used uniformly here. */
private fun userMessage(text: String): Message =
    Message(com.wangzan101.mika.core.model.Role.User, listOf(Part.Text(text)), com.wangzan101.mika.core.model.Author.User)
