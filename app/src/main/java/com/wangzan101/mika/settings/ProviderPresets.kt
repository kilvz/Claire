package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.PromptCaching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Common provider templates used when adding a model: they only prefill the API type, Base URL and initial capability checkboxes,
 * while the model name and Key are entered by the user and capabilities remain editable.
 * [sessionHeader] is the header name in which the provider expects the conversation session ID (see [com.wangzan101.mika.core.model.ModelSession]); null when not needed.
 * [selfHosted] means the service runs on the user's own machine: [baseUrl] is only the local default port, and the form asks the user to change it to that machine's address; the Key may be left empty.
 * [familyTokens] are lowercase tokens present in that vendor's own model names: when the Key also exposes models from other vendors, the vendor's own are tried first by default (see [ModelOptions.candidates]);
 * not provided for hosts serving multiple vendors' models or for self-hosted services.
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
    val selfHosted: Boolean = false,
    val familyTokens: List<String> = emptyList(),
    /**
     * The reasoning levels documented officially by this provider: when the model list endpoint does not return levels (as is mostly the case with the OpenAI-compatible protocol), the UI offers these as the selectable levels.
     * Only values confirmed by the official documentation are filled in, never guessed; in the order the provider documents them.
     */
    val reasoningLevels: List<String> = emptyList(),
    /** The URL contains a parameter the user must replace (e.g. {account_id}), or the provider requires a personalized endpoint: the address is editable in the UI. */
    val urlEditable: Boolean = false,
)

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** The template whose Base URL matches (when a different template was selected during add, or a URL was given directly in tests, so the capability defaults are derived from the address); null if none. */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { preset ->
        preset.baseUrl.isNotEmpty() && (
            url.trimEnd('/') == preset.baseUrl.trimEnd('/') ||
            (preset.baseUrl.contains('{') && matchesTemplate(preset.baseUrl, url))
        )
    }

    private fun matchesTemplate(template: String, url: String): Boolean {
        val parts = template.split("{account_id}")
        if (parts.size != 2) return false
        val prefix = parts[0]
        val suffix = parts[1].trimEnd('/')
        val trimmed = url.trim().trimEnd('/')
        if (!trimmed.startsWith(prefix) || !trimmed.endsWith(suffix)) return false
        val middle = trimmed.substring(prefix.length, trimmed.length - suffix.length)
        return middle.isNotEmpty() && !middle.contains('/')
    }

    /**
     * Which provider template shares the same host name (paths may differ): when a custom entry uses a vendor's official address, switching to that template is suggested.
     * Self-hosted services (localhost addresses) are not considered. Null when the address cannot be parsed or nothing matches.
     */
    fun forHost(url: String): ProviderPreset? {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return null
        // Zhipu's standard API and Coding Plan share a host but differ in path; the one sharing more path segments is the more specific preset.
        return all.filter { !it.selfHosted && it.baseUrl.isNotEmpty() && it.baseUrl.toHttpUrlOrNull()?.host == host }
            .maxByOrNull { pathOverlap(it.baseUrl, url) }
    }

    val all: List<ProviderPreset> = listOf(
        // Since V4 both models (deepseek-flash / deepseek-v4-pro) have a 1M context: with the window left at the old 64k default,
        // compaction keeps rewriting the request prefix, the provider's prefix cache misses for that whole portion, and the same task can cost several times as much
        // Reasoning levels follow the official docs (create-chat-completion): reasoning_effort accepts none/low/high/max,
        // none turns thinking off and the rest turn it on; when omitted the server defaults to thinking on. minimal/medium/xhigh are not filled in: the docs say they are only aliases
        ProviderPreset("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", caps(context = 1_000_000),
            familyTokens = listOf("deepseek"), reasoningLevels = listOf("none", "low", "high", "max")),
        // On this vendor's OpenAI-compatible endpoint many models (such as the qwen3.5 series) only have explicit caching: without marking it nothing is ever reused
        ProviderPreset("通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1", caps(caching = PromptCaching.Explicit), familyTokens = listOf("qwen")),
        ProviderPreset("Kimi", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.cn/v1", caps(), familyTokens = listOf("kimi", "moonshot")),
        ProviderPreset("智谱", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/paas/v4", caps(), familyTokens = listOf("glm")),
        // The Coding Plan Key is separate from the standard API; the dedicated OpenAI Chat Completion address is given in Zhipu's Coding Plan integration guide.
        ProviderPreset("智谱 Coding Plan", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/coding/paas/v4", caps(), familyTokens = listOf("glm")),
        // Hosts open models from several vendors; whether vision works depends on the model
        ProviderPreset("硅基流动", ProviderConfig.Kind.OpenAiCompat, "https://api.siliconflow.cn/v1", caps()),
        // Doubao: enter a model ID or an inference endpoint ID (ep-…) as the model name; this vendor does not always expose a list-models endpoint, so fill it in manually when listing fails
        ProviderPreset("火山方舟", ProviderConfig.Kind.OpenAiCompat, "https://ark.cn-beijing.volces.com/api/v3", caps(vision = true, context = 128_000), familyTokens = listOf("doubao")),
        // China site; reasoning is by default written into the reply body as <think> and kept verbatim in the history
        ProviderPreset("MiniMax", ProviderConfig.Kind.OpenAiCompat, "https://api.minimax.cn/v1", caps(vision = true, context = 200_000), familyTokens = listOf("minimax")),
        ProviderPreset("阶跃星辰", ProviderConfig.Kind.OpenAiCompat, "https://api.stepfun.com/v1", caps(), familyTokens = listOf("step")),
        // Hunyuan's newer models are on TokenHub (the old api.hunyuan.cloud.tencent.com no longer receives new models)
        ProviderPreset("腾讯混元", ProviderConfig.Kind.OpenAiCompat, "https://tokenhub.tencentmaas.com/v1", caps(context = 256_000), familyTokens = listOf("hunyuan")),
        // The Key is the bce-v3/… string generated by the console and is sent as a Bearer token
        ProviderPreset("百度千帆", ProviderConfig.Kind.OpenAiCompat, "https://qianfan.baidubce.com/v2", caps(), familyTokens = listOf("ernie")),
        ProviderPreset("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", caps()),
        // One Key covers 200+ models over the OpenAI-compatible protocol; the free model is orcarouter/free, and orcarouter/auto picks a model automatically from the prompt
        ProviderPreset("OrcaRouter", ProviderConfig.Kind.OpenAiCompat, "https://api.orcarouter.ai/v1", caps()),
        ProviderPreset("OpenDesign Go", ProviderConfig.Kind.OpenAiCompat, "https://amr-link.open-design.ai/v1", caps()),
        // Subscription based: one Key covers models from several vendors; the protocol and address for each model are stated individually in the model list, and the API type here is only the fallback when they are not.
        // Every request must carry the conversation session ID or it is rejected (HTTP 400)
        ProviderPreset("OpenCode Go", ProviderConfig.Kind.OpenAiCompat, "https://opencode.ai/zen/go/v1", caps(), sessionHeader = "x-opencode-session"),
        ProviderPreset("Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", caps(vision = true, context = 200_000), familyTokens = listOf("claude")),
        ProviderPreset("Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com", caps(vision = true, context = 128_000), familyTokens = listOf("gemini")),
        // The official API uses Responses: the new flagship models must use Responses to call tools, and Chat Completions with tools is rejected. Listing models is likewise GET /models
        ProviderPreset("OpenAI", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1", caps(vision = true, context = 400_000), familyTokens = listOf("gpt")),
        ProviderPreset("xAI", ProviderConfig.Kind.OpenAiCompat, "https://api.x.ai/v1", caps(vision = true, context = 256_000), familyTokens = listOf("grok")),
        // The list-models endpoint states for each model whether it supports tool calls and vision, plus its context length
        ProviderPreset("Mistral", ProviderConfig.Kind.OpenAiCompat, "https://api.mistral.ai/v1", caps(vision = true, context = 128_000), familyTokens = listOf("mistral")),
        ProviderPreset("Groq", ProviderConfig.Kind.OpenAiCompat, "https://api.groq.com/openai/v1", caps(context = 128_000)),
        // Cloudflare Workers AI: OpenAI-compatible endpoint; {account_id} in the address must be replaced with the user's Cloudflare account ID
        ProviderPreset("Cloudflare Workers AI", ProviderConfig.Kind.OpenAiCompat, "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1", caps(vision = true, context = 128_000), familyTokens = listOf("llama-3.3", "gpt-oss", "llama-4", "llama", "gemma", "mistral"), urlEditable = true),
        // Runs on the user's own machine: the address is the local default port; when it runs on a computer, change it to that computer's LAN address; no Key required
        ProviderPreset("Ollama", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", caps(), selfHosted = true),
        ProviderPreset("LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1", caps(), selfHosted = true),
        ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    val custom: ProviderPreset get() = all.last()

    /** When several presets share a host, pick the closest one by the number of matching path segments; ties at the root address keep the preset order. */
    private fun pathOverlap(presetUrl: String, inputUrl: String): Int {
        val preset = presetUrl.trimEnd('/').toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        val input = inputUrl.trim().toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        return preset.indices.count { preset[it] == input.getOrNull(it) }
    }
}
