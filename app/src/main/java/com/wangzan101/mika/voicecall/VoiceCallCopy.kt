package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/**
 * Copy mapping for the call page: each error kind and each end reason has its own separate wording; the
 * Ended(Background) produced when RESUMED times out is rendered here as "please return to the foreground". Results of typed requests (synthesized callId,
 * no function_call on the model side) come back locally: the route's model-instruction text is swapped for the local wording, and the task's own answer/reason is passed through as-is.
 */

fun voiceCallErrorText(error: VoiceCallError): String = tr(
    when (error) {
        VoiceCallError.Auth -> R.string.voicecall_error_auth
        VoiceCallError.ConnectTimeout -> R.string.voicecall_error_timeout
        VoiceCallError.Disconnected -> R.string.voicecall_error_disconnected
        VoiceCallError.RateLimited -> R.string.voicecall_error_rate_limited
        VoiceCallError.Provider -> R.string.voicecall_error_provider
        VoiceCallError.MicSilent -> R.string.voicecall_error_mic_silent
        VoiceCallError.AudioFocus -> R.string.voicecall_error_audio_focus
        VoiceCallError.Playback -> R.string.voicecall_error_playback
        VoiceCallError.Busy -> R.string.voicecall_error_busy
    },
)

fun voiceCallEndText(reason: EndReason): String = tr(
    when (reason) {
        EndReason.Hangup -> R.string.voicecall_ended_hangup
        EndReason.Notification -> R.string.voicecall_ended_notification
        EndReason.IncomingCall -> R.string.voicecall_ended_incoming
        EndReason.TimeLimit -> R.string.voicecall_ended_limit
        EndReason.Background -> R.string.voicecall_ended_background
    },
)

/** The line next to the status dot at the top: in progress it is a status word, in a terminal state it is the end/error text. */
fun voiceCallStatusText(state: VoiceCallState): String = when (state) {
    VoiceCallState.Connecting -> tr(R.string.voicecall_status_connecting)
    VoiceCallState.MicCheck -> tr(R.string.voicecall_status_mic_check)
    is VoiceCallState.Active -> tr(R.string.voicecall_status_active)
    is VoiceCallState.Ended -> voiceCallEndText(state.reason)
    is VoiceCallState.Failed -> voiceCallErrorText(state.error)
}

/** Result text of a typed request: the route constants are English instructions meant for the model, so they are swapped for local wording here; the rest (the task's answer/reason) is kept as-is. */
fun typedResultText(text: String): String = when (text) {
    VoiceCallRoute.RESULT_BUSY -> tr(R.string.voicecall_task_running)
    VoiceCallRoute.RESULT_START_FAILURE -> tr(R.string.voicecall_task_failed)
    VoiceCallRoute.RESULT_NEEDS_USER -> tr(R.string.voicecall_see_task_page)
    VoiceCallRoute.RESULT_CANCELLED -> tr(R.string.voicecall_task_cancelled)
    VoiceCallRoute.RESULT_DONE_FALLBACK -> tr(R.string.voicecall_task_done)
    VoiceCallRoute.RESULT_FAILED_FALLBACK -> tr(R.string.voicecall_task_failed)
    else -> text
}
