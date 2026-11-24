package com.wangzan101.mika.calls

import android.os.SystemClock
import android.telecom.Call
import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.launch
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.disconnected

// Call runtime: system call events and auto-answer triggering
internal fun CallRuntime.onCallAdded(service: MikaInCallService, call: Call) {
    unobservedManagedCall = false
    tracked.getOrPut(call) { Tracked(call, service) }
    onCallChanged(service, call)
}

internal fun CallRuntime.onCallChanged(service: MikaInCallService, call: Call) {
    val t = tracked[call]?.takeIf { it.service === service } ?: return
    if (call.state == Call.STATE_DISCONNECTED) t.taskBinding.end()
    if (t.phase == CallPhase.RINGING && t.initialRingPolicy == null) {
        t.initialRingPolicy = settings.state.value
        t.ringObservedAt = SystemClock.elapsedRealtime()
        t.ringUptime = SystemClock.uptimeMillis()
        t.ringObservedWallMs = System.currentTimeMillis()
        if (autoPauseWriting || !canAutomaticallyAnswerHere()) t.autoTried = true
    }
    val calls = liveCalls()
    if (calls.isNotEmpty()) lockedDial?.takeUnless { work?.lockedDialTicket === it.ticket && work?.placed == true }
        ?.let { finishLockedDial(it, "Another call was detected; the locked-screen dial was not performed") }
    if (calls.size > 1) calls.filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
    val active = work
    if (active != null) {
        if (active.track == null && active.placed && matchesOutgoing(active, t)) {
            active.track = t
            t.taskBinding.bind(active.task)
        }
        val target = active.track
        if (target === t && active.direction == CallDirection.OUTGOING &&
            call.state in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE)) {
            if (active.lockedDialTicket != null) lockedDialDetail = "Android confirmed the locked-screen dial"
            active.result.complete(ok("Android confirmed the dial; check the live status to see whether it connects"))
        }
        when {
            calls.size > 1 -> cancelWork("Multiple calls were detected; control was handed back to the system dialer")
            target === t && disconnected(call) -> cancelWork(callEndMessage(t))
            target === t && active.direction == CallDirection.INCOMING && !active.answerSent && t.phase != CallPhase.RINGING ->
                cancelWork(if (t.phase == CallPhase.ACTIVE || t.phase == CallPhase.CONNECTING || t.phase == CallPhase.HOLDING)
                    "The system phone app handled it; the AI did not step in" else "The incoming call stopped ringing; the AI did not answer")
            target === t && active.direction == CallDirection.INCOMING && !active.answerSent &&
                (t.direction != CallDirection.INCOMING || !carrierCall(t.call)) ->
                cancelWork("The incoming call changed; the AI did not answer")
            target === t && active.automatic && !active.answerSent &&
                !CallPolicy.callerAllowed(settings.state.value, t.number, type = t.type, isStranger = confirmedStranger(t)) -> cancelWork("The incoming call no longer matches the auto-answer rules")
            target === t && t.phase != CallPhase.ACTIVE && active.bridging ->
                cancelWork("The call switched; AI audio stopped")
        }
    }
    publish()
    maybeStartAutoAnswer()
}

internal fun CallRuntime.onCallerLabelEvent(service: ScreenAccessibility, event: AccessibilityEvent) {
    if (tracked.isEmpty()) return
    val config = settings.state.value
    if (!config.enabled || !config.autoAnswer || config.incomingScope != IncomingScope.TYPES) return
    when (event.eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> Unit
        else -> return
    }
    val t = liveCalls().singleOrNull() ?: return
    val initial = t.initialRingPolicy ?: return
    if (!initial.enabled || !initial.autoAnswer || initial.incomingScope != IncomingScope.TYPES ||
        t.phase != CallPhase.RINGING || t.direction != CallDirection.INCOMING || t.suppressed ||
        event.eventTime < t.ringUptime || t.number == null || !carrierCall(t.call) ||
        CallPolicy.isBypassed(config, t.number) || CallPolicy.isBypassed(initial, t.number)) return
    val pending = work?.let { it.automatic && it.track === t && !it.answerSent } == true
    if (!pending && (t.autoTried || SystemClock.elapsedRealtime() - t.ringObservedAt > CallPolicy.AUTO_START_WINDOW_MS)) return
    val changed = try {
        if (t.labelService !== service) {
            t.labelService = service
            t.evidence = IncomingTypeEvidence()
        }
        val reader = t.labelReader ?: IncomingCallLabels(app).also { t.labelReader = it }
        reader.read(service, event, t.number, t.evidence)
    } catch (_: RuntimeException) {
        // The dialer can remove its window while accessibility is reading it.
        false
    }
    if (changed) {
        onCallChanged(t.service, t.call)
    }
}

internal fun CallRuntime.maybeStartAutoAnswer() {
    if (work != null || lockedDial != null || autoPauseWriting || !canAutomaticallyAnswerHere()) return
    val t = liveCalls().singleOrNull() ?: return
    val initial = t.initialRingPolicy ?: return
    if (t.phase != CallPhase.RINGING || t.direction != CallDirection.INCOMING || t.suppressed || t.autoTried) return
    val config = settings.state.value
    val needsLookup = initial.incomingScope == IncomingScope.STRANGERS || config.incomingScope == IncomingScope.STRANGERS
    if (!CallPolicy.canStartAutoAnswer(initial, config, t.number,
            SystemClock.elapsedRealtime() - t.ringObservedAt,
            ringObservedWallMs = t.ringObservedWallMs, type = t.type, isStranger = needsLookup)) return
    if (autoAnswerBlocker(config) != null || !t.service.tracks(t.call) || !carrierCall(t.call)) return
    if (needsLookup && !confirmedStranger(t)) {
        val number = t.number ?: return
        if (t.lookupNumber != number && hasContactsAccess()) {
            t.contactLookup?.cancel()
            t.lookupNumber = number
            t.unsavedNumber = null
            t.contactLookup = scope.launch {
                if (isUnsavedNumber(number) && tracked[t.call] === t && t.number == number) {
                    t.unsavedNumber = number
                    maybeStartAutoAnswer()
                }
            }
        }
        return
    }
    t.autoTried = true
    startWork(Work(CallDirection.INCOMING, config, track = t, auto = true))
}

internal fun CallRuntime.onCallRemoved(service: MikaInCallService, call: Call) {
    val removed = tracked[call]?.takeIf { it.service === service } ?: return
    removed.taskBinding.end()
    removed.contactLookup?.cancel()
    tracked.remove(call)
    if (work?.track === removed) cancelWork(
        if (confirmedEnded(removed)) callEndMessage(removed) else "The Android call state was lost; the system is handling the call",
    )
    publish()
}

internal fun CallRuntime.onTelecomMuteChanged(service: MikaInCallService, muted: Boolean) {
    val current = work ?: return
    if (current.track?.service === service && current.muteLease?.observeTelecomMute(muted) == true) {
        cancelWork("The system call mute was released; the AI handed the call back")
    }
}
