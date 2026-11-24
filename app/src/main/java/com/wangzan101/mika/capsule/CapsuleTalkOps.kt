package com.wangzan101.mika.capsule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.ui.VoiceCopy
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_SENT_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_SHORT_MS
import com.wangzan101.mika.capsule.CapsuleController.Companion.NOTE_LONG_MS

// Capsule: hold-to-talk, the speech result, and the note strip
/** Whether talking on the capsule is possible right now: there is a panel window, it is not the 'you handle it' bar, and voice input is not set to off. */
internal fun CapsuleController.canTalk(): Boolean = panelWindow != null && !isBar() && !edgeHidden &&
    (context.applicationContext as MikaApp).speech.choice.value != SpeechChoice.Off

/** The screen reader's 'talk': start if not talking (no finger, so it can only send), otherwise send. */
internal fun CapsuleController.toggleTalk() {
    if (talk.active && !talk.byTouch && !talk.released) talk.finish(CapsuleVoice.Drop.Send)
    else beginTalk(byTouch = false)
}

/**
 * Start talking: a pending single or double tap is dropped. Who it talks to is the card a single tap would expand (on the idle icon the latest conversation is read first, and the line at the top of the panel follows once it arrives).
 * Without the recording permission (the capsule cannot show the system permission dialog) or when voice input is unavailable, it does not record; it shows a note on the capsule, and a tap opens Claire.
 */
internal fun CapsuleController.beginTalk(byTouch: Boolean) {
    val v = view ?: return
    // Pressing again is allowed while the previous segment is still transcribing after release: talk.begin discards the old one
    if (capturing || (talk.active && !talk.released) || !canTalk()) return
    resetTaps()
    wake()
    clearNote()
    val loading = if (v.kind == CapsuleKind.Idle || v.kind == CapsuleKind.Notice) scope.async { loadIdle() } else null
    when (val b = talk.begin(CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, idle)), byTouch)) {
        CapsuleTalk.Begin.Started -> {
            talkIdle?.cancel()
            talkIdle = loading
            loading?.let {
                scope.launch {
                    val chat = it.await()
                    if (talk.active && talkIdle === it) talk.title = CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, chat))
                }
            }
        }
        CapsuleTalk.Begin.NoPermission -> {
            loading?.cancel()
            showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
        }
        is CapsuleTalk.Begin.Unavailable -> {
            loading?.cancel()
            showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
        }
    }
}

/** Finished talking: send or open the card according to where the finger was released; when nothing was heard or an error occurred, show a note on the capsule (same wording as the input bar). */
internal fun CapsuleController.spoken(drop: CapsuleVoice.Drop, state: HoldToTalk.State) {
    val idleJob = talkIdle
    talkIdle = null
    when (state) {
        is HoldToTalk.State.Done -> scope.launch {
            idleJob?.let { idle = it.await() }
            if (drop == CapsuleVoice.Drop.Edit) editSpoken(state.text) else sendSpoken(state.text)
        }
        is HoldToTalk.State.Problem -> {
            idleJob?.cancel()
            when (val why = state.why) {
                null -> showNote(VoiceCopy.NO_MATCH, CapsuleKind.Failed, opensMika = false, NOTE_SHORT_MS)
                SpeechFailure.NoPermission -> showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                // The missing permission is on the recognition service: that cannot be explained on the capsule, so bring it into Claire (where hold-to-talk explains it and offers a button to its settings page)
                is SpeechFailure.ServiceNoPermission ->
                    showNote(tr(R.string.capsule_voice_service_need_mic), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                is SpeechFailure.Unavailable -> showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMika = true, NOTE_LONG_MS)
                else -> showNote(VoiceCopy.failure(why), CapsuleKind.Failed, opensMika = false, NOTE_LONG_MS)
            }
        }
        else -> idleJob?.cancel()
    }
}

/**
 * Release to send directly: send to the conversation in the card a single tap would expand (the same targeting as double tap to send the screen), and the capsule flashes 'steered / answered / sent: …'.
 * When that card has no input row (it needs you to take over or to approve), do not send; expand the card and put the text in the input row first.
 */
internal fun CapsuleController.sendSpoken(text: String) {
    val target = CapsuleCards.of(model.cardRuns(), follow, idle)
    val kind = target?.let(CapsuleCards::input)?.kind
    if (target == null || kind == null) {
        open()
        inputUi = inputUi.withSpoken(text)
        refresh()
        return
    }
    submit(target, kind, text, CapsuleCards.conversationOf(target), inputUi.followUpMode)
    showNote(CapsuleVoice.sent(kind, text), CapsuleKind.Done, opensMika = false, NOTE_SENT_MS)
    refresh()
}

/** Release over 'edit': expand the card with the text in the input row and the keyboard up, ready to be sent after editing. */
internal fun CapsuleController.editSpoken(text: String) {
    open()
    inputUi = inputUi.withSpoken(text)
    refresh()
    if (card?.let(CapsuleCards::input) != null) openInput()
}

internal fun CapsuleController.showNote(text: String, kind: CapsuleKind, opensMika: Boolean, ms: Long) {
    resetTaps()
    note = CapsuleNote(text, kind, opensMika, ++noteCount)
    wake()
    noteJob?.cancel()
    noteJob = scope.launch {
        delay(ms)
        note = null
        fade()
    }
}

internal fun CapsuleController.clearNote() {
    noteJob?.cancel()
    note = null
}
