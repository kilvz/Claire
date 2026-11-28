# Front desk (chat / voice call / AI phone) · device acceptance record

Date: 2026-11-23
Scope: the `front-desk` branch (design in `docs/design/specs/2026-11-23-front-desk-design.md`, plan in `docs/design/plans/2026-11-23-front-desk.md` Task 11).
How to use: what can run automatically is run on a device by `LiveRunTest#runDesk` and the result is ticked `[x]`; items that require a person to speak or a real incoming call are marked "awaiting device", with the steps written down for the owner. **Any single failure means this version does not pass acceptance.**

Test environment: an Android 14 test phone; the perf package is installed (like the release build, non-debuggable and precompiled: `./gradlew :app:assemblePerf` → `adb install -r` → `adb shell cmd package compile -f -m speed com.wangzan101.mika`). Foreground and background tasks use the main model already configured on the phone (the light model is set to "same as main model"); the task parallel switch is off, so the parallel limit is 1.

Safety constraint: on the device only dispatch harmless, reversible work (open a settings page, check the weather, open the calculator and take a look); do not send messages, call real people, pay money, delete anything, or change the account and system settings.

## How to run

```bash
# Install the test package (do not use connectedAndroidTest: it uninstalls the app and wipes the owner's data)
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# One scenario: separate the deskScript steps with ||
#   say:<text>        type and send on the chat page, wait for the front desk to finish this round
#   wait:running|queued|needsyou|idle|tasks=N|report
#   home            go to the home screen (later reports should take the fallback: write back to the conversation + notification)
#   sleep:<seconds> / notifications
adb shell am instrument -w -r -e class com.wangzan101.mika.acceptance.LiveRunTest#runDesk \
  -e deskRun desk-chat -e deskWaitSec 240 [-e deskParallelMax 1] \
  -e deskScript "'say:...||wait:running||...'" \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
# Results: result.txt, log.txt and per-step screenshots step-NN.png under acceptance/<deskRun>/ in the app's external files directory
```

`result.txt` records `firstWordMs` (from sending to the first word), `firstTaskMs` (from sending to dispatching the first job), the reply, what was dispatched, and tool chips for each round; `saved*` are the rounds stored for this conversation (a fallback write-back report is `desk_report`).

## 1. Chat page

- [x] Dispatch a job that opens an app ("open system settings and tell me the names of the first 2 menu items on the settings home page"): the front desk replies, posts a task card, and the card says "in progress"
  - Time to first word: 2.4–3.4 s for dispatch rounds (2.5 s that time for this item; 4.3 s in one run before the fix); the design goal is to start speaking within 1–2 seconds for text (a target, not a hard threshold), and **the dispatch round exceeds it**: the front-desk model first sends the dispatch tool call and only produces text after getting the result; this round trip takes about 1.6–2.5 s on the current main model. Rounds that only reply without dispatching take 1.2–2.1 s, within the target.
  - Conclusion: **pass (accepted)**. The dispatch round's time to first word exceeds the target; accepted against the target value rather than a threshold, see "Problems found" 4.
- [x] While a task is running, ask a question that needs the internet ("also check today's weather in Beijing for me"): the front desk dispatches it, says "queued / checking", and the second card says "queued · #1"
- [x] Ask "how is that job going": the front desk answers from the task board (reading / weather queued at #1), **without calling any tool**
- [x] Say "change it to 3": the front desk adds it to the running job, and a small chip above the reply says "added to 'check the first two items on the settings home page'"; the task finally reports 3 items
  - Note: the chip text is "added to '<task title>'", while the plan says "added"; the meaning is the same.
- [x] Dispatch two jobs at once (parallel limit 1, `deskParallelMax=1`): the second shows "queued · #1" and the front desk says "waiting in first place"; when the first finishes the second starts automatically, and both succeed
- [x] The background needs an answer ("open one of the phone's built-in utility apps and take a look; which one to open, ask me first after starting"): the card changes to "needs you", and the front desk asks in the chat "which one do you want to open?"; after replying "calculator" the front desk relays it to the task (chip "replied '…'"), and the task opens the calculator and reports back
- [x] The task finishes while the page is not open (gone to the home screen): the report is written back to the conversation (stored as one `desk_report` line) and a notification is sent (Ruling H)
- [x] While the page is not open, **only one notification** per finished task (the front desk's); the queued job **sends no notification** when it starts ("started" is only written back to the conversation); the notification body is plain text without markers such as `**` (see "Problems found" 2 and 3; retested desk-notify3 after judging by run)
- [x] While the page is not open, the background stops to ask the user (same sentence as "the background needs an answer" above, then go to the home screen): the notification shade has only the task's own "needs your answer" (with a reply box and two option buttons "calculator" and "clock"); the front desk's question is only written back to the conversation (one `desk_report` line "asking you: …"), **with no second notification** (see "Problems found" 6, retested desk-ask-away)
- [x] Retest after the fix: at the moment a job has just been dispatched and the task service has not taken over yet, the card no longer flashes "queued", and the front desk no longer says "still queued" (see "Problems found" 1)

## 2. Voice call · standard mode (listen → think → speak)

**Awaiting device (requires the owner to be present and speak).** On a device nobody can speak for the owner; the chain below is covered by unit tests (`VoiceDeskBridgeTest`: dispatch returns immediately, two jobs run at once, asks in the call when the background needs an answer and relays it, the terminal state is said only once, after hang-up it goes to the fallback; `CascadeVoiceSessionTest`, `VoiceCallSessionTest`: barge-in only interrupts playback; the UI was verified 12/12 on a device by `VoiceCallPageTest`).

Steps:
1. First choose "Standard" in "Settings › Model › Voice › Call method" (listen, think and speak each use the selected models), then enter the call page from the earpiece button next to the chat-page input field and wait for "Connected".
2. Say "open settings and check the Android version number".
   - [ ] The front desk starts speaking within 2–3 seconds and says it has dispatched the job; a task card appears on the call page.
3. While the task is running, continue "how is the weather in Beijing today".
   - [ ] The front desk replies as usual (dispatches a second job or says it is checking), without getting stuck or waiting for the first job.
4. While the front desk is speaking, barge in with "wait a moment".
   - [ ] The front desk stops playback immediately; both tasks are **not cancelled** (the cards are still in progress).
5. Wait for the tasks to finish.
   - [ ] The result is **said only once** in the call.
6. Dispatch another job (e.g. "open the calculator and take a look"), and hang up immediately after saying it.
   - [ ] After hanging up the task continues; when it finishes the result is written back to the conversation that started the call (one `desk_report` line) and a notification is received.

## 3. Voice call · real-time mode (when real-time configuration exists)

**Awaiting device (requires the owner to be present and speak; real-time voice must be configured on the phone first).** The steps are exactly the same as section 2, except that step 1 changes the selection in "Settings › Model › Voice › Call method" to "Real-time (advanced)". Also check one item (Ruling P): when the real-time protocol has no prompt input, it relies on the model calling the tool again to fetch the result —
- [ ] When the background needs an answer, the model asks it in the call; after the owner answers, the task can continue promptly (note the approximate number of seconds from the answer to the task continuing).
- Pass: every item in steps 2–6 of section 2 holds in real-time mode, and in the item above the model asked the question and the task continued after the owner answered (record the seconds as they are).

## 4. AI phone

**Awaiting device (requires the owner to be present; do not dial real numbers).** Automatic acceptance neither dials nor answers any real call. The chain is covered by unit tests: `PhoneDeskTest` (only enables ask_owner, its request returns immediately, the owner's reply is passed to the model as a prompt instead of being read out verbatim, a pending request is withdrawn when the call ends, and when there is no prompt input it waits for the next request to fetch the result) and `OwnerQuestionsTest` (the request lands in the owner's conversation, a reply in the notification reply box is recorded in the conversation, and it can be answered even with only the notification).

Steps (requires the owner's own second phone or a trusted acquaintance to play the "other party"):
1. Use AI phone to call your own second phone (or have it call in); after connecting, the "other party" asks a question that only the owner can decide, e.g. "are you free at 3 p.m. tomorrow?".
   - [ ] The owner's phone receives a notification with a reply box, with the original question inside; the request is also in the corresponding conversation.
2. Reply "yes" in the notification's reply box.
   - [ ] In the call, Claire relays it to the other party in her own words (not verbatim), and the other party can hear it.
3. Reply once more in the conversation.
   - [ ] A request that has already been answered is not relayed again.
4. Have the "other party" ask another question that only the owner can decide (e.g. "what about the day after tomorrow?"), and this time the owner does not reply and hangs up directly.
   - [ ] The notification for the second request pops up as usual (the answered first one is no longer in the notification shade).
   - [ ] After hanging up, this unanswered request is withdrawn: its notification disappears, and the conversation gains a line "The call has ended; this question has been withdrawn, no need to reply again."; replying later in the conversation is not relayed into any call.

## 5. Smoke subset

The plan says "per the release-smoke-test convention", but no convention with that name can be found in the repository; run the existing smoke set `eval-smoke` (`EvalRunTest -e evalSet eval-smoke`):

- [x] 2/2 succeeded (`smoke-settings-version` and `smoke-impossible-menu` both wrapped up as expected), with no new failures; false successes 0.

## Problems found

1. **(Fixed) A freshly dispatched task first shows "queued", and the front desk says "still queued".** Before the task service takes over, the state is `Idle`, and the task board treats `Idle` as queued; the dispatch result is actually "started". The card flashes "queued" and the front desk also says "the task is still queued" when answering from the task board. Fix: when `FrontDesk` updates the task board, a task that is `Idle` and whose port says it is not in the start queue (`queuePosition == null`) is recorded as in progress; genuinely queued ones (the port gave a position) stay as before. Unit tests `FrontDeskTest.startingTaskIsNotShownAsQueued` / `idleTaskWithAQueuePositionIsQueued`.
2. **(Fixed) While the page is not open, the same finished task received two notifications, and the queued job also sent a "started" notification when it began.** The front desk is the only voice of the run it is following at the moment:
   - A task sends its own "task complete" only when exactly one front desk is currently following **this run** and will say the outcome. The decision is in `DeskRegistry.reportsEnding`: the port identifies the run it follows by run ID (`followedTaskOf`), then checks whether any of the remaining front desks follows this task (`FrontDesk.tracks`); a front desk the user cancelled themselves does not speak (`ReportPolicy.saysEnding`), and the task still sends. The task service asks through the predicate `MikaApp.deskReportsEnding`; the task package does not reference the front-desk package.
   - Otherwise the task sends its own as before: another job started by typing directly after wrap-up in a child conversation, a result delivered after the process was gone (`restored`), a deleted front-desk conversation, or a task not dispatched by the front desk.
   - The first version judged by the `deskParent` marker on the child conversation, which also flattened the cases above to zero notifications; after review pointed this out, it was changed to judge by run.
   - In the fallback path, start, queue and progress are only written back to the conversation and send no notification; the decision is only in `ReportPolicy.notifies`.
   - Retests (desk-notify2, after the first version of the fix; desk-notify3, after judging by run; two jobs, limit 1, second queued, go to the home screen): the notification shade has only two, one per job; the second job's "started" appears only in the conversation.
3. **(Fixed) Fallback notifications contained Markdown markers.** The notification body now uses the app's existing `MarkdownModel.toPlainText` to strip markers; the line written back to the conversation keeps the original text, and the chat page renders it as usual. Retest notification body: "…the number shown is 405.".
4. **(Known gap, accepted) The dispatch round's time to first word is slow.** Dispatch rounds take 2.4–3.4 s against a target of 1–2 s; see section 1. The design treats it as a target, not a hard threshold, and the item is recorded as passing (accepted).
5. **(Unrelated to this branch) `ChatAttachmentsTest.rejectedAttachmentsShowTheirIndividualReasons` already fails on main.** #797 changed the attachment limit text from "200 MiB" to a tier-based "200 MB", and the test still asserts "MiB". It is the one failure in the chat package's 65/66 device tests.
6. **(Fixed) While the page is not open, when the background needs the user to answer, confirm or take over, two notifications were received.** The task sends its own "needs your action" with a reply box or confirm/takeover buttons, and the front-desk fallback sent another "asking you…". Changed to keep the task's notification (the confirmation and takeover safety flow and buttons are on it) and only write the front desk's question back to the conversation. When preconditions are not met the task sends no extra reminder, and the front desk still sends this one. The decision is in `ReportPolicy.notifies`. Retest desk-ask-away: only the task's "needs your answer".

## Record

| Item | Model | Android version | Result | Notes |
|---|---|---|---|---|
| Chat page, 10 items | Android 14 test phone | 14 | Pass | Dispatch round time to first word 2.4–3.4 s (target 1–2 s, accepted); reply-only rounds 1.2–2.1 s |
| Smoke subset | Android 14 test phone | 14 | Pass | eval-smoke 2/2 |
| Voice call · standard | | | Awaiting device | Requires the owner to speak |
| Voice call · real-time | | | Awaiting device | Requires the owner to speak and real-time configuration |
| AI phone | | | Awaiting device | Requires the owner present; no real numbers dialed |
