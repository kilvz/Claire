package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ClientSetup
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.apiKey
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.markTyped
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.settings.rememberModels
import com.wangzan101.mika.settings.save
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.model.MemoryVoiceCatalogBackend
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCredentialBackend
import com.wangzan101.mika.voice.model.VoiceCredentialStore
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.voiceTestEnvironment
import javax.crypto.KeyGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** Gives the model-listing result by address; an address that is not registered lists an empty catalog. */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private val listHeaders = mutableListOf<Map<String, String>>()
    /** The next model listing throws this; used to confirm exceptions become a failure without killing the test coroutine. */
    private var listBoom: Throwable? = null
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Claire/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, headers ->
            listBoom?.let { throw it }
            listCalls += Triple(kind, url, apiKey)
            listHeaders += headers
            listing[url] ?: ModelListResult.Ok(emptyList())
        },
    )

    /** Models that were built (model name, request headers, etc.); the result of an explicit test call is decided by [reply]. */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun migratedSpeechSelectionWritesCatalogWithoutLegacyDetection() = runTest {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "Provider", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertModel(VoiceModel("provider:p1", "whisper-1", "Whisper", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        val ref = VoiceModelRef("provider:p1", "whisper-1", VoiceCapability.STT)
        val controller = voiceTestEnvironment(catalog).stt
        val legacy = SpeechSettings(MemoryPrefs())
        val vm = ModelSetupViewModel(
            store(), backgroundScope, io = StandardTestDispatcher(testScheduler), speech = legacy, stt = controller,
        )
        var picked = 0

        vm.pickSpeechModel("p1", "whisper-1") { picked++ }
        runCurrent()

        assertEquals(SttSelection.Model(ref), controller.current())
        assertEquals(SpeechChoice.Auto, legacy.choice.value)
        assertEquals(1, picked)
        vm.pickSttModel(ref) { picked++ }
        runCurrent()
        assertEquals(2, picked)
        legacy.noteSystemSilent()
        legacy.noteSystemSilent()
        assertTrue(legacy.systemSilent.value)
        vm.pickSpeech(SpeechChoice.System)
        runCurrent()
        assertEquals(SttSelection.Model(SttSelectionController.SYSTEM_STT_REF), controller.current())
        assertEquals(SpeechChoice.Auto, legacy.choice.value)
        assertFalse(legacy.systemSilent.value)
    }

    /** A model name does not restrict the role; only the main model is probed once up front (and not again once verified). */
    @Test fun modelNamesDoNotRestrictExplicitRoleChangesAndOnlyMainIsProbed() = runTest {
        val store = store()
        val id = "fishaudio/drama-3-int4"
        val provider = store.connect("Speech", ProviderConfig.Kind.OpenAiCompat, "https://tts.test/v1", null, listOf(ListedModel(id)))
        val vm = vm(store)
        for (role in listOf(ModelRole.Vision, ModelRole.Light, ModelRole.Main)) {
            vm.pick(role, provider.id, id)
            runCurrent()
            assertEquals(id, store.configFor(role)?.model)
        }
        assertEquals("only the main model is probed", listOf(id), built.map { it.first })
        assertEquals(setOf(id), store.provider(provider.id)?.verifiedModels)
    }

    /** The main model's test call fails (no tool call, model not found): the previous main model stays and the reason is shown; vision and light are not probed. */
    @Test fun failedMainProbeKeepsThePreviousMain() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a"), ListedModel("b")))
        assertTrue(store.use(ModelRole.Main, p.id, "a", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        reply = { ModelReply(listOf(Part.Text("hi")), StopReason.EndTurn, null) }
        var picked = 0
        vm.pick(ModelRole.Main, p.id, "b") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(0, picked)
        assertEquals("a", store.configFor(ModelRole.Main)?.model)
        assertEquals(ListState.Failed(tr(com.wangzan101.mika.R.string.model_probe_no_tools_pick, "b")), vm.state.value.manualPick[p.id])
        reply = { throw ModelError.ModelNotFound("b") }
        vm.pick(ModelRole.Main, p.id, "b"); runCurrent()
        assertEquals("a", store.configFor(ModelRole.Main)?.model)
        assertTrue((vm.state.value.manualPick[p.id] as ListState.Failed).message.contains("「b」"))
        assertTrue(store.provider(p.id)!!.verifiedModels.isEmpty())
        assertEquals(listOf("b", "b"), built.map { it.first })
        vm.pick(ModelRole.Light, p.id, "b"); runCurrent()
        assertEquals("b", store.configFor(ModelRole.Light)?.model)
        assertEquals("the light model is not probed", 2, built.size)
    }

    /** A hand-typed light model is probed first: if it fails (not found, no tool call) the previous light model stays and it is not recorded as hand-typed; models picked from the list are not probed. */
    @Test fun typedLightModelWithFailingProbeKeepsThePreviousLight() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a"), ListedModel("b")))
        assertTrue(store.use(ModelRole.Light, p.id, "a", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        reply = { throw ModelError.ModelNotFound("typo") }
        var picked = 0
        vm.pickManual(ModelRole.Light, p.id, " typo ") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(0, picked)
        assertEquals("a", store.configFor(ModelRole.Light)?.model)
        assertTrue((vm.state.value.manualPick[p.id] as ListState.Failed).message.contains("「typo」"))
        reply = { ModelReply(listOf(Part.Text("hi")), StopReason.EndTurn, null) }
        vm.pickManual(ModelRole.Light, p.id, "typo2"); runCurrent()
        assertEquals(ListState.Failed(tr(com.wangzan101.mika.R.string.model_probe_no_tools_pick, "typo2")), vm.state.value.manualPick[p.id])
        assertEquals("a", store.configFor(ModelRole.Light)?.model)
        assertTrue(store.provider(p.id)!!.typedModels.isEmpty())
        vm.pick(ModelRole.Light, p.id, "b"); runCurrent()
        assertEquals("b", store.configFor(ModelRole.Light)?.model)
        assertEquals("a light model picked from the list is not probed", listOf("typo", "typo2"), built.map { it.first })
    }

    /** A hand-typed vision model that only replies without calling a tool still passes (vision does not rely on tools): it is used and recorded as hand-typed and verified. */
    @Test fun typedVisionModelThatOnlyRepliesIsAccepted() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a")))
        val vm = vm(store)
        reply = { ModelReply(listOf(Part.Text("hi")), StopReason.EndTurn, null) }
        var picked = 0
        vm.pickManual(ModelRole.Vision, p.id, "vl-typed") { picked++ }
        runCurrent()
        assertEquals(1, picked)
        assertNull(vm.state.value.manualPick[p.id])
        assertEquals("vl-typed", store.configFor(ModelRole.Vision)?.model)
        assertEquals(setOf("vl-typed"), store.provider(p.id)?.typedModels)
        assertEquals(setOf("vl-typed"), store.provider(p.id)?.verifiedModels)
        assertEquals(listOf("vl-typed"), built.map { it.first })
    }

    /** A hand-typed fallback is probed once and only set on success; fallbacks picked from the list are not probed. */
    @Test fun typedFallbackProbesOnce() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a"), ListedModel("b")))
        assertTrue(store.use(ModelRole.Main, p.id, "a", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        var picked = 0
        vm.pickFallbackManual(ModelRole.Main, 0, p.id, "backup-typed") { picked++ }
        runCurrent()
        assertEquals(1, picked)
        assertEquals(listOf("backup-typed"), store.list().filter { it.id in store.roles.value.mainFallbacks }.map { it.model })
        assertEquals(setOf("backup-typed"), store.provider(p.id)?.typedModels)
        assertEquals(listOf("backup-typed"), built.map { it.first })
        reply = { throw ModelError.ModelNotFound("nope") }
        vm.pickFallbackManual(ModelRole.Light, 0, p.id, "nope"); runCurrent()
        assertTrue(vm.state.value.manualPick[p.id] is ListState.Failed)
        assertTrue(store.roles.value.lightFallbacks.isEmpty())
        vm.pickFallback(ModelRole.Light, 0, p.id, "b"); runCurrent()
        assertEquals(1, store.roles.value.lightFallbacks.size)
        assertEquals("a fallback picked from the list is not probed", listOf("backup-typed", "nope"), built.map { it.first })
    }

    /** A hand-typed light model inside "use as" is probed first, and on failure the previous light model stays; one from the list is used directly without sending a request. */
    @Test fun typedLightViaUseAsIsProbedButListedLightIsNot() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a"), ListedModel("b")))
        assertTrue(store.use(ModelRole.Light, p.id, "a", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        reply = { throw ModelError.ModelNotFound("typo") }
        var done = 0
        vm.useFromProvider(ModelRole.Light, p.id, "typo") { done++ }
        runCurrent()
        assertEquals(0, done)
        assertTrue(vm.state.value.manualPick[p.id] is ListState.Failed)
        assertEquals("a", store.configFor(ModelRole.Light)?.model)
        assertEquals(listOf("typo"), built.map { it.first })
        vm.useFromProvider(ModelRole.Light, p.id, "b") { done++ }
        runCurrent()
        assertEquals(1, done)
        assertEquals("b", store.configFor(ModelRole.Light)?.model)
        assertEquals("one from the list is not probed", 1, built.size)
    }

    /** For a provider saved by skipping the test, a main-model fallback picked from the list is probed too; on failure that slot stays unchanged. A vision fallback is not probed. */
    @Test fun mainFallbackFromAnUntestedProviderIsProbed() = runTest {
        val store = store()
        val listed = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a"), ListedModel("b")))
        assertTrue(store.use(ModelRole.Main, listed.id, "a", ProviderPresets.custom.capabilities))
        val untested = store.connect("Local", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:8000/v1", null, emptyList(), untested = true)
        store.markTyped(untested.id, "local-m")
        assertTrue(store.provider(untested.id)!!.savedUntested)
        val vm = vm(store)
        vm.pickFallback(ModelRole.Main, 0, listed.id, "b"); runCurrent()
        assertTrue("a listed, already-tested provider is not probed", built.isEmpty())
        vm.clearFallback(ModelRole.Main, 0); runCurrent()
        reply = { throw ModelError.ModelNotFound("local-m") }
        vm.pickFallback(ModelRole.Main, 0, untested.id, "local-m"); runCurrent()
        assertTrue(vm.state.value.manualPick[untested.id] is ListState.Failed)
        assertTrue(store.roles.value.mainFallbacks.isEmpty())
        assertEquals(listOf("local-m"), built.map { it.first })
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickFallback(ModelRole.Main, 0, untested.id, "local-m"); runCurrent()
        assertEquals(listOf("local-m"), store.list().filter { it.id in store.roles.value.mainFallbacks }.map { it.model })
        vm.pickFallback(ModelRole.Vision, 0, untested.id, "local-m"); runCurrent()
        assertEquals("a vision fallback is not probed", 2, built.size)
    }

    /** "Use as → main model" probes first; on failure the previous main model stays, and only a pass switches it. */
    @Test fun useFromProviderProbesMainFirst() = runTest {
        val store = store()
        val p = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("xxx-tts-proxy")))
        assertTrue(store.use(ModelRole.Main, p.id, "old-main", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("xxx-tts-proxy") }
        var done = 0
        vm.useFromProvider(ModelRole.Main, p.id, "xxx-tts-proxy") { done++ }
        runCurrent()
        assertEquals(0, done)
        assertTrue(vm.state.value.manualPick[p.id] is ListState.Failed)
        assertEquals("old-main", store.configFor(ModelRole.Main)?.model)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.useFromProvider(ModelRole.Main, p.id, "xxx-tts-proxy") { done++ }
        runCurrent()
        assertEquals(1, done)
        assertEquals("xxx-tts-proxy", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("xxx-tts-proxy"), store.provider(p.id)?.verifiedModels)
        assertEquals(2, built.size)
    }

    private val localSpeechUrl = "http://192.168.2.231:8091/v1"

    /** First provider, no main model yet: when a model name was typed in, only that one is probed, and on success it becomes the main model. */
    @Test fun firstProviderProbesOnlyTheTypedModelBeforeMakingItMain() = runTest {
        val models = listOf(ListedModel("fishaudio/drama-3-int4"), ListedModel("vendor/model-123"))
        listing[localSpeechUrl] = ModelListResult.Ok(models)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel("private-tts")
        vm.testAndSave()
        runCurrent()

        val p = store.providers.value.single()
        assertEquals(models, p.models)
        assertEquals(setOf("private-tts"), p.typedModels)
        assertEquals(setOf("private-tts"), p.verifiedModels)
        assertEquals(listOf("private-tts"), built.map { it.first })
        assertEquals("private-tts", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        assertNull(vm.state.value.probeNote)
    }

    /** With a main model already set, "test and save" only lists models: no chat request is sent and the main model is unchanged. */
    @Test fun savingAnotherProviderOnlyListsModels() = runTest {
        val store = store()
        val first = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a")))
        assertTrue(store.use(ModelRole.Main, first.id, "a", ProviderPresets.custom.capabilities))
        listing[deepseekUrl] = deepseek
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(2, store.providers.value.size)
        assertEquals("a", store.configFor(ModelRole.Main)?.model)
        assertEquals(1, listCalls.size)
        assertTrue(built.isEmpty())
    }

    @Test fun settingsCatalogErrorsAreShownEvenWithAManualModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel("fishaudio/drama-3-int4")
        for (failure in listOf(ListFailure.Auth, ListFailure.Network)) {
            listing[localSpeechUrl] = ModelListResult.Failed(failure)
            vm.testAndSave()
            runCurrent()

            assertEquals(ListState.Failed(failure.text), vm.state.value.form.test)
            assertTrue(store.providers.value.isEmpty())
            assertTrue(built.isEmpty())
        }
    }

    @Test fun settingsWithNoCatalogCanSaveAManualModelWithoutTesting() = runTest {
        listing[localSpeechUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("fishaudio/drama-3-int4")
        vm.testAndSave()
        runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(built.isEmpty())

        vm.saveWithoutTest()
        runCurrent()

        val p = store.providers.value.single()
        assertTrue(p.savedUntested)
        assertEquals(setOf("fishaudio/drama-3-int4"), p.typedModels)
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
    }

    @Test fun reconnectingSavesMixedCatalogWithoutProbing() = runTest {
        val models = listOf(
            ListedModel("fishaudio/drama-3-int4"),
            ListedModel("vendor/opaque-speech", unsupportedApis = listOf("/v1/audio/speech")),
            ListedModel("vendor/opaque-marked"),
            ListedModel("vendor/chat"),
        )
        listing[localSpeechUrl] = ModelListResult.Ok(models)
        val store = store()
        val p = store.connect("192.168.2.231", ProviderConfig.Kind.OpenAiCompat, localSpeechUrl, null, emptyList())
        val other = store.connect("Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k", listOf(ListedModel("a")))
        assertTrue(store.use(ModelRole.Main, other.id, "a", ProviderPresets.custom.capabilities))
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()

        assertTrue(built.isEmpty())
        assertEquals("a", store.configFor(ModelRole.Main)?.model)
        val saved = store.provider(p.id)!!
        assertEquals(models, saved.models)
    }

    /** A model that is in the catalog but that Claire cannot call (only other APIs are listed): it must be probed before being set as vision/light, and is not set when the probe fails. */
    @Test fun listedModelMikaCannotCallIsProbedBeforeBecomingVisionOrLight() = runTest {
        val store = store()
        val p = store.connect(
            "Relay", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1", "k",
            listOf(ListedModel("vendor/chat"), ListedModel("vendor/opaque-image", unsupportedApis = listOf("/v1/images/generations"))),
        )
        val vm = vm(store)
        reply = { throw ModelError.ModelNotFound("no chat endpoint") }
        var done = 0
        vm.useFromProvider(ModelRole.Vision, p.id, "vendor/opaque-image") { done++ }
        runCurrent()
        assertEquals("a failure is not saved", 0, done)
        assertTrue(vm.state.value.manualPick[p.id] is ListState.Failed)
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(listOf("vendor/opaque-image"), built.map { it.first })
    }

    /** The model recommended during first-time setup fails: the provider is still saved without a main model, the reason is shown and "choose a main model" is offered; the choice is probed again. */
    @Test fun onboardingKeepsTheProviderWithoutAMainWhenTheCandidateFails() = runTest {
        val model = ListedModel("fishaudio/drama-3-int4")
        listing[localSpeechUrl] = ModelListResult.Ok(listOf(model, ListedModel("vendor/chat")))
        reply = { ModelReply(listOf(Part.Text("hi")), StopReason.EndTurn, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()

        val p = store.providers.value.single()
        assertEquals(2, p.models?.size)
        assertEquals("only one is probed", 1, built.size)
        assertNull(store.roles.value.main)
        assertFalse(vm.state.value.canNext)
        assertFalse(vm.state.value.showForm)
        val tried = built.single().first
        assertEquals(
            tr(com.wangzan101.mika.R.string.model_saved_no_main, tr(com.wangzan101.mika.R.string.model_probe_no_tools_pick, tried)),
            vm.state.value.probeNote,
        )
        assertEquals(p.id, vm.state.value.pickToProbe)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pick(ModelRole.Main, p.id, "vendor/chat"); runCurrent()
        assertEquals("vendor/chat", store.configFor(ModelRole.Main)?.model)
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
        assertTrue(vm.state.value.canNext)
    }

    /** The hand-typed model is one the listing says Claire cannot call: it is not probed; the recommended chat model is probed instead. */
    @Test fun onboardingSkipsATypedModelMikaCannotCallAndProbesAChatModel() = runTest {
        val model = ListedModel("vendor/opaque-speech", unsupportedApis = listOf("/v1/audio/speech"))
        listing[localSpeechUrl] = ModelListResult.Ok(listOf(model, ListedModel("vendor/chat")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel(model.id)
        vm.testAndSave()
        runCurrent()

        assertEquals(listOf("vendor/chat"), built.map { it.first })
        assertEquals("vendor/chat", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("vendor/chat"), store.providers.value.single().verifiedModels)
    }

    /** First provider: listing the models saves it, then the recommended one is asked to call a tool once, and on success it becomes the main model. */
    @Test fun testAndSaveProbesTheRecommendedModelForTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        runCurrent()
        assertTrue("the form is expanded when there is no provider yet", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(listOf("deepseek-chat"), built.map { it.first })
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals(1, store.list().size)
        val s = vm.state.value
        assertEquals(setOf("deepseek-chat"), s.providers.single().verifiedModels)
        assertFalse("the form collapses after saving", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertNull(s.probeNote)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    @Test fun providerSessionHeaderIsRememberedWhenListingWithoutAChatProbe() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label)
        vm.setApiKey("k")
        vm.testAndSave()
        runCurrent()

        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
        assertTrue(listHeaders.single()[go.sessionHeader].orEmpty().isNotEmpty())
        assertEquals(listOf("glm-x"), built.map { it.first })
    }

    /** When the Cloudflare Workers AI catalog is unavailable, entering the account and model ID and saving directly. */
    @Test fun cloudflarePresetFullFlowFromAccountIdToSavedProvider() = runTest {
        val cf = ProviderPresets.all.first { it.label == "Cloudflare Workers AI" }
        val store = store()
        val vm = vm(store)

        vm.selectPreset(cf.label)
        var form = vm.state.value.form
        assertTrue(form.isCloudflare)
        assertFalse("Cloudflare shows a separate Account ID field", form.urlEditable)
        assertFalse(form.canTest)
        assertEquals(tr(com.wangzan101.mika.R.string.model_url_account_id), form.urlProblem)

        // The user enters the Account ID and the API Key
        val actualUrl = "https://api.cloudflare.com/client/v4/accounts/my_acc_123/ai/v1"
        vm.setAccountId("my_acc_123")
        vm.setApiKey("cf_token_xyz")
        form = vm.state.value.form
        assertEquals("my_acc_123", form.accountId)
        assertEquals(actualUrl, form.baseUrl)
        assertNull(form.urlProblem)
        assertTrue(form.canTest)

        // Cloudflare offers no model-listing endpoint (GET /models returns 405/NotSupported)
        listing[actualUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()

        form = vm.state.value.form
        assertTrue(form.noList)
        assertFalse("Requires model name to probe", form.canTest)

        // The user enters the model name @cf/openai/gpt-oss-20b
        vm.setModel("@cf/openai/gpt-oss-20b")
        assertTrue(vm.state.value.form.canTest)

        vm.saveWithoutTest(); runCurrent()
        assertFalse("the form closes after saving", vm.state.value.showForm)
        val saved = store.providers.value.single()
        assertEquals("Cloudflare Workers AI (my_a…_123)", saved.name)
        assertEquals(actualUrl, saved.baseUrl)
        assertEquals(setOf("@cf/openai/gpt-oss-20b"), saved.typedModels)
        assertTrue(saved.verifiedModels.isEmpty())
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
    }

    /** A custom entry uses some provider's official address: suggest switching to that provider's preset; when the address is the preset's but the API type differs, say so too; switching keeps the key. */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "这是「Gemini」的官方地址，用它的预设更省事：地址和接口类型都已填好。接口类型也对不上：「Gemini」用的是 Gemini 接口，这里选的是 OpenAI 兼容。",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // Another path on the same host (such as its compatible endpoint): still suggest it, but do not say the API type is wrong
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("no suggestion once a preset is chosen", after.suggestedPreset)
    }

    /** Hand-typed: the main model is probed on this one, and on success it is recorded as hand-typed and verified; fallbacks are not probed. */
    /** A provider that offers no list: the main model is probed first; a main-model fallback that is not in the catalog (hand-typed) is probed once too; a light fallback picked from the list is not probed. */
    @Test fun manualMainIsProbedOnceAndUnlistedMainFallbackIsProbedToo() = runTest {
        val store = store()
        val provider = store.connect("Local", ProviderConfig.Kind.OpenAiCompat, "https://chat.test/v1", "", emptyList())
        val vm = vm(store)
        var picked = 0
        vm.pick(ModelRole.Main, provider.id, " qwen3 ") { picked++ }
        vm.pickFallback(ModelRole.Main, 0, provider.id, "ignored-while-running") { picked++ }
        runCurrent()
        vm.pickFallback(ModelRole.Main, 0, provider.id, "qwen-backup") { picked++ }
        runCurrent()

        assertEquals(2, picked)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(listOf("qwen-backup"), store.list().filter { it.id in store.roles.value.mainFallbacks }.map { it.model })
        assertEquals(setOf("qwen3", "qwen-backup"), store.provider(provider.id)?.typedModels)
        assertEquals(setOf("qwen3", "qwen-backup"), store.provider(provider.id)?.verifiedModels)
        assertEquals(listOf("qwen3", "qwen-backup"), built.map { it.first })
        assertEquals(2, probes.size)
    }

    /** A provider without a list saved by an older version (its hand-typed models were never probed): warn; a passing "retest" clears the warning and records it, while a failing one shows the reason. */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        store.use(ModelRole.Light, p.id, "backup", ProviderPresets.custom.capabilities)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retestModel(p.id, "gemini"); runCurrent()
        assertEquals(
            ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 gemini-flash-latest）。"),
            vm.state.value.retest[p.id to "gemini"],
        )
        assertEquals(setOf("gemini", "backup"), vm.state.value.unverified(vm.state.value.providers.single()).toSet())
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retestModel(p.id, "gemini"); runCurrent()
        assertNull(vm.state.value.retest[p.id to "gemini"])
        assertEquals(listOf("backup"), vm.state.value.unverified(vm.state.value.providers.single()))
        assertEquals(listOf("gemini", "gemini"), built.map { it.first })
        // Providers with a list are not warned about
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** Plain http: no warning for localhost or the local network; other addresses get one warning but can still be tested. */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("这个地址不加密，API Key 和对话内容可能被同一网络里的人看到。", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("Qwen")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "Qwen"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("the second provider does not change the main model", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        vm.openForm(); vm.selectPreset("Qwen"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "Qwen" }
        // The selection page lists every model of every provider, without filtering by capability
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("choosing vision records vision", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // Use the main model: the vision path uses the main model, with no separate vision config anymore
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // Off
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("an unused config is deleted", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("Custom")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("a custom OpenAI-compatible endpoint can be saved without a Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // The service offers no list: a model name is required first, so nothing was saved
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // The hand-typed model can be saved directly. The next step requires explicitly choosing a main model.
        vm.setModel("chatty"); vm.saveWithoutTest(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        assertEquals(listOf("qwen3"), built.map { it.first })
        assertEquals(setOf("qwen3", "chatty"), vm.state.value.options(vm.state.value.providers.single()).map { it.id }.toSet())
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("an existing config keeps its capabilities", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("the original list is kept on failure", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.mika.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.mika.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("工具调用 · 未确认" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("工具调用" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("不支持工具调用" to false), ModelSetupCopy.tags(o[2]))
    }

    /** A custom provider can be named (leading and trailing whitespace is trimmed); when left blank the host name of the address is used. The same address under different names counts as two providers. */
    @Test fun customProviderUsesTheGivenNameOrFallsBackToTheHost() = runTest {
        val url = "https://relay.example.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("m1")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k1")
        assertEquals("relay.example.com", vm.state.value.form.defaultName)
        vm.setName("  我的中转  ")
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("我的中转"), store.providers.value.map { it.name })
        assertEquals("", vm.state.value.form.name)

        vm.openForm()
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k2"); vm.setName("   ")
        vm.testAndSave(); runCurrent()
        val names = store.providers.value.map { it.name }
        assertEquals(listOf("我的中转", "relay.example.com"), names)
        assertEquals(listOf("k1", "k2"), store.providers.value.map { store.providerKey(it.id) })
    }

    /** Names have a length cap; the name is cleared when switching to a preset; when the address belongs to a preset the default name is the preset name; the preset's name is not affected by the name in the form. */
    @Test fun customNameIsCappedAndOnlyAppliesToCustom() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setName("名".repeat(ModelSetupViewModel.MAX_NAME + 8))
        assertEquals(ModelSetupViewModel.MAX_NAME, vm.state.value.form.name.length)
        // Truncated by code point, so an emoji is not cut in half
        vm.setName("😀".repeat(ModelSetupViewModel.MAX_NAME + 3))
        val capped = vm.state.value.form.name
        assertEquals(ModelSetupViewModel.MAX_NAME, capped.codePointCount(0, capped.length))
        assertEquals("😀".repeat(ModelSetupViewModel.MAX_NAME), capped)
        vm.setBaseUrl(deepseekUrl)
        assertEquals("DeepSeek", vm.state.value.form.defaultName)
        vm.selectPreset("DeepSeek")
        assertEquals("", vm.state.value.form.name)
        assertEquals("DeepSeek", vm.state.value.form.copy(name = "别的名字").providerName)
    }

    /** A listing error shows the reason, and retesting after typing a model still only queries the catalog. */
    @Test fun listErrorShowsTheReasonAndOpensTheModelField() = runTest {
        for ((i, failure) in listOf(ListFailure.Auth, ListFailure.Server, ListFailure.BadResponse).withIndex()) {
            val url = "https://relay$i.example.com/v1"
            listing[url] = ModelListResult.Failed(failure)
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
            vm.testAndSave(); runCurrent()
            val f = vm.state.value.form
            assertEquals(ListState.Failed(failure.text), f.test)
            assertTrue("manual entry is possible after an error", f.showModel)
            assertTrue(store.providers.value.isEmpty())
            vm.setModel("plan-model"); vm.testAndSave(); runCurrent()
            assertEquals(ListState.Failed(failure.text), vm.state.value.form.test)
            assertTrue(store.providers.value.isEmpty())
            assertTrue(built.isEmpty())
            vm.closeForm()
        }
    }

    /** When the list already contains the model, the catalog entry is reused. */
    @Test fun typedModelThatIsListedIsNotMarkedTyped() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-reasoner"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek-reasoner"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(emptySet<String>(), p.typedModels)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
        // Collapsing "manual entry" drops the field that would otherwise need filling in
        vm.openForm(); vm.setApiKey("sk"); vm.setManual(true); vm.setModel("x"); vm.setManual(false)
        assertEquals("", vm.state.value.form.typedModel)
    }

    /** A provider that has a list can still be given a hand-typed model in the picker; on success it is recorded as hand-typed, survives a list refresh and is tagged "hand-typed". */
    @Test fun pickerAllowsTypingAModelForAListedProvider() = runTest {
        val store = store()
        val p = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk", deepseek.models)
        val vm = vm(store)
        runCurrent()
        vm.pick(ModelRole.Light, p.id, "deepseek-v9"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
        assertEquals("stored in the file", setOf("deepseek-v9"), store().provider(p.id)?.typedModels)
        store.rememberModels(p.id, deepseek.models)
        vm.pick(ModelRole.Light, p.id, "deepseek-chat"); runCurrent()
        val options = vm.state.value.options(store.provider(p.id)!!)
        assertTrue(options.single { it.id == "deepseek-v9" }.typed)
        assertFalse(options.single { it.id == "deepseek-chat" }.typed)
        // Picking one from the list does not record it as hand-typed
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
    }

    // ---------- Saving directly without testing ----------

    /** Not a single network call: no model listing, no test call, only the address, the key and the fact that it is "not tested". */
    @Test fun saveWithoutTestStoresProviderWithoutListingOrProbe() = runTest {
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk-1"); vm.saveWithoutTest(); runCurrent()
        assertTrue("the model list was never fetched", listCalls.isEmpty())
        assertTrue("no probe call was made", built.isEmpty())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue("marked as untested", p.savedUntested)
        // It is there in the file too
        assertTrue(store().provider(p.id)!!.savedUntested)
        // Left for the user to choose a main model explicitly.
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse("the form collapses after saving", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue("after saving it says clearly that it was not tested", s.probeNote?.contains(p.name) == true)
    }

    /** A hand-typed model saved by skipping the test: after reopening it is picked as the main model, probed once and used only on success (the provider no longer counts as untested either). */
    @Test fun savedManualModelIsProbedOnceBeforeBecomingMainAfterReload() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk")
        vm.setManual(true)
        vm.setModel("deepseek-v9")
        vm.saveWithoutTest()
        runCurrent()
        assertTrue(listCalls.isEmpty())
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-v9"), p.typedModels)
        assertNull(store.roles.value.main)

        val reloaded = store()
        val again = vm(reloaded)
        again.pick(ModelRole.Main, p.id, "deepseek-v9")
        runCurrent()

        assertEquals("deepseek-v9", reloaded.configFor(ModelRole.Main)?.model)
        assertFalse(reloaded.provider(p.id)!!.savedUntested)
        assertEquals(setOf("deepseek-v9"), reloaded.provider(p.id)!!.verifiedModels)
        assertEquals(listOf("deepseek-v9"), built.map { it.first })
    }

    /** Saving another provider without testing while a main model exists: the main model is unchanged. */
    @Test fun saveWithoutTestKeepsTheCurrentMainModel() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        val main = store.configFor(ModelRole.Main)
        assertEquals("deepseek-chat", main?.model)
        vm.openForm(); vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://127.0.0.1:11434/v1"); vm.setManual(true); vm.setModel("local-model")
        vm.saveWithoutTest(); runCurrent()
        assertEquals(main, store.configFor(ModelRole.Main))
    }

    /** An invalid address format, or a required key left blank, stores nothing. */
    @Test fun saveWithoutTestRefusesAnInvalidForm() = runTest {
        val store = store()
        val vm = vm(store)
        assertFalse("the Key is not filled in yet", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("api.deepseek.com")
        vm.setApiKey("sk")
        assertFalse("the URL has no protocol", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        // Self-hosted services need no key, so saving is allowed
        vm.setBaseUrl("http://127.0.0.1:11434/v1")
        vm.setApiKey("")
        assertTrue(vm.state.value.form.canSaveUntested)
    }

    /** Saving an already-tested provider again with the test skipped: the provider is not duplicated, the key is replaced with the new one, and verified models are kept. */
    @Test fun saveWithoutTestKeepsExistingProviderAndItsVerifiedModels() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        store.markVerified(p.id, "deepseek-chat")
        assertFalse(p.savedUntested)
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk-2"); vm.saveWithoutTest(); runCurrent()
        assertEquals("not added twice", 1, store.providers.value.size)
        val after = store.provider(p.id)!!
        assertEquals("sk-2", store.providerKey(p.id))
        assertEquals("the fetched list is kept", listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder"), after.models!!.map { it.id })
        assertEquals(setOf("deepseek-chat"), after.verifiedModels)
        assertFalse("a verified model means the provider no longer counts as untested", after.savedUntested)
    }

    // ---------- A pasted key containing newlines, zero-width characters or non-ASCII ----------

    /** A newline in the middle is kept as is and flagged immediately; neither save action sends a request or stores anything. */
    @Test fun newlineInTheKeyIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\ncd")
        val form = vm.state.value.form
        assertEquals("sk-ab\ncd", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    /** `\r` and `\r\n` are likewise kept, not rewritten, and do not crash. */
    @Test fun crlfInTheKeyIsRejectedAndDoesNotCrash() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\r\ncd")
        assertEquals("sk-ab\r\ncd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        vm.setApiKey("sk-\rcd")
        assertEquals("sk-\rcd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** A Chinese prefix is kept and flagged immediately; neither testing nor direct saving sends a request or stores anything. The notice disappears once it is removed. */
    @Test fun chinesePrefixIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-abc123")
        val form = vm.state.value.form
        assertEquals("密钥：sk-abc123", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("sk-abc123")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
    }

    /** Clearing counts as deleting the whole thing: both the red text and "invisible characters removed" are dismissed, and while it is empty neither save action is available. */
    @Test fun clearingTheKeyDropsHintsAndLeavesSaveDisabled() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-\nabc")
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())

        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** Once the zero-width spaces are stripped it is used as a normal key and not flagged as illegal characters. */
    @Test fun zeroWidthSpaceIsStrippedAndTreatedAsANormalKey() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-abc", listCalls.single().third)
        assertEquals("sk-abc", store.providerKey(store.providers.value.single().id))
    }

    /** An ordinary space in the middle is not an illegal character: it is still sent to the provider, and a failure goes through the existing "connection failed". */
    @Test fun spaceInsideTheKeyStillHitsTheProvider() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk-1 2")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-1 2", listCalls.single().third)
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** Changing the key follows the same rules: newlines and Chinese prefixes are neither saved nor cause a new model listing. Once the zero-width characters are stripped the key can be changed. */
    @Test fun changeKeyRefusesNewlinesAndIllegalCharacters() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        val listed = listCalls.size
        vm.changeKey(id, "sk-\n2"); runCurrent()
        assertEquals("a Key with a newline is not saved", "sk", store.providerKey(id))
        assertEquals("a Key with a newline does not re-list models", listed, listCalls.size)
        vm.changeKey(id, "密钥：sk-3"); runCurrent()
        assertEquals("the invalid Key is not saved", "sk", store.providerKey(id))
        assertEquals("an invalid Key does not re-list models", listed, listCalls.size)
        vm.changeKey(id, "sk-\u200B2"); runCurrent()
        assertEquals("sk-2", store.providerKey(id))
        assertEquals(listed + 1, listCalls.size)
        assertEquals("sk-2", listCalls.last().third)
    }

    /** The header value still throws during listing: it is turned into a failure without killing the coroutine, and the raw exception text (which may contain the key) is not exposed. */
    @Test fun listingExceptionDoesNotCrashAndHidesTheHeaderValue() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store).also { it.selectPreset("DeepSeek") }
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        listBoom = IllegalArgumentException("Unexpected char 0x0a in Authorization value: Bearer sk-\nabc")
        vm.refresh(id); runCurrent()
        val failed = vm.state.value.listing[id] as ListState.Failed
        assertEquals("Key 里有无法使用的字符，请只粘贴密钥本身。", failed.message)
        assertFalse(failed.message.contains("Bearer"))
        assertEquals("sk", store.providerKey(id))
    }

    /** Previews the voice models that depend on the provider before deleting it: capability references are returned as they are, so the confirmation panel can spell out the impact. */
    @Test fun providerDeletionPreviewsDependentVoiceModels() = runTest {
        val catalog = VoiceCatalogStore(MemoryVoiceCatalogBackend(), migrated = true)
        catalog.upsertService(VoiceService("provider:p1", "One", VoiceAccount.ExistingProvider("p1")))
        catalog.upsertService(VoiceService("local", "Local", VoiceAccount.None))
        catalog.upsertModel(VoiceModel("provider:p1", "m1", "M1", stt = SttBinding(SttProtocol.AudioTranscriptions)))
        catalog.upsertModel(VoiceModel("provider:p1", "m2", "M2", tts = com.wangzan101.mika.voice.model.TtsBinding(com.wangzan101.mika.voice.model.TtsProtocol.OpenAiSpeech)))
        catalog.upsertModel(VoiceModel("local", "m3", "M3", stt = SttBinding(SttProtocol.FunAsrStreaming)))
        val store = store()
        val manager = VoiceAccountManager(catalog, voiceCredentialStore())
        val vm = ModelSetupViewModel(
            store, backgroundScope, io = StandardTestDispatcher(testScheduler), voiceAccounts = manager,
        )

        val usage = vm.providerVoiceUsage("p1")

        assertEquals(
            setOf(
                VoiceModelRef("provider:p1", "m1", VoiceCapability.STT),
                VoiceModelRef("provider:p1", "m2", VoiceCapability.TTS),
            ),
            usage.toSet(),
        )
        assertEquals("a local provider's models are not counted", emptyList<VoiceModelRef>(), vm.providerVoiceUsage("local"))
    }

    private fun voiceCredentialStore(): VoiceCredentialStore = VoiceCredentialStore(
        object : VoiceCredentialBackend {
            override fun read(secretId: String): String? = null
            override fun write(secretId: String, value: String): Boolean = true
            override fun delete(secretId: String) {}
        },
        object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
            override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
        },
    )
}
