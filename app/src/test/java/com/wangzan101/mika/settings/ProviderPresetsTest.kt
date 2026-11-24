package com.wangzan101.mika.settings

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ui.models.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** Addresses and names are unique (presets are identified by address); all cloud presets use https, and plain http is only for services running on your own machine. */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
    }

    /** Self-hosted services: the address is editable and the key is optional, but the name still comes from the preset; cloud presets have a fixed address and require a key. */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("testable without a Key", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** A matching host name is enough to recognize the provider (the path does not matter); self-hosted services and unrecognized addresses are not matched. */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertEquals("Zhipu", ProviderPresets.forHost("https://open.bigmodel.cn/api/paas/v4")?.label)
        assertEquals("Zhipu Coding Plan", ProviderPresets.forHost("https://open.bigmodel.cn/api/coding/paas/v4")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // Every preset that has an address and is not self-hosted recognizes itself
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }

    /** Both DeepSeek V4 models have a 1M context: with the old 64k default window, compaction rewrites the prefix so often that the prefix cache is invalidated for the whole segment. */
    @Test fun deepSeekPresetUsesTheFullWindow() {
        val deepseek = preset("DeepSeek")
        assertEquals(1_000_000, deepseek.capabilities.contextTokens)
        assertFalse(deepseek.capabilities.vision)
    }

    /** Curated reasoning levels come only from official documentation: DeepSeek's reasoning_effort values are none/low/high/max (none turns thinking off), and aliases are not preset. */
    @Test fun reasoningLevelsAreCuratedAndOfficialOnly() {
        assertEquals(listOf("none", "low", "high", "max"), preset("DeepSeek").reasoningLevels)
        // Providers that have not been verified are never preset, and the UI keeps showing no levels (see ReasoningOptions)
        ProviderPresets.all.filter { it.label != "DeepSeek" }.forEach { p ->
            assertEquals(p.label, emptyList<String>(), p.reasoningLevels)
        }
    }

    @Test fun openDesignGoUsesTheCompatibleEndpointAndRequiresTheUsersKey() {
        val go = preset("OpenDesign Go")
        assertEquals(ProviderConfig.Kind.OpenAiCompat, go.kind)
        assertEquals("https://amr-link.open-design.ai/v1", go.baseUrl)
        assertTrue(go.familyTokens.isEmpty())
        assertEquals(go, ProviderPresets.forHost("https://amr-link.open-design.ai/v1/models"))
        val form = AddForm.of(go)
        assertTrue(form.keyRequired)
        assertFalse(form.canTest)
        assertTrue(form.copy(apiKey = "test-key").canTest)
    }

    @Test fun orcarouterPresetUsesTheCompatibleEndpointAndRequiresTheUsersKey() {
        val orca = preset("OrcaRouter")
        assertEquals(ProviderConfig.Kind.OpenAiCompat, orca.kind)
        assertEquals("https://api.orcarouter.ai/v1", orca.baseUrl)
        assertTrue(orca.familyTokens.isEmpty())
        assertEquals(orca, ProviderPresets.forHost("https://api.orcarouter.ai/v1/models"))
        val form = AddForm.of(orca)
        assertTrue(form.keyRequired)
        assertFalse(form.canTest)
        assertTrue(form.copy(apiKey = "test-key").canTest)
    }

    /** Coding Plan keys are not interchangeable with the standard API; the address must point at the official, separate OpenAI-compatible endpoint. */
    @Test fun codingPlanUsesItsOwnDocumentedEndpoint() {
        val standard = preset("Zhipu")
        val coding = preset("Zhipu Coding Plan")
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", coding.baseUrl)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, coding.kind)
        assertNotEquals(standard.baseUrl, coding.baseUrl)
    }

    /** The form starts on an English-first international provider, never on a China-only vendor, so the assistant is not biased toward Chinese models. */
    @Test fun defaultPresetIsInternational() {
        assertEquals("OpenAI", ProviderPresets.default.label)
        assertTrue(ProviderPresets.default.baseUrl.startsWith("https://api.openai.com"))
        assertNotEquals(ProviderPresets.default, ProviderPresets.custom)
    }

    /** The Cloudflare Workers AI preset requires the user to replace {account_id}; the address is editable and the template matches the actual endpoint. */
    @Test fun cloudflarePresetRequiresAccountIdAndHasEditableUrl() {
        val cf = preset("Cloudflare Workers AI")
        assertEquals(ProviderConfig.Kind.OpenAiCompat, cf.kind)
        assertTrue(cf.capabilities.vision)
        assertTrue(cf.familyTokens.contains("llama-3.3"))
        assertTrue(cf.urlEditable)

        val form = AddForm.of(cf)
        assertTrue(cf.urlEditable)
        assertTrue(form.isCloudflare)
        assertFalse("Cloudflare shows a separate Account ID field and hides the raw URL field", form.urlEditable)
        assertTrue(form.keyRequired)
        assertEquals(tr(com.wangzan101.mika.R.string.model_url_account_id), form.urlProblem)
        assertFalse(form.canTest)

        val filledForm = form.copy(
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1",
            apiKey = "test_key",
        )
        assertNull(filledForm.urlProblem)
        assertTrue(filledForm.canTest)

        assertEquals(cf, ProviderPresets.forBaseUrl("https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1"))
        assertEquals(cf, ProviderPresets.forHost("https://api.cloudflare.com/client/v4/accounts/test_account_id_123/ai/v1"))
    }
}
