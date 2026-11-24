# do_each adoption rate: same-shape loop nudge + few-shot + guard dead-code cleanup

Date: 2026-11-21
Status: awaiting approval

## Intent and why

Measured cost structure: the money is in "turns × history length + reading the screen", and turns are the biggest lever. do_each (run the same block of steps once per item in a list, compressing N turns into 1) is currently the only ready-made handle on this lever, yet real-device adoption was 0/3 — in the cases where it should be used, the model still went observe→tap→back item by item.

Root-cause analysis (2026-11-21 session, evidence is in the code):

1. **The mid-run nudge path is dead code**: `OperationGuards.arrived()` / `cycle()` have had zero calls since the first public commit (093965fcd), and `trail` / `visits` were never written. The only copy that would suggest do_each while the model works item by item (`guard.same_state`, feedback/tools.md) therefore never fires. Three companion test names (such as `returningToTheSameScreenWithoutAnythingNewNudgesAtThree`) claim it triggers, but the assertions are `isEmpty()`.
2. **Decision dynamics work against spontaneous adoption**: under turn-by-turn decisions, tapping the first item is the local optimum; do_each requires handing over the recognition rule + a multi-step body + trust in an engine that has never been used, all at once; after doing 1~2 items by hand, the observe→tap→back pattern is already established in the context.
3. **The main model's decision window is narrow**: `ModelRouter` assigns simple steps with step>1 to the lightweight model, and a weak model is even less able to construct a valid composite call.

## Goals

- When working item by item (open details → act → go back) repeats ≥3 times, the model receives one explicit do_each suggestion — delivered at the mechanism level, not left to the model to remember on its own.
- The do_each tool description carries a realistic call example, lowering the bar for the first construction.
- same_state / state_cycle dead code is revived (rather than deleted), and test names and assertions agree again.

## Non-goals

- Not changing the do_each engine itself (EachRunner / EachCursor / ScrollCursor).
- Not changing the lightweight-model routing table (DoEach stays non-main-only — a lightweight do_each call does not get blocked back).
- No automatic triggering: the suggestion is the model's call, and the engine does not turn item-by-item operations into do_each for it.
- Not chasing 100% adoption; the reminder is a nudge, and the model is free to keep going item by item.

## Anti-goals

- Spamming reminders: a normal two-screen back-and-forth (comparing two pages) and scrolling a feed (observe→scroll) must not trigger; each task gets at most one reminder.
- Prompt bloat: the prompts were just slimmed down, so the few-shot increment must be small (target <200 tokens).
- Any app special case: detection looks only at the shape of the verb sequence and the UI state hash.
- No more tests that do not live up to their names.

## Constraints

- Prefix cache: the reminder is appended only as a new inter-turn message, and no already-sent message is changed (same as all notices).
- Generality: aimed at generic AOSP behavior, capability-based detection, no brand special cases.
- Fix the category, not the individual case: the three tests such as threeStateLoopIsCaught all get their real semantics back, not just an assertion tweak to turn them green.

## Approach

### A. Same-shape loop detection → do_each suggestion (the main fix)

New class `EachLoopDetector` (core/agent), a pure state machine, one instance per task:

- **Input**: at the end of each AgentLoop turn, feed `(verb, ok)` (hooked in near the existing `track.after(...)`).
- **Turns that count**: one on-screen verb per turn. When a do_steps batch, do_each, phone_run, a composite tool or a non-screen verb (sandbox/memory/follow-up question, etc.) appears: reset the sequence and do not count this turn — they are alternatives or interruptions by nature.
- **Detection**: maintain the recent verb sequence and detect a repeated motif of length 2–5:
  - full repetition ≥3 times (the last 3×L verbs are L-periodic, L∈[2,5])
  - the motif contains exactly one `Back` and at least one `Tap` — the signature of "open details → act → go back"
  - a turn with `ok=false` breaks the consecutive count
  - no trigger if this task's model has already used do_each
- **False-positive protection**: repetition without `Back` (observe→scroll through a feed) never triggers; the motif of a two-screen comparison (A B A B) contains no Back and does not trigger either.
- **After triggering**: once per task. It produces a reminder, and AgentLoop appends it to the history at the end of the turn via `ctx.notice(source, text)` (the same delivery mechanism as `TurnPolicy.unconfirmed`, append-only).
- **Copy**: a new feedback key `loop.repeating_items` (prompts/feedback/loop.md): state the detected pattern clearly ("the last N steps repeat open→act→back"), suggest putting the remaining items into a single do_each, and give a minimal item/body hint (pointing at the tool description instead of repeating its full text).
- **Along the way**: when injecting the suggestion, call `track.markRecovery()` — the next step is forced back to the main model so the strong model makes this batch decision (addressing root cause 3, reusing the existing "recovery step" semantics, zero new mechanism).

### B. few-shot (inside the tool description)

`prompts/tools/do_each.md`: replace the existing one-line example with a realistic 4-step body (open → wait for details to load → act → go back), with the scenario written generically as "operate a list item by item". Add it only in this one place, the tool description; do not touch capabilities/batch.md and do not duplicate it in two places. Keep the increment within 200 tokens.

### C. Reviving same_state / state_cycle

Revive rather than delete: the two channels split the work — A looks at verb shape and is precisely responsible for the list item-by-item scenario (covering both isomorphic and non-isomorphic detail pages); C looks at the UI state hash and is responsible for "irregular revisits" (A→B→C→A→D→A style getting lost) and periodic circling.

- **`frame()` wiring**: when the new frame hash differs from the previous one (and frameId changes):
  - append that hash to `trail` (append only if adjacent values differ, capped at TRAIL_KEEP=64)
  - the hash is already in `seenStates` (back to an old state) → `out += arrived(hash)`
  - then `out += cycle()`
- **Keep the gates as they are (the builder check decision)**: we considered relaxing the cycle gate of `arrived()` so list scenarios also go through same_state, and rejected it — `cycle()` is already non-empty for a single round trip (rounds=1), so in the list scenario it is suppressed every time the flow returns to the list screen; if the gate were relaxed to "suppress only once rounds reach the reminder level", a 3-cycle loop would emit 3 same_state reminders before state_cycle got its turn, violating the anti-goal of spamming reminders. Keep "when circling, cycle does the talking": same_state covers irregular revisits, state_cycle covers periodic loops, and channel A covers lists.
- **cycleLadder first level 2→3**: A B A B (2 rounds) is a legitimate two-screen comparison, and a first level of 2 is too sensitive; raise it to 3 (only on A B A B A B, three round trips, is there a reminder); levels 4 and stop stay unchanged. sameStateLadder [3,6,stop] is unchanged.
- **Tests get their real semantics back**:
  - `returningToTheSameScreenWithoutAnythingNewNudgesAtThree`: use an irregular-revisit sequence (returning to the same screen through a different new page each time) and assert that on the 3rd arrival same_state emits Warn and the reminder carries a do_each suggestion.
  - `threeStateLoopIsCaught`: 1→2→3 for three full cycles, assert state_cycle Warn (and no same_state throughout — periodic loops are spoken for by the cycle channel alone).
  - `cycleStopFollowsTheStepBudget`: budget 30 → stop level max(6, 30/5)=6 cycles, assert that stop appears at the corresponding frame count.
  - `backAndForthBetweenTwoStatesDoesNotStopProgress`: 3 frames, 2 rounds < 3; after raising the first level, assert that empty still holds — kept as it is, and true to its name.

### One option not taken (recorded for reference)

- "Relax the cycle gate of arrived() so list scenarios go through same_state": every return to the list screen is suppressed as half a cycle with rounds=1, and relaxing it would make a 3-cycle loop emit 3 same_state reminders, violating the anti-spam goal; the list scenario is channel A's job, so this was dropped.
- "Follow the old comment's semantics (reset visits whenever a new state is seen)": that would make same_state almost never trigger, so reviving it would lose its point; dropped.

## Verification

- JVM: `:core:test` all green — guard tests rewritten + EachLoopDetector unit tests:
  - a typical list pattern ([Observe,Tap,Back]×3, [Tap,Back]×3) triggers once
  - observe→scroll through a feed does not trigger; a two-screen comparison does not trigger
  - a failed turn breaks the count; after one trigger it does not repeat; no trigger once the model has used do_each
  - do_steps/composite tools appearing are not counted and do not misfire
- Full regression: `./gradlew :app:testDebugUnitTest` (note: bare `:app:test` OOMs).
- Real device (needs the user present): re-test 3 list-type tasks with LiveRun — the `loop.repeating_items` reminder appears in the record; adoption target ≥1/3 (the model starts a do_each call after the reminder). Real-device results go back into this document.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| The reminder comes a little late (only on the 3rd repetition) | Acceptable: the first 2~3 items are the necessary stage for the model to understand the list; an earlier reminder would instead interrupt normal exploration |
| The lightweight model still cannot construct a valid do_each after the reminder | markRecovery when injecting the suggestion, and the next step is forced to the main model |
| few-shot adds tokens | only one place, the tool description; 200-token cap |
| After reviving cycle, false positives on normal two-screen operations | first level 2→3; the warn level only reminds, it does not stop |
| The model still ignores the reminder once it is in the history | that is exactly what a nudge is; the real-device adoption re-test will give real data before deciding whether to escalate |
