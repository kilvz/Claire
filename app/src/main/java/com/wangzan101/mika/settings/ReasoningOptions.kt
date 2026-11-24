package com.wangzan101.mika.settings

import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.model.ListedModel

/**
 * Reasoning levels. For models whose levels the provider listing states, all four protocols (Chat Completions, Messages, generateContent, Responses) follow it
 * (the Anthropic listing states the levels and thinking modes supported by each model individually, see ModelCatalog; the OpenAI-compatible protocol additionally gets the `none` documented officially, see [withDocumentedOff]);
 * when not stated: the Responses family gets the generic manual levels, the OpenAI-compatible protocol first uses the levels documented officially in the preset (see [ProviderPreset.reasoningLevels]),
 * and third-party gateways that match no preset also get the generic manual levels — both are flagged, actual support depends on the server and no field is sent by default; the other protocols are not guessed:
 * each model accepts different levels and spellings, and a wrong guess gets every request rejected.
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // Offered for manual selection when the listing does not state levels, without claiming every model supports them; the UI explains this, and nothing is ever specified by default.
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { declared ->
                // The plan endpoint's model catalog once listed ultra as selectable even though the server rejects it. The catalog is external input too:
                // this integration only shows levels the request layer genuinely knows, so bad metadata cannot turn every call into HTTP 400.
                val levels = if (config.kind == ProviderConfig.Kind.ChatGpt) declared.filter(manualLevels::contains) else declared
                return ReasoningOptions(withDocumentedOff(config, listed, levels), fromProvider = true)
            }
            // Empty options are still returned, so a previously saved level can be reset to the default in the UI.
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return when (ProviderStore.adapterFor(config, listed?.endpoint).first) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    ReasoningOptions(manualLevels, fromProvider = false)
                // Offer exactly the levels this vendor documents officially in its preset; OpenAI-compatible addresses that match no preset (third-party gateways)
                // also get the generic levels, flagged as "not from the provider", so the entry point can render and the user explicitly bears the risk; the default is still "default", sending no field.
                ProviderConfig.Kind.OpenAiCompat ->
                    ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels
                        ?.takeIf { it.isNotEmpty() }?.let { ReasoningOptions(it, fromProvider = false) }
                        ?: ReasoningOptions(manualLevels, fromProvider = false)
                ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini -> null
            }
        }

        /**
         * The listing reports the intensities selectable while thinking is on (DeepSeek's `effort.supported_levels` is low / high / max),
         * while the official docs separately document `none` for turning thinking off; it is prepended so that thinking cannot become impossible to disable once the listing is read. Only the `none` documented in the preset is added, no other level.
         */
        private fun withDocumentedOff(config: ProviderConfig, listed: ListedModel, levels: List<String>): List<String> {
            if (levels.isEmpty() || OFF in levels) return levels
            // Evaluated against the endpoint this model actually uses: nothing is added when the listing assigns it another protocol
            if (ProviderStore.adapterFor(config, listed.endpoint).first != ProviderConfig.Kind.OpenAiCompat) return levels
            val documented = ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels.orEmpty()
            return if (OFF in documented) listOf(OFF) + levels else levels
        }

        private const val OFF = "none"
    }
}

/**
 * The reply limit and reasoning level used when building models for proactive features (cheap background calls).
 *
 * Level: only when the provider listing states this model's levels ([ReasoningOptions.fromProvider]) is the lowest one taken (ranked by [RANK],
 * since the listing itself may be unordered; unrecognized levels are ignored); when the listing does not state them (manual or preset levels) nothing is guessed and the level selected for this role is used.
 * The reply limit is only sent with the Messages and generateContent protocols (see ProviderStore.buildModel), and only for the two request kinds with short replies
 * (triage and topic summarization, see [ProactiveCall.long]): thinking tokens count toward the limit, so when thinking is known to be off (the level is none, or the Messages protocol has no level selected
 * and thinking is off anyway) [CAP] is used, otherwise the more generous [CAP_THINKING], so thinking cannot consume the whole limit and leave the reply empty.
 * Segment, daily, weekly and monthly summaries get no separate limit (null, using the adapter default like ordinary tasks): a chunk has a dozen-odd sentences of hundreds of characters each, which a few hundred tokens cannot hold,
 * and a truncated reply is unreadable, so that chunk would never be completed.
 */
internal object ProactiveModelSetup {
    /** Triage handles at most 8 items with one sentence of reasoning each; topic summarization handles at most a dozen-odd short labels. It still fits when every reasoning is written in full (counting one and a half tokens per character). */
    const val CAP = 2_400
    const val CAP_THINKING = 4_096
    private val RANK = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /** The lowest of the levels the provider states; null when none are stated or none is recognized. */
    fun lowestDeclared(options: ReasoningOptions?): String? =
        options?.takeIf { it.fromProvider }?.levels?.filter { it in RANK }?.minByOrNull(RANK::indexOf)

    /** The level and reply limit actually used (null means no separate limit). [adapter] is the protocol actually used, [roleEffort] the level selected for this role, and [call] the kind of request. */
    fun of(adapter: ProviderConfig.Kind, options: ReasoningOptions?, roleEffort: String?, call: ProactiveCall): Pair<String?, Int?> {
        val effort = lowestDeclared(options) ?: roleEffort
        val noThinking = effort == "none" || (adapter == ProviderConfig.Kind.Anthropic && effort == null)
        return effort to when {
            call.long -> null
            noThinking -> CAP
            else -> CAP_THINKING
        }
    }
}
