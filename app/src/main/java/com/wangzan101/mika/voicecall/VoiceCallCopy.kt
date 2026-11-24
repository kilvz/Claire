package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.frontdesk.DeskNotifier
import com.wangzan101.mika.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Copy mapping for the call page: every error kind and every end reason has its own separate wording; the Ended(Background) that a RESUMED timeout produces
 * is rendered here as "Please come back to the foreground". Front desk reports and the results of a directly dispatched typed message also become local wording here.
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

/** [idleMinutes] is the configured idle-hangup duration in minutes and is only used for [EndReason.Idle]. */
fun voiceCallEndText(reason: EndReason, idleMinutes: Int = 0): String = when (reason) {
    EndReason.Hangup -> tr(R.string.voicecall_ended_hangup)
    EndReason.Notification -> tr(R.string.voicecall_ended_notification)
    EndReason.IncomingCall -> tr(R.string.voicecall_ended_incoming)
    EndReason.Idle -> tr(R.string.voicecall_ended_idle, idleMinutes)
    EndReason.Background -> tr(R.string.voicecall_ended_background)
}

/**
 * The cue given to the model before an idle hangup (not UI text shown to the user): when the conversation can keep replying, the model paraphrases it in the call's language;
 * when it cannot, [R.string.voicecall_idle_line] is read out as-is.
 */
const val VOICECALL_IDLE_CUE =
    "The call has been quiet for a while. In one short sentence, ask whether the user is still there " +
        "and say you will hang up soon if you hear nothing. Do not call any tool."


/** The line next to the status dot at the top: in progress it is a status word, in a terminal state it is the end/error text. */
fun voiceCallStatusText(state: VoiceCallState): String = when (state) {
    VoiceCallState.Connecting -> tr(R.string.voicecall_status_connecting)
    VoiceCallState.MicCheck -> tr(R.string.voicecall_status_mic_check)
    is VoiceCallState.Active -> tr(R.string.voicecall_status_active)
    is VoiceCallState.Ended -> voiceCallEndText(state.reason, state.idleMinutes)
    is VoiceCallState.Failed -> voiceCallErrorText(state.error)
}

/** What the front desk report says during a call: "That job of yours is done: ..."; the body is capped at [DESK_RESULT_MAX_CHARS] characters. */
fun deskReportSpeech(report: DeskReport): String =
    tr(R.string.voicecall_desk_report, report.title, DeskNotifier.line(report.event).take(DESK_RESULT_MAX_CHARS))

/** The result of a directly dispatched typed message comes back locally: on dispatch say "Working on it" (later progress is reported by the front desk as usual), on failure say "That did not work". */
fun typedDeskResult(result: JsonObject): String =
    when ((result["status"] as? JsonPrimitive)?.content) {
        "started", "queued", "already_dispatched" -> tr(R.string.voicecall_task_running)
        else -> tr(R.string.voicecall_task_failed)
    }
