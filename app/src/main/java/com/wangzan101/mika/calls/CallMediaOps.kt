package com.wangzan101.mika.calls

import android.media.AudioManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.calls.CallRuntime.CallProblem
import com.wangzan101.mika.calls.CallRuntime.MediaStage
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.MAX_MEDIA_DIAGNOSTICS
import com.wangzan101.mika.calls.CallRuntime.Companion.MUTE_ACK_MS
import com.wangzan101.mika.calls.CallRuntime.Companion.FAREWELL_ENDING

// Call runtime: call audio bridge, mute lease and recovery
internal suspend fun CallRuntime.probeAudio(): Boolean {
    val helper = shell ?: return false
    val mode = settings.state.value.audioMode
    val epoch = ++probeEpoch
    val probe = withContext(Dispatchers.IO) { runCatching { helper.probeCallAudio(mode.wire) }.getOrNull() }
    if (helper !== shell || mode != settings.state.value.audioMode) return false
    val ready = probe?.getBoolean("ready") == true
    if (epoch == probeEpoch) {
        audioReady = ready
        audioDetail = if (ready) app.getString(R.string.calls_audio_probe_ready)
            else probe?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() }
                ?: app.getString(R.string.calls_audio_probe_unavailable)
        publish()
    }
    return ready
}

/** [pushOwnerTask]: in realtime calls, push a cue when the owning task's state changes (standard calls carry it in each turn's trailing note, so no push needed); [canCue]: see [PhoneDesk.canCue]. */
internal suspend fun CallRuntime.runAudio(w: Work, helper: IMikaShell, session: CallRealtimeSession, pushOwnerTask: Boolean = false,
                                         canCue: Boolean = true) {
    val languageTag = callLanguageTag()
    val greeting = CallPolicy.spokenGreeting(w.config.greeting, hasTask = w.task != null, languageTag = languageTag)
    val notification = app.getString(
        if (greeting.isBlank()) R.string.calls_notification_connected_waiting else R.string.calls_notification_connected,
    )
    if (!notifications.show(w.id, app.getString(R.string.calls_notification_connecting_audio), ringing = false)) {
        throw CallProblem("Call notifications are unavailable; control was handed back to the system")
    }
    // Capture is never started while ringing, dialing, held, or in a different call.
    val target = w.track ?: throw CallProblem("The call has ended")
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper) {
        throw CallProblem("The call state changed; the AI did not attach to the audio")
    }
    w.mediaStage = MediaStage.MUTE_ACQUIRE
    val mute = acquireTelecomMute(w, target, helper)
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
        !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
        throw CallProblem("The call state or mute changed; the AI did not attach to the audio")
    }
    w.mediaStage = MediaStage.SHELL_OPEN
    w.audioHelper = helper
    val audioAccount = target.call.details?.accountHandle
    val channel = awaitCallAudioOpen(
        isCurrent = {
            sameActiveMuteTarget(w, target) && !target.suppressed && shell === helper &&
                target.call.details?.accountHandle == audioAccount &&
                (w.direction != CallDirection.OUTGOING || (audioAccount != null && audioAccount == w.sim?.handle)) &&
                settings.state.value.enabled && w.config.audioMode == settings.state.value.audioMode &&
                mute.isConfirmed(target.service.telecomMuted, microphoneMuted())
        },
        nowMs = SystemClock::elapsedRealtime,
    ) {
        // A successful attempt transfers every descriptor to Work before cancellation can run cleanup.
        // Only the helper's resource-free readiness rejection can request another attempt.
        withContext(NonCancellable + Dispatchers.IO) {
            val result = helper.openCallAudio(w.config.audioMode.wire, w.audioOwner)
            if (result?.getBoolean("ready") != true) {
                if (result?.getString("errorCode") == "cellular_audio_not_ready") return@withContext null
                throw CallProblem(result?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "Two-way call audio was not established on this device")
            }
            val down = result.fd("downlink")
            val up = result.fd("uplink")
            if (down == null || up == null ||
                result.getInt("captureRate") != session.inputSampleRate ||
                result.getInt("playRate") != session.outputSampleRate) {
                runCatching { down?.close() }
                runCatching { up?.close() }
                throw CallProblem("The built-in helper's call audio format is incompatible")
            }
            val capture = ParcelFileDescriptor.AutoCloseInputStream(down)
            val playback = ParcelFileDescriptor.AutoCloseOutputStream(up)
            w.capture = capture
            w.playback = playback
            CallPcmBridge(capture, playback) {
                withContext(Dispatchers.IO) { runCatching { helper.flushCallAudio(w.audioOwner) }.getOrDefault(false) }
            }
        }
    }
    if (!sameActiveMuteTarget(w, target) || target.suppressed || shell !== helper ||
        !settings.state.value.enabled || !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) {
        throw CallProblem("The call state or mute changed; no speech was sent")
    }
    // 0 = no time limit: an over-long timeout is passed as "never fires" (the library special-cases waiting on Long.MAX_VALUE and schedules no alarm)
    val remaining = w.config.maxCallSeconds.takeIf { it > 0 }
        ?.let { (it * 1_000L - (SystemClock.elapsedRealtime() - w.activeElapsed)).coerceAtLeast(1) }
    w.mediaStage = MediaStage.PCM_BRIDGE
    // Phone front desk: can only ask the owner; the owner's replies and task state are given to the model only as cues to relay in its own words, never read verbatim to the other party;
    // replies not yet handed to the model when the call ends are recorded in the call history (callbacks may come from another thread, so write back on the main thread)
    val desk = PhoneDesk(
        ownerTask = { w.ownerTask },
        ask = OwnerAskNotifier(app, app.ownerQuestions, w.ownerConversationId),
        cue = { text, ended -> session.sendCue(text, ended) },
        unrelayed = { reply -> scope.launch { appendTranscript(w.transcript, CallTranscriptLine.OWNER_UNRELAYED, reply) } },
        context = { text -> session.sendContext(text) },
        canCue = canCue,
        languageTag = languageTag,
    )
    try {
        withTimeoutOrNull(remaining ?: Long.MAX_VALUE) {
            coroutineScope {
                val ownerTaskPush = if (pushOwnerTask) launch { desk.pushOwnerTaskChanges() } else null
                // Let the AI say a goodbye shortly before the limit (both providers play it verbatim); if the goodbye leads to a natural hang-up, wrap up early here
                val closingJob = CallPolicy.closingDelayMs(remaining)?.let { closingAt ->
                    launch {
                        delay(closingAt)
                        if (work === w && w.stopReason.value == null && w.aiActive &&
                            !target.suppressed && sameActiveMuteTarget(w, target)) {
                            runCatching { session.sendGreeting(CallPolicy.closingLine(languageTag)) }
                        }
                    }
                }
                val watchdog = launch {
                    var lastBytes = -1L
                    var stalled = 0
                    while (true) {
                        delay(if (w.aiActive) 1_500 else 350)
                        // Locks expire naturally (especially the budget taken for a length tier when the duration is unlimited), so re-acquire on expiry; it must not be released while the call is still going
                        if (w.wakeLock?.isHeld == false) acquireCallWakeLock(w)
                        val stats = withContext(Dispatchers.IO) {
                            runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull()
                        }
                        if (stats?.getBoolean("active") != true) {
                            throw CallMediaFailure(shellAudioStopDiagnostic(stats?.getString("reason"))
                                ?: "The device stopped two-way audio; the call is handled by the system")
                        }
                        val bytes = stats.getLong("downBytes")
                        if (!w.aiActive) {
                            if (callAudioHasStarted(greeting, bytes, stats.getLong("upBytes"))) {
                                if (!notifications.show(w.id, notification, ringing = false)) {
                                    throw CallProblem("The call notification is unavailable; control returned to the system")
                                }
                                w.aiActive = true
                                detail = notification
                                if (w.direction == CallDirection.INCOMING) {
                                    w.result.complete(ok(if (greeting.isBlank()) "Android answered; the AI is waiting for the other party to speak"
                                        else "Android answered; the two-way audio bridge is now transferring"))
                                }
                                publish()
                            } else if (SystemClock.elapsedRealtime() - w.activeElapsed >= 7_000) {
                                throw CallMediaFailure("Two-way audio was not established in time; the call is handled by the system")
                            }
                        } else {
                            stalled = if (lastBytes == bytes) stalled + 1 else 0
                            if (stalled >= 2) throw CallMediaFailure("Carrier audio input made no progress; the call is handled by the system")
                        }
                        lastBytes = bytes
                    }
                }
                try {
                    channel.run(
                        session, greeting, counters = w.mediaCounters,
                        onTranscript = { role, text -> appendTranscript(w.transcript, role, text) },
                        onStage = { stage ->
                            if (work === w && w.voiceStage != stage) {
                                w.voiceStage = stage
                                publish()
                            }
                        },
                        sendDtmf = { digits -> onMain { sendCallDtmf(target, digits, owner = w) } },
                        desk = desk::execute,
                        onFinalTranscript = { role, text ->
                            if (work === w && w.stopReason.value == null && target.phase == CallPhase.ACTIVE &&
                                liveCalls().singleOrNull() === target && liveTranscriptCallId == target.id &&
                                appendTranscript(liveTranscript, role, text)) {
                                publish()
                            }
                        },
                        playedOutputFrames = {
                            withContext(Dispatchers.IO) {
                                val stats = helper.callAudioStats(w.audioOwner)
                                if (stats?.getBoolean("upPlaybackHeadFresh") == true && stats.getBoolean("upPlaybackRouteReady")) {
                                    (stats.getLong("upPlaybackHeadFrames") - stats.getLong("upPlaybackPrimeFrames"))
                                        .coerceIn(0L, stats.getLong("upPlaybackEpochDataBytes") / 2L)
                                } else 0L
                            }
                        },
                        awaitFarewellDrain = { fence ->
                            if (!sameActiveMuteTarget(w, target) || target.suppressed || work !== w) false
                            else awaitFarewellPlayback(helper, w, fence)
                        },
                        commitFarewell = {
                            // No suspension: speech can still cancel the waiter up to this point.
                            if (target.suppressed || w.stopReason.value != null || shell !== helper ||
                                !settings.state.value.enabled || !sameActiveMuteTarget(w, target) ||
                                !mute.isConfirmed(target.service.telecomMuted, microphoneMuted())) false
                            else if (runCatching { target.call.disconnect() }.isSuccess) w.stopReason.record(FAREWELL_ENDING)
                            else false
                        },
                    )
                } finally {
                    watchdog.cancel()
                    closingJob?.cancel()
                    ownerTaskPush?.cancel()
                }
            }
        }
    } finally {
        // Call ended: withdraw pending questions and notifications; late replies are no longer relayed
        desk.close()
    }
    // A natural ending already issued the owner-bound disconnect with media still running.
    if (w.stopReason.value == FAREWELL_ENDING) return
    // No time limit: a normal return from the bridge can only be a natural ending (already handled above); reaching here is the fallback for anything other than an exception, so do not falsely report reaching the time limit
    if (w.config.maxCallSeconds <= 0) return
    val ending = "The AI call time limit was reached"
    if (!w.stopReason.record(ending)) return
    w.mediaStage = MediaStage.RELEASE
    w.session?.close()
    val restored = restoreAudio(w)
    w.aiActive = false
    w.bridging = false
    if (!restored || work !== w || target.suppressed || !sameActiveMuteTarget(w, target)) return
    // The hang-up may not be confirmed: try several times; do not hand a live call back to the user just because the first attempt was unconfirmed
    var ended = false
    repeat(CallPolicy.HANGUP_ATTEMPTS) {
        if (ended || work !== w || target.suppressed || !sameActiveMuteTarget(w, target)) return@repeat
        ended = runCatching { target.call.disconnect() }.isSuccess &&
            (waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
    }
    w.stopReason.refine(ending, if (ended) "The time limit was reached; Android hung up"
        else "The time limit was reached; Android did not confirm the hang-up, so it was handed back to the system")
}

internal suspend fun CallRuntime.awaitFarewellPlayback(helper: IMikaShell, w: Work, fence: CallFarewellFence): Boolean {
    suspend fun reached(): Boolean {
        val stats = withContext(Dispatchers.IO) { runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() }
        return stats?.getBoolean("active") == true && fence.reached(
            playedEpoch = stats.getLong("upPlaybackEpoch", -1L),
            acceptedBytes = stats.getLong("upPlaybackEpochDataBytes", -1L),
            primeFrames = stats.getLong("upPlaybackPrimeFrames", -1L),
            playedFrames = stats.getLong("upPlaybackHeadFrames", -1L),
            fresh = stats.getBoolean("upPlaybackHeadFresh"),
            routed = stats.getBoolean("upPlaybackRouteReady"),
        )
    }
    return withTimeoutOrNull(10_000L) {
        while (!reached()) delay(100L)
        // Keep the collector live through the quiet tail; any new speech cancels this waiter.
        delay(if (fence.explicit) 1_000L else 3_500L)
        reached()
    } == true
}

internal fun CallRuntime.appendTranscript(lines: MutableList<CallTranscriptLine>, role: String, text: String): Boolean {
    val line = CallTranscriptLine(role, text.trim().take(CallHistory.MAX_LINE_LENGTH))
    if (line.text.isEmpty() || lines.lastOrNull() == line) return false
    if (lines.size >= CallHistory.MAX_LINES) lines.removeAt(0)
    lines.add(line)
    return true
}

internal suspend fun CallRuntime.acquireTelecomMute(w: Work, target: Tracked, helper: IMikaShell): TelecomMuteLease {
    val service = target.service
    val lease = awaitTelecomMuteBaseline(
        isCurrent = { sameActiveMuteTarget(w, target) && !target.suppressed && settings.state.value.enabled &&
            shell === helper && runCatching { helper.asBinder().isBinderAlive }.getOrDefault(false) &&
            settings.state.value.credentialsConfigured && w.config.audioMode == settings.state.value.audioMode },
        nowMs = SystemClock::elapsedRealtime,
        sample = { TelecomMuteBaseline(
            cellularModeReady = runCatching { app.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL }.getOrDefault(false),
            telecomMuted = service.telecomMuted,
            hardwareMuted = microphoneMuted(),
            observationRevision = service.muteObservationRevision,
        ) },
    )
    w.muteLease = lease
    if (lease.needsMuteRequest) {
        // Mark before the Binder request: an in-flight mute still needs a compensating
        // unmute if the acknowledgement times out while this exact call remains active.
        lease.markMuteRequested()
        if (!service.requestMute(target.call, true)) {
            throw CallProblem("Android did not accept the mute request for this call")
        }
    }
    val confirmed = awaitMute {
        sameActiveMuteTarget(w, target) && !target.suppressed &&
            lease.isConfirmed(service.telecomMuted, microphoneMuted())
    }
    if (!confirmed) throw CallProblem("Android did not confirm the call microphone is muted; the AI did not attach to the audio")
    return lease
}

internal fun CallRuntime.sameActiveMuteTarget(w: Work, target: Tracked): Boolean =
    target.phase == CallPhase.ACTIVE && sameMuteTarget(w, target)

internal fun CallRuntime.sameMuteTarget(w: Work, target: Tracked): Boolean =
    work === w && w.track === target && isCurrentCall(target, target.id) &&
        (target.phase == CallPhase.ACTIVE || target.phase == CallPhase.HOLDING) && target.service.tracks(target.call)

internal fun CallRuntime.microphoneMuted(): Boolean? = runCatching {
    app.getSystemService(AudioManager::class.java)?.isMicrophoneMute
}.getOrNull()

internal suspend fun CallRuntime.awaitMute(ready: () -> Boolean): Boolean = withTimeoutOrNull(MUTE_ACK_MS) {
    while (!ready()) delay(40)
    true
} == true

internal suspend fun CallRuntime.shellAudioFailure(w: Work): String? = withContext(Dispatchers.IO) {
    val helper = w.audioHelper ?: return@withContext null
    val stats = runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull() ?: return@withContext null
    if (stats.getBoolean("active")) null else shellAudioStopDiagnostic(stats.getString("reason"))
}

internal suspend fun CallRuntime.restoreAudio(w: Work): Boolean {
    // Release and flush hardware media before making the handset microphone available again.
    val shellRestored = restoreShellAudio(w)
    val muteRestored = restoreTelecomMute(w)
    return shellRestored && muteRestored
}

internal suspend fun CallRuntime.restoreTelecomMute(w: Work): Boolean {
    val lease = w.muteLease ?: return true
    val target = w.track
    fun sameCall() = target != null && sameMuteTarget(w, target)
    val plan = lease.beginRelease(sameCall())
    if (plan == TelecomMuteLease.Release.REQUEST_UNMUTE && target != null) {
        // No suspension since sameCall() above. Do not send a global mute command on a
        // different call, even if this service is still bound after the original call ended.
        target.service.requestMute(target.call, false)
    }
    val restored = awaitMute {
        plan.confirmed(sameCall(), target?.service?.telecomMuted, microphoneMuted())
    }
    if (restored) w.muteLease = null
    return restored
}

internal suspend fun CallRuntime.restoreShellAudio(w: Work): Boolean = withContext(Dispatchers.IO) {
    val helper = w.audioHelper
    runCatching { w.capture?.close() }.onFailure { debugMediaException(w, it) }
    runCatching { w.playback?.close() }.onFailure { debugMediaException(w, it) }
    w.capture = null
    w.playback = null
    if (helper == null) return@withContext true
    runCatching {
        helper.stopCallAudio(w.audioOwner)
        val stats = helper.callAudioStats(w.audioOwner) ?: return@runCatching false
        w.mediaCounters.outputShellAccepted(
            bytes = stats.getLong("upBytes", -1L),
            zeroCount = stats.getLong("upWriteZeroCount", -1L),
            zeroMaxMs = stats.getLong("upWriteZeroMaxMs", -1L),
            lastWriteAgeMs = stats.getLong("upLastWriteAgeMs", -1L),
            playbackHeadFrames = stats.getLong("upPlaybackHeadFrames", -1L),
            flushMaxMs = stats.getLong("upFlushMaxMs", -1L),
            captureSilencedMs = if (stats.getBoolean("clientSilencedKnown")) stats.getLong("clientSilencedMaxMs", -1L) else -1L,
            playbackRebuilds = stats.getLong("upPlaybackRebuilds", -1L),
            rebuildDroppedBytes = stats.getLong("upRebuildDroppedBytes", -1L),
        )
        !stats.getBoolean("active") && stats.getBoolean("microphoneRestored")
    }.onFailure { debugMediaException(w, it) }.getOrDefault(false).also {
        if (it) w.audioHelper = null
    }
}

internal fun CallRuntime.debugMediaException(w: Work, error: Throwable) {
    if (!BuildConfig.DEBUG || w.mediaDiagnosticCount >= MAX_MEDIA_DIAGNOSTICS) return
    w.mediaDiagnosticCount++
    val type = error.javaClass.simpleName.take(64).ifBlank { "Exception" }
    Log.d("MikaCallMedia", "stage=${w.mediaStage.name} exception=$type")
}

/** Never spend the outgoing ringing interval with an unattended cloud session. */
internal fun CallRuntime.validateSessionTarget(w: Work, helper: IMikaShell) {
    prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
    if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
        throw CallProblem("Call audio settings or the built-in helper changed; the AI did not attach")
    }
    if (w.direction == CallDirection.INCOMING) {
        validateTarget(w)
    } else {
        requireOutgoingForegroundService(w)
        val target = w.track ?: throw CallProblem("The outgoing call ended; the AI did not attach")
        val account = target.call.details?.accountHandle
        if (!sameActiveMuteTarget(w, target) || target.suppressed || !carrierCall(target.call) ||
            target.direction != CallDirection.OUTGOING || account == null || account != w.sim?.handle) {
            throw CallProblem("The outgoing call changed; the AI did not attach")
        }
    }
}

internal fun CallRuntime.validateTarget(w: Work) {
    if (w.direction == CallDirection.INCOMING) {
        val t = w.track
        if (t == null || !isCurrentCall(t, w.expectedCallId) || t.phase != CallPhase.RINGING ||
            t.direction != CallDirection.INCOMING || t.suppressed || !t.service.tracks(t.call) || !carrierCall(t.call)) {
            throw CallProblem("The incoming call changed; the AI did not answer")
        }
        if (w.automatic) {
            if (!canAutomaticallyAnswerHere()) throw CallProblem("Background standby is unavailable; the system phone app is handling this incoming call")
            val config = settings.state.value
            val initial = t.initialRingPolicy
            if (autoPauseWriting || initial == null ||
                !CallPolicy.callerAllowed(initial, t.number, t.ringObservedWallMs, t.type, confirmedStranger(t)) ||
                !CallPolicy.callerAllowed(config, t.number, type = t.type, isStranger = confirmedStranger(t)) || config.audioMode != w.config.audioMode ||
                SystemClock.elapsedRealtime() - t.ringObservedAt > CallPolicy.AUTO_ANSWER_WINDOW_MS) {
                throw CallProblem("The incoming call timed out or no longer matches the auto-answer rules")
            }
        }
    } else {
        if (liveCalls().isNotEmpty()) throw CallProblem("There is already a call; no call was placed")
        validateLockedDial(w)
    }
}
