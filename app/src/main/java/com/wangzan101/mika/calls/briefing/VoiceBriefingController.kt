package com.wangzan101.mika.calls.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPlaybackTruncation
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class VoiceBriefingActivity { IDLE, CONNECTING, LISTENING, CLOSING, AUTHORIZED }

data class VoiceBriefingState(
    val activity: VoiceBriefingActivity = VoiceBriefingActivity.IDLE,
    val targetKey: String = "",
    val ownerTranscript: String = "",
    val ownerPartial: String = "",
    val assistantTranscript: String = "",
    val message: String? = null,
) {
    val active: Boolean get() = activity in setOf(
        VoiceBriefingActivity.CONNECTING, VoiceBriefingActivity.LISTENING, VoiceBriefingActivity.CLOSING,
    )
}

internal interface VoiceBriefingConnection {
    val playbackTruncation: CallPlaybackTruncation? get() = null
    val events: Flow<VolcRealtimeEvent>
    fun send(bytes: ByteArray): Boolean
    fun greet(text: String): Boolean
    suspend fun closeAndAwait()
}

internal interface VoiceBriefingAudio {
    suspend fun start(
        scope: CoroutineScope,
        mayUseAudio: () -> Boolean,
        send: (ByteArray) -> Boolean,
        onFailure: (String) -> Unit,
    )
    fun enqueue(pcm: ByteArray)
    fun interrupt(): Long
    fun stopImmediately()
    suspend fun close()
}

/** Screen-owned, foreground-only. Call methods on the UI thread; gates may be read on IO.
 * The caller must cancel on lifecycle STOP, a changed number/SIM, or any non-IDLE call state.
 * canContinue must additionally reject those conditions until onAuthorized is invoked.
 */
class VoiceBriefingController internal constructor(
    private val scope: CoroutineScope,
    private val connect: suspend (VolcRealtimeCredentials, VolcRealtimeConfig) -> VoiceBriefingConnection,
    private val newAudio: () -> VoiceBriefingAudio,
    private val localGate: () -> Boolean,
    private val maxSessionMillis: Long = 180_000,
    /** The language of the briefing conversation (the app UI language); null means the default. */
    private val languageTag: () -> String? = { null },
    /** The greeting the app speaks, following the UI language. */
    private val greetingText: () -> String = { VoiceBriefingConversation.GREETING },
) : AutoCloseable {
    constructor(context: Context, scope: CoroutineScope, endpoint: String = VolcRealtimeProtocol.ENDPOINT) : this(
        scope,
        { credentials, config -> ProviderConnection(VolcRealtimeClient(endpoint).connect(credentials, config)) },
        { AndroidVoiceBriefingAudio(context.applicationContext) },
        {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                kotlin.runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall == false }
                    .getOrDefault(false)
        },
        languageTag = { AppLanguage.resolved(context).tag },
        greetingText = { tr(R.string.calls_voice_briefing_greeting) },
    )

    private val mutable = MutableStateFlow(VoiceBriefingState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null
    private var audio: VoiceBriefingAudio? = null
    private var closed = false

    fun start(
        targetKey: String,
        credentials: VolcRealtimeCredentials,
        config: VolcRealtimeConfig,
        initialBrief: CallTaskBrief? = null,
        canContinue: () -> Boolean,
        onAuthorized: (CallTaskBrief) -> Unit,
    ): Boolean {
        if (closed || operation != null) return false
        if (targetKey.isBlank() || !credentials.valid || !safeGate(canContinue)) {
            mutable.value = VoiceBriefingState(message = "Check the number, microphone permission and that the phone is free, then try again")
            return false
        }
        val conversation = try { VoiceBriefingConversation(initialBrief) }
        catch (_: IllegalArgumentException) {
            mutable.value = VoiceBriefingState(message = "The existing briefing cannot be saved in full; please edit it first")
            return false
        }
        val token = ++generation
        val languageTag = languageTag()
        val greeting = greetingText()
        mutable.value = VoiceBriefingState(activity = VoiceBriefingActivity.CONNECTING, targetKey = targetKey)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var provider: VoiceBriefingConnection? = null
            var localAudio: VoiceBriefingAudio? = null
            var authorized: CallTaskBrief? = null
            var cleanlyClosed = false
            var failure: String? = null
            // Audio callbacks can run off the UI thread. Latch failure before cancellation so
            // even an already buffered final, or a callback after device cleanup, cannot dial.
            val audioFailure = AtomicReference<String?>(null)
            val valid = { audioFailure.get() == null && token == generation && !closed && safeGate(canContinue) }
            try {
                withTimeout(maxSessionMillis) {
                    coroutineScope {
                        val watcher = launch {
                            while (true) {
                                if (!valid()) throw VoiceBriefingFailure("The voice briefing has stopped; no call was placed")
                                delay(50)
                            }
                        }
                        try {
                            val prompt = VoiceBriefingConversation.promptFor(languageTag, greeting) +
                                initialBrief?.let { VoiceBriefingConversation.briefContext(it, languageTag) }.orEmpty()
                            provider = connect(credentials, config.copy(systemPrompt = prompt, enablePhoneDtmf = false))
                            if (!valid()) throw VoiceBriefingFailure("The voice briefing has stopped; no call was placed")
                            localAudio = newAudio().also { audio = it }
                            localAudio!!.start(this, valid,
                                send = { bytes -> valid() && provider!!.send(bytes) },
                                onFailure = { message ->
                                    audioFailure.compareAndSet(null, message)
                                    this@coroutineScope.cancel(CancellationException("Local briefing audio ended"))
                                },
                            )
                            if (!valid() || !provider!!.greet(greeting)) throw VoiceBriefingFailure("The voice connection was interrupted; please try again")
                            mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.LISTENING)
                            provider!!.events.firstOrNull { event ->
                                if (!valid()) throw VoiceBriefingFailure("The voice briefing has stopped; no call was placed")
                                when (event) {
                                    VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                                        val heardFrames = localAudio!!.interrupt()
                                        if (provider!!.playbackTruncation?.interrupt(heardFrames) == false) {
                                            throw VoiceBriefingFailure("Unable to sync the audio already played; no call was placed")
                                        }
                                    }
                                    is VolcRealtimeEvent.Audio -> localAudio!!.enqueue(event.pcm)
                                    is VolcRealtimeEvent.UserTranscript -> {
                                        authorized = conversation.accept(event)
                                        mutable.value = mutable.value.copy(
                                            ownerTranscript = conversation.ownerTranscript,
                                            ownerPartial = if (event.isFinal) "" else event.text,
                                        )
                                    }
                                    is VolcRealtimeEvent.AssistantTranscript -> {
                                        val full = mutable.value.assistantTranscript + event.delta
                                        if (full.length > 16_384) throw VoiceBriefingFailure("The conversation reached its length limit; please start again")
                                        mutable.value = mutable.value.copy(assistantTranscript = full)
                                    }
                                    is VolcRealtimeEvent.Failed, VolcRealtimeEvent.Closed ->
                                        throw VoiceBriefingFailure("The voice connection ended; no call was placed. Please start again")
                                    // Blank recognition without prior text can be ordinary silence.
                                    // An unfinished partial may contain a constraint: never drop it
                                    // and then authorize from a later, unrelated complete final.
                                    VolcRealtimeEvent.UserTranscriptionEmpty -> {
                                        if (mutable.value.ownerPartial.isNotBlank()) {
                                            throw VoiceBriefingFailure("That sentence was not recognized completely; no call was placed. Please give the full briefing again")
                                        }
                                    }
                                    VolcRealtimeEvent.UserTranscriptionFailed ->
                                        throw VoiceBriefingFailure("This sentence could not be recognized completely; no call was placed. Please give the briefing again")
                                    else -> Unit
                                }
                                authorized != null
                            }
                            if (authorized == null) throw VoiceBriefingFailure("The voice connection ended; no call was placed")
                        } finally {
                            watcher.cancel()
                            localAudio?.stopImmediately()
                            // Audio workers belong to this coroutineScope and are canceled before it exits.
                            localAudio?.close()
                        }
                    }
                }
            } catch (error: CancellationException) {
                failure = if (token == generation) audioFailure.get() ?: "The voice briefing reached its time limit or was cancelled; no call was placed" else null
            } catch (error: VoiceBriefingFailure) {
                failure = error.safeMessage
            } catch (_: Exception) {
                failure = "The voice briefing was interrupted; no call was placed. Check permissions or the connection and try again"
            } finally {
                conversation.cancel()
                if (token == generation) mutable.value = mutable.value.copy(
                    activity = VoiceBriefingActivity.CLOSING,
                    // Publish the actual stop reason while shutdown is pending. Provider cleanup
                    // may take several seconds and must not look like successful authorization.
                    message = audioFailure.get() ?: failure,
                )
                withContext(NonCancellable) {
                    // No owner instruction can escape until both devices and the provider have ended.
                    val audioClosed = runCatching { localAudio?.stopImmediately(); localAudio?.close() }.isSuccess
                    // Provider shutdown is attempted even if device cleanup reports a failure.
                    val providerClosed = runCatching { withTimeout(4_500) { provider?.closeAndAwait() } }.isSuccess
                    cleanlyClosed = audioClosed && providerClosed
                }
                if (audio === localAudio) audio = null
            }
            if (token != generation || closed) return@launch
            val brief = authorized
            if (brief != null && cleanlyClosed && failure == null && currentCoroutineContext().isActive && valid()) {
                // Invalidate callbacks/events from this session before entering the ordinary dial path.
                generation++
                mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.AUTHORIZED, ownerPartial = "")
                onAuthorized(brief.validatedSnapshot())
            } else {
                mutable.value = mutable.value.copy(activity = VoiceBriefingActivity.IDLE,
                    message = audioFailure.get() ?: failure ?: "The voice briefing has stopped; no call was placed")
            }
        }
        operation = job
        // Cancellation can win before a LAZY job ever enters its body. In that case its
        // finally block cannot release the operation, so completion must own that fallback.
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                if (job.isCancelled) mutable.value = mutable.value.copy(
                    activity = VoiceBriefingActivity.IDLE,
                    message = mutable.value.message ?: "The voice briefing was cancelled; no call was placed",
                )
            }
        }
        job.start()
        return true
    }

    fun cancel(message: String? = null) {
        generation++
        audio?.stopImmediately()
        operation?.cancel()
        mutable.value = mutable.value.copy(activity = if (operation != null) VoiceBriefingActivity.CLOSING else VoiceBriefingActivity.IDLE,
            ownerPartial = "", message = message)
        // A new session cannot start until cleanup (or pre-start completion) releases it.
    }

    override fun close() { closed = true; cancel() }

    private fun safeGate(caller: () -> Boolean): Boolean = runCatching { localGate() && caller() }.getOrDefault(false)
}

private class ProviderConnection(private val session: VolcRealtimeSession) : VoiceBriefingConnection {
    override val playbackTruncation get() = session.playbackTruncation
    override val events get() = session.events
    override fun send(bytes: ByteArray) = session.sendPcm16(bytes)
    override fun greet(text: String) = session.sendGreeting(text)
    override suspend fun closeAndAwait() {
        session.close()
        session.awaitClosed()
    }
}
