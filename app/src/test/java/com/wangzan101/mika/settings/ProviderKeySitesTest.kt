package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderKeySitesTest {

    @Test
    fun `known providers get the official key signup page by host name`() {
        assertEquals("https://platform.deepseek.com/api_keys", ProviderKeySites.forBaseUrl("https://api.deepseek.com"))
        assertEquals("https://platform.openai.com/api-keys", ProviderKeySites.forBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://aistudio.google.com/app/apikey", ProviderKeySites.forBaseUrl("https://generativelanguage.googleapis.com"))
        assertEquals("https://console.anthropic.com/settings/keys", ProviderKeySites.forBaseUrl("https://api.anthropic.com"))
        assertEquals("https://console.groq.com/keys", ProviderKeySites.forBaseUrl("https://api.groq.com/openai/v1"))
        assertEquals("https://www.orcarouter.ai/console/token", ProviderKeySites.forBaseUrl("https://api.orcarouter.ai/v1"))
    }

    @Test
    fun `same host with a different path still matches`() {
        // An official URL typed into the custom form by hand, with a path that differs from the preset, is still recognized by host
        assertEquals("https://platform.deepseek.com/api_keys", ProviderKeySites.forBaseUrl("https://api.deepseek.com/v1"))
    }

    @Test
    fun `unrecognized addresses get no link`() {
        assertNull(ProviderKeySites.forBaseUrl("https://llm.example.com/v1"))
        assertNull(ProviderKeySites.forBaseUrl("http://127.0.0.1:11434/v1"))
        assertNull(ProviderKeySites.forBaseUrl(""))
        assertNull(ProviderKeySites.forBaseUrl("not a url"))
    }

    @Test
    fun `every cloud provider preset resolves to a signup page`() {
        // Presets whose official key page is not confirmed yet: exempted explicitly; once confirmed, add the mapping and remove them from here
        val unmapped = setOf("OpenDesign Go", "OpenCode Go")
        // Self-hosted (local address) and custom (empty address) presets are not included; the mapping follows the preset list, so a missing entry fails
        val missing = ProviderPresets.all
            .filter { !it.selfHosted && it.baseUrl.isNotEmpty() }
            .filter { it.label !in unmapped }
            .filter { ProviderKeySites.forBaseUrl(it.baseUrl) == null }
            .map { it.label }
        assertEquals("these presets are missing an official key signup page mapping: $missing", emptyList<String>(), missing)
    }
}
