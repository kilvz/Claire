package com.wangzan101.mika.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface CallRealtimeSession : AutoCloseable {
    val events: Flow<VolcRealtimeEvent>
    val playbackTruncation: CallPlaybackTruncation? get() = null
    val inputSampleRate: Int get() = VolcRealtimeClient.INPUT_SAMPLE_RATE
    val outputSampleRate: Int get() = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
    val inputChunkBytes: Int get() = VolcRealtimeClient.INPUT_CHUNK_BYTES
    fun sendPcm16(bytes: ByteArray, byteCount: Int = bytes.size): Boolean
    fun sendGreeting(text: String): Boolean
    fun setMuted(muted: Boolean): Boolean
    fun sendToolResult(callId: String, result: JsonObject): Boolean = false
    /**
     * A message arrived from a backend task: speak it after the current sentence finishes and when the user is not
     * speaking, without interrupting; returns false when it cannot be spoken now (the caller takes another route).
     * An accepted message is always [ended] exactly once at the end: true when fully spoken; false when interrupted or
     * the call ended before it got a turn.
     */
    fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit = {}): Boolean = false
    /**
     * A cue handed to the model (not something the other party said, and not read verbatim): the model reads it and
     * continues in its own words. Queuing and the [ended] contract are the same as [sendNotice].
     * Returns false when the protocol has no text input that keeps the model replying; the caller takes another route.
     */
    fun sendCue(text: String, ended: (spoken: Boolean) -> Unit = {}): Boolean = false

    /** A cue used purely as background for the model: added to the context without making it speak; returns false when the protocol has no such input. */
    fun sendContext(text: String): Boolean = false

    /** Text the user typed, treated as one utterance and answered; returns false when unsupported. */
    fun sendUserText(text: String): Boolean = false
    override fun close()
}

interface CallRealtimeProvider {
    /** Local validation only: invalid config must fail before an outgoing carrier call is placed. */
    fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean = false) {}

    suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean = false,
        /** Language used for the phone tool description, the same as [systemPrompt]; null means Chinese. */
        languageTag: String? = null,
    ): CallRealtimeSession
}

class ProfileRealtimeProvider : CallRealtimeProvider {
    private fun config(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean, languageTag: String? = null): VolcRealtimeConfig {
        if (profile.protocol == CallVoiceProtocol.OPENAI_REALTIME) require(profile.endpoint == OpenAiCallProtocol.ENDPOINT)
        return VolcRealtimeConfig(systemPrompt = systemPrompt, speaker = profile.speaker, model = profile.model, enablePhoneDtmf = enablePhoneDtmf, protocol = profile.protocol, languageTag = languageTag)
    }

    override fun validate(profile: CallVoiceProfile, systemPrompt: String, enablePhoneDtmf: Boolean) {
        config(profile, systemPrompt, enablePhoneDtmf)
    }

    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
        enablePhoneDtmf: Boolean,
        languageTag: String?,
    ): CallRealtimeSession {
        return VolcRealtimeClient(endpoint = profile.endpoint).connect(credentials, config(profile, systemPrompt, enablePhoneDtmf, languageTag))
    }
}
