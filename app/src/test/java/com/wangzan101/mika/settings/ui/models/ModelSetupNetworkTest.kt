package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.connect
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Checks catalog requests and the model test call the user confirms individually against a local service. */
class ModelSetupNetworkTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun modelList(vararg ids: String): MockResponse {
        val models = ids.joinToString(",") { """{"id":"$it"}""" }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"object":"list","data":[$models]}""")
    }

    private fun setUp(kind: ProviderConfig.Kind = ProviderConfig.Kind.OpenAiCompat): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Claire/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(kind)
        vm.setBaseUrl(server.url("/").toString().trimEnd('/'))
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun missingCatalogNeverFallsBackToAChatRequest() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("manual-model")
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())

        runBlocking {
            vm.saveWithoutTest()
            // The form may hide as soon as the first provider appears; it returns to Idle only after the hand-typed model is saved and the form is reset.
            withTimeout(15_000) { vm.state.first { !it.showForm && it.form.test == ListState.Idle } }
        }
        val saved = store.providers.value.single()
        assertEquals(setOf("manual-model"), saved.typedModels)
        assertTrue(saved.verifiedModels.isEmpty())
        assertNull(store.roles.value.main)
        assertEquals(listOf("/models", "/models"), List(server.requestCount) { server.takeRequest().path })
    }

    private fun toolCall(): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
    )

    private fun rejected(): MockResponse = MockResponse().setResponseCode(400)
        .setHeader("Content-Type", "application/json").setBody("""{"error":{"message":"bad request"}}""")

    private fun ModelSetupViewModel.pickAndWait(role: ModelRole, providerId: String, model: String) = runBlocking {
        pick(role, providerId, model)
        withTimeout(15_000) { state.first { it.manualPick[providerId] != ListState.Running } }
    }

    /**
     * With a main model already set, saving a provider sends only one GET; afterwards each main-model change sends one request for that model alone, and on failure the previous main model stays unchanged;
     * verified models are no longer probed.
     */
    @Test fun savingWithAMainSendsOnlyTheCatalogRequestAndEachMainChoiceProbesOnce() {
        server.enqueue(modelList("gpt-x", "gpt-y", "tts-z"))
        server.enqueue(toolCall())
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        val other = store.connect("Other", ProviderConfig.Kind.OpenAiCompat, "https://other.test/v1", "k", emptyList())
        assertTrue(store.use(ModelRole.Main, other.id, "old-main", ProviderPresets.custom.capabilities))
        vm.testAndWait()
        val saved = store.providers.value.first { it.id != other.id }
        assertEquals(listOf("gpt-x", "gpt-y", "tts-z"), saved.models.orEmpty().map { it.id })
        assertEquals("old-main", store.configFor(ModelRole.Main)?.model)
        assertEquals(1, server.requestCount)

        vm.pickAndWait(ModelRole.Main, saved.id, "gpt-y")
        assertEquals("gpt-y", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("gpt-y"), store.provider(saved.id)?.verifiedModels)
        vm.pickAndWait(ModelRole.Main, saved.id, "gpt-x")
        assertTrue(vm.state.value.manualPick[saved.id] is ListState.Failed)
        assertEquals("no switch when the check fails", "gpt-y", store.configFor(ModelRole.Main)?.model)

        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
        assertTrue(requests[1].body.readUtf8().contains("\"model\":\"gpt-y\""))
        assertTrue(requests[2].body.readUtf8().contains("\"model\":\"gpt-x\""))
    }

    /** A hand-typed light model is probed once for that one model; on failure the previous light model stays. */
    @Test fun typedLightModelIsProbedOnceAndRejectedOnFailure() {
        server.enqueue(modelList("gpt-x"))
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        val other = store.connect("Other", ProviderConfig.Kind.OpenAiCompat, "https://other.test/v1", "k", emptyList())
        assertTrue(store.use(ModelRole.Main, other.id, "old-main", ProviderPresets.custom.capabilities))
        assertTrue(store.use(ModelRole.Light, other.id, "old-light", ProviderPresets.custom.capabilities))
        vm.testAndWait()
        val saved = store.providers.value.first { it.id != other.id }
        runBlocking {
            vm.pickManual(ModelRole.Light, saved.id, "gpt-typo")
            withTimeout(15_000) { vm.state.first { it.manualPick[saved.id] is ListState.Failed } }
        }
        assertEquals("old-light", store.configFor(ModelRole.Light)?.model)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.last().body.readUtf8().contains("\"model\":\"gpt-typo\""))
    }

    /** First provider, no main model yet: when the recommended model fails, the provider is still saved without a main model, and only one test call was sent. */
    @Test fun failedFirstProbeKeepsTheProviderWithoutAMain() {
        server.enqueue(modelList("gpt-x", "gpt-y"))
        server.enqueue(rejected())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val saved = store.providers.value.single()
        assertNull(store.roles.value.main)
        assertTrue(saved.verifiedModels.isEmpty())
        assertEquals(saved.id, vm.state.value.pickToProbe)
        assertTrue(vm.state.value.probeNote.orEmpty().isNotEmpty())
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("GET /models", "POST /responses"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.last().body.readUtf8().contains("\"model\":\"gpt-x\""))
    }

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}"""))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertEquals(listOf("/models"), List(server.requestCount) { server.takeRequest().path })
    }

    /** First provider, no main model yet: one test call is sent for the recommended model only (through its own endpoint), and on success it becomes the main model; retesting probes only the confirmed one. */
    @Test fun firstProviderProbesOnlyTheRecommendedResponsesModel() {
        server.enqueue(modelList("gpt-x", "gpt-y"))
        server.enqueue(toolCall())
        server.enqueue(toolCall())
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val saved = store.providers.value.single()
        assertEquals("gpt-x", store.configFor(ModelRole.Main)?.model)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, store.configFor(ModelRole.Main)?.kind)
        assertEquals(setOf("gpt-x"), saved.verifiedModels)
        assertEquals(2, server.requestCount)
        runBlocking {
            vm.retestModel(saved.id, "gpt-x")
            withTimeout(15_000) { vm.state.first { it.retest.isEmpty() && server.requestCount == 3 } }
        }

        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/models", "/responses", "/responses"), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
        val bodies = requests.drop(1).map { it.body.readUtf8() }
        assertTrue(bodies.all { it.contains("\"model\":\"gpt-x\"") && it.contains("\"name\":\"get_time\"") })
    }
}
