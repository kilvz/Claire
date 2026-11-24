# In-app real-time voice call · on-device acceptance checklist

Date: 2026-11-22
Scope: the `worktree-voice-call` branch (design in `docs/design/specs/2026-11-22-voice-call-design.md`).
How to use: run through each item on a real device; tick `[x]` when it passes, and record the symptom plus model/Android version when it fails. **Any single failure means this version does not pass acceptance.**

Preconditions: real-time voice credentials configured in Settings (the same ones as for AI phone calls); microphone permission granted.

## 1. Connecting and the basic chain

- [ ] The handset button next to the microphone in the input bar opens the call page; the capsule menu "Voice chat" opens it too
- [ ] Connecting: the orb is in mesh form → switches to a "Connected" green dot and the timer starts running
- [ ] The first session plays a greeting, and **plays it only once** (not replayed within the same call)
- [ ] The local two-tone "ding-dong" prompt sounds before the greeting
- [ ] Speaking produces a waveform response (the orb takes on a wavy form)
- [ ] While the assistant speaks, the orb rises and falls with its voice
- [ ] Hanging up: the red hang-up button → falls to "Ended", the timer stops at the final duration, the bottom input/hands-free/hang-up controls disappear, leaving only collapse at the top left
- [ ] Tap collapse → the page exits, the notification bar still holds a call notification (with hang-up), and the call continues
- [ ] Hang up from the notification bar → the call ends, and the page is dismissed too if it is open

## 2. Audio routing matrix (every item must be tried)

- [ ] Speaker: with no headset plugged in, the default loudspeaker works normally, without echo explosions in either direction
- [ ] Earpiece: holding it to the ear sounds normal (if there is a hands-free toggle, switching over and back both work normally)
- [ ] Wired headset: plugged in before connecting = sound comes straight from the headset; plugging in mid-call switches to the headset automatically; unplugging switches back automatically
- [ ] Bluetooth headset: connecting switches to Bluetooth automatically; disconnecting switches back automatically
- [ ] Hands-free button: both on and off genuinely change the output device (the selected state matches the system's actual device)
- [ ] After hanging up the audio mode is restored (music/video volume and output path for loudspeaker playback return to what they were before the call)

## 3. Interruption and echo

- [ ] The user cuts in while the assistant is speaking → the assistant stops immediately and the old audio no longer resurfaces
- [ ] Interrupt 5 times in a row, and never once does "the second half of an old sentence suddenly play"
- [ ] Quiet room: the assistant's speech misdetected by VAD as user transcription → triggers no execution at all (echo guard)
- [ ] Room at maximum loudspeaker volume: same as above, holds

## 4. Interruptions and abnormal cases

- [ ] Incoming call (MODE_IN_CALL/RINGTONE) → the call ends with "Incoming call" and explains why
- [ ] Another app grabs audio focus (e.g. plays a video) → degrade or end as designed, with no double-track overlap
- [ ] Network lost → falls to "Connection interrupted", retrying in place reconnects and attempt increments
- [ ] 10-minute cap → wraps up when the time is up and shows the reason, without automatic retry
- [ ] Screen locked: the call continues (foreground service keeps it alive); **locking the screen does not pop the call page up by itself**
- [ ] System mute / microphone taken by another app → shows wording such as "No microphone input", not a silent hang
- [ ] Backgrounding and coming back: page state, timer and status line are all still there
- [ ] Rotation: the call **does not drop** (MainActivity already declares configChanges), and the page layout is normal
- [ ] System dark/light switching, font scaling, split screen: the main interface redraws immediately and correctly (a whole-app behavior change; confirm in passing)

## 5. The full voice task-execution chain

- [ ] Say "wake me up at eight tomorrow morning" → the status line scrolls progress → the alarm really gets set → the assistant repeats the result
- [ ] During the task the status line has at most 12 lines and scrolling stays legible
- [ ] **The status line contains no private details**: no coordinates, calendar titles, contact names or specific text content
- [ ] The task needs user confirmation/takeover (e.g. needs a password, needs a person to act) → prompts "go look at the task page", without pretending it is done
- [ ] Interrupting mid-task → tasks that were started but not finished are cancelled (operations already in effect are not claimed to be undone)
- [ ] Panel text input box: typing and sending goes the same way as speaking
- [ ] After the call ends, both sides' text **appears in the conversation that started the call** (question/answer/time), and is written only once

## 6. Guarding against wrong execution (safety gates; all of these must not take effect)

- [ ] The model fabricates a `function_call` (with no complete user transcription in that round) → not executed
- [ ] Replaying the same `call_id` across rounds → not executed
- [ ] A `function_call` whose parameters do not match that round's transcription → not executed, and that transcription is not consumed
- [ ] The assistant's own voice taken as a user instruction → not executed
- [ ] One complete utterance is executed only once (no duplicate orders/duplicate messages)

## 7. Failures and gates

- [ ] Credentials not configured: the call page shows "Configure real-time voice first" + a go-to-configure button, which opens the configuration page when tapped
- [ ] Microphone permission not granted: a button in the page requests permission; after a refusal it changes to "Go to settings", and coming back from system settings connects directly
- [ ] The provider returns a rate-limit/auth error: each has its own wording, auth ones guide the user to configuration, rate-limit ones can be retried

## Record

| Item | Model | Android version | Result | Notes |
|---|---|---|---|---|
|  |  |  |  |  |
