package com.wangzan101.mika.calls

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread policy only. Runtime binds the lease to a single Call and its InCallService. */
internal class TelecomMuteLease private constructor(private val originalMuted: Boolean) {
    enum class Release {
        PRESERVE, VERIFY_OVERRIDE, REQUEST_UNMUTE, VERIFY_UNMUTED;

        fun confirmed(sameCall: Boolean, telecomMuted: Boolean?, hardwareMuted: Boolean?): Boolean = when (this) {
            PRESERVE, VERIFY_OVERRIDE -> !sameCall || telecomMuted != null && telecomMuted == hardwareMuted
            REQUEST_UNMUTE, VERIFY_UNMUTED -> hardwareMuted == false && (!sameCall || telecomMuted == false)
        }
    }

    val needsMuteRequest: Boolean get() = !originalMuted
    private var muteRequested = false
    private var telecomAcknowledged = originalMuted
    private var relinquished = false
    private var releasing = false

    fun markMuteRequested() {
        check(needsMuteRequest && !muteRequested && !releasing)
        muteRequested = true
    }

    /** False after an acknowledged mute means Telecom or the user took back control. */
    fun observeTelecomMute(muted: Boolean): Boolean {
        if (releasing || relinquished) return false
        if (muted) {
            telecomAcknowledged = true
        } else if (telecomAcknowledged) {
            relinquished = true
            return true
        }
        return false
    }

    fun isConfirmed(telecomMuted: Boolean?, hardwareMuted: Boolean?): Boolean =
        !releasing && !relinquished && telecomAcknowledged && telecomMuted == true && hardwareMuted == true

    fun beginRelease(sameCall: Boolean): Release {
        val alreadyReleasing = releasing
        releasing = true
        return when {
            relinquished -> Release.VERIFY_OVERRIDE
            !muteRequested -> Release.PRESERVE
            sameCall && !alreadyReleasing -> Release.REQUEST_UNMUTE
            else -> Release.VERIFY_UNMUTED
        }
    }

    companion object {
        fun fromBaseline(telecomMuted: Boolean?, hardwareMuted: Boolean?): TelecomMuteLease? =
            telecomMuted?.takeIf { it == hardwareMuted }?.let(::TelecomMuteLease)
    }
}

/** Android 14+ sends the legacy audio state on bind, then has a dedicated mute callback. */
internal class TelecomMuteState(private val dedicatedMuteCallback: Boolean) {
    enum class Source { LEGACY_AUDIO, MUTE_CALLBACK }
    enum class Update { CHANGED, DUPLICATE, IGNORED_LEGACY }

    var value: Boolean? = null
        private set

    fun update(source: Source, muted: Boolean): Update {
        // A later route/audio snapshot is not authoritative for mute on Android 14+.
        if (dedicatedMuteCallback && source == Source.LEGACY_AUDIO && value != null) return Update.IGNORED_LEGACY
        if (value == muted) return Update.DUPLICATE
        value = muted
        return Update.CHANGED
    }

    fun reset() {
        value = null
    }
}

/** An allowlist maps known shell outcomes to fixed diagnostics; no raw shell string leaves here. */
internal fun shellAudioStopDiagnostic(reason: String?): String? = when (val code = reason?.trim()) {
    "Handset microphone was unmuted." -> "The device microphone was unmuted; the AI handed the call back"
    "Cellular audio mode ended.", "Cellular audio ended." -> "Carrier call audio mode ended; the system is handling the call"
    "Downlink consumer stopped draining audio." -> "Carrier downlink audio is blocked; the system is handling the call"
    "Android silenced the cellular audio capture." -> "Android stopped providing call audio to the AI; control was handed back to the system"
    "Uplink playback stopped accepting audio." -> "Carrier uplink audio playback made no progress; the system is handling the call"
    "Helper released.", "Call audio owner died." -> "The built-in helper released the call audio; the system is handling the call"
    else -> when {
        code == null -> null
        code.startsWith("Downlink read failed (") -> "The device cannot read carrier downlink audio; the system is handling the call"
        code.startsWith("Uplink write failed (") -> "The device cannot write carrier uplink audio; the system is handling the call"
        code.startsWith("Uplink flush failed:") -> "The device cannot flush carrier uplink audio; the system is handling the call"
        code.startsWith("Uplink did not route to the telephony device (") ||
            code.startsWith("Uplink unexpectedly routed to device type ") -> "Device uplink is not routed to the carrier call; the system is handling the call"
        code.startsWith("Downlink unexpectedly routed to device type ") -> "Device downlink is not routed to the carrier call; the system is handling the call"
        else -> null
    }
}

internal data class TelecomMuteBaseline(
    val cellularModeReady: Boolean,
    val telecomMuted: Boolean?,
    val hardwareMuted: Boolean?,
    val observationRevision: Long,
)

/**
 * Before owning mute, wait for a settled cellular route and an authoritative baseline.
 * Equal false callbacks still indicate initialization activity and restart the stable interval.
 * This never suppresses an unmute after a lease has acknowledged mute, and never retries setMuted.
 */
internal suspend fun awaitTelecomMuteBaseline(
    isCurrent: () -> Boolean,
    nowMs: () -> Long,
    sample: () -> TelecomMuteBaseline,
): TelecomMuteLease {
    var stableSince: Long? = null
    var previous: TelecomMuteBaseline? = null
    var observedMutedBaseline = false
    return withTimeoutOrNull(1_500L) {
        while (true) {
            if (!isCurrent()) throw CallMediaFailure("The call identity or audio helper changed; the AI did not attach to the audio")
            val state = sample()
            // An already-muted handset remains the owner's decision. If it is unmuted
            // while we are still waiting, do not claim the new false baseline and remute it.
            if (observedMutedBaseline && (state.telecomMuted == false || state.hardwareMuted == false)) {
                throw CallMediaFailure("The call was unmuted before takeover; the AI did not attach to the audio")
            }
            if (state.telecomMuted == true && state.hardwareMuted == true) observedMutedBaseline = true
            val baseline = TelecomMuteLease.fromBaseline(state.telecomMuted, state.hardwareMuted)
            if (!state.cellularModeReady || baseline == null) {
                stableSince = null
                previous = null
            } else {
                val now = nowMs()
                if (previous != state || stableSince == null) stableSince = now
                previous = state
                if (now - (stableSince ?: now) >= 250L) return@withTimeoutOrNull baseline
            }
            delay(25L)
        }
        @Suppress("UNREACHABLE_CODE") null
    } ?: throw CallMediaFailure("Android call audio or the original mute state was not stable; the AI did not attach to the audio")
}
