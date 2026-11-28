# In-app real-time voice call · on-device acceptance checklist

Date: 2026-11-22
Scope: the `worktree-voice-call` branch (design in `docs/design/specs/2026-11-22-voice-call-design.md`).
How to use: run through each item on a real device; tick `[x]` when it passes, and record the symptom plus model/Android version when it fails. **Any single failure means this version does not pass acceptance.**

Preconditions: real-time voice credentials configured in Settings (the same ones as for AI phone calls); microphone permission granted.

## 1. Connecting and the basic chain

- [ ] The earpiece button next to the input field's microphone opens the call page; the capsule menu item "Voice chat" opens it too
- [ ] Connecting: the orb is in mesh form → turns into a green "Connected" dot and the timer starts
- [ ] The first session plays a greeting, and **only once** (it does not play again within the same call)
- [ ] Two local "ding-dong" chime tones sound before the greeting
- [ ] Speaking produces a waveform response (the orb becomes a wave form)
- [ ] While the assistant speaks, the orb pulses with its voice
- [ ] Hang up: the red hang-up button → settles on "Ended", the timer stops at the final duration, the input/hands-free/hang-up controls at the bottom disappear, leaving only Collapse at the top left
- [ ] Tapping Collapse or pressing Back → returns to the previous page; the notification shade still has a call notification (with hang up) and the call continues; every page has a green bar at the top, "In call · Back to call" + timer, and tapping it returns to the call page
- [ ] After collapsing, pressing Back again does not bounce back to the call page
- [ ] Hang up from the notification shade → the call ends, and the page is dismissed too if it is open

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

- [ ] Incoming call (MODE_IN_CALL/RINGTONE) → the call ends with "Incoming call" and an explanation
- [ ] Another app takes audio focus (e.g. plays a video) → degrades or ends as designed, with no doubled audio from two paths
- [ ] Network drops → settles on "Connection interrupted"; retrying in place reconnects and the attempt count increases
- [ ] Silent hang-up (set to 2 minutes) → stay silent: around second 90 Claire asks whether you are still there; keep ignoring it, and at the 2-minute mark the call hangs up and shows "No activity for 2 minutes; the call has ended", with no automatic retry; saying something, typing or task progress in between resets the timer
- [ ] Lock screen: the call continues (kept alive by the foreground service); **the lock screen does not pop up the call page by itself**
- [ ] System muted / microphone taken by another app → shows "no microphone input" style text instead of silently getting stuck
- [ ] Go to the background and come back: the page state, timer and status line are all still there
- [ ] Rotation: the call **does not drop** (MainActivity already declares configChanges), and the page layout is normal
- [ ] System dark/light switch, font scaling, split screen: the main UI redraws immediately and correctly (an app-wide behaviour change, confirmed along the way)

## 5. The full voice task-execution chain

- [ ] Say "wake me up at eight tomorrow morning" → the status line scrolls progress → the alarm is really set → the assistant repeats the result
- [ ] During work the status line has at most 12 lines, and scrolling stays crisp
- [ ] **The status line contains no private details**: no coordinates, calendar titles, contact names or specific text content
- [ ] The task needs user confirmation/takeover (e.g. a password or a human action) → prompts "check the task page" instead of pretending it is done
- [ ] Interrupt during work → started but unfinished tasks are cancelled (operations that already took effect are not claimed to be undone)
- [ ] Panel text input field: typed messages go out the same way as speech
- [ ] After each completed round in the call, the chat page gains a round (question/answer/time), with no duplicate writes after hang-up
- [ ] In the chat page, this section is bounded by two lines of small light text, "Voice call · time" and "Call ended · duration"; if the call was hung up without saying anything, there is only one line, "Voice call · duration · no speech"
- [ ] Reopening the call page is blank and shows only this call; tapping "Last call · time ›" at the top returns to that section in the chat
- [ ] The ⋯ at the top right of the call page → "Call history" lists every call in this conversation; tapping one returns to the start of that call in the chat and briefly highlights it; ⋯ → "Call settings" opens the call method
- [ ] Tapping a task card on the call page opens the task timeline; Back returns to the call page

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
