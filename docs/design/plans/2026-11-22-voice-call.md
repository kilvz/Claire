# In-App Real-Time Voice Call Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full-duplex real-time voice conversation with the assistant inside the app + running errands by voice (with a strict safety gate) + entry points in the input bar and the capsule, all built in one go.

**Architecture:** Reuse the existing engine (`VolcRealtimeClient` with both protocols, `CallPlaybackQueue` generations, `PcmRecorder` streaming, `PlaybackTickets`, `CallVoiceProfileStore`), and add a new `voicecall/` package (orchestration / safety gate / routing / transcript / foreground service / audio route) plus a full-screen Compose call page. The reference implementation contributes design semantics only, not code.

**Tech Stack:** Kotlin, coroutines/Flow, Compose, AudioRecord/AudioTrack, the existing calls/voice foundation.

**Spec:** `docs/design/specs/2026-11-22-voice-call-design.md`

## Global Constraints

- Generic AOSP, no brand special cases; code/comments/docs/commit messages must not contain the name of any external project.
- Every new user-visible string must be present in all 12 folders: values, values-zh, values-b+zh+Hant, values-ar/es/fa/in/ja/ko/pt/ru/tr. New file `strings_voicecall.xml`.
- Regression command: `./gradlew :core:test :app:testDebugUnitTest` (bare `:app:test` is forbidden, OOM). In Gradle output you must look for "BUILD SUCCESSFUL/FAILED"; do not trust `$?` after a pipe.
- Regenerating golden samples must double-filter: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots`.
- minSdk 29: `AudioRecord.isClientSilenced()` (API 29) is directly usable, no capability check needed; `setCommunicationDevice` needs an API 31 check.
- Zero engine rewrites: `VolcRealtimeProtocol` already has session.create/append/update(tools)/item.create/greeting/mute/close and all event constants; `CallRealtimeSession` already has sendPcm16/sendGreeting/setMuted/sendToolResult/close. Add only what is missing.
- androidTest fake-engine mode: hand-write a fake implementing the interface and pass it in as a parameter (see VoiceHoldTest), without going through DI; `ChineseUi()` rule.
- TaskState subscription: parallel tasks must fetch their own via `runState(id)` keyed by conversationId; never collect the global taskState directly.

## Review Focus

1. A forged `function_call` (no transcript for the current turn / replay across turns / parameters that don't match the transcript) must all be refused execution, and the unconsumed transcript must not linger — Task 3 tests.
2. The assistant's own voice during playback being transcribed by the server-side VAD as the user → the echo guard drops it and does not trigger execution — Task 3/4 tests.
3. After a barge-in, audio from an old generation must never reach the ear (after invalidate the writer checks `isCurrent`) — Task 4 tests.
4. On the limit / an incoming call / focus being stolen / hang-up, the FGS, audio focus, AudioTrack and WebSocket are all released (shutdown of the state machine is idempotent, external cancellation is not rethrown) — Task 4 tests.
5. The progress status line is at most 12 lines and must not contain details such as coordinates or calendar titles — Task 5 tests.

---

### Task 1: Parameterize PcmRecorder (inputSource / chunkMs / silence reading)

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/voice/PcmRecorder.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voice/PcmRecorderTest.kt` (add cases if it already exists)

**Interfaces:**
- Produces: `class PcmRecorder(maxMs: Int = 60_000, streaming: Boolean = false, unbounded: Boolean = false, inputSource: Int = MediaRecorder.AudioSource.MIC, chunkMs: Int = 50) : StreamingRecorder`, plus a new `val silenced: StateFlow<Boolean>` (reads `AudioRecord.isClientSilenced()` for every chunk). Behavior for existing three-parameter callers is unchanged (voice input is not affected).

- [ ] **Step 1: Write the failing test** — inputSource/chunkMs defaults match current behavior (a chunk is 320 samples at 20ms and 800 samples at 50ms); silenced starts as false. Run `./gradlew :app:testDebugUnitTest --tests '*PcmRecorder*' -q` and confirm red (the parameter does not exist).
- [ ] **Step 2: Implement** — add the two constructor parameters, compute the chunk length from chunkMs instead of `Wav.RATE / 20`, and update `_silenced` after recording each chunk. The VOICE_COMMUNICATION init-failure fallback to MIC does not go here (it goes in the Task 4 orchestration layer).
- [ ] **Step 3: Run green** + the full `./gradlew :app:testDebugUnitTest --tests '*Voice*' -q` to confirm zero regression in the existing voice paths.
- [ ] **Step 4: Commit** `feat(voicecall): parameterize PcmRecorder input source / chunk length / silence reading`

### Task 2: Protocol completion — mika_request registration and agentTools configuration

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/calls/VolcRealtimeProtocol.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/calls/VolcRealtimeClient.kt`
- Test: `app/src/test/java/com/wangzan101/mika/calls/VolcRealtimeAgentToolsTest.kt`

**Interfaces:**
- Consumes: the existing `enablePhoneTools` (the template for the session.update tools frame), `VolcRealtimeConfig`, `sendToolResult` (function result already exists, use it directly).
- Produces: `VolcRealtimeConfig` gains `agentTools: Boolean = false`; `VolcRealtimeProtocol.mikaRequestTool(): JsonObject` (tool definition: mika_request, with a required `utterance` string parameter); after SESSION_CREATED, when agentTools=true it automatically sends a session.update to register. **The functionResult/synthesize/response.done from the spec are no longer added** (sendToolResult/sendGreeting/AssistantAudioEnded already cover them) — this is the narrowed decision after taking inventory.

- [ ] **Step 1: Write the failing test (MockWebServer as a fake realtime service)** — with agentTools=true, after connecting it receives a session.update containing mika_request; with false it sends none; the tool definition JSON contains utterance as required. Run and confirm red.
- [ ] **Step 2: Implement** — follow enablePhoneTools' frame construction and send timing; write mika_request's description as "hand the user's complete utterance to the task system for execution" (in English, in the same style as the existing tool frames). At the same time: when agentTools=true, append a constraint paragraph to the session instructions (the model calls mika_request only once per complete utterance and stays silent the rest of the time, and repeats the result to the user only once) — the instructions resource goes into prompts, not hard-coded in code; a test pins that the instructions contain the constraint paragraph.
- [ ] **Step 3: Run green** `./gradlew :app:testDebugUnitTest --tests '*VolcRealtime*' -q`.
- [ ] **Step 4: Commit** `feat(voicecall): register the mika_request tool and config switch for realtime sessions`

### Task 3: VoiceCallRequest execution safety gate (pure logic)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallRequest.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallRequestTest.kt`

**Interfaces:**
- Produces:
  - `class VoiceCallRequest(private val echoWindow: RingBuffer<String> /* what the assistant has said, capacity 500 characters */)`
  - `fun onUserTranscript(text: String)`: record the current turn's final+complete user transcript (overwritten each turn, only the latest is kept)
  - `fun offer(callId: String, utterance: String): VoiceCallDecision`
  - `sealed interface VoiceCallDecision { data class Accept(val text: String) : VoiceCallDecision; data object Reject : VoiceCallDecision }`
- Rules (all testable): ① no transcript for the current turn → Reject; ② callId already seen (including replay on reconnect) → Reject; ③ after normalizing utterance (strip whitespace/punctuation/case) it and the current-turn transcript are not substrings of each other → Reject, and the transcript is not consumed; ④ utterance and assistant content inside the echo window are substrings of each other → Reject; ⑤ pass → Accept(the transcript), and the transcript is consumed once (offering again for the same turn → Reject).

- [ ] **Step 1: Write the failing test** — positive and negative cases for each of the 5 rules + cross-turn replay + a second offer in the same turn after consumption + the 500-character ring boundary of the echo window. Run and confirm red (the class does not exist).
- [ ] **Step 2: Implement VoiceCallRequest.kt** — all hand-written; the normalization function is file-level private.
- [ ] **Step 3: Run green** `./gradlew :app:testDebugUnitTest --tests '*VoiceCallRequest*' -q`.
- [ ] **Step 4: Commit** `feat(voicecall): safety gate for executing voice requests (anti-forgery / anti-replay / anti-echo)`

### Task 4: VoiceCallSession call orchestration (the state-machine core)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallSession.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallAudio.kt` (AudioRecord/AudioTrack abstraction, to make faking easy)
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallTone.kt` (locally synthesized two-tone prompt sound, a pure function that emits PCM)
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallSessionTest.kt`, `VoiceCallToneTest.kt`

**Interfaces:**
- Consumes: Task 1 PcmRecorder, Task 2 agentTools, `CallRealtimeSession`, `CallPlaybackQueue`, Task 3 VoiceCallRequest.
- Produces:
  - `sealed interface VoiceCallState { data object Connecting; data object MicCheck; data class Active(val startedAt: Long); data class Ended(val reason: EndReason); data class Failed(val error: VoiceCallError, val attempt: Int) }`; `enum class EndReason { Hangup, Notification, IncomingCall, TimeLimit, Background }`; `enum class VoiceCallError { Auth, ConnectTimeout, Disconnected, RateLimited, Provider, MicSilent, AudioFocus, Playback, Busy }`
  - `class VoiceCallSession(...)`, `val state: StateFlow<VoiceCallState>`, `fun hangup()`, `fun retry()`, `val micLevel/speakerLevel: StateFlow<Float>`.
  - The `VoiceCallAudio` interface: capture/playback/focus/mode all abstracted, with both a production implementation and a test fake.

- [ ] **Step 1: Write the failing test** — every state-machine transition (Connecting→MicCheck→Active→Ended; attempt++ on each Failed path; external cancellation is not rethrown); the RESUMED 3-second timeout; the mic check (first chunk 3s of silence/silenced → MicSilent); VOICE_COMMUNICATION init failure falls back to MIC; the 10-minute limit; after barge-in the writer checks isCurrent and discards old chunks; shutdown is idempotent (hanging up twice does not crash). Prompt tones: 720/960Hz sine at 65ms each, envelope, not counted in the level. Run and confirm red.
- [ ] **Step 2: Implement** — an enum state machine (no booleans); the echo guard uses Task 3's echoWindow; the greeting goes through `sendGreeting` (cloud-synthesized), once per session; the playback writer reuses CallPlaybackQueue (20s limit); error classification maps to VoiceCallError.
- [ ] **Step 3: Run green** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`.
- [ ] **Step 4: Commit** `feat(voicecall): call orchestration state machine and audio pipeline`

### Task 5: VoiceCallRoute task routing + VoiceCallTranscript transcript persistence

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallRoute.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallTranscript.kt`
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallRouteTest.kt`, `VoiceCallTranscriptTest.kt`

**Interfaces:**
- Consumes: `VoiceCallDecision.Accept` from Task 3, `TaskState` (core), `sendToolResult`.
- Produces:
  - `fun interface VoiceCallTaskLauncher { fun launch(goal: String): String }` (returns conversationId; at wiring time it connects to the same task entry point as TaskService and ChatViewModel)
  - `class VoiceCallRoute(launcher, runState: (String) -> StateFlow<TaskState>?, sendResult: (String, String) -> Unit)`: `suspend fun execute(callId, text)` — a one-time destination (first come, first served), subscribes to runState by conversationId, an interruption cancels tasks already started but not finished, and the result goes back through sendResult.
  - `fun TaskState.toVoiceLine(): String?` — a short status line (≤40 characters, with details like coordinates and calendar titles stripped); the status-line buffer is capped at 12 lines.
  - `class VoiceCallTranscript`: `fun onUser(text)`/`fun onAssistant(text)`; `fun turns(): List<Turn>`; `data class Turn(val question: String, val answer: String, val at: Long)`; at the end of the session, `finally` (NonCancellable) writes them back to the originating conversation in one batch.

- [ ] **Step 1: Write the failing test** — routing is first come, first served (two callIds compete and only one executes); the 12-line status cap and detail stripping; a task that is Held/WaitingConfirmation → "needs you — open the task page"; interruption → cancel the unfinished task; Succeeded → the result text goes back through sendResult; transcripts aggregate question/answer/time and are written back once (a repeated end does not write twice). Run and confirm red.
- [ ] **Step 2: Implement** — all hand-written; writing back to the conversation goes through the same storage entry point as chat.
- [ ] **Step 3: Run green** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`.
- [ ] **Step 4: Commit** `feat(voicecall): voice task routing and transcript persistence`

### Task 6: VoiceCallService (microphone foreground service) + VoiceCallAudioRoute (speakerphone)

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallService.kt`
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/VoiceCallAudioRoute.kt`
- Modify: `app/src/main/AndroidManifest.xml` (service declaration, foregroundServiceType="microphone")
- Test: `app/src/test/java/com/wangzan101/mika/voicecall/VoiceCallAudioRouteTest.kt`

**Interfaces:**
- Produces: `VoiceCallService` (CATEGORY_CALL ongoing notification + a hang-up action; `select` races the call coroutine against the notification hang-up); `enum class RouteDevice { Speaker, Earpiece, Wired, Bluetooth }`; `fun chooseRoute(available: List<RouteDevice>, sdkInt: Int, speakerRequested: Boolean): RouteDevice` (pure function: wired/bluetooth take priority over the earpiece; with no private device, keep the speaker); `class VoiceCallAudioRoute` (API 31+ setCommunicationDevice, below that speakerphone; AudioDeviceCallback hotplug; restore on hang-up).

- [ ] **Step 1: Write the failing test** — every chooseRoute combination (speaker only/earpiece/wired/bluetooth/all present × speakerphone requested or not × SDK 30/31). Run and confirm red.
- [ ] **Step 2: Implement** the two files + the Manifest.
- [ ] **Step 3: Run green** `./gradlew :app:testDebugUnitTest --tests '*VoiceCall*' -q`.
- [ ] **Step 4: Commit** `feat(voicecall): microphone foreground service and speakerphone audio routing`

### Task 7: VoiceCallOverlay full-screen UI + entry points + strings

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/voicecall/ui/VoiceCallOverlay.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ui/ChatScreen.kt`(输入栏听筒按钮)
- Modify: `app/src/main/java/com/wangzan101/mika/capsule/CapsuleController.kt` + `CapsuleCardContent.kt`(菜单「语音聊天」)
- Modify: `app/src/main/java/com/wangzan101/mika/ui/MainActivity.kt`(EXTRA_OPEN_VOICE_CALL 直开)
- Create: `app/src/main/res/values*/strings_voicecall.xml`(×12 目录)
- Test: `app/src/androidTest/java/com/wangzan101/mika/voicecall/ui/VoiceCallPageTest.kt`

**Interfaces:**
- Consumes: `VoiceCallState`/`micLevel/speakerLevel` from Task 4, the status-line flow from Task 5, the speakerphone from Task 6.
- Produces: `@Composable fun VoiceCallOverlay(session: VoiceCallSession, route: VoiceCallRoute, audioRoute: VoiceCallAudioRoute, onCollapse: () -> Unit)` — a full-screen Dialog (`usePlatformDefaultWidth=false`, `decorFitsSystemWindows=false`, tapping outside does not dismiss), opaque and dark; a self-drawn breathing ball (follows the level, reusing the VoiceWave language but self-drawn with parameterized size, not copying the fixed 22dp version); status lines scroll, ≤12; mm:ss; red hang-up; speakerphone; collapse; a text input box (typing and talking take the same path, through the session's text channel); permission button flow (request / go to settings / go to configuration).

- [ ] **Step 1: androidTest smoke test (inject a fake runCall, hand-write the fake as in VoiceHoldTest)** — connect→listen→playback animation→barge-in→hang up→retry→limit, the whole chain; permission denied→go to settings button.
- [ ] **Step 2: Implement the UI + both entry points + the EXTRA direct open**.
- [ ] **Step 3: Fill in the strings across ×12 folders (localized greeting)**.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest -q` + `./gradlew :app:assembleDebugAndroidTest -q` (the androidTest compiles; the real-device run is left to acceptance).
- [ ] **Step 5: Commit** `feat(voicecall): full-screen call page and input-bar/capsule entry points`

### Task 8: Full regression + real-device acceptance checklist

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest --console=plain -q` all green (look for BUILD SUCCESSFUL); when TerminalHubTest fails alone and the diff does not touch app/terminal, treat it as known environment flakiness.
- [ ] **Step 2:** Golden-sample check: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -q` (zero diff if there were no prompt changes).
- [ ] **Step 3:** Write `docs/design/acceptance/2026-11-22-voice-call-device-matrix.md`: speaker/earpiece/bluetooth × barge-in/echo, isSilenced, hotplug, incoming call, focus stolen, lock screen, the full voice-errand chain, and the four anti-misfire items (forgery/replay/echo/cross-turn) — one checkbox each, ticked item by item during real-device verification.
- [ ] **Step 4: Commit** `test(voicecall): full regression and real-device acceptance checklist`
