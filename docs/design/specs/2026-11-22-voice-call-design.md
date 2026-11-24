# In-app realtime voice call · design (all in one go)

Date: 2026-11-22
Status: Pending approval
Research basis: in-session research notes (a close read of the whole PR chain of a reference implementation from the same source + a comparison with this repository's engine layer; the notes are not checked in, and all conclusions have already been folded into this spec)

## Intent and why

Talk to the assistant by realtime voice inside the app, **and get things done by voice**: full duplex, interruptible, hands-free, kept alive by a foreground service; what the user says goes through a strict safety gate into the existing task system, progress scrolls in the call panel, and the model restates the result; the call text is written back into the conversation.
The engine layer (`VolcRealtimeClient`/`CallPlaybackQueue`/`CallInputPacer`/`PcmRecorder`/`PlaybackTickets`/`CallVoiceProfileStore`) already exists in this repository and is newer than the reference implementation — **do not port the other client** (it is a fork of our old engine, so porting would be a downgrade); the additions are concentrated in the shell, the safety gate, routing and the UI, about 1,600–1,900 new lines of code + 600–800 lines of tests.
The user has already decided: no phasing, do it all at once; execute in worktree isolation.

## Acceptance (all are gates)

1. JVM all green: all safety-gate branches, state machine transitions, protocol frames (fake service), route decisions, transcript aggregation, echo guard, backpressure.
2. androidTest smoke: full call-page chain (connect→listen→play→interrupt→hang up→retry→limit) + permission-denied path + fake task execution routing.
3. **Real-device audio matrix (hard gate)**: speaker/earpiece/Bluetooth headset × interruption/echo; VOICE_COMMUNICATION availability and AEC; isSilenced; hot plugging; incoming-call interruption; focus stolen; lock-screen behavior; measured false blocks/misses of the echo guard.
4. **Full-chain real-device verification of getting things done by voice**: say one sentence→execute→status line→restatement; every anti-mis-execution measure (forged function_call, echo impersonating an instruction, cross-turn replay) does not take effect.

## Feature semantics (full UX chain)

- **Entry**: the earpiece button in the chat input bar (coexists with press-and-hold to talk, enters call mode); the floating capsule menu 「语音聊天」 (Voice chat) opens it directly with an explicit intent. Prerequisite = realtime voice credentials already configured (reusing the AI phone call config) + microphone permission. The system digital assistant entry differs from our IA, so this spec does not cover it.
- **Permission flow**: in the call panel, RECORD_AUDIO is requested only when the user taps the button (no automatic popup); after denial the button becomes 「去设置」 (Go to settings) and opens the app details page, rechecking on ON_RESUME; when credentials are missing the button jumps to the realtime voice config page.
- **Connecting**: Connecting (cancellable) → wait for the UI to be RESUMED (if it does not return to the foreground within 3 seconds, report 「请回到前台」 (Please come back to the foreground)) → the microphone-type foreground service gets the lease → request audio focus → mic check (first chunk non-empty within 3 seconds and not silenced by the system) → speaker playback starts → local two-tone prompt → greeting played only once per session → 「已连接」 (Connected) green dot + mm:ss timer + red hang-up button.
- **Conversation**: the user speaks → server-side VAD produces a transcript → the model is constrained by the prompt: for each complete utterance it calls only one `mika_request` tool and stays silent → after strict validation on the app side it is handed to the existing chat/work entry for execution → progress scrolls as short status lines (at most 12 lines, without details such as coordinates or schedule titles) → the result goes back to the model as a function result → the model restates the result only once. The panel has a text input box, and typing and speaking go the same route.
- **Interruption (barge-in)**: as soon as the user starts speaking (server speech_started) → the playback queue generation is invalidated + AudioTrack pause/flush/play + cancel local tasks that have not completed (without claiming to undo operations that already took effect).
- **Hands-free**: Android 12+ `setCommunicationDevice` (wired/Bluetooth headset takes priority over the earpiece; keep the speaker when there is no private listening device), older systems use the speakerphone toggle; the selected state reads the system's actual communication device, hot plugging refreshes via AudioDeviceCallback; hanging up restores the original audio mode.
- **Ending**: hang-up button / notification shade hang-up / incoming call (MODE_IN_CALL/RINGTONE or phone function busy → busy error) / 10-minute cap after connecting (show the reason, no automatic retry) / collapse when the page is ON_STOP and background is not allowed. Both sides' text is written back to the originating conversation in bulk inside a NonCancellable finally.
- **Error UX**: auth failure, connection timeout, disconnection, rate limiting, provider error, no microphone input, audio focus failure, playback failure each get their own separate copy + in-place retry (attempt++ reruns the whole connection flow).

## Audio pipeline and full duplex (established facts, implement accordingly)

Microphone AudioRecord (16k mono PCM16, 320 samples/20ms chunk) → WebSocket (realtime duplex service, STT+LLM+TTS all server-side) → downlink 24k mono PCM16 → ring playback queue → AudioTrack (USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH, MODE_STREAM, non-blocking writes). VAD is entirely server-side; the client does no local VAD and no push-to-talk. Uplink and downlink run in independent coroutines; recording uses a `Channel<ShortArray>(100)` for backpressure, accumulating at most 5 seconds during connection setup, and overflow fails outright rather than silently dropping samples; uplink 20ms frames are paced to absolute deadlines; the playback queue is capped at 20 seconds (#695 lesson), and a single downlink packet is ≤1 second. Prompt tone: locally synthesized 720/960Hz sine, 65ms each with a fade-in/fade-out envelope, written directly to the playback track and not counted in the level meter.

## Component list

All new code follows generic AOSP design: public API capability detection + fallback chain, with no special cases for any device model; code/comments/docs/commit messages do not contain any external project names.

1. **`voice/PcmRecorder` (+~15, modified)**: `inputSource` (calls pass VOICE_COMMUNICATION to trigger platform AEC/NS, falling back to MIC if initialization fails), `chunkSamples`, `isSilenced` reading (capability detection, always false when unsupported). The existing voice input path behavior is unchanged.
2. **`calls/VolcRealtimeProtocol` (+~80, modified)**: `mikaActions` config item, `mika_request` tool registration frame, `functionResult` frame, `synthesize` frame (reused for read-aloud), `response.done` event parsing.
3. **`calls/VolcRealtimeClient` (+~60, modified)**: `VolcRealtimeConfig.agentTools`, `sendFunctionResult(callId, text)`, `synthesize(text)`, `VolcRealtimeEvent.ResponseDone`.
4. **`voicecall/VoiceCallSession` (~350, new)**: call orchestration, **explicit enum state machine** (Connecting→MicCheck→Active→Ended/Error; no boolean stitching, `ensureActive()` distinguishes external cancellation): RESUMED wait, focus, mic check (3s first chunk + isSilenced + VOICE_COMMUNICATION→MIC fallback), prompt tone, greeting once, playback writer (reuses CallPlaybackQueue), interruption (queue invalidation + pause/flush/play + notifying the route to cancel the task), 10-minute cap, 8 kinds of error copy + in-place retry, echo guard (during playback, a "user transcript" that is a substring of the 500-character ring buffer of what the assistant has already said is dropped; threshold adjustable).
5. **`voicecall/VoiceCallRequest` (~120, new)**: execution safety gate — only a "current-turn, final and complete" user transcript can feed execution; the `utterance` parameter and the transcript undergo a normalized **one-way** containment check — the utterance must fall inside the current turn's transcript, and anything longer than the transcript (the model added words the user did not say) is rejected outright (no match means the transcript is not consumed); callId is single-use (including reconnection replay); assistant echo is removed; each turn's transcript is consumed once. Rewrite it yourself by semantics.
6. **`voicecall/VoiceCallRoute` (~200, new)**: one-shot destination routing from voice requests to the existing chat/work task entry (first come, first served); subscribes to TaskState to emit short status lines; when a task needs confirmation/takeover, prompt 「去任务页看」 (See the task page); interruption cancels started-but-unfinished tasks; the result goes back through `sendFunctionResult`.
7. **`voicecall/VoiceCallTranscript` (~50, new)**: event→Turn (question/answer/time) aggregation, written back to the originating conversation at the end.
8. **`voicecall/VoiceCallService` (~130, new)**: microphone-type foreground service + CATEGORY_CALL ongoing notification (with a hang-up action); `select` races the call coroutine against the notification hang-up; Android 14+ mic FGS can only be started from the foreground; do not open the call page on the lock screen.
9. **`voicecall/VoiceCallAudioRoute` (~120, new)**: hands-free routing with two paths + selected state reading the system's actual device + hot-plug refresh + restore on hang-up; decisions extracted into pure functions for JVM tests.
10. **`ui/voicecall/VoiceCallOverlay` (~450, new)**: Compose full-screen Dialog (`usePlatformDefaultWidth=false`, `decorFitsSystemWindows=false`, tapping outside does not dismiss), opaque dark; a central breathing sphere rises and falls with the bidirectional level (**drawn in-house**, reusing the VoiceWave/Breathing language, no third-party particle engine); scrolling status-line area (≤12 lines) + mm:ss + hang up + hands-free + collapse + text input box.
11. **Entry points (~30 each, modified)**: the earpiece button in the input bar enters call mode; the capsule menu 「语音聊天」 opens it directly.
12. **Prompt constraints**: session instructions make the model call only one `mika_request` per complete utterance and stay silent, and restate the result only once; goes into the prompts resources.
13. **Strings**: about 40 new keys × 12 directories (values + 11 locales), greeting localized, no hard-coding.

## Execution safety (the core a port must preserve)

The model can forge a `function_call` — **only a current-turn complete transcript may trigger execution**: final+complete validation, parameter-transcript compatibility check (on mismatch not even the transcript is consumed), single-use callId to prevent reconnection replay, echo removal to prevent the assistant's voice from impersonating an instruction, single consumption of each turn's transcript to prevent duplicate execution. Better to refuse execution than to execute wrongly.

## Test strategy

- JVM: all VoiceCallRequest branches (echo/compatibility/single-use/cross-turn), all state machine transitions (retry attempt++, external cancellation vs internal failure), protocol frame construction and parsing (MockWebServer fake realtime service: tool registration/downlink audio/function_call/interruption), route decision pure functions, transcript aggregation, backpressure overflow, error→copy mapping.
- androidTest: call page smoke (injecting a fake runCall full chain), permission-denied path, fake task execution→status line→function result.
- Real-device matrix + full get-things-done chain: see acceptance 3, 4; record each item in the PR description.

## Risks and mitigations

- **Audio-path device differences (largest)**: AEC quality, AudioTrack hang, isSilenced, setCommunicationDevice compatibility — capability detection + fallback chain, and the real-device matrix is an acceptance gate.
- **Inherent residual echo self-excitation**: when platform AEC fails, the guard is probabilistic (≥4-character substring heuristic, may falsely block the user repeating the assistant's words); prefer the conservative direction (lower execution sensitivity during playback), and tune the threshold on real devices.
- **Permissions and policy**: RECORD_AUDIO runtime permission; continuous background recording must hang off a microphone-type FGS (Android 14+ can only start from the foreground); keep the FGS alive when backgrounded + hang up from the notification.
- **Power and heat**: continuous 16k capture + 24k playback + long connection; audio threads at `THREAD_PRIORITY_AUDIO`; 10-minute cap as a backstop.
- **Server-side billing/rate limiting**: billed by duration; error copy distinguishes retryable from go-to-configuration.
- **Credentials separated by purpose**: calls read the default realtime voice profile; the pitfall of trial-listening/read-aloud text credentials hitting the wrong API is already known, so store and read them separately by purpose.
- **Multiple playback sources stacking**: in-process mutual exclusion + generation tokens to block late callbacks (PlaybackTickets is already the right answer).
