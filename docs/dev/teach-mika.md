# Teaching Mika

A human demonstration is the input, and the product is a **knowledge skill awaiting confirmation**: applicable scenarios, entry clues and preconditions, strategy, the reasons the user explains, and what can be seen on success. No coordinate macros are recorded, and a demonstration is not replayed as step-by-step taps.

Kept separate from "learning from completed tasks". `RunLearning` / `LearnGate` / experience routes only digest runs Mika did itself. Demonstration capture does not write into that path, nor does it change route replay.

## Entry

The name is 「教 Mika」 (Teach Mika).

- In the panel opened by 「＋」 in the chat input bar.
- On the idle card after the floating capsule is expanded. The entry is tucked into the arrow above the input bar and collapsed by default; only tapping the arrow makes 「教 Mika」 appear, and tapping again collapses it. The 「正在学习」 (Learning) overlay during learning is not here, and pausing or ending does not collapse it.

The entry for when a task is stuck is not in this version. A demonstration cannot be started while a task is running; while a demonstration is in progress (capturing or paused), no new task is started, and the next queued item stays in the queue and is not taken out to run.

## Capture

Before starting, write or confirm one sentence of the goal. Text in the input box that has not been sent can be pre-filled, but it cannot guess a sentence and start on its own. The first screen explains: steps stay only on this phone; when generating the draft, if a model is configured, a **desensitized summary** is sent to the current model. Without a model it is only organized locally. Do not say "stays on the phone" as "does not use the network".

Capture only after the user taps 「开始示范」 (Start demonstration). During capture Mika does not operate the phone. There is an accessibility overlay at the top of the screen showing 「正在学习」 or 「已暂停」 (Paused), with pause, resume and end on it. It uses the same kind of window as the floating capsule, and does not request notification permission or 「显示在其他应用上层」 (Display over other apps). A notification may exist, but it is not the only way out; without notification permission it can still stop. If this overlay cannot be added, recording does not start. If the overlay disappears midway, or accessibility is turned off, the demonstration becomes stopped, no longer records, and states the reason. Nothing is captured while paused, not yet started, or while the app restarts. After a restart, if it was capturing or paused last time, it is recorded as stopped, and the user must tap resume again before capture happens; resuming likewise requires adding this overlay first. When switching to another app or returning to Mika the overlay is still there; it does not rely on the UI staying on one page.

Events come from the clicks, long presses, text changes, scrolls and window switches already available in accessibility. Apps disabled in app access are not recorded. Consecutive scrolls, consecutive text changes in the same input box, and repeated clicks within a short time are merged. It is not assumed that one event is one action. Full-screen images, recordings and full screen text are not saved.

Passwords, verification codes, card numbers etc. are not written into steps, logs or skills; goals or corrections that hit the sensitive filter are not written to disk either. Steps without reliable nodes are marked as insufficient information, and coordinates are not invented. Mika's own UI events are dropped to avoid the overlay polluting the trajectory. An entry that was not reached once cannot be written as "does not exist".

## Review and draft

After ending the demonstration you can change key steps and delete accidental touches. Only when the information is insufficient, what was tapped cannot be explained, and the window cannot be recognized either, is one sentence of supplementation required. Sources are divided into observed, stated by the user, and speculation marked by the model.

The draft goes through the existing five sections and `SkillDrafter.validate`, with `source: demonstrated`, and its status is awaiting confirmation. `skill_save` is not called, so before accepting there is nothing in the directory and the model cannot read it. After accepting it is still a trial: the body states that it has not yet been trial-run successfully; when the current UI shows no entry or precondition, it stops applying it and falls back to ordinary viewing and asking.

After giving up, rejecting the draft, deleting the skill, or accepting it, the local trajectory is always deleted. Only the merged steps are kept in the trajectory, not the raw events.

## Not in this version

It does not automatically replay before saving, so it will not pay, send or place an order again just to verify. It does not write unverified as a successful trial run.
