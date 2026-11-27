package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Read-aloud fallback chain and key-pool rotation: each synthesis starts with the head, and on failure [VoiceFailover]
 * decides whether to try the next one; each model uses its own account's key pool
 * ([VoiceAccountResolver.withPooledKey]). A backup uses its own default voice (the voice in the request was chosen for
 * the head and other providers usually do not recognize it) but keeps the requested speed. Streaming read-aloud only
 * switches before any audio has been handed out; once playback has started it fails as usual rather than changing voices
 * mid-sentence.
 */
internal class FallbackTtsModel(
    private val chain: List<VoiceModelRef>,
    private val first: ResolvedVoiceModel,
    private val resolve: (VoiceModelRef) -> ResolvedVoiceModel,
    private val accounts: VoiceAccountResolver,
    private val create: (ResolvedVoiceModel) -> TtsModel,
    private val head: TtsModel,
) : TtsModel {
    override val name: String get() = head.name
    override val capabilities: TtsCapabilities get() = head.capabilities

    /** Cache built models by "position + key" so the system engine and HTTP clients do not need rebuilding for every sentence. */
    private val built = mutableMapOf<Pair<Int, String>, TtsModel>()

    @Synchronized
    private fun modelFor(index: Int, keyed: ResolvedVoiceModel): TtsModel {
        val key = index to keyed.account.credentials.apiKey
        if (index == 0 && keyed.account.credentials.apiKey == first.account.credentials.apiKey) return head
        return built.getOrPut(key) { create(keyed) }
    }

    private fun resolveAt(ref: VoiceModelRef): ResolvedVoiceModel = if (ref == chain.first()) first else resolve(ref)

    private fun TtsRequest.forIndex(index: Int): TtsRequest = if (index == 0) this else copy(voice = null)

    override suspend fun synthesize(request: TtsRequest): TtsResponse =
        VoiceFailover.run(chain, ::resolveAt) { resolved, index ->
            accounts.withPooledKey(resolved) { keyed -> modelFor(index, keyed).synthesize(request.forIndex(index)) }
        }

    /** Only a streaming-capable head is a [StreamingTtsModel]; a non-streaming backup synthesizes the whole clip and hands it over as one complete audio file. */
    fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = flow {
        var started = false
        VoiceFailover.run(chain, ::resolveAt, mayContinue = { !started }) { resolved, index ->
            accounts.withPooledKey(resolved, mayRetry = { !started }) { keyed ->
                val model = modelFor(index, keyed)
                val piece = request.forIndex(index)
                if (model is StreamingTtsModel) {
                    model.streamAudio(piece).collect { part ->
                        // Record it before handing it out: downstream errors also count as started, so do not switch models and retry
                        started = true
                        emit(part)
                    }
                } else {
                    val response = model.synthesize(piece)
                    started = true
                    emit(TtsAudioPart.Complete(response))
                }
            }
        }
    }

    companion object {
        /** Wrap the head as streaming when it can stream, otherwise as the ordinary model. */
        fun of(
            chain: List<VoiceModelRef>,
            first: ResolvedVoiceModel,
            resolve: (VoiceModelRef) -> ResolvedVoiceModel,
            accounts: VoiceAccountResolver,
            create: (ResolvedVoiceModel) -> TtsModel,
        ): TtsModel {
            val head = create(first)
            val model = FallbackTtsModel(chain, first, resolve, accounts, create, head)
            if (head !is StreamingTtsModel) return model
            return object : StreamingTtsModel {
                override val name: String get() = model.name
                override val capabilities: TtsCapabilities get() = model.capabilities
                override suspend fun synthesize(request: TtsRequest): TtsResponse = model.synthesize(request)
                override fun streamAudio(request: TtsRequest): Flow<TtsAudioPart> = model.streamAudio(request)
            }
        }
    }
}
