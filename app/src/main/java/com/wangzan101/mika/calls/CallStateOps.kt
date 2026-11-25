package com.wangzan101.mika.calls

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract.PhoneLookup
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.R
import com.wangzan101.mika.voice.cascade.CallUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.wangzan101.mika.calls.CallRuntime.CarrierSim
import com.wangzan101.mika.calls.CallRuntime.Tracked
import com.wangzan101.mika.calls.CallRuntime.Work
import com.wangzan101.mika.calls.CallRuntime.Companion.TOKEN

// Call runtime: state publishing, preconditions, and call checks
internal fun CallRuntime.publish() {
    dtmf.invalidate()
    val config = settings.state.value
    val enabled = config.enabled
    val calls = if (enabled) liveCalls() else emptyList()
    val current = calls.firstOrNull()
    val soleCall = calls.singleOrNull()
    val soleCallId = soleCall?.id
    if (soleCallId != liveTranscriptCallId) {
        liveTranscriptCallId = soleCallId
        liveTranscript.clear()
    }
    val active = work.takeIf { enabled }
    val notificationReady = enabled && notifications.ready()
    val pending = current != null && active != null && isPendingAutoAnswer(active, current) && calls.size == 1
    val countdown = active?.autoDelayEndsAt?.takeIf { pending }
        ?.let { it - SystemClock.elapsedRealtime() }?.takeIf { it > 0L }?.let { ((it + 999) / 1_000).toInt() }
    val pauseSeconds = if (enabled && config.autoAnswer) {
        CallPolicy.autoAnswerPauseRemainingMs(config, System.currentTimeMillis())
            .takeIf { it > 0L }?.let { 1L + (it - 1L) / 1000L }
    } else null
    val standbyNotify = enabled && config.autoAnswer && CallStandbyService.notificationsAvailable(app)
    val baseBlocker = if (enabled && config.autoAnswer) autoAnswerBlocker(config, notificationReady) else null
    val standbyBlocked = if (enabled && config.autoAnswer) when {
        !userUnlocked() -> "After a restart, the phone owner must unlock the phone first"
        !standbyNotify -> "Allow background standby notifications"
        baseBlocker != null -> baseBlocker
        pauseSeconds != null -> "Auto-answer is paused"
        standbyStarting && standbyIncomingRequested -> null
        !isIncomingStandbyRunning() -> standbyStartError ?: "Start background standby on the Phone screen"
        else -> null
    } else null
    val blocker = if (enabled && config.autoAnswer) baseBlocker ?: if (!canAutomaticallyAnswerHere()) {
        standbyBlocked ?: "Background standby is starting"
    } else null else null
    val autoStage = when {
        countdown != null -> CallAutoAnswerStage.COUNTDOWN
        pending -> CallAutoAnswerStage.PREPARING
        active?.automatic == true && active.answerSent && active.stopReason.value == null && !active.aiActive ->
            CallAutoAnswerStage.ANSWERING
        !enabled || !config.autoAnswer -> CallAutoAnswerStage.OFF
        pauseSeconds != null -> CallAutoAnswerStage.PAUSED
        blocker != null -> CallAutoAnswerStage.BLOCKED
        calls.isNotEmpty() || active != null || unobservedManagedCall -> CallAutoAnswerStage.BUSY
        else -> CallAutoAnswerStage.STANDBY
    }
    val profile = activeVoiceProfile()
    val serviceReady = profile.credentialsConfigured && profile.connectionTested
    val deviceReady = enabled && armed && shell != null && audioReady
    val manualReady = deviceReady && serviceReady && prerequisite(ignoreWork = true, notificationReady = notificationReady) == null
    val dialReady = manualReady && sims.isNotEmpty()
    val autoAnswerReady = manualReady && config.audioVerified && CallStandbyService.notificationsAvailable(app) &&
        config.hasIncomingSelection && (config.incomingScope != IncomingScope.STRANGERS || hasContactsAccess()) &&
        (config.incomingScope != IncomingScope.TYPES || ScreenAccessibility.instance.value != null)
    mutable.value = CallRuntimeState(
        prepared = enabled && armed && !configuring,
        phase = current?.phase ?: when {
            active?.direction == CallDirection.OUTGOING -> CallPhase.DIALING
            enabled && unobservedManagedCall -> CallPhase.UNKNOWN
            else -> CallPhase.IDLE
        },
        direction = current?.direction ?: active?.direction,
        callId = calls.singleOrNull()?.id,
        number = current?.number ?: active?.requestedNumber,
        aiActive = active?.aiActive == true,
        voiceStage = active?.takeIf { it.aiActive }?.voiceStage,
        detail = if (enabled && unobservedManagedCall && current == null) "Android is on a call, but Claire has not received the callback for it" else detail,
        pendingAutoAnswer = pending,
        autoAnswerStage = autoStage,
        autoAnswerRemainingSeconds = countdown,
        autoAnswerPauseRemainingSeconds = pauseSeconds,
        autoAnswerBlockedReason = blocker.takeIf { autoStage == CallAutoAnswerStage.BLOCKED },
        liveTranscript = liveTranscript.toList(),
        activeTask = soleCall?.taskBinding?.visibleFor(soleCall.call,
            soleCall.service.tracks(soleCall.call), carrierCall(soleCall.call)),
        historyWriteFailed = records.writeFailed.value,
        simOptions = sims.map { it.info },
        shellConnected = shell != null,
        notificationsAvailable = notificationReady,
        audioReady = enabled && audioReady,
        serviceReady = serviceReady,
        deviceReady = deviceReady,
        manualReady = manualReady,
        dialReady = dialReady,
        autoAnswerReady = autoAnswerReady,
        audioDetail = audioDetail,
        canAnswer = enabled && current?.phase == CallPhase.RINGING && current.direction == CallDirection.INCOMING &&
            !current.suppressed && calls.size == 1 && (active == null || active.track === current && !active.answerSent && active.stopReason.value == null) &&
            prerequisite(ignoreWork = true, notificationReady = notificationReady) == null && carrierCall(current.call),
        canHangUp = enabled && armed && current != null && current.phase != CallPhase.DISCONNECTING &&
            calls.size == 1 && carrierCall(current.call),
        canTakeOver = active != null && active.stopReason.value == null,
        canSendDtmf = enabled && current != null && current.phase == CallPhase.ACTIVE &&
            calls.size == 1 && current.service.tracks(current.call) && carrierCall(current.call),
        dtmfBusy = dtmf.busy,
        backgroundStandbyRunning = isIncomingStandbyRunning(),
        backgroundStandbyStarting = standbyStarting && standbyIncomingRequested,
        backgroundStandbyBlockedReason = standbyBlocked,
        lockedDialRequestId = lockedDial?.ticket?.id,
        lockedDialRemainingSeconds = lockedDial?.let(::remainingSeconds),
        lockedDialPreparing = lockedDial?.claimed == true,
        lockedDialDetail = lockedDialDetail,
    )
    changes.value++
}

internal fun CallRuntime.report(message: String): CallActionResult {
    detail = message
    publish()
    return fail(message)
}

internal fun CallRuntime.autoAnswerBlocker(config: CallConfig, notificationReady: Boolean? = null): String? = when {
    !config.audioVerified -> "Confirm two-way call audio for the current mode first"
    config.incomingScope == IncomingScope.STRANGERS && !hasContactsAccess() -> app.getString(R.string.calls_strangers_note)
    !config.hasIncomingSelection -> if (config.incomingScope == IncomingScope.TYPES)
        app.getString(R.string.calls_pick_types) else "The auto-answer list is empty"
    config.incomingScope == IncomingScope.TYPES && ScreenAccessibility.instance.value == null ->
        app.getString(R.string.calls_type_accessibility)
    else -> prerequisite(ignoreWork = true, notificationReady = notificationReady)
        ?: if (!audioReady) audioDetail ?: "Call audio on this device is not ready yet" else null
}

internal fun CallRuntime.prerequisite(
    ignoreWork: Boolean = false,
    notificationReady: Boolean? = null,
): String? = when {
    !settings.state.value.enabled -> "AI calling is off"
    configuring -> "AI calling is being configured"
    !armed || !access.hasPhonePermissions() -> "Prepare AI calling first"
    shell == null -> "Claire's built-in helper is not connected"
    standardCalls && cascade.blocker(CallUse.Phone) != null -> "Choose the listening, speaking and thinking models for calls in the voice settings first"
    !activeVoiceProfile().credentialsConfigured -> "Configure realtime voice credentials first"
    !activeVoiceProfile().connectionTested -> "Test the realtime voice connection first"
    !(notificationReady ?: notifications.ready()) -> "Allow AI call notifications so you can cancel or take over"
    !ignoreWork && work?.direction == CallDirection.OUTGOING -> "Another call is already being handled"
    else -> null
}

internal fun CallRuntime.isCurrentCall(target: Tracked, expectedCallId: String?): Boolean =
    liveCalls().singleOrNull() === target && (expectedCallId == null || target.id == expectedCallId)

internal fun CallRuntime.isPendingAutoAnswer(w: Work, target: Tracked): Boolean =
    w.automatic && w.track === target && target.phase == CallPhase.RINGING && !target.suppressed &&
        !w.answerSent && w.stopReason.value == null

internal fun CallRuntime.hasContactsAccess() =
    app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

internal fun CallRuntime.confirmedStranger(t: Tracked?): Boolean =
    t?.unsavedNumber != null && t.unsavedNumber == t.number && hasContactsAccess() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || t.call.details.contactDisplayName.isNullOrBlank())

/** Only a successful, empty phone lookup qualifies. Never infer contact status from a missing name. */
internal suspend fun CallRuntime.isUnsavedNumber(number: String): Boolean = withContext(Dispatchers.IO) {
    if (!hasContactsAccess()) return@withContext false
    val uri = Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number))
    try {
        app.contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)
            ?.use { !it.moveToFirst() } == true
    } catch (_: RuntimeException) {
        false
    }
}

internal fun CallRuntime.liveCalls() = tracked.values.filter { it.call.compatState != Call.STATE_DISCONNECTED }

internal fun CallRuntime.confirmedEnded(t: Tracked): Boolean = t.call.compatState == Call.STATE_DISCONNECTED ||
    (t.call !in tracked && managedCallPresent() == false)

/** Call.state is deprecated since Android 12; Details carries the same state from API 31 on. */
internal val Call.compatState: Int
    get() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details?.let { return it.state }
        @Suppress("DEPRECATION") val legacy = state
        return legacy
    }

internal fun CallRuntime.managedCallPresent(): Boolean? = try {
    telecom?.isInManagedCall
} catch (_: SecurityException) {
    null
} catch (_: RuntimeException) {
    null
}

internal fun CallRuntime.isEmergency(number: String): Boolean? = runCatching { telephony?.isEmergencyNumber(number) }.getOrNull()

internal fun CallRuntime.carrierCall(call: Call): Boolean {
    val d = call.details ?: return false
    if (d.videoState != VideoProfile.STATE_AUDIO_ONLY ||
        d.callProperties and (Call.Details.PROPERTY_SELF_MANAGED or Call.Details.PROPERTY_IS_EXTERNAL_CALL or
            Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL or Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE or
            Call.Details.PROPERTY_CONFERENCE or Call.Details.PROPERTY_RTT) != 0 ||
        call.parent != null || call.children.isNotEmpty()) return false
    val number = d.handle?.let { rawHandle ->
        if (rawHandle.scheme != PhoneAccount.SCHEME_TEL) return false
        CallPolicy.incomingNumber(rawHandle.schemeSpecificPart) ?: return false
    }
    val handle = d.accountHandle ?: return false
    if (subscription(handle) == null) return false
    return number == null || isEmergency(number) == false
}

internal fun CallRuntime.matchesOutgoing(w: Work, tracked: Tracked): Boolean = OutgoingCallMatch.matches(
    requestedNumber = w.requestedNumber,
    observedNumber = tracked.number,
    direction = tracked.direction,
    accountMatches = w.sim?.handle?.let { it == tracked.call.details?.accountHandle } == true,
    requestedToken = w.id,
    observedToken = runCatching { tracked.call.details?.intentExtras?.getString(TOKEN) }.getOrNull(),
)

internal suspend fun CallRuntime.readSims() {
    sims = if (!access.hasPhonePermissions()) emptyList() else withContext(Dispatchers.IO) {
        try {
            val service = telecom ?: return@withContext emptyList()
            val preferred = service.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            val subscriptionService = app.getSystemService(SubscriptionManager::class.java)
            service.callCapablePhoneAccounts.mapNotNull { handle ->
                val id = subscription(handle) ?: return@mapNotNull null
                val sim = subscriptionService?.getActiveSubscriptionInfo(id) ?: return@mapNotNull null
                CarrierSim(CallSim("sim-$id", sim.displayName?.toString()?.take(80)
                    ?.ifBlank { null } ?: "SIM ${sim.simSlotIndex + 1}", handle == preferred), handle)
            }.distinctBy { it.info.id }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}

// Reading a SIM's subscription ID by call account requires Android 11; earlier versions cannot read it, so treat it as having no selectable SIM.
internal fun CallRuntime.subscription(handle: PhoneAccountHandle): Int? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else runCatching {
        telephony?.createForPhoneAccountHandle(handle)?.subscriptionId?.takeIf { it >= 0 }
    }.getOrNull()

internal fun CallRuntime.callEndMessage(call: Tracked): String = when (call.call.details?.disconnectCause?.code) {
    android.telecom.DisconnectCause.BUSY -> "The other party is busy; the call ended"
    android.telecom.DisconnectCause.ERROR, android.telecom.DisconnectCause.RESTRICTED -> "Android reported that the call failed"
    android.telecom.DisconnectCause.MISSED -> "The incoming call was missed"
    else -> "The call ended"
}
