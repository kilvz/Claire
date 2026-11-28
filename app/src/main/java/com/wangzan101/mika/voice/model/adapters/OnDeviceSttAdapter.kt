package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.voice.FailedSession
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.ProviderSpeech
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.Wav
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.ondevice.InstalledVoicePack
import com.wangzan101.mika.voice.ondevice.OnDeviceBackends
import com.wangzan101.mika.voice.ondevice.OnDeviceEngines
import com.wangzan101.mika.voice.ondevice.OnDeviceLanguage
import com.wangzan101.mika.voice.ondevice.OnDeviceSpeechSession
import com.wangzan101.mika.voice.ondevice.OnDeviceSttException
import com.wangzan101.mika.voice.ondevice.OnDeviceSttStream
import com.wangzan101.mika.voice.ondevice.OnDeviceTimeout
import com.wangzan101.mika.voice.ondevice.VoicePackCatalog
import com.wangzan101.mika.voice.ondevice.VoicePackEntry
import com.wangzan101.mika.voice.ondevice.VoicePackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device offline recognition (an installed voice pack). Like whole-clip upload recognition, it records while held and transcribes the whole clip on release, so it can lead a fallback chain
 * and can also sit behind a cloud model. It only knows the two interfaces [com.wangzan101.mika.voice.ondevice.OnDeviceSttBackend] /
 * [com.wangzan101.mika.voice.ondevice.OnDeviceSttEngine] and does not know which inference library is underneath.
 *
 * Failures are mapped to [VoiceModelError]: a missing pack is [VoiceModelError.PackMissing] and everything else is [VoiceModelError.OnDeviceFailed];
 * both move to the next entry in the chain and neither is a network error. [budgetMs] is replaceable only for tests and defaults to the clip length ([OnDeviceTimeout.budgetMs]).
 */
class OnDeviceSttAdapter(
    private val catalog: () -> VoicePackCatalog,
    private val store: VoicePackStore,
    private val engines: OnDeviceEngines,
    private val backends: OnDeviceBackends,
    private val budgetMs: (samples: Int) -> Long = { OnDeviceTimeout.budgetMs(it) },
) : SttAdapter {
    override val protocol = SttProtocol.OnDevice

    override fun spec(model: ResolvedVoiceModel): SpeechEngineSpec {
        val packId = packId(model)
        val entry = entry(packId)
        if (backends.find(entry.backend)?.abiSupported() != true) {
            throw VoiceModelError.Unsupported(VoiceCapability.STT, protocol.name)
        }
        installed(entry)
        return SpeechEngineSpec.OnDevice(packId)
    }

    override fun start(context: Context, model: ResolvedVoiceModel, language: String?, hooks: SttHooks): SpeechSession {
        spec(model)
        // Call audio arrives from outside; the first version of calls does not use on-device packs (too slow to produce text)
        if (hooks.recorder != null) return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.OnDeviceUnsupported))
        val packId = packId(model)
        val entry = entry(packId)
        val pack = installed(entry)
        prewarm(model)
        if (entry.streaming) {
            // Live pack: the recorder feeds the transducer while the user speaks; the model may still be loading (the recorder queues audio)
            val code = OnDeviceLanguage.pick(languageTag(context, language), entry.languages)
            return categorizedSession {
                OnDeviceSpeechSession(
                    recorder = PcmRecorder(streaming = true, unbounded = true, bufferChunks = 1_200),
                    openStream = { streamingStream(pack, entry, code) },
                    mapError = { error ->
                        if (error is OnDeviceSttException) mapped(error, entry)
                        else VoiceModelError.OnDeviceFailed("Live on-device recognition failed: ${error.message}", error)
                    },
                )
            }
        }
        val transcribe = utterance(context, model, languageTag(context, language))
        // The transcription limit is computed by utterance from the clip length, not the 30 s used for whole-clip upload (hitting that would look like a connection failure)
        return categorizedSession { ProviderSpeech(PcmRecorder(), transcribe, timeoutMs = Long.MAX_VALUE) }
    }

    /** Starts loading the model in the background on press-to-talk, alongside recording; errors surface later at transcription. */
    override fun prewarm(model: ResolvedVoiceModel) {
        val pack = runCatching { installed(entry(packId(model))) }.getOrNull() ?: return
        engines.prewarm(pack)
    }

    override fun utterance(context: Context, model: ResolvedVoiceModel, language: String?): UtteranceTranscribe {
        val packId = packId(model)
        return { wav ->
            // Check fresh every time: files downloaded later work immediately, and deleted ones report "download required"
            val entry = entry(packId)
            val pack = installed(entry)
            try {
                val pcm = Wav.decode(wav)
                val code = OnDeviceLanguage.pick(language, entry.languages)
                // Waiting for the model to load (with its own limit) is not counted in the recognition budget
                val engine = engines.engine(pack)
                val budget = budgetMs(pcm.size)
                // Native decoding runs separately in OnDeviceEngines, so this returns as soon as time is up without waiting for it
                withTimeoutOrNull(budget) { engine.transcribe(pcm, code) }
                    // Count as an on-device failure, not a network timeout: a slow device must not show "cannot connect"
                    ?: throw VoiceModelError.OnDeviceFailed("On-device recognition took longer than $budget ms")
            } catch (e: OnDeviceSttException) {
                throw mapped(e, entry)
            } catch (e: VoiceModelError) {
                throw e
            } catch (e: CancellationException) {
                // A cancellation of the caller propagates as usual; when the caller is still waiting but gets cancelled (for example loading was cancelled), count it as an on-device failure for this attempt and try the next one
                currentCoroutineContext().ensureActive()
                throw VoiceModelError.OnDeviceFailed("On-device recognition was cancelled", e)
            } catch (e: Exception) {
                // Unexpected failures such as undecodable audio or language selection errors also count as on-device failures so the chain always moves on (otherwise they would stop here as protocol errors)
                throw VoiceModelError.OnDeviceFailed("On-device recognition failed: ${e.message}", e)
            }
        }
    }

    /**
     * Opens this session's live stream; waits for the shared model to load (the recorder queues audio meanwhile).
     * A pack whose engine only transcribes whole clips cannot serve a live pack, so it is an on-device failure.
     */
    private suspend fun streamingStream(pack: InstalledVoicePack, entry: VoicePackEntry, language: String?): OnDeviceSttStream =
        try {
            engines.engine(pack).newStream(language)
                ?: throw VoiceModelError.OnDeviceFailed("voice pack ${entry.id} has no live stream")
        } catch (e: OnDeviceSttException) {
            throw mapped(e, entry)
        }

    /**
     * A missing file ([OnDeviceSttException.Kind.Missing]) means "download required" only when the pack is really not installed at this moment;
     * when the pack is still installed (the model was released or a version change is in progress) it is a failure of this attempt, so the chain moves on rather than sending the user to download again.
     */
    private fun mapped(error: OnDeviceSttException, entry: VoicePackEntry): VoiceModelError =
        if (error.kind == OnDeviceSttException.Kind.Missing && store.installedAny(entry) != null) {
            VoiceModelError.OnDeviceFailed(error.message ?: "On-device model was released", error)
        } else {
            VoiceModelError.from(error, entry.id)
        }

    private fun packId(model: ResolvedVoiceModel): String =
        model.sttBinding(protocol).endpoint.ifBlank { model.model.id }

    private fun entry(packId: String): VoicePackEntry {
        val packs = try {
            catalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable APK-bundled manifest is a packaging problem: treat it as on-device unavailability, not a network error
            throw VoiceModelError.OnDeviceFailed("Voice pack catalog is unavailable", e)
        }
        return packs.entry(packId) ?: throw VoiceModelError.PackMissing(packId)
    }

    private fun installed(entry: VoicePackEntry): InstalledVoicePack =
        store.installedAny(entry) ?: throw VoiceModelError.PackMissing(entry.id)
}
