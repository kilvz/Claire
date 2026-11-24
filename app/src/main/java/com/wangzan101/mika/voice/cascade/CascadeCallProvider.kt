package com.wangzan101.mika.voice.cascade

import com.wangzan101.mika.calls.CallRealtimeProvider
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.LlmBrain
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.serialization.json.JsonObject

/**
 * A standard call behind the realtime provider seam. The phone call's prompt becomes the thinking model's
 * instructions, and its keypad and ask-the-owner tools are offered as functions; credentials come from the voice models themselves.
 */
class CascadeCallProvider(
    private val engine: CascadeEngine,
    private val use: CallUse,
    private val language: String? = null,
    /** The trailing note for the call's front desk (the state of the task this call belongs to), read once before every model call; null when there is no owning task. */
    private val background: DeskBackground? = null,
) : CallRealtimeProvider {
    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        engine.blocker(use)?.let { throw VoiceModelError.IncompleteConfig("Standard call is not ready: $it") }
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
    ): CallRealtimeSession {
        // Carrier phone call: keypad plus the phone front desk (ask-the-owner only)
        val tools = if (enablePhoneDtmf) VolcRealtimeProtocol.phoneCallTools().map { LlmBrain.toolSpec(it as JsonObject) } else emptyList()
        return engine.open(use, systemPrompt, tools, language, background = background)
    }
}
