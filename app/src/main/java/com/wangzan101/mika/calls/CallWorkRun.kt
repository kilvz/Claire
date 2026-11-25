package com.wangzan101.mika.calls

import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.cascade.CascadeCallProvider
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.calls.CallRuntime.CallProblem
import com.wangzan101.mika.calls.CallRuntime.MediaStage
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.MAX_MEDIA_DIAGNOSTICS
import com.wangzan101.mika.calls.CallRuntime.Companion.PHONE_DTMF_PROMPT
import com.wangzan101.mika.calls.CallRuntime.Companion.FAREWELL_ENDING
import com.wangzan101.mika.calls.CallRuntime.Companion.TOKEN

// Call runtime: execution of a single AI call, wake locks, and cancellation
internal fun CallRuntime.startWork(attempt: Work) {
    check(work == null)
    work = attempt
    detail = app.getString(
        if (attempt.automatic) R.string.calls_answer_pending else R.string.calls_notification_preparing,
    )
    if (attempt.automatic) {
        attempt.autoDelayEndsAt = SystemClock.elapsedRealtime() + attempt.config.autoAnswerDelaySeconds * 1_000L
    }
    attempt.job = scope.launch(start = CoroutineStart.LAZY) { runWork(attempt) }
    publish()
    attempt.job?.start()
}

internal suspend fun CallRuntime.waitAutoAnswerDelay(w: Work) {
    while (w.automatic) {
        val remaining = (w.autoDelayEndsAt ?: break) - SystemClock.elapsedRealtime()
        if (remaining <= 0L) break
        withTimeoutOrNull(minOf(1_000L, remaining)) { w.skipAutoDelay.await() }
        publish()
    }
    w.autoDelayEndsAt = null
    detail = app.getString(R.string.calls_notification_answer_preparing)
    publish()
}

/** A user-confirmed outgoing attempt owns a temporary lease, independent of auto-answer. */
internal suspend fun CallRuntime.ensureOutgoingForegroundService(w: Work) {
    if (standbyService == null) {
        if (!requestCallForegroundService(incoming = false)) {
            throw CallProblem(standbyStartError ?: "The outgoing-call background phone service did not start; no call was placed")
        }
        if (!awaitCallForegroundReady(
                isStarting = { standbyStarting },
                isReady = { work === w && standbyService?.leaseAvailable == true },
            )) {
            throw CallProblem(standbyStartError ?: "Android did not confirm the outgoing-call background phone service started; no call was placed")
        }
    }
    requireOutgoingForegroundService(w)
}

internal fun CallRuntime.requireOutgoingForegroundService(w: Work) {
    val leaseReady = standbyService?.leaseAvailable == true && CallStandbyService.notificationsAvailable(app)
    if (work !== w || !leaseReady || !hasOutgoingForegroundWork()) {
        throw CallProblem(standbyStartError ?: "The outgoing-call background phone service is unavailable; the AI did not continue")
    }
}

internal suspend fun CallRuntime.runWork(w: Work) {
    try {
        if (!notifications.show(w.id, detail.orEmpty(), ringing = w.direction == CallDirection.INCOMING)) {
            throw CallProblem("AI call notifications must be enabled so you can cancel or take over at any time")
        }
        if (!interactiveForeground()) acquireCallWakeLock(w, 20_000L)
        if (w.direction == CallDirection.OUTGOING) ensureOutgoingForegroundService(w)
        if (w.automatic) waitAutoAnswerDelay(w)
        prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
        validateTarget(w)
        val helper = shell ?: throw CallProblem("Claire's built-in helper is not connected")
        val migrated = realtimeController.migrated
        val standard = standardCalls
        // Standard call: the owning task's state is appended as a trailing note each turn for the phone front desk, keeping the system prompt byte-for-byte unchanged
        val ownerBackground = w.ownerTask?.let(PhoneDesk::background)
        val provider = if (standard) CascadeCallProvider(cascade, CallUse.Phone, background = ownerBackground) else if (migrated) {
            try {
                realtimeController.callProvider()
            } catch (e: VoiceModelError) {
                throw CallProblem("The realtime voice configuration is unavailable; check the voice model")
            }
        } else ProfileRealtimeProvider()
        val profile = if (migrated) {
            activeVoiceProfile()
        } else {
            voiceProfiles.get(w.config.activeVoiceProfileId)
                ?: throw CallProblem("The realtime voice configuration is unavailable; select it again")
        }
        val credentials = if (standard) VolcRealtimeCredentials() else if (migrated) {
            try {
                realtimeController.credentials()
            } catch (e: VoiceModelError) {
                throw CallProblem("Realtime voice credentials are unavailable; save them again")
            }
        } else {
            withContext(Dispatchers.IO) { voiceProfiles.credentials(profile.id) }
                ?: throw CallProblem("Realtime voice credentials are unavailable; save them again")
        }
        if (!probeAudio()) throw CallProblem(audioDetail ?: "Two-way call audio is not available on this device")
        if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
            throw CallProblem("Call audio settings changed; try again")
        }
        validateTarget(w)
        val ownerRules = withContext(Dispatchers.IO) {
            runCatching { instructions.promptSnapshot() }
                .getOrElse { throw CallProblem("Could not read the call instructions; the AI did not operate the phone") }
        }
        validateTarget(w)
        // Existing call prompt first, phone front desk note after; realtime calls put the owning task's state at opening into the prompt, then push updates when it changes
        val systemPrompt = PhoneDesk.systemPrompt(
            CallPolicy.prompt(w.config.systemPrompt, w.direction, ownerRules, task = w.task) + PHONE_DTMF_PROMPT,
            task = w.task.takeIf { w.direction == CallDirection.OUTGOING },
            openingOwnerTask = if (standard) null else PhoneDesk.renderOwnerTask(w.ownerTask?.value),
            canCue = PhoneDesk.canCue(standard, profile.protocol),
        )
        try {
            provider.validate(profile, systemPrompt, enablePhoneDtmf = true)
        } catch (_: IllegalArgumentException) {
            throw CallProblem("The realtime voice configuration or these call instructions are invalid; the AI did not operate the phone. Check the configuration or shorten the instructions")
        } catch (_: VoiceModelError) {
            throw CallProblem("The realtime voice configuration is unavailable; the AI did not operate the phone")
        }
        val session = startCallSession(
            direction = w.direction,
            connect = {
                validateSessionTarget(w, helper)
                w.mediaStage = MediaStage.VOLC_CONNECT
                val connected = withTimeoutOrNull(5_000L) {
                    provider.connect(profile, credentials, systemPrompt, enablePhoneDtmf = true)
                        .also { w.session = it } // Adopt before the timeout/cancellation boundary.
                } ?: throw CallProblem("The realtime voice connection timed out; the AI did not take over")
                if (!connected.setMuted(true)) throw CallProblem("The realtime voice service did not become ready")
                validateSessionTarget(w, helper)
                connected
            },
            awaitCarrier = {
                if (!withContext(Dispatchers.IO) { access.ready() }) {
                    armed = false
                    throw CallProblem("The system call-management permission is unavailable; the AI did not operate the phone")
                }
                prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
                val ringingCall = w.track
                if (w.automatic && ringingCall != null && (ringingCall.initialRingPolicy?.incomingScope == IncomingScope.STRANGERS ||
                        settings.state.value.incomingScope == IncomingScope.STRANGERS)) {
                    val number = ringingCall.number
                    if (number == null || !isUnsavedNumber(number) || ringingCall.number != number) {
                        throw CallProblem(app.getString(R.string.calls_strangers_note))
                    }
                }
                validateTarget(w)
                w.mediaStage = MediaStage.TELECOM_CONNECT
                if (w.direction == CallDirection.INCOMING) {
                    val target = w.track ?: throw CallProblem("The incoming call has ended")
                    w.answerSent = true
                    w.telecomActionIssued = true
                    detail = "Waiting for Android to confirm the answer"
                    if (runCatching { target.call.answer(VideoProfile.STATE_AUDIO_ONLY) }.isFailure) {
                        throw CallProblem("Android could not send the answer request")
                    }
                    publish()
                } else {
                    if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("There is already a call, or the line could not be confirmed idle; no call was placed")
                    val sim = w.sim ?: throw CallProblem("No SIM is available")
                    val number = w.requestedNumber ?: throw CallProblem("No phone number is available")
                    // Recheck the chosen account before placing the call; Telecom must not fall
                    // back to another SIM if the selection disappeared during preparation.
                    readSims()
                    if (w.lockedDialTicket != null && !withContext(Dispatchers.IO) { access.ready() }) {
                        armed = false
                        throw CallProblem("The system call-management permission is unavailable; the locked-screen dial was not performed")
                    }
                    if (sims.none { it.handle == sim.handle }) throw CallProblem("The selected dialing SIM is no longer available; select another")
                    prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
                    if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("There is already a call, or the line could not be confirmed idle; no call was placed")
                    if (isEmergency(number) != false) throw CallProblem("Could not confirm that this number is safe to dial")
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
                        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(TOKEN, w.id) })
                    }
                    validateLockedDial(w)
                    requireOutgoingForegroundService(w)
                    w.placed = true
                    w.telecomActionIssued = true
                    val issued = try {
                        checkNotNull(telecom).placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
                        true
                    } catch (_: SecurityException) {
                        false
                    } catch (_: RuntimeException) {
                        false
                    }
                    lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                        finishLockedDial(it, if (issued) "Waiting for Android to confirm the locked-screen dial" else "Android rejected the locked-screen dial")
                    }
                    if (!issued) {
                        throw CallProblem("Android rejected the call")
                    }
                    if (!waitFor(CallPolicy.DIAL_TIMEOUT_MS) { w.track != null }) {
                        throw CallProblem("No Android call callback arrived for this number; check the system dialer")
                    }
                }
                val limit = if (w.direction == CallDirection.INCOMING) CallPolicy.CONTROL_TIMEOUT_MS else 55_000L
                if (!waitFor(limit) { w.track?.phase == CallPhase.ACTIVE }) {
                    throw CallProblem("Android did not confirm the call connected; the system is handling the call")
                }
                val target = w.track ?: throw CallProblem("The call has ended")
                if (!carrierCall(target.call) || liveCalls().singleOrNull() !== target) {
                    throw CallProblem("The AI cannot safely take over the current call")
                }
                w.activeAt = System.currentTimeMillis()
                w.activeElapsed = SystemClock.elapsedRealtime()
                acquireCallWakeLock(w)
                w.bridging = true
                detail = "Connected; setting up two-way audio"
                publish()
            },
        )
        runAudio(w, helper, session, pushOwnerTask = !standard, canCue = PhoneDesk.canCue(standard, profile.protocol))
    } catch (e: CancellationException) {
        // Coroutine cancellation may carry a secondary descriptor-close exception.
        if (BuildConfig.DEBUG) e.suppressed.take(MAX_MEDIA_DIAGNOSTICS).forEach { debugMediaException(w, it) }
        throw e
    } catch (e: CallProblem) {
        debugMediaException(w, e)
        w.stopReason.record(e.message ?: "AI calling is not ready")
    } catch (e: CallMediaFailure) {
        debugMediaException(w, e)
        if (w.stopReason.value == null) {
            w.stopReason.record(shellAudioFailure(w) ?: e.message ?: "The call audio disconnected")
        }
    } catch (e: VolcRealtimeException) {
        debugMediaException(w, e)
        w.stopReason.record(when (e.kind) {
            VolcRealtimeException.Kind.AUTH -> "The realtime voice service rejected authentication; check the call voice credentials and whether the service is activated"
            VolcRealtimeException.Kind.RATE_LIMITED -> "The realtime voice service reached its quota; the system is handling the call"
            VolcRealtimeException.Kind.TIMEOUT, VolcRealtimeException.Kind.NETWORK -> "The realtime voice connection is unavailable; the system is handling the call"
            VolcRealtimeException.Kind.BACKPRESSURE -> "Realtime audio is congested; the system is handling the call"
            else -> "The realtime voice service could not continue; check the call configuration"
        })
    } catch (e: VoiceModelError) {
        debugMediaException(w, e)
        w.stopReason.record(when (e.category) {
            VoiceErrorCategory.AUTH_FAILED -> "The realtime voice service rejected authentication; check the call voice credentials and whether the service is activated"
            VoiceErrorCategory.RATE_LIMITED -> "The realtime voice service reached its quota; the system is handling the call"
            VoiceErrorCategory.TIMEOUT, VoiceErrorCategory.NETWORK -> "The realtime voice connection is unavailable; the system is handling the call"
            else -> "The realtime voice service could not continue; check the call configuration"
        })
    } catch (e: Exception) {
        debugMediaException(w, e)
        w.stopReason.record("The AI audio connection failed; the system is handling the call")
    } finally {
        withContext(NonCancellable) {
            w.mediaStage = MediaStage.RELEASE
            runCatching { w.session?.close() }.onFailure { debugMediaException(w, it) }
            val restored = try { restoreAudio(w) } finally { releaseCallWakeLock(w) }
            if (w.stopReason.value == FAREWELL_ENDING) {
                val target = w.track
                val ended = target != null && (confirmedEnded(target) ||
                    waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))
                w.stopReason.refine(FAREWELL_ENDING, if (ended) "The call was wrapped up; Android hung up"
                    else "The call was wrapped up; Android did not confirm the hang-up, so it was handed back to the system")
            }
            val reason = w.stopReason.value ?: "The AI exited; the system is handling the call"
            if (BuildConfig.DEBUG) w.mediaCounters.playbackDiagnostic()?.let { Log.d("MikaCallMedia", "playback $it") }
            w.track?.let {
                if (w.telecomActionIssued) {
                    val answeredByAi = w.aiActive && w.direction == CallDirection.INCOMING
                    val summaryRequested = settings.state.value.summaryEnabled && w.transcript.isNotEmpty()
                    val record = CallRecord(w.id, it.number, w.direction, w.activeAt ?: it.createdAt,
                        System.currentTimeMillis(), reason, w.transcript.toList(), task = w.task,
                        audioReport = w.mediaCounters.recordReport(),
                        summaryStatus = if (summaryRequested) SummaryStatus.PENDING else SummaryStatus.NOT_REQUESTED)
                    scope.launch {
                        records.append(record)
                        if (answeredByAi) notifications.completed(w.id, record.number, w.direction,
                            summaryPending = summaryRequested)
                        if (summaryRequested) summarizeCall(w.id, record.transcript)
                    }
                }
            }
            if (work === w) {
                lockedDial?.takeIf { it.ticket === w.lockedDialTicket }?.let {
                    finishLockedDial(it, reason, cancelUnplacedWork = false)
                }
                work = null
                detail = if (restored) reason else "$reason; audio restoration is not yet confirmed"
                if (w.lockedDialTicket != null) lockedDialDetail = detail
                stopBackgroundStandbyIfUnneeded()
                if (w.telecomActionIssued && w.track?.phase == CallPhase.ACTIVE) notifications.handoff()
                else notifications.clear()
                publish()
            }
            w.result.complete(fail(detail ?: reason))
            w.cleaned.complete(restored)
        }
    }
}

/** Only a pending AI attempt or active call can hold a timed partial wake lock. */
internal fun CallRuntime.acquireCallWakeLock(w: Work, timeoutMs: Long = CallStandbyPolicy.activeWakeBudgetMs(w.config.maxCallSeconds)) {
    releaseCallWakeLock(w)
    runCatching {
        val power = app.getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Claire:ActiveCall")
        lock.setReferenceCounted(false)
        w.wakeLock = lock
        lock.acquire(timeoutMs)
    }.onFailure { debugMediaException(w, it) }
}

internal fun CallRuntime.releaseCallWakeLock(w: Work) {
    val lock = w.wakeLock ?: return
    w.wakeLock = null
    runCatching { if (lock.isHeld) lock.release() }.onFailure { debugMediaException(w, it) }
}

internal fun CallRuntime.cancelWork(reason: String): Work? {
    val active = work ?: return null
    active.stopReason.record(reason)
    active.track?.suppressed = true
    active.job?.cancel()
    publish()
    return active
}

internal suspend fun CallRuntime.awaitCleanup(w: Work?): Boolean =
    w == null || withTimeoutOrNull(CallPolicy.CONTROL_TIMEOUT_MS) { w.cleaned.await() } == true

internal suspend fun CallRuntime.awaitResult(w: Work): CallActionResult = try {
    w.result.await()
} catch (e: CancellationException) {
    if (!w.telecomActionIssued && !w.automatic && work === w) cancelWork("The action was cancelled; the system is handling the call")
    throw e
}

internal suspend fun CallRuntime.waitFor(timeout: Long, test: () -> Boolean): Boolean =
    withTimeoutOrNull(timeout) { changes.first { test() }; true } == true
