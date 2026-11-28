package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.agent.FailoverPolicy
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.settings.movedFallbacks
import kotlinx.coroutines.CancellationException

/**
 * How voice fallback chains ([VoiceSelections.sttFallbacks] / [VoiceSelections.ttsFallbacks]) change. Pure functions
 * used by [VoiceCatalogStore] and the UI, with the same rules as chat-model fallback chains
 * ([com.wangzan101.mika.settings.setFallback]).
 */
object VoiceFallbacks {
    /** Maximum backups in one chain. Voice is used while waiting, and a long chain can make the worst case very slow. */
    const val MAX = 3

    /**
     * Only models that transcribe a whole uploaded recording qualify: after release the whole recording is handed over,
     * and on failure the same recording can go to the next model. Streaming recognition that writes while you speak (and
     * system recognition) is connected to the service throughout, cannot switch mid-way, and neither joins a chain nor
     * acts as its head.
     */
    val UTTERANCE_STT_PROTOCOLS: Set<SttProtocol> = setOf(
        SttProtocol.AudioTranscriptions,
        SttProtocol.ChatInputAudio,
        SttProtocol.GeminiInline,
        SttProtocol.ElevenLabsUpload,
        SttProtocol.DeepgramUpload,
        SttProtocol.AzureShortAudio,
        SttProtocol.TencentSentence,
        SttProtocol.XfyunDictation,
        // On-device packs also transcribe the whole recording after release, so they can lead the chain or back up a cloud model
        SttProtocol.OnDevice,
    )

    /** Whether [model] can join a [capability] fallback chain (and whether such a chain can hang off it). Real-time calls have no fallback chain. */
    fun eligible(model: VoiceModel, capability: VoiceCapability): Boolean = when (capability) {
        // Live recognizers produce text while the user speaks and cannot switch mid-way, so they never join a fallback chain
        VoiceCapability.STT -> model.stt?.let { it.protocol in UTTERANCE_STT_PROTOCOLS && !it.live } == true
        VoiceCapability.TTS -> model.tts != null
        VoiceCapability.REALTIME -> false
    }

    /**
     * Change slot [slot]: a null [ref] removes that slot (later entries move up); an entry already in the chain moves to
     * the target slot without duplicating; a new entry replaces whatever is there, or appends when the chain is not full.
     * Out of range or a full chain returns null.
     */
    fun set(chain: List<VoiceModelRef>, slot: Int, ref: VoiceModelRef?): List<VoiceModelRef>? {
        if (slot < 0 || slot >= MAX) return null
        return when {
            ref == null -> if (slot in chain.indices) chain.toMutableList().apply { removeAt(slot) } else chain
            ref in chain -> {
                val rest = chain.filter { it != ref }
                rest.toMutableList().apply { add(minOf(slot, rest.size), ref) }
            }
            slot in chain.indices -> chain.toMutableList().apply { this[slot] = ref }
            chain.size < MAX -> chain + ref
            else -> null
        }
    }

    /** Move position [from] to position [to], the same algorithm as chat fallback chains. Out of range is null. */
    fun moved(chain: List<VoiceModelRef>, from: Int, to: Int): List<VoiceModelRef>? = movedFallbacks(chain, from, to)

    /**
     * Clean up before saving: deduplicate, drop the head [primary] itself, wrong-capability entries, and anything [usable]
     * says is no longer in the catalog or cannot join the chain, then truncate to [MAX].
     */
    fun normalized(
        chain: List<VoiceModelRef>,
        primary: VoiceModelRef?,
        capability: VoiceCapability,
        usable: (VoiceModelRef) -> Boolean,
    ): List<VoiceModelRef> = chain
        .asSequence()
        .distinct()
        .filter { it.capability == capability && it != primary && usable(it) }
        .take(MAX)
        .toList()
}

/**
 * Whether a failed voice step should try the next entry in the chain: decided only here. Provider errors ([ModelError],
 * possibly wrapped by the voice layer) go through the same decision as chat-model chains, [FailoverPolicy.canFailover]
 * (rate limit, 5xx, network, auth, quota, model missing … switch; rejected content, bad request, unparseable reply do
 * not). In the voice-specific cases, timeouts, network, rate limits, auth, a misconfigured model (missing credentials or
 * address) and a missing on-device engine are all this model/account's problem, so switch; cancellation and an
 * unparseable reply do not switch.
 */
object VoiceFailover {
    fun canTryNext(error: Throwable): Boolean {
        if (error is CancellationException || error is VoiceModelError.Cancelled) return false
        val modelError = generateSequence(error) { it.cause }.take(8).firstOrNull { it is ModelError }
        if (modelError != null) return FailoverPolicy.canFailover(modelError)
        return when (VoiceModelError.from(error).category) {
            VoiceErrorCategory.TIMEOUT,
            VoiceErrorCategory.NETWORK,
            VoiceErrorCategory.RATE_LIMITED,
            VoiceErrorCategory.AUTH_FAILED,
            VoiceErrorCategory.MISSING_CREDENTIALS,
            VoiceErrorCategory.INCOMPLETE_CONFIG,
            VoiceErrorCategory.SYSTEM_UNAVAILABLE -> true
            VoiceErrorCategory.UNSUPPORTED,
            VoiceErrorCategory.PROTOCOL_ERROR,
            VoiceErrorCategory.CANCELLED -> false
        }
    }

    /**
     * Try [candidates] in order: [resolve] each first (read its account; a misconfigured backup is skipped, while a
     * misconfigured head still throws), then [attempt]. On failure, try the next only when [canTryNext] says to switch and
     * [mayContinue] still allows it (for example streaming read-aloud has not produced audio yet). When none work, throw
     * the error from the last request actually sent; only if nothing was sent, throw the configuration error.
     */
    suspend fun <C, M, T> run(
        candidates: List<C>,
        resolve: (C) -> M,
        mayContinue: () -> Boolean = { true },
        attempt: suspend (model: M, index: Int) -> T,
    ): T {
        require(candidates.isNotEmpty())
        var lastAttemptError: Throwable? = null
        var lastConfigError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val model = try {
                resolve(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (index == 0) throw e
                lastConfigError = e
                continue
            }
            try {
                return attempt(model, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastAttemptError = e
                if (!canTryNext(e) || !mayContinue()) throw e
            }
        }
        throw lastAttemptError ?: lastConfigError!!
    }
}
