package com.wangzan101.mika.voice

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider

/**
 * Pure logic for "speech to text" under Settings › Models: how the picker groups providers and what is written on the right of the capability card.
 * Touches neither the UI nor the network, so it can be unit-tested directly.
 */
object SpeechOptions {
    /**
     * One group in the picker: [models] holds only models that look like voice models. Each group also has a manual entry below it (the UI owns that):
     * provider model lists are often incomplete — voice models in particular are easily missing — and some provide no list at all.
     */
    data class Group(val provider: Provider, val models: List<String>)

    /** One group per provider that can transcribe; models are listed exactly as the provider gives them, with no purpose guessed from the name. */
    fun groups(providers: List<Provider>): List<Group> =
        providers.filter(Transcriber::supports).map { provider ->
            Group(provider, provider.models.orEmpty().map { it.id })
        }

    /** The two lines on the right of the card: [title] is what is selected, [sub] is supplementary (may be absent), and when [subOk] is true the supplementary line uses the green for "available". */
    data class Summary(val title: String, val sub: String?, val subOk: Boolean)

    fun summary(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): Summary {
        val unset = Summary(tr(R.string.settings_not_set), null, false)
        // The system says it exists, but trying it got no response
        val silent = Summary(tr(R.string.speech_system), tr(R.string.speech_system_silent), false)
        return when (choice) {
            SpeechChoice.Off -> Summary(tr(R.string.models_off), null, false)
            is SpeechChoice.Doubao -> Summary(tr(R.string.speech_doubao_model), tr(R.string.speech_doubao_provider), false)
            is SpeechChoice.FunAsr -> Summary(tr(R.string.speech_funasr_model), FunAsrProtocol.hostOf(choice.url), false)
            is SpeechChoice.Bailian -> Summary(
                choice.model,
                tr(R.string.speech_bailian_provider) + " · " + tr(if (choice.region == BailianRegion.Singapore) R.string.speech_bailian_singapore else R.string.speech_bailian_beijing),
                false,
            )
            SpeechChoice.Auto -> when {
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> unset
            }
            SpeechChoice.System -> when {
                // The built-in engine was chosen explicitly but is unusable now: say so plainly instead of silently switching to something else
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> Summary(tr(R.string.speech_system), tr(R.string.speech_system_gone), false)
            }
            is SpeechChoice.Provider -> {
                val p = providers.firstOrNull { it.id == choice.providerId } ?: return unset
                Summary(choice.model, p.name, false)
            }
        }
    }

    /** What is shown on the settings page when the connection test for a live-transcribing service fails; carries none of the server's own wording. */
    fun probeFailure(e: Throwable): String = when {
        e is ModelError.Auth || e is LiveAsrError && e.auth -> tr(R.string.speech_live_auth)
        e is LiveAsrError -> tr(R.string.speech_live_refused, e.code)
        e is ModelError.RateLimited -> tr(R.string.speech_live_refused, "429")
        else -> tr(R.string.speech_live_unreachable)
    }

    private fun systemSummary(system: SystemCapability) = Summary(
        tr(R.string.speech_system),
        tr(if (system.onDevice) R.string.speech_system_offline else R.string.speech_system_online),
        subOk = system.onDevice,
    )
}
