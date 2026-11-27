package com.wangzan101.mika.settings

import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.search.GoogleGroundingSearch
import com.wangzan101.mika.core.search.WebSearchResult
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.UsageKey
import com.wangzan101.mika.core.model.usage.UsageLedger
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class ProviderStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val built = mutableListOf<Pair<ProviderConfig, String?>>()
    private val endpoints = mutableListOf<ModelEndpoint?>()

    private val setups = mutableListOf<ClientSetup>()

    private fun store(dailyUsage: (String) -> Long = { 0L }) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3", dailyUsage = dailyUsage,
    ) { cfg, apiKey, endpoint, setup ->
        built += cfg to apiKey
        setups += setup
        endpoints += endpoint
        FakeModel(cfg.capabilities)
    }

    private fun cfg(id: String, tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
        id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.test/v1", model = "m-$id",
        capabilities = Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false),
        name = "P $id",
    )

    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    @Test fun nicknameSurvivesReloadAndIsNotPartOfProviderIdentity() {
        val s = store()
        val base = "https://api.deepseek.com"
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, base, "k1", emptyList(), nickname = "  Work  ")
        assertEquals("Work", p.nickname)
        // 再连同一家(换 Key、不填备注):还是这一家,备注留着,不新建
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, base, "k2", emptyList())
        assertEquals(p.id, again.id)
        assertEquals("Work", again.nickname)
        assertEquals(1, s.providers.value.size)
        // 填了别的备注也还是这一家,只是改了备注
        val renamed = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, base, null, emptyList(), nickname = "Home")
        assertEquals(p.id, renamed.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("k2", s.providerKey(p.id))

        assertEquals("Home", store().provider(p.id)!!.nickname)
        val raw = File(tmp.root, "providers.json").readText()
        assertTrue(raw.contains("\"nickname\":\"Home\""))
        assertFalse(raw.contains("k2"))
    }

    @Test fun setProviderNicknameCleansPersistsAndClears() {
        val s = store()
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "k", emptyList())
        assertNull(p.nickname)
        s.setProviderNickname(p.id, "  Personal\n ")
        assertEquals("Personal", store().provider(p.id)!!.nickname)
        s.setProviderNickname(p.id, "x".repeat(Provider.MAX_NICKNAME + 10))
        assertEquals(Provider.MAX_NICKNAME, store().provider(p.id)!!.nickname!!.length)
        s.setProviderNickname(p.id, "   ")
        assertNull(store().provider(p.id)!!.nickname)
        assertFalse(File(tmp.root, "providers.json").readText().contains("\"nickname\""))
        assertEquals(p.id, store().provider(p.id)!!.id)
        assertEquals("k", store().providerKey(p.id))
    }

    @Test fun providersFileWithoutNicknameStillLoadsAndBlankNicknameIsNull() {
        val s = store()
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "k", emptyList())
        val file = File(tmp.root, "providers.json")
        assertFalse(file.readText().contains("nickname"))
        assertNull(store().provider(p.id)!!.nickname)
        file.writeText(file.readText().replace("\"id\":\"${p.id}\"", "\"id\":\"${p.id}\",\"nickname\":\"  \""))
        assertNull(store().provider(p.id)!!.nickname)
        file.writeText(file.readText().replace("\"nickname\":\"  \"", "\"nickname\":7"))
        assertNull(store().provider(p.id)!!.nickname)
    }

    @Test fun cleanNicknameCountsCodePoints() {
        assertNull(Provider.cleanNickname(null))
        assertNull(Provider.cleanNickname(" \t "))
        val emoji = "😀".repeat(Provider.MAX_NICKNAME + 1)
        val cut = Provider.cleanNickname(emoji)!!
        assertEquals(Provider.MAX_NICKNAME, cut.codePointCount(0, cut.length))
    }

    @Test fun openAiServiceTiersSurviveReloadAndApplyPerProviderAndModel() {
        val s = store()
        val base = "https://api.openai.com/v1"
        val api = s.connect("OpenAI", ProviderConfig.Kind.OpenAiResponses, base, "api-key",
            listOf(ListedModel("gpt-x"), ListedModel("gpt-y")))
        val plan = s.connect("ChatGPT", ProviderConfig.Kind.ChatGpt, base, null, listOf(ListedModel("gpt-x")))
        assertTrue(s.use(ModelRole.Main, api.id, "gpt-x", none))
        assertTrue(s.use(ModelRole.Light, plan.id, "gpt-x", none))
        assertTrue(s.use(ModelRole.Vision, api.id, "gpt-y", none))
        assertTrue(s.setModelServiceTier(api.id, "gpt-x", OpenAiServiceTier.Fast))
        assertTrue(s.setModelServiceTier(plan.id, "gpt-x", OpenAiServiceTier.Ultrafast))

        val reloaded = store()
        reloaded.modelFor(ModelRole.Main)
        reloaded.modelForRoleConfig(ModelRole.Light, reloaded.configFor(ModelRole.Light)!!.id)
        reloaded.modelFor(ModelRole.Vision)
        assertEquals(listOf(OpenAiServiceTier.Fast, OpenAiServiceTier.Ultrafast, null), setups.map { it.serviceTier })

        assertTrue(reloaded.setModelServiceTier(api.id, "gpt-x", null))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().serviceTier)
    }

    @Test fun openAiServiceTierDoesNotApplyToThirdPartyEndpoints() {
        val s = store()
        val base = "https://api.openai.com/v1"
        val thirdPartyEndpoint = ModelEndpoint(ModelProtocol.Responses, "https://other.test/v1")
        val custom = s.connect("Custom", ProviderConfig.Kind.OpenAiResponses, thirdPartyEndpoint.baseUrl, null, emptyList())
        assertFalse(s.setModelServiceTier(custom.id, "gpt-x", OpenAiServiceTier.Fast))

        val direct = s.connect("OpenAI", ProviderConfig.Kind.OpenAiResponses, base, "api-key", emptyList())
        assertTrue(s.use(ModelRole.Main, direct.id, "gpt-x", none))
        assertTrue(s.setModelServiceTier(direct.id, "gpt-x", OpenAiServiceTier.Fast))
        s.rememberModels(direct.id, listOf(ListedModel("gpt-x", endpoint = thirdPartyEndpoint)))
        assertFalse(s.provider(direct.id)!!.supportsServiceTier("gpt-x"))
        s.modelFor(ModelRole.Main)
        assertNull(setups.last().serviceTier)
    }

    /** Save a config and make it the main model (configs nobody uses get pruned). */
    private fun ProviderStore.saveAsMain(c: ProviderConfig, apiKey: String?) {
        save(c, apiKey)
        assertTrue(choose(ModelRole.Main, RoleChoice.Model(c.id)))
    }

    // ---------- Main model fallback chain (item 3) ----------

    @Test fun mainFallbackGapSlotAppendsAtEnd() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(1, "a"))
        assertEquals(listOf("a"), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackAppendReplaceAndClear() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        assertEquals(listOf("a", "b"), s.roles.value.mainFallbacks)
        s.save(cfg("c"), "sk-c")
        assertTrue(s.setMainFallback(0, "c"))
        assertEquals(listOf("c", "b"), s.roles.value.mainFallbacks)
        assertTrue(s.setMainFallback(0, null))
        assertEquals(listOf("b"), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackMoveDedupes() {
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        assertTrue(s.setMainFallback(0, "b"))
        assertEquals(listOf("b", "a"), s.roles.value.mainFallbacks)
        assertTrue(s.setMainFallback(1, "b"))
        assertEquals(listOf("a", "b"), s.roles.value.mainFallbacks)
    }

    @Test fun movedFallbacksIsBoundedAndKeepsOthersInOrder() {
        val chain = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedFallbacks(chain, 1, 0))
        assertEquals(listOf("a", "c", "b"), movedFallbacks(chain, 1, 2))
        assertEquals(listOf("b", "c", "a"), movedFallbacks(chain, 0, 2))
        assertEquals(chain, movedFallbacks(chain, 1, 1))
        assertNull(movedFallbacks(chain, 0, -1))
        assertNull(movedFallbacks(chain, 2, 3))
        assertNull(movedFallbacks(chain, -1, 0))
        assertNull(movedFallbacks(emptyList(), 0, 0))
    }

    @Test fun moveFallbackPersistsAndOnlyTouchesThatRole() {
        val s = store()
        s.saveAsMain(cfg("m"), "sk-m")
        // 没人用的配置下一次写入时会被清掉:存一个、马上放进链
        listOf("a", "b", "c").forEachIndexed { i, id -> s.save(cfg(id), "sk-$id"); assertTrue(s.setFallback(ModelRole.Main, i, id)) }
        listOf("va", "vb").forEachIndexed { i, id ->
            s.save(cfg(id, vision = true), "sk-$id"); assertTrue(s.setFallback(ModelRole.Vision, i, id))
        }
        assertTrue(s.setFallback(ModelRole.Light, 0, "a"))
        assertTrue(s.setFallback(ModelRole.Light, 1, "b"))

        assertTrue(s.moveFallback(ModelRole.Main, 2, 1))
        assertEquals(listOf("a", "c", "b"), s.roles.value.fallbacks(ModelRole.Main))
        assertEquals(listOf("va", "vb"), s.roles.value.fallbacks(ModelRole.Vision))
        assertEquals(listOf("a", "b"), s.roles.value.fallbacks(ModelRole.Light))

        assertTrue(s.moveFallback(ModelRole.Light, 0, 1))
        assertEquals(listOf("b", "a"), s.roles.value.fallbacks(ModelRole.Light))
        assertEquals(listOf("a", "c", "b"), s.roles.value.fallbacks(ModelRole.Main))

        // 越界什么也不改
        assertFalse(s.moveFallback(ModelRole.Vision, 1, 2))
        assertFalse(s.moveFallback(ModelRole.Main, -1, 0))
        assertEquals(listOf("va", "vb"), s.roles.value.fallbacks(ModelRole.Vision))

        val reloaded = store()
        assertEquals(listOf("a", "c", "b"), reloaded.roles.value.fallbacks(ModelRole.Main))
        assertEquals(listOf("va", "vb"), reloaded.roles.value.fallbacks(ModelRole.Vision))
        assertEquals(listOf("b", "a"), reloaded.roles.value.fallbacks(ModelRole.Light))
    }

    @Test fun mainFallbackRejectsUnknownConfig() {
        val s = store()
        assertFalse(s.setMainFallback(0, "nope"))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    // ---------- Key pool wiring: 401/403 marks exhausted, 429 cools down ----------

    /** Real wiring (without swapping buildModel): the bad key that got the 401 is marked exhausted in the pool and is no longer picked by rotation; the good key takes over and completes the request. */
    @Test fun authFailureMarksKeyExhaustedThroughWiredModel() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            val c = cfg("auth-x").copy(baseUrl = server.url("/v1").toString().trimEnd('/'))
            s.saveAsMain(c, "sk-bad, sk-good")
            val pid = s.list().first { it.id == c.id }.providerId
            try {
                val m = s.modelForConfig(c.id)!!
                val reply = m.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
                assertEquals("hi", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                // The rotation position survives re-registration (#757): the two consecutive turns each use one key, without assuming which one runs first
                val first = server.takeRequest().getHeader("Authorization")
                val second = server.takeRequest().getHeader("Authorization")
                assertNotEquals(first, second)
                assertEquals(setOf("Bearer sk-bad", "Bearer sk-good"), setOf(first, second))
                // The key that got the 401 is marked exhausted: only the key that took over is available in the pool, so rotation will not pick it again
                assertEquals(2, KeyPoolManager.shared.keyCount(pid))
                assertEquals(1, KeyPoolManager.shared.activeKeyCount(pid))
                assertEquals(second?.removePrefix("Bearer "), KeyPoolManager.shared.getActiveKey(pid))
            } finally {
                KeyPoolManager.shared.reset(pid)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun mainFallbackRejectsSlotAtMax() {
        // Slots are only 0..MAX-1 (the UI shows only these too): slot == MAX must be rejected (MK-1 B-D3).
        val s = store()
        s.save(cfg("a"), "sk-a")
        assertFalse(s.setMainFallback(MAX_MAIN_FALLBACKS, "a"))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    @Test fun mainFallbackChainSkipsMainItself() {
        // If an old setting listed the main model itself among the fallbacks, it is skipped when the chain is built (MK-1 B-D2).
        // Mind the order: setMainFallback prunes configs that are not referenced yet, so assign a first and then save b.
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        val (models, _) = s.mainFallbackChain()
        assertEquals(1, models.size)
    }

    @Test fun fallbackChainIdentifiesProvidersWithTheSameDisplayNameSeparately() {
        val s = store()
        val primary = connected(s, name = "Gateway")
        val backup = s.connect("Gateway", ProviderConfig.Kind.OpenAiCompat,
            "https://backup.test/v1", "backup-key", listOf(ListedModel("m1")))
        s.use(ModelRole.Main, primary.id, "m1", none)
        s.use(ModelRole.Light, backup.id, "m1", none)
        assertTrue(s.setMainFallback(0, s.configFor(ModelRole.Light)!!.id))

        assertEquals(listOf(backup.id), s.mainFallbackChain().second)
    }

    @Test fun isMainModelMatchesCurrentMain() {
        // Used to block the main model itself when a fallback is typed by hand (MK-1 D1).
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.isMainModel(p.id, "m1"))
        assertFalse(s.isMainModel(p.id, "m2"))
        assertFalse(s.isMainModel("nope", "m1"))
    }

    @Test fun choosingFallbackAsMainClearsItFromFallbacks() {
        // Selecting a fallback as the main model: it must not stay in the fallback chain at the same time (MK-1 D2).
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val fbId = s.ensureConfigId(p.id, "m2", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        assertTrue(s.use(ModelRole.Main, p.id, "m2", none))
        assertEquals(emptyList<String>(), s.roles.value.mainFallbacks)
    }

    @Test fun storedMainDuplicateClearedOnReload() {
        // In an old file the main model itself was listed in the fallback chain: clear it on reload so the UI matches the actual chain (MK-1 D2).
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        s.save(cfg("b"), "sk-b")
        assertTrue(s.setMainFallback(1, "b"))
        val reopened = store()
        assertEquals(listOf("b"), reopened.roles.value.mainFallbacks)
    }

    @Test fun settingMainAsFallbackIsDroppedImmediately() {
        // Whichever path writes the main model into a fallback slot, it is cleared at save time instead of waiting for the next load (review T-1112-74a8f2 D2).
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertTrue(s.setMainFallback(0, "a"))
        assertTrue(s.roles.value.mainFallbacks.isEmpty())
    }

    @Test fun fallbackInheritsMainReasoningEffortWhenSupported() {
        // The main model has a reasoning effort set and the fallback declares support for the same level: carry it over when building the fallback (MK-1 B-D1).
        val s = store()
        val mainP = s.connect("MainP", ProviderConfig.Kind.OpenAiCompat, "https://mainp.test/v1", "k",
            listOf(ListedModel("m1", reasoningEfforts = listOf("low", "high"))))
        s.use(ModelRole.Main, mainP.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val fbP = s.connect("FbP", ProviderConfig.Kind.OpenAiCompat, "https://fbp.test/v1", "k",
            listOf(ListedModel("m9", reasoningEfforts = listOf("low", "high"))))
        val fbId = s.ensureConfigId(fbP.id, "m9", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        setups.clear()
        s.modelForConfig(fbId)
        assertEquals("high", setups.last().reasoningEffort)
    }

    @Test fun fallbackOmitsMainReasoningEffortWhenUnsupported() {
        // The fallback does not declare support for the level the main model uses: omit it and use its own default, so the request is not rejected because of the parameter (MK-1 B-D1).
        val s = store()
        val mainP = s.connect("MainP", ProviderConfig.Kind.OpenAiCompat, "https://mainp.test/v1", "k",
            listOf(ListedModel("m1", reasoningEfforts = listOf("low", "high"))))
        s.use(ModelRole.Main, mainP.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val fbP = s.connect("FbP", ProviderConfig.Kind.OpenAiCompat, "https://fbp.test/v1", "k",
            listOf(ListedModel("m9", reasoningEfforts = listOf("low"))))
        val fbId = s.ensureConfigId(fbP.id, "m9", none)!!
        assertTrue(s.setMainFallback(0, fbId))
        setups.clear()
        s.modelForConfig(fbId)
        assertNull(setups.last().reasoningEffort)
    }

    private fun connected(s: ProviderStore, name: String = "P", key: String = "k") =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", key, listOf(ListedModel("m1"), ListedModel("m2")))

    @Test fun overlappingLegacyRolesSurviveRestartAndRefreshUntilTheUserChooses() {
        val s = store()
        val provider = connected(s)
        assertTrue(s.use(ModelRole.Main, provider.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, provider.id, "m2", none))
        val existing = File(tmp.root, "providers.json")
        val stored = existing.readText()
        val entryStart = "\"id\":\"${provider.id}\",\"name\""
        assertTrue(entryStart in stored)
        existing.writeText(stored.replace(entryStart, "\"ttsModels\":[\"m1\"],$entryStart"))

        val reopened = store()
        // The old ttsModels field is still readable as legacy data; runtime roles are no longer filtered by it
        assertEquals(setOf("m1"), reopened.provider(provider.id)?.ttsModels)
        reopened.rememberModels(provider.id, listOf(ListedModel("m1"), ListedModel("m2")))
        assertEquals("m1", reopened.configFor(ModelRole.Main)?.model)
        assertEquals("m2", reopened.configFor(ModelRole.Vision)?.model)
        assertEquals(reopened.roles.value, store().roles.value)
    }

    @Test fun ttsAdapterBuildsForAnyNonBlankModel() {
        val s = store()
        val p = connected(s)
        val choice = VoiceChoice(p.id, " manual-tts ", TtsProtocol.OpenAiSpeech)
        val capabilities = TtsCapabilities()
        // The model no longer has to be marked as TTS; the name is trimmed and the adapter is built directly
        assertEquals("manual-tts", s.ttsModel(choice, capabilities)?.name)
        assertEquals("m1", s.ttsModel(choice.copy(model = "m1"), capabilities)?.name)
    }

    /** The address read back is invalid (hand-edited file): no read-aloud model is built and nothing throws; preview and read-aloud behave as if none were chosen. */
    @Test fun ttsAdapterIsNullForAnInvalidBaseUrl() {
        val s = store()
        val p = connected(s)
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("https://p.test/v1", "not a url"))
        val reopened = store()
        assertEquals("not a url", reopened.provider(p.id)?.baseUrl)
        assertNull(reopened.ttsModel(VoiceChoice(p.id, "tts-1", TtsProtocol.OpenAiSpeech), TtsCapabilities()))
        assertNull(reopened.ttsModel(VoiceChoice(p.id, "tts-1", TtsProtocol.GeminiTts), TtsCapabilities()))
    }

    /** Blank hand-typed names already in the file are dropped when it is read. */
    @Test fun blankTypedModelsAreDroppedOnRead() {
        val s = store()
        val p = connected(s)
        s.markTyped(p.id, "manual-tts")
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("\"typedModels\":[\"manual-tts\"]", "\"typedModels\":[\"\",\" \",\"manual-tts\"]"))
        assertEquals(setOf("manual-tts"), store().provider(p.id)?.typedModels)
    }

    @Test fun keyChangeInvalidatesAllowanceForOnlyThatProvider() {
        val store = store()
        val first = connected(store)
        val other = connected(store, "Other")
        val revisions = store.providerKeyRevisions.value
        val providers = store.providers.value

        store.updateProviderKey(first.id, "new-key")

        assertEquals(providers, store.providers.value)
        assertEquals(revisions.getValue(first.id) + 1, store.providerKeyRevisions.value[first.id])
        assertEquals(revisions[other.id], store.providerKeyRevisions.value[other.id])
        assertNull(store.allowanceKey(first, revisions.getValue(first.id)))
        assertEquals("new-key", store.allowanceKey(first, store.providerKeyRevisions.value.getValue(first.id)))
        store.removeProvider(first.id)
        assertNull(store.providerKeyRevisions.value[first.id])
    }

    // ---------- Role selection ----------

    @Test fun modelContextSurvivesPruningAndIsSharedOnlyWithinTheSameProviderAndModel() {
        val s = store()
        val first = connected(s)
        val second = connected(s, "Other")
        s.use(ModelRole.Main, first.id, "m1", none)
        s.use(ModelRole.Vision, second.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(1_000_000, 600_000)
        assertTrue(s.setModelContext(cfg.id, saved))
        for (role in listOf(ModelRole.Main, ModelRole.Light)) {
            val caps = s.modelFor(role)!!.capabilities
            assertEquals(1_000_000, caps.contextTokens)
            assertEquals(600_000, caps.compactionTriggerTokens)
        }
        assertEquals(64_000, s.modelFor(ModelRole.Vision)!!.capabilities.contextTokens)
        assertNull(s.modelFor(ModelRole.Vision)!!.capabilities.compactionTriggerTokens)
        s.use(ModelRole.Main, first.id, "m2", none)
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings()))
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(600_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(reloaded.setModelContext(reloaded.configFor(ModelRole.Main)!!.id, ModelContextSettings()))
        assertNull(store().provider(first.id)!!.contextSettings["m1"])
    }

    @Test fun perModelReadTimeoutFlowsIntoTheClientSetupAndPersists() {
        // #295: one generation from a slow local inference service (e.g. reached through a tunnel) can exceed the global default 120-second read timeout;
        // the per-model configured read timeout is stored with ModelContextSettings and passed into the ClientSetup used to build the model.
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(readTimeoutSeconds = 600)
        assertTrue(s.setModelContext(cfg.id, saved))
        s.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        reloaded.modelFor(ModelRole.Main)
        assertEquals(600, setups.last().readTimeoutSeconds)
    }

    @Test fun configuredReadTimeoutAppliesToTheRealHttpClient() = runTest {
        MockWebServer().use { server ->
            server.start()
            // The response headers deliberately arrive later than the single model's one-second read timeout; if the setting never reached OkHttp, this would not throw Network.
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Local", ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "k", listOf(ListedModel("m")))
            s.use(ModelRole.Main, p.id, "m", none)
            val cfg = s.configFor(ModelRole.Main)!!
            assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 1)))
            try {
                s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
                fail("Expected the configured read timeout")
            } catch (_: ModelError.Network) {
                // The real adapter times out while reading the response headers, not just a number stored in ClientSetup.
            }
        }
    }

    @Test fun readTimeoutMustFitOkHttpRange() {
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 0)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = -5)))
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 2_147_484)))
        assertTrue(s.setModelContext(cfg.id, ModelContextSettings(readTimeoutSeconds = 2_147_483)))
    }

    @Test fun previouslySavedOversizeReadTimeoutFallsBackWithoutLosingContextLimits() {
        val s = store()
        val first = connected(s)
        s.use(ModelRole.Main, first.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        assertTrue(s.setModelContext(cfg.id, ModelContextSettings(1_000_000, 600_000, 600)))
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace("\"readTimeoutSeconds\":600", "\"readTimeoutSeconds\":2147484"))

        val reloaded = store()
        assertEquals(ModelContextSettings(1_000_000, 600_000), reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(1_000_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
        assertNull(setups.last().readTimeoutSeconds)
    }

    /** After a preset changes its default context, configs saved with the old default catch up when read back; values the user changed and explicit per-model windows are left alone (see repairStaleContextDefaults). */
    @Test fun stalePresetWindowIsRepairedOnLoadButUserValuesAreNot() {
        val s = store()
        val deepseek = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "k", listOf(ListedModel("deepseek-flash")))
        val other = connected(s)
        s.use(ModelRole.Main, deepseek.id, "deepseek-flash", none)
        s.use(ModelRole.Light, other.id, "m1", none)
        // The user wrote a window for another config separately: leave it alone
        assertTrue(s.setModelContext(s.configFor(ModelRole.Light)!!.id, ModelContextSettings(windowTokens = 32_000)))
        val reloaded = store()
        assertEquals(1_000_000, reloaded.configFor(ModelRole.Main)!!.capabilities.contextTokens)
        // Entries whose preset default did not change (the example address matches no preset) and entries the user wrote are both left alone
        assertEquals(64_000, reloaded.configFor(ModelRole.Light)!!.capabilities.contextTokens)
    }

    @Test fun manualTriggerUsesCoreBoundsAndASmallerReselectedWindowKeepsTheStoredValue() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 1_000_000))
        val id = s.configFor(ModelRole.Main)!!.id
        for (value in listOf(500_000, 980_000, 950_000)) {
            assertTrue(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        for (value in listOf(0, 499_999, 980_001)) {
            assertFalse(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        assertFalse(s.setModelContext(id, ModelContextSettings(windowTokens = -1)))
        s.use(ModelRole.Main, p.id, "m2", none)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 32_000))
        val saved = store().provider(p.id)!!.contextSettings.getValue("m1")
        assertEquals(950_000, saved.triggerTokens)
        assertEquals(24_000, saved.limits(s.modelFor(ModelRole.Main)!!.capabilities.contextTokens).trigger)
    }

    @Test fun missingContextFieldsUseAutoAndMalformedEntriesDoNotDiscardOtherModels() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val file = File(tmp.root, "providers.json")
        val original = file.readText()
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        val entries = """{"m1":{"windowTokens":1000000},"m2":{"triggerTokens":600000},"bad":{"triggerTokens":{}}}"""
        file.writeText(original.replace("\"services\":[{", "\"services\":[{\"contextSettings\":$entries,"))
        assertEquals(mapOf("m1" to ModelContextSettings(1_000_000), "m2" to ModelContextSettings(triggerTokens = 600_000)),
            store().provider(p.id)!!.contextSettings)
    }

    @Test fun failedContextSaveLeavesBothTheStoredAndActiveModelAloneAndCanBeRetried() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val id = s.configFor(ModelRole.Main)!!.id
        val saved = ModelContextSettings(triggerTokens = 40_000)
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setModelContext(id, saved))
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(blocked.delete())
        assertNull(store().provider(p.id)!!.contextSettings["m1"])
        assertTrue(s.setModelContext(id, saved))
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m1"])
    }

    @Test
    fun freshInstallHasNoMainVisionOffAndLightSameAsMain() {
        val s = store()
        assertEquals(RoleChoices(main = null, vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.modelFor(ModelRole.Main))
        assertNull(s.modelFor(ModelRole.Vision))
        assertNull(s.modelFor(ModelRole.Light))
    }

    @Test
    fun lightSameAsMainResolvesToMainAndVisionStaysOffEvenIfMainCanSee() {
        val s = store()
        s.saveAsMain(cfg("a", vision = true), "sk-a")
        assertEquals("a", s.configFor(ModelRole.Light)?.id)
        // The implicit rule "a main model that can see images also serves vision" is gone
        assertNull(s.configFor(ModelRole.Vision))
        val m = s.modelFor(ModelRole.Main)
        assertEquals("capabilities unchanged after adding metering (temp: fold once every 16 rounds)", cfg("a", vision = true).capabilities.copy(compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS), m?.capabilities)
        assertEquals("a" to "sk-a", built.single().first.id to built.single().second)
    }

    @Test
    fun visionSameAsMainUsesMainAndMainItselfSeesUntilSwitchedAway() {
        val s = store()
        s.saveAsMain(cfg("a", vision = false), "sk-a")
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val v = s.configFor(ModelRole.Vision)!!
        assertEquals("a", v.id)
        assertTrue("user said the main model sees images, so the image path still sends screenshots", v.capabilities.vision)
        s.modelFor(ModelRole.Vision)
        assertTrue(built.last().first.capabilities.vision)
        assertEquals("sk-a", built.last().second)
        assertTrue("user said the main model sees images, so the main model also looks at screenshots when working itself", s.configFor(ModelRole.Main)!!.capabilities.vision)
        // Still there after reopening
        assertEquals(RoleChoice.SameAsMain, store().roles.value.vision)
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Off))
        assertFalse("switching away reverts to the stored flag", s.configFor(ModelRole.Main)!!.capabilities.vision)
    }

    @Test
    fun pickingTheMainModelAsVisionModelMakesMainSeeAndSwitchingAwayUndoesIt() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m1", none))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        s.modelFor(ModelRole.Main)
        assertTrue("the built main model has images", built.last().first.capabilities.vision)
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        assertFalse("images switched to another model, main model follows the list and initial value", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertFalse("stored config untouched", store().list().first { it.model == "m1" }.capabilities.vision)
    }

    @Test
    fun userSaysModelCanSeeOverridesUnknownAndPersists() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertFalse("not in the list, so follow the initial value", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val reopened = store()
        assertEquals(mapOf("m1" to true), reopened.provider(p.id)!!.visionOverrides)
        assertTrue("still there after reopening", reopened.configFor(ModelRole.Main)!!.capabilities.vision)
        // The user can turn it off too: when vision uses the main model, the main model stops attaching images as the user said, while the vision path keeps working as before
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertTrue(s.setModelVision(p.id, "m1", false))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.vision)
        assertFalse(s.setModelVision("missing", "m1", true))
    }

    @Test
    fun listingSaysNoVisionStaysNoUnlessTheUserOverrides() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1", toolCalls = true, vision = false)))
        val o = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertFalse(o.canSee)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", o.capabilities))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val after = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertTrue(after.canSee)
        assertEquals(true, after.visionByUser)
    }

    @Test
    fun visionSameAsMainWithoutMainHasNoVisionModel() {
        val s = store()
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertNull(s.configFor(ModelRole.Vision))
    }

    @Test
    fun choicesTheRoleDoesNotTakeAreRejected() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertFalse(s.choose(ModelRole.Main, RoleChoice.SameAsMain))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Off))
        assertFalse(s.choose(ModelRole.Light, RoleChoice.Off))
        assertFalse("config does not exist", s.choose(ModelRole.Main, RoleChoice.Model("zzz")))
        assertEquals(RoleChoices(main = "a"), s.roles.value)
    }

    @Test
    fun explicitChoiceIsNotBlockedByCapabilityFlagsAndMarksThem() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        val main = s.configFor(ModelRole.Main)!!
        val vision = s.configFor(ModelRole.Vision)!!
        assertEquals("m1" to "m2", main.model to vision.model)
        assertTrue("choosing it as main model records tool calls", main.capabilities.toolCalls)
        assertTrue("chosen for images counts as seeing images", vision.capabilities.vision)
        // The light role requires no capabilities at all
        assertTrue(s.use(ModelRole.Light, p.id, "m2", none))
        assertEquals(vision.id, s.configFor(ModelRole.Light)?.id)
        assertFalse(s.use(ModelRole.Main, "missing", "m1", none))
    }

    @Test
    fun usingTheSameModelReusesItsConfig() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        assertEquals(1, s.list().size)
        assertEquals(s.roles.value.main, (s.roles.value.light as RoleChoice.Model).configId)
        assertEquals("k", s.apiKey(s.roles.value.main!!))
    }

    @Test
    fun lightUsersGetTheLightModelAndNotTheMainOne() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        built.clear()
        s.modelFor(ModelRole.Light)
        assertEquals("m2" to "k", built.single().first.model to built.single().second)
        built.clear()
        s.modelFor(ModelRole.Main)
        assertEquals("m1", built.single().first.model)
    }

    // ---------- Configs nobody uses are deleted ----------

    @Test
    fun configsNoRoleUsesAreDeletedButTheProviderKeyStays() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertEquals(listOf("m1", "m2"), s.list().map { it.model })
        // Switch the light role back to the main model: nothing uses m2 anymore
        assertTrue(s.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals(listOf("m1"), s.list().map { it.model })
        // Switch the main model: nothing uses m1 anymore
        s.use(ModelRole.Main, p.id, "m2", none)
        assertEquals(listOf("m2"), s.list().map { it.model })
        assertEquals("k", s.providerKey(p.id))
        assertEquals("k", s.apiKey(s.list().single().id))
        assertEquals(listOf("m2"), store().list().map { it.model })
    }

    @Test
    fun visionSameAsMainKeepsTheMainConfigAndNothingElse() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertEquals(listOf("m1"), s.list().map { it.model })
        assertEquals(setOf(s.roles.value.main), s.roles.value.referenced)
    }

    @Test
    fun loadDropsUnusedConfigsAndChoicesPointingNowhere() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("b"), null)
        assertEquals(2, s.list().size)
        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        // Hand-broken file: vision points at a config that does not exist, so it falls back to Off
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace("\"Vision\":{\"use\":\"none\"}", "\"Vision\":{\"use\":\"model\",\"config\":\"gone\"}"))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    // ---------- Key ----------

    @Test
    fun keyIsNotStoredInPlainPrefs() {
        val secret = "sk-very-secret-0123456789"
        store().saveAsMain(cfg("a"), secret)
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("plaintext key found in ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        assertEquals(secret, store().apiKey("a"))
    }

    @Test
    fun saveWithNullKeyKeepsExistingKey() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("a").copy(model = "other"), null)
        assertEquals("sk-a", s.apiKey("a"))
        assertEquals("other", s.list().single().model)
        assertTrue(s.hasKey(s.list().single().providerId))
    }

    @Test
    fun undecryptableKeyReadsAsMissing() {
        store().saveAsMain(cfg("a"), "sk-a")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(ProviderStore(tmp.root, AesGcmKeyCipher { otherKey }).apiKey("a"))
    }

    /** Model names that passed the test call are stored with the provider; connecting the same provider again merges them; old files without this field read as empty. */
    @Test
    fun verifiedModelsPersist() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), verified = setOf("a"))
        assertEquals(setOf("a"), p.verifiedModels)
        s.markVerified(p.id, "b")
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", null, emptyList(), verified = setOf("c"))
        assertEquals(setOf("a", "b", "c"), store().provider(p.id)?.verifiedModels)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(Regex(",\"verifiedModels\":\\[[^\\]]*\\]"), ""))
        assertFalse(f.readText().contains("verifiedModels"))
        assertEquals(emptySet<String>(), store().provider(p.id)?.verifiedModels)
    }

    @Test
    fun connectedProviderWithoutModelsPersistsWithItsListAndKey() {
        val s = store()
        val listed = listOf(ListedModel("a", displayName = "A", toolCalls = true, contextTokens = 128_000), ListedModel("b", vision = false))
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "sk-d", listed)
        assertTrue("connected but no model chosen yet", s.list().isEmpty())
        assertNull(s.roles.value.main)
        val reopened = store()
        assertEquals(listOf(p), reopened.providers.value)
        assertEquals(listed, reopened.providers.value.single().models)
        assertEquals("sk-d", reopened.providerKey(p.id))
        // Connect the same provider again: update the key and the list without adding a duplicate entry
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "sk-new", listOf(ListedModel("c")))
        assertEquals(p.id, again.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("sk-new", s.providerKey(p.id))
        assertEquals(listOf("c"), store().providers.value.single().models!!.map { it.id })
        // Never listed is null; listed but empty is an empty list
        s.rememberModels(p.id, emptyList())
        assertEquals(emptyList<ListedModel>(), store().providers.value.single().models)
    }

    // ---------- Saved directly without testing ----------

    /** A provider saved by skipping the test carries the flag, and it is still there after re-reading the file (files from older versions lack this field and count as tested). */
    @Test
    fun untestedProviderPersistsItsFlag() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        assertTrue(p.savedUntested)
        assertTrue("still there after reloading", store().provider(p.id)!!.savedUntested)
        // Providers that were not saved by skipping the test do not carry this flag
        val other = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", emptyList())
        assertFalse(other.savedUntested)
        assertFalse(store().provider(other.id)!!.savedUntested)
    }

    /** Once the model list has been fetched or a single model has passed the test call, "not tested" no longer holds. */
    @Test
    fun listingOrVerifyingClearsTheUntestedFlag() {
        val s = store()
        val listed = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.rememberModels(listed.id, listOf(ListedModel("m1")))
        assertFalse("being listed counts as tested", store().provider(listed.id)!!.savedUntested)

        val verified = s.connect("Z", ProviderConfig.Kind.OpenAiCompat, "https://z.test/v1", "k", emptyList(), untested = true)
        assertTrue(s.provider(verified.id)!!.savedUntested)
        s.markVerified(verified.id, "m1")
        assertFalse("verified counts as tested", s.provider(verified.id)!!.savedUntested)
        assertEquals(setOf("m1"), s.provider(verified.id)!!.verifiedModels)
    }

    /** Returns the main model's provider while that provider has not passed a test (no list, hand-typed, unverified); it does not count once the model is verified or the provider has a list. */
    @Test
    fun untestedMainProviderFollowsTheMainModel() {
        val s = store()
        assertNull("no main model", s.untestedMainProvider())
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.markTyped(p.id, "m1")
        s.use(ModelRole.Main, p.id, "m1", none)
        assertEquals(p.id, s.untestedMainProvider()?.id)
        s.markVerified(p.id, "m1")
        assertNull("test passed", s.untestedMainProvider())
        val listed = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", listOf(ListedModel("m2")))
        s.use(ModelRole.Main, listed.id, "m2", none)
        assertNull("chosen from the list does not count", s.untestedMainProvider())
    }

    /** Saving with the test skipped again for a provider whose models were already listed: the existing list is kept, not overwritten by an empty one, and it still counts as "tested". */
    @Test
    fun savingUntestedAgainKeepsAnExistingModelList() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listOf(ListedModel("m1")))
        val again = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k2", emptyList(), untested = true)
        assertEquals(p.id, again.id)
        assertEquals(listOf("m1"), again.models!!.map { it.id })
        assertFalse("already listed, so not untested", again.savedUntested)
        assertEquals("k2", s.providerKey(p.id))
    }

    @Test
    fun modelsOfOneProviderShareOneKey() {
        val s = store()
        val p = connected(s, key = "k1")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.updateProviderKey(p.id, "k2")
        assertEquals(listOf("k2", "k2"), s.list().map { s.apiKey(it.id) })
        s.modelFor(ModelRole.Main)
        assertEquals("k2", built.last().second)
        // The key file holds only one copy (stored per provider)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(p.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun localServiceWithoutKeyHasNoKey() {
        val s = store()
        val p = s.connect("local", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        assertFalse(s.hasKey(p.id))
    }

    @Test
    fun configsExposeCurrentList() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertEquals(s.list(), s.configs.value)
        assertEquals(1, s.configs.value.size)
    }

    // ---------- Removing a provider ----------

    @Test
    fun removeProviderDropsItsModelsAndKeyAndResetsItsRoles() {
        val s = store()
        val p = connected(s, key = "k1")
        s.save(cfg("other"), "sk-o")
        s.choose(ModelRole.Main, RoleChoice.Model("other"))
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        s.removeProvider(p.id)
        assertEquals(listOf("other"), s.list().map { it.id })
        assertEquals(RoleChoices(main = "other", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.providerKey(p.id))
        assertEquals(listOf("P other"), s.providers.value.map { it.name })
        // Data is consistent after reopening
        val reopened = store()
        assertEquals(listOf("other"), reopened.list().map { it.id })
        assertEquals(s.roles.value, reopened.roles.value)
        assertNull(reopened.providerKey(p.id))
        assertEquals("sk-o", reopened.apiKey("other"))
    }

    @Test
    fun removingTheMainProviderLeavesMainUnsetAndKeepsOtherRoles() {
        val s = store()
        val a = connected(s, "A")
        val b = connected(s, "B")
        s.use(ModelRole.Main, a.id, "m1", none)
        s.use(ModelRole.Vision, b.id, "m1", none)
        s.removeProvider(a.id)
        assertNull(s.roles.value.main)
        assertEquals(b.id, s.configFor(ModelRole.Vision)?.providerId)
        // Reopening does not treat the remaining config as the main model (that was the rule in older versions)
        assertNull(store().roles.value.main)
        assertEquals(b.id, store().configFor(ModelRole.Vision)?.providerId)
    }

    // ---------- Files from older versions ----------

    private fun enc(k: String): String = java.util.Base64.getEncoder().encodeToString(AesGcmKeyCipher { key }.encrypt(k.toByteArray()))

    private fun legacy(id: String, name: String, url: String, model: String, vision: Boolean = false) =
        """{"id":"$id","name":"$name","kind":"OpenAiCompat","baseUrl":"$url","model":"$model","toolCalls":true,"vision":$vision,""" +
            """"contextTokens":64000,"reasoning":false,"groundingVerified":false,"promptCaching":"implicit"}"""

    /** Files from older versions: model configs only, keys stored by config id, and roles record only the explicit choices. */
    private fun writeLegacyFiles(roles: String = """{"Main":"c2","Vision":"c3"}""") {
        File(tmp.root, "providers.json").writeText(
            """{"providers":[""" +
                legacy("c1", "DeepSeek", "https://api.deepseek.com", "deepseek-chat") + "," +
                legacy("c2", "DeepSeek", "https://api.deepseek.com/", "deepseek-reasoner") + "," +
                legacy("c3", "", "http://192.168.1.5:8000/v1", "local", vision = true) +
                """],"roles":$roles}""",
        )
        File(tmp.root, "provider-keys.json").writeText("""{"c1":"${enc("sk-ds")}","c2":"${enc("sk-ds")}","c3":"${enc("sk-local")}"}""")
    }

    @Test
    fun legacyConfigsAreGroupedIntoProvidersOnLoad() {
        writeLegacyFiles()
        val s = store()
        assertEquals(listOf("DeepSeek", "192.168.1.5"), s.providers.value.map { it.name })
        val ds = s.providers.value[0]
        val local = s.providers.value[1]
        assertNull("models not listed yet", ds.models)
        // Keys move to the providers
        assertEquals("sk-ds", s.providerKey(ds.id))
        assertEquals("sk-local", s.providerKey(local.id))
        // Explicit choices stay as they were; c1 is deleted because nobody uses it
        assertEquals(RoleChoices(main = "c2", vision = RoleChoice.Model("c3"), light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c2", "c3"), s.list().map { it.id })
        assertEquals(listOf(ds.id, local.id), s.list().map { it.providerId })
        assertEquals("deepseek-reasoner", s.configFor(ModelRole.Main)?.model)
        s.modelFor(ModelRole.Vision)
        assertEquals("c3" to "sk-local", built.last().first.id to built.last().second)
        // Old keys stored by config id are cleared
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(ds.id, local.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun legacyDefaultsAreWrittenOutAsTheyWorked() {
        // No main model specified: the first config was used back then; it cannot see images, so vision stays Off; light follows the main model
        writeLegacyFiles(roles = "{}")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c1"), s.list().map { it.id })
        assertTrue(File(tmp.root, "providers.json").readText().contains("\"uses\""))
    }

    @Test
    fun legacyMainThatCanSeeKeepsServingVision() {
        writeLegacyFiles(roles = """{"Main":"c3","Light":"c1"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c3", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("c1")), s.roles.value)
        assertEquals("c3", s.configFor(ModelRole.Vision)?.id)
        assertEquals(listOf("c1", "c3"), s.list().map { it.id })
    }

    @Test
    fun legacyRolesPointingToMissingConfigsFallBackToTheOldDefaults() {
        writeLegacyFiles(roles = """{"Main":"gone","Vision":"gone"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
    }

    @Test
    fun migrationRunsOnceAndRepeatsWithoutSideEffects() {
        writeLegacyFiles(roles = """{"Main":"c3"}""")
        val first = store()
        val providers = first.providers.value
        val configs = first.list()
        val roles = first.roles.value
        val configText = File(tmp.root, "providers.json").readText()
        val keyText = File(tmp.root, "provider-keys.json").readText()
        repeat(2) {
            val again = store()
            assertEquals(providers, again.providers.value)
            assertEquals(configs, again.list())
            assertEquals(roles, again.roles.value)
            assertEquals(configText, File(tmp.root, "providers.json").readText())
            assertEquals(keyText, File(tmp.root, "provider-keys.json").readText())
        }
    }

    @Test
    fun interruptedMigrationIsRedoneWithoutLosingKeys() {
        writeLegacyFiles()
        store()
        // Simulate an interruption mid-way: the config file is back in the old format, and both the old and the new copies exist in the key file
        val migratedKeys = File(tmp.root, "provider-keys.json").readText()
        writeLegacyFiles()
        val legacyKeys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
            as kotlinx.serialization.json.JsonObject
        val newKeys = kotlinx.serialization.json.Json.parseToJsonElement(migratedKeys) as kotlinx.serialization.json.JsonObject
        File(tmp.root, "provider-keys.json").writeText(kotlinx.serialization.json.JsonObject(legacyKeys + newKeys).toString())
        val s = store()
        assertEquals(2, s.providers.value.size)
        assertEquals(listOf("sk-ds", "sk-local"), listOf("c2", "c3").map { s.apiKey(it) })
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(s.providers.value.map { it.id }.toSet(), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    // ---------- Vision grounding verified ----------

    @Test
    fun groundingVerifiedFollowsTheVisionModelAndPersists() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        // Does nothing when there is no vision model
        s.setVisionGroundingVerified(true)
        assertFalse(s.list().single().capabilities.groundingVerified)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
        assertTrue(store().configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        // When vision uses the main model, the main model's entry is the one changed
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertFalse(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
    }

    @Test
    fun roleChoicesResolveAndValidate() {
        val r = RoleChoices(main = "a", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("b"))
        assertEquals("a", r.resolve(ModelRole.Vision))
        assertEquals("b", r.resolve(ModelRole.Light))
        assertFalse(r.sameModel(ModelRole.Main, ModelRole.Light))
        assertTrue(r.sameModel(ModelRole.Main, ModelRole.Vision))
        assertTrue(RoleChoices(main = "a").sameModel(ModelRole.Main, ModelRole.Light))
        assertFalse(RoleChoices().sameModel(ModelRole.Main, ModelRole.Light))
        assertEquals(setOf("a", "b"), r.referenced)
        assertNull(RoleChoices().resolve(ModelRole.Light))
        assertNull(r.with(ModelRole.Light, RoleChoice.Off))
        assertNull(r.with(ModelRole.Main, RoleChoice.SameAsMain))
        assertEquals(RoleChoices(main = null, vision = RoleChoice.SameAsMain, light = RoleChoice.SameAsMain), r.keepOnly(emptySet()))
    }

    // ---------- Per-model endpoints ----------

    private val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://x.test/openai/v1", "reasoning_content")
    private val messages = ModelEndpoint(ModelProtocol.Messages, "https://x.test/anthropic/v1")
    private val generate = ModelEndpoint(ModelProtocol.GenerateContent, "https://x.test/google/v1beta")
    private val responses = ModelEndpoint(ModelProtocol.Responses, "https://x.test/openai/v1/")

    @Test
    fun perModelEndpointsPersistWithTheList() {
        val s = store()
        val listed = listOf(
            ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("c", endpoint = generate),
            ListedModel("r", endpoint = responses), ListedModel("u", unsupportedApis = listOf("realtime")), ListedModel("plain"),
        )
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listed)
        assertEquals(listed, store().providers.value.single().models)
    }

    @Test
    fun eachModelIsBuiltWithTheEndpointItsListingNamed() = runTest {
        val s = store()
        val p = s.connect(
            "X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k",
            listOf(ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("plain")),
        )
        s.use(ModelRole.Main, p.id, "b", none)
        s.use(ModelRole.Light, p.id, "plain", none)
        store().modelFor(ModelRole.Main)
        store().modelFor(ModelRole.Light)
        assertEquals(listOf(messages, null), endpoints)
        assertEquals(listOf("k", "k"), built.map { it.second })
        // Listed again with a changed address: the next model build uses the new one
        val moved = messages.copy(baseUrl = "https://y.test/anthropic/v1")
        s.rememberModels(p.id, listOf(ListedModel("b", endpoint = moved)))
        s.modelFor(ModelRole.Main)
        assertEquals(moved, endpoints.last())
    }

    @Test
    fun adapterAndBaseUrlFollowTheProtocolWithoutRepeatingTheVersionPath() {
        val c = cfg("1")
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://example.test/v1", ProviderStore.adapterFor(c, null))
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://x.test/openai/v1", ProviderStore.adapterFor(c, chat))
        assertEquals(ProviderConfig.Kind.Anthropic to "https://x.test/anthropic", ProviderStore.adapterFor(c, messages))
        assertEquals(ProviderConfig.Kind.Gemini to "https://x.test/google", ProviderStore.adapterFor(c, generate))
        assertEquals(ProviderConfig.Kind.OpenAiResponses to "https://x.test/openai/v1", ProviderStore.adapterFor(c, responses))
        val anthropicConfig = c.copy(kind = ProviderConfig.Kind.Anthropic, baseUrl = "https://api.anthropic.com")
        assertEquals(ProviderConfig.Kind.Anthropic to "https://api.anthropic.com", ProviderStore.adapterFor(anthropicConfig, null))
    }

    // ---------- Request headers ----------

    /** Providers that declare a session header: models built within one conversation carry the same session id, and a different conversation gets a different one; every model carries the User-Agent. */
    @Test
    fun sessionHeaderIsStableWithinAConversationAndDiffersAcross() {
        val s = store()
        val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(ListedModel("m1")), sessionHeader = "x-test-session")
        s.use(ModelRole.Main, p.id, "m1", none)
        val a = ModelSession.of("conv-a")
        s.modelFor(ModelRole.Main, a); s.modelFor(ModelRole.Light, a); s.modelFor(ModelRole.Main, ModelSession.of("conv-b"))
        val sent = setups.map { it.headers["x-test-session"] }
        assertEquals(listOf(a.id, a.id, ModelSession.of("conv-b").id), sent)
        assertTrue(sent[0] != sent[2])
        assertTrue(setups.all { it.headers["User-Agent"] == "Claire/1.2.3" })
    }

    /** The listing routes the model to Responses (a model declaring only that protocol under the same provider): it goes through the real Responses adapter and still carries the client identifier and the session id. */
    @Test
    fun responsesRoutedModelCarriesTheSessionHeader() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            val responsesBase = server.url("/v1").toString()
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, responsesBase))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed), sessionHeader = "x-test-session")
            s.use(ModelRole.Main, p.id, "r-only", none)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val session = ModelSession.of("conv-a")
            s.modelFor(ModelRole.Main, session)!!.complete("s", emptyList(), emptyList())
            val req = server.takeRequest()
            assertEquals("/v1/responses", req.path)
            assertEquals("Claire/1.2.3", req.getHeader("User-Agent"))
            assertEquals(session.id, req.getHeader("x-test-session"))
            assertEquals("Bearer k", req.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noSessionHeaderForProvidersThatDoNotDeclareIt() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.modelFor(ModelRole.Main, ModelSession.of("conv-a"))
        assertEquals(mapOf("User-Agent" to "Claire/1.2.3"), setups.single().headers)
    }

    /** The session header is stored with the provider; providers saved by older versions (without this field) get it from the preset whose address matches, so preset addresses carry it. */
    @Test
    fun sessionHeaderPersistsAndIsFilledFromThePresetForOldFiles() {
        val goUrl = ProviderPresets.all.first { it.sessionHeader != null }
        val s = store()
        val p = s.connect(goUrl.label, goUrl.kind, goUrl.baseUrl, "k", emptyList())
        assertEquals(goUrl.sessionHeader, p.sessionHeader)
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(",\"sessionHeader\":\"${goUrl.sessionHeader}\"", ""))
        assertFalse(f.readText().contains("sessionHeader"))
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        assertTrue("written back", f.readText().contains("sessionHeader"))
    }

    /** The test call goes through the real adapter: it carries the client identifier, the declared session header and a trial tool; only a tool call in the reply counts as a pass, and server errors are passed back unchanged. */
    @Test
    fun probeGoesThroughTheRealAdapterWithHeaders() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            val url = server.url("/v1").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", "x-test-session", "m", null, none))
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Claire/1.2.3", req.getHeader("User-Agent"))
            assertTrue(req.getHeader("x-test-session").orEmpty().isNotEmpty())
            assertEquals("Bearer sk", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body, body.contains("\"tools\"") && body.contains("get_time"))
            assertFalse("Chat Completions does not send a reply limit", body.contains("max_tokens"))

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"type":"MissingSessionID"}}"""))
            val e = s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", null, "m", null, none) as ProbeResult.Failed
            assertTrue(e.error.message.orEmpty(), e.error.message.orEmpty().contains("MissingSessionID"))
            assertNull(server.takeRequest().getHeader("x-test-session"))

            // Responses gets no reply cap; a function call in the reply counts as a pass
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiResponses, url, "sk", null, "m", null, none))
            val responses = server.takeRequest()
            assertEquals("/v1/responses", responses.path)
            assertEquals("Claire/1.2.3", responses.getHeader("User-Agent"))
            val responsesBody = responses.body.readUtf8()
            assertFalse(responsesBody.contains("max_output_tokens"))
            assertTrue(responsesBody.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    /** The endpoint works but the model only replied with text and called no tool: not a failure, but the two must be distinguishable. */
    @Test
    fun probeThatGetsOnlyTextReportsNoToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"It is noon."},"finish_reason":"stop"}]}"""))
            assertEquals(ProbeResult.NoToolCall, s.probe(ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "sk", null, "m", null, none))
        } finally {
            server.shutdown()
        }
    }

    /** The test call leaves enough of the Messages reply limit for one tool call (16 tokens is not enough). */
    @Test
    fun probeLeavesRoomForAToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"get_time","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.Anthropic, server.url("/").toString(), "sk", null, "m", null, none))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"max_tokens\":1024"))
            assertTrue(body.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    // ---------- Usage ----------

    @Test
    fun everyModelHandedOutIsMeteredUnderTheRoleItWasAskedFor() = runTest {
        val seen = mutableListOf<Pair<UsageSource, Usage?>>()
        val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val p = connected(s, name = "甲")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Vision)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(
            listOf(
                UsageSource(p.id, "甲", "m1", UsagePurpose.Main),
                UsageSource(p.id, "甲", "m1", UsagePurpose.Vision),
                UsageSource(p.id, "甲", "m2", UsagePurpose.Light),
            ),
            seen.map { it.first },
        )
        assertEquals(Usage(3, 2), seen.first().second)
    }

    @Test fun dailyTokenBudgetPersistsClearsAndRejectsInvalidValues() {
        val s = store()
        val p = connected(s)
        assertNull(s.provider(p.id)!!.dailyTokenBudget)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        assertEquals(10_000L, store().provider(p.id)!!.dailyTokenBudget)
        for (invalid in listOf(0L, 9_999L, 100_000_001L)) {
            assertFalse(s.setDailyTokenBudget(p.id, invalid))
            assertEquals(10_000L, s.provider(p.id)!!.dailyTokenBudget)
        }
        assertTrue(s.setDailyTokenBudget(p.id, null))
        assertNull(store().provider(p.id)!!.dailyTokenBudget)
        assertFalse(s.setDailyTokenBudget("missing", 10_000))
    }

    @Test fun malformedSavedDailyBudgetLoadsAsUnlimited() {
        val s = store()
        val p = connected(s)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        val file = File(tmp.root, "providers.json")
        file.writeText(file.readText().replace("\"dailyTokenBudget\":10000", "\"dailyTokenBudget\":1"))
        assertNull(store().provider(p.id)!!.dailyTokenBudget)
    }

    @Test fun failedDailyBudgetSaveLeavesTheActiveAndPersistedValuesAlone() {
        val s = store()
        val p = connected(s)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setDailyTokenBudget(p.id, 20_000))
        assertEquals(10_000L, s.provider(p.id)!!.dailyTokenBudget)
        assertTrue(blocked.delete())
        assertEquals(10_000L, store().provider(p.id)!!.dailyTokenBudget)
    }

    @Test fun dailyBudgetRejectsOnlyItsOwnProviderWithoutAffectingOtherCalls() = runTest {
        val used = mutableMapOf<String, Long>()
        val seen = mutableListOf<UsageSource>()
        val s = ProviderStore(
            tmp.root, AesGcmKeyCipher { key }, dailyUsage = { used[it] ?: 0 },
            usage = { source, _ -> seen += source },
        ) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val limited = connected(s, name = "Limited")
        val available = connected(s, name = "Available")
        s.use(ModelRole.Main, limited.id, "m1", none)
        s.use(ModelRole.Light, available.id, "m1", none)
        assertTrue(s.setDailyTokenBudget(limited.id, 10_000))
        assertTrue(s.setDailyTokenBudget(available.id, 10_000))
        used[limited.id] = 10_000
        used[available.id] = 9_999

        try {
            s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
            fail("a provider that hit its daily budget should stop the next request")
        } catch (e: ModelError.DailyBudgetExceeded) {
            assertEquals("Limited", e.providerName)
            assertEquals(limited.id, e.providerId)
            assertEquals(10_000L, e.limit)
            assertEquals(10_000L, e.used)
        }
        assertTrue("a rejected call is not recorded as a nonexistent usage", seen.isEmpty())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(listOf(available.id), seen.map { it.providerId })
    }

    /** Proactive capability and provider each have their own budget: the real ledger records usage and is also queried for today's usage. */
    private class Budgets(dir: File) {
        var clock = java.time.Instant.parse("2026-11-10T04:00:00Z").toEpochMilli()
        val ledger = UsageLedger(File(dir, "usage.json"), now = { clock }, flushDelayMs = 60_000, zone = { java.time.ZoneOffset.UTC })
        var proactiveLimit = ProactiveBudget.DEFAULT_DAILY_LIMIT
    }

    private fun budgeted(b: Budgets) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key }, usage = b.ledger, dailyUsage = b.ledger::providerTokensToday,
        proactiveUsage = b.ledger::proactiveTokensToday, proactiveLimit = { b.proactiveLimit },
        proactiveCalls = b.ledger::proactiveCallsToday,
    ) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }

    @Test fun proactiveCallIgnoresTheProviderDailyBudgetAndDoesNotCountTowardsIt() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Main), Usage(10_000, 0))

        try {
            s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
            fail("the provider's budget for today is used up")
        } catch (_: ModelError.DailyBudgetExceeded) {}
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())

        assertEquals("proactive usage does not touch the provider's 'used today'", 10_000L, b.ledger.providerTokensToday(p.id))
        assertEquals(5L, b.ledger.proactiveTokensToday())
        val proactive = b.ledger.snapshot().rows.single { it.key.purpose == UsagePurpose.Proactive }
        assertEquals("when the light model uses the main model, usage is recorded under the main model's config", UsageKey(p.id, "m1", UsagePurpose.Proactive), proactive.key)
    }

    @Test fun exhaustedProactiveBudgetRefusesOnlyProactiveCalls() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertTrue(s.setDailyTokenBudget(p.id, 10_000))
        b.proactiveLimit = 10_000
        // Proactive usage recorded under another provider also counts against the same budget
        b.ledger.record(UsageSource("other", "O", "x", UsagePurpose.Proactive), Usage(10_000, 0))
        val before = b.ledger.snapshot()

        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("proactive budget for today is used up")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertEquals(10_000L, e.limit)
            assertEquals(10_000L, e.used)
        }
        assertEquals("a rejected call is not recorded as a nonexistent usage", before, b.ledger.snapshot())

        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        assertEquals(5L, b.ledger.providerTokensToday(p.id))

        // Recovers the next day
        b.clock += 24 * UsageLedger.HOUR_MS
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
        assertEquals("light model set to m2 uses m2", "m2", b.ledger.snapshot().rows.last { it.key.purpose == UsagePurpose.Proactive && it.key.providerId == p.id }.key.model)
    }

    /** When the provider reports no usage the token count does not grow, so calls stop at the request-count limit (800 calls at the default 200k tokens); main-model calls are unaffected. */
    @Test fun proactiveCallsStopAtTheDailyCallLimitWhenNoUsageIsReported() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val cap = ProactiveBudget.callLimit(b.proactiveLimit)
        assertEquals(800L, cap)
        repeat(cap.toInt()) {
            b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null)
        }
        assertEquals(0L, b.ledger.proactiveTokensToday())
        assertEquals(cap, b.ledger.proactiveCallsToday())

        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("proactive request count for today reached the limit")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
        }
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
    }

    /**
     * With only one request left in the quota, the adapter wants to send another request within the same call (here a Responses service that rejects the include parameter: after a 400 it retries without it):
     * the retry is not sent and the count stops at the limit; the request already sent is recorded as it happened. This uses the real adapter and ledger.
     */
    @Test fun proactiveRetryInsideOneCallStopsAtTheCallLimit() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val b = Budgets(tmp.newFolder())
            val s = ProviderStore(
                tmp.root, AesGcmKeyCipher { key }, usage = b.ledger, dailyUsage = b.ledger::providerTokensToday,
                proactiveUsage = b.ledger::proactiveTokensToday, proactiveLimit = { b.proactiveLimit },
                proactiveCalls = b.ledger::proactiveCallsToday,
            )
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, server.url("/v1").toString()))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed))
            s.use(ModelRole.Main, p.id, "r-only", none)
            val cap = ProactiveBudget.callLimit(b.proactiveLimit)
            repeat(cap.toInt() - 1) { b.ledger.record(UsageSource(p.id, "Go", "r-only", UsagePurpose.Proactive), null) }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400)
                .setBody("""{"error":{"param":"include","message":"reasoning.encrypted_content unsupported"}}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            try {
                s.proactiveModel()!!.complete("s", emptyList(), emptyList())
                fail("only one quota left, so the resent request should not go out")
            } catch (e: ModelError.ProactiveBudgetExceeded) {
                assertTrue(e.byCalls)
            }
            assertEquals(1, server.requestCount)
            assertEquals(cap, b.ledger.proactiveCallsToday())
        } finally {
            server.shutdown()
        }
    }

    /** Triage stops itself once it reaches eighty percent (inside TriageRunner); direct proactive calls can still use the whole budget. */
    @Test fun directProactiveCallsMayUseTheWholeBudgetPastTheTriageShare() = runTest {
        val b = Budgets(tmp.newFolder())
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val src = UsageSource(p.id, "P", "m1", UsagePurpose.Proactive)
        b.ledger.record(src, Usage(ProactiveBudget.triageShare(b.proactiveLimit).toInt(), 0))
        repeat(ProactiveBudget.triageShare(ProactiveBudget.callLimit(b.proactiveLimit)).toInt() - 1) { b.ledger.record(src, null) }
        assertEquals(ProactiveBudget.Limit.Tokens,
            ProactiveBudget.triageExhausted(b.ledger.proactiveTokensToday(), b.ledger.proactiveCallsToday(), b.proactiveLimit))
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
    }

    /** The request-count limit follows the token limit: after raising the token limit, a provider that reports no usage no longer stops at 200 calls. */
    @Test fun raisingTheTokenLimitRaisesTheDerivedCallLimit() = runTest {
        val b = Budgets(tmp.newFolder())
        b.proactiveLimit = 50_000
        val s = budgeted(b)
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        repeat(200) { b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null) }
        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("at 50k tokens the request cap is the floor of 200")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertTrue(e.byCalls)
            assertEquals(200L, e.limit)
        }
        b.proactiveLimit = 2_000_000
        s.proactiveModel()!!.complete("s", emptyList(), emptyList())
        repeat((8_000 - b.ledger.proactiveCallsToday()).toInt()) { b.ledger.record(UsageSource(p.id, "P", "m1", UsagePurpose.Proactive), null) }
        assertEquals(8_000L, b.ledger.proactiveCallsToday())
        try {
            s.proactiveModel()!!.complete("s", emptyList(), emptyList())
            fail("at 2M tokens the request cap is 8000")
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            assertEquals(8_000L, e.limit)
        }
    }

    @Test fun proactiveModelIsNullWithoutAnyModel() {
        assertNull(budgeted(Budgets(tmp.newFolder())).proactiveModel())
    }

    /** The proactive capability model: the reply is capped short; when the provider declares levels, the lowest one is used, and otherwise the level chosen for this purpose applies; nothing outside the proactive capability is affected. */
    @Test fun proactiveModelIsBuiltWithACapAndTheLowestDeclaredEffort() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(
            ListedModel("m1", reasoningEfforts = listOf("high", "low", "minimal", "weird")),
            ListedModel("m2"),
        ))
        s.use(ModelRole.Main, p.id, "m1", none)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        s.proactiveModel()
        assertEquals("minimal", setups.last().reasoningEffort)
        assertEquals(ProactiveModelSetup.CAP_THINKING, setups.last().maxOutputTokens)
        // The kind of request is carried all the way into the settings used to build the model: long-reply calls (daily summaries and the like) get no extra cap, and the level is still the lowest one
        for (call in listOf(ProactiveCall.Notes, ProactiveCall.Day, ProactiveCall.Span)) {
            s.proactiveTargeted(call)
            assertNull(call.name, setups.last().maxOutputTokens)
            assertEquals("minimal", setups.last().reasoningEffort)
        }
        s.proactiveTargeted(ProactiveCall.Learn)
        assertEquals(ProactiveModelSetup.CAP_THINKING, setups.last().maxOutputTokens)
        s.modelFor(ModelRole.Main)
        assertEquals("user tasks still use the selected tier", "high", setups.last().reasoningEffort)
        assertNull(setups.last().maxOutputTokens)
        assertEquals(TriageTargetOf(p.id, "P", "m1"), s.proactiveTarget())

        // The light model picks m2 separately (the provider declares no levels): do not guess, send no level
        s.use(ModelRole.Light, p.id, "m2", none)
        s.proactiveModel()
        assertNull(setups.last().reasoningEffort)
        assertEquals(TriageTargetOf(p.id, "P", "m2"), s.proactiveTarget())
    }

    @Test fun proactiveCapIsSmallOnlyWhenThinkingIsOff() {
        val short = ProactiveCall.Triage
        val listed = ReasoningOptions(listOf("none", "low", "high"), fromProvider = true)
        assertEquals("none" to ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, listed, "high", short))
        assertEquals(null to ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, short))
        assertEquals(null to ProactiveModelSetup.CAP_THINKING, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, null, null, short))
        val guessed = ReasoningOptions(listOf("none", "low"), fromProvider = false)
        assertEquals("manual tier is not guessed", "high" to ProactiveModelSetup.CAP_THINKING, ProactiveModelSetup.of(ProviderConfig.Kind.OpenAiResponses, guessed, "high", short))
        assertNull(ProactiveModelSetup.lowestDeclared(ReasoningOptions(listOf("ultra"), fromProvider = true)))
        assertEquals(ProactiveModelSetup.CAP, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, ProactiveCall.Learn).second)
    }

    // Segment, daily, weekly and monthly summaries run to a dozen-plus sentences of a hundred-plus characters each: no extra cap (whether or not thinking is on, and whichever protocol is used), so the adapter default applies as for ordinary tasks.
    // The short cap applies only to triage and topic summarization; it must hold a full batch of triage verdicts (8 items, each with its longest one-sentence reason)
    @Test fun longProactiveCallsAreNotCapped() {
        val listed = ReasoningOptions(listOf("none", "low", "high"), fromProvider = true)
        for (call in listOf(ProactiveCall.Notes, ProactiveCall.Day, ProactiveCall.Span)) {
            assertTrue(call.long)
            assertEquals("none" to null, ProactiveModelSetup.of(ProviderConfig.Kind.Gemini, listed, "high", call))
            assertEquals(null to null, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, null, call))
            assertEquals("high" to null, ProactiveModelSetup.of(ProviderConfig.Kind.Anthropic, null, "high", call))
        }
        assertFalse(ProactiveCall.Triage.long || ProactiveCall.Learn.long)
        val fullBatch = com.wangzan101.mika.core.proactive.NoticeTriage.MAX_BATCH * (com.wangzan101.mika.core.proactive.NoticeTriage.REASON_MAX + 60)
        assertTrue("a full batch of judgments fits (at one and a half tokens per character)", ProactiveModelSetup.CAP >= fullBatch * 3 / 2)
    }

    @Test fun proactiveTargetIsNullWithoutAnyModel() {
        assertNull(store().proactiveTarget())
    }

    // The target and the model come from the same config read: the target returned is the config used to build the model
    @Test fun proactiveTargetedModelIsBuiltFromTheTargetItNames() {
        val s = store()
        assertNull(s.proactiveTargeted(ProactiveCall.Triage))
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1"), ListedModel("m2")))
        s.use(ModelRole.Main, p.id, "m1", none)
        val main = s.proactiveTargeted(ProactiveCall.Triage)!!
        assertEquals(TriageTargetOf(p.id, "P", "m1"), main.target)
        assertEquals(main.target.providerId to main.target.model, built.last().first.providerId to built.last().first.model)
        s.use(ModelRole.Light, p.id, "m2", none)
        val light = s.proactiveTargeted(ProactiveCall.Triage)!!
        assertEquals(TriageTargetOf(p.id, "P", "m2"), light.target)
        assertEquals("m2", built.last().first.model)
        assertEquals(s.proactiveTarget(), light.target)
    }

    @Test fun geminiDeveloperApiKeyOnlyUsesValidGeminiProviderAndPrefersMain() {
        val s = store()
        assertNull(s.geminiDeveloperApiKey())

        val openAi = s.connect(
            "OpenAI", ProviderConfig.Kind.OpenAiCompat, "https://api.openai.com/v1",
            "sk-openai-key", listOf(ListedModel("gpt-5")),
        )
        val anthropic = s.connect(
            "Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com",
            "sk-ant-key", listOf(ListedModel("claude-sonnet-4-5")),
        )
        assertTrue(s.use(ModelRole.Main, openAi.id, "gpt-5", none))
        assertNull(s.geminiDeveloperApiKey())

        s.connect(
            "Gemini Invalid", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "bad\nkey", listOf(ListedModel("gemini-2.5-flash")),
        )
        s.connect(
            "Gemini Whitespace", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "AIza bad key", listOf(ListedModel("gemini-2.5-flash")),
        )
        s.connect(
            "Gemini Blank", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "   ", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertNull(s.geminiDeveloperApiKey())

        val gemFirst = s.connect(
            "Gemini First", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "  AIza-first  ", listOf(ListedModel("gemini-2.5-flash")),
        )
        val gemMain = s.connect(
            "Gemini Main", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "AIza-main", listOf(ListedModel("gemini-2.5-pro")),
        )
        assertEquals("AIza-first", s.geminiDeveloperApiKey())

        assertTrue(s.use(ModelRole.Main, gemMain.id, "gemini-2.5-pro", none))
        assertEquals("AIza-main", s.geminiDeveloperApiKey())

        assertTrue(s.use(ModelRole.Main, anthropic.id, "claude-sonnet-4-5", none))
        assertEquals("AIza-first", s.geminiDeveloperApiKey())

        s.removeProvider(gemFirst.id)
        s.removeProvider(gemMain.id)
        assertNull(s.geminiDeveloperApiKey())
    }

    @Test fun googleGroundingAvailabilityFollowsMainModelProviderKind() {
        val s = store()
        assertEquals(GroundingStatus(available = false, providerName = null), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val gemini = s.connect(
            "Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "gem-key", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertTrue(s.use(ModelRole.Main, gemini.id, "gemini-2.5-flash", none))
        assertEquals(GroundingStatus(available = true, providerName = "Gemini"), s.groundingStatus())
        assertTrue(s.googleGroundingSearch() is GoogleGroundingSearch)

        val gemNoKey = s.connect(
            "Gemini Empty", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com",
            "", listOf(ListedModel("gemini-2.5-flash")),
        )
        assertTrue(s.use(ModelRole.Main, gemNoKey.id, "gemini-2.5-flash", none))
        assertEquals(GroundingStatus(available = false, providerName = "Gemini Empty"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val local = s.connect(
            "LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1",
            "local-key", listOf(ListedModel("qwen-local")),
        )
        assertTrue(s.use(ModelRole.Main, local.id, "qwen-local", none))
        assertEquals(GroundingStatus(available = false, providerName = "LM Studio"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val anthropic = s.connect(
            "Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com",
            "sk-ant", listOf(ListedModel("claude-sonnet-4-6")),
        )
        assertTrue(s.use(ModelRole.Main, anthropic.id, "claude-sonnet-4-6", none))
        assertEquals(GroundingStatus(available = false, providerName = "Anthropic"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())

        val responses = s.connect(
            "OpenAI Responses", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1",
            "sk-oai", listOf(ListedModel("gpt-5")),
        )
        assertTrue(s.use(ModelRole.Main, responses.id, "gpt-5", none))
        assertEquals(GroundingStatus(available = false, providerName = "OpenAI Responses"), s.groundingStatus())
        assertNull(s.googleGroundingSearch())
    }

    @Test fun googleGroundingSearchExecutesGeminiRequestAndSessionHeadersAgainstMockServer() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            val dir = tmp.newFolder("grounding-gem")
            val s = ProviderStore(dir, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
            val base = server.url("/").toString().trimEnd('/')
            val p = s.connect(
                "Gemini", ProviderConfig.Kind.Gemini, base, "gem-secret",
                listOf(ListedModel("gemini-2.5-flash")), sessionHeader = "x-test-session",
            )
            assertTrue(s.use(ModelRole.Main, p.id, "gemini-2.5-flash", none))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"Sunny in Tokyo."}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/wx","title":"Tokyo Weather"}}],"groundingSupports":[{"segment":{"text":"Sunny in Tokyo."},"groundingChunkIndices":[0]}]}}]}""",
                ),
            )
            val result = s.googleGroundingSearch(ModelSession.of("conv-g"))!!.search("tokyo weather", 3)
            val success = result as WebSearchResult.Success
            assertEquals(1, success.hits.size)
            assertEquals("Tokyo Weather", success.hits[0].title)
            assertEquals("https://example.com/wx", success.hits[0].url)

            val req = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", req.path)
            assertEquals("gem-secret", req.getHeader("x-goog-api-key"))
            assertEquals("Claire/1.2.3", req.getHeader("User-Agent"))
            assertEquals(ModelSession.of("conv-g").id, req.getHeader("x-test-session"))
            assertTrue(req.body.readUtf8().contains("googleSearch"))
        } finally {
            server.shutdown()
        }
    }

    @Suppress("TestFunctionName")
    private fun TriageTargetOf(id: String, name: String, model: String) = com.wangzan101.mika.core.proactive.TriageTarget(id, name, model)

    private class FakeModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
