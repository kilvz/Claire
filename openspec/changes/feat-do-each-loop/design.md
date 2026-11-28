# Design: cross-screen loop primitive `do_each`

## Context

Current batch capability matrix (exploration conclusions):

| Tool | Surface | Shape | Can loop? |
|---|---|---|---|
| `do_steps` | Single screen | Linear batch (with expect conditions) | No (explicitly same screen) |
| `phone_run` | Cross-screen | Linear sequence (30 commands, re-finding the target per command) | No |
| `web_script` | Web page | A whole JS program | Yes (high bar: writing a program) |
| `phone_wait` / `web_wait_for` | Any screen | Conditional wait | — (saves polling, but neglected) |

Real-record evidence (`20261114-223716-79pbvk` "watch ads for rewards"): 35 turns = 3 ads × (waiting/polling ~5 + closing ×3 + claiming ×1); tool distribution `wait` 9, `phone_observe` 8, `phone_tap` 12, `phone_wait` 2, `do_steps` 0, `phone_run` 0; input tokens 15K→148K (history O(N²), 95% cached).

The gap to fill: the phone side has no "cross-screen loop" shape; and the decision layer has no "repetition → batch" teaching at all, instead being pulled the opposite way by `effect`/`guard`.

## Goals / Non-Goals

**Goals:**

- One `do_each` primitive, surface-agnostic, expressing cross-screen loops like "open→act→return ×N".
- Declarative and low-barrier — a step array like `do_steps`, not a JS program.
- The loop body reuses `do_steps`'s step semantics (expect conditional waits, local validation, stop on failure, controlled actions going through the gate).
- Decision layer: teach "repetition → do_each" at the wording level, without touching the runtime yet.

**Non-Goals:**

- No runtime repetition detection / planning phase (a future direction; this design only leaves the summary shape, without adding detection logic).
- `web_script` is unchanged (kept as the escape hatch for arbitrary programs do_each cannot cover).
- No parallel subagent version of "one agent per item" (that is a subagent-parallelism problem, orthogonal).

## Decisions

### D1: The loop structure is surface-agnostic; only item location and action execution are dispatched

`do_each` is one tool (`Verb.DoEach`), mounted to both the phone and web tool groups. The loop engine (find next → skip done → execute body → decide termination → summarize) is fully shared; two points dispatch by surface:

- **item location**: on phone, match on the a11y tree (role/text/resourceId + optional container); on web, match on the DOM (cssSelector/role/text).
- **action execution**: goes through the existing `ActionExecutor` (`phone_tap`/`web_tap` already share the execution and recording path).

The `mixed_surface` decision follows `do_steps`'s: the item and body within one `do_each` must be on the same surface; mixed surfaces are rejected outright.

### D2: The loop head = an explicit `@item` in the body, not an implicit "open"

The loop body is a **complete step sequence for a single item**, whose first step usually "opens the current item", using the special reference `@item` to denote the item located this round; the last step "return to the list" is written by the model itself in the body (usually `back`). This is more flexible than "engine implicitly opens + implicitly returns":

- "Open→greet→return": `body = [ tap @item, tap "Greet", back ]` — tap the item, tap the Greet button, then go back.
- "In-place loop" (scrolling collection, batch mark-as-read, never leaving the list): the `body` uses neither `@item` nor `back`, and expresses the same thing.

**Rejected alternative**: engine-implicit open/return — it removes the template for the `@item` step, but closes off the "some loops don't open/don't return" variants, and would need an extra `navigate` field to toggle, ending up more verbose.

### D3: Parameter shape (draft, to be tightened after the prototype)

```
do_each(
  item: ItemPattern,        // {role?, text?, resourceId?, cssSelector?} fields taken per surface
  body: [Step],             // steps for each item; may use @item
  until?: Until,            // {count: N} | {text: ...} | default: "no more items and reveal to the end"
  skip?: Skip,              // {text_matches: ...} skip matched items
  reveal?: "scroll"|"none", // when no undone item is found on the current screen, scroll to find more (list tasks default to scroll)
  max_items?: N,            // safety cap (defaults to the per-call resource cap)
)
```

`Step` reuses the step shape of `do_steps`/`phone_run` (tap/type/scroll/swipe/key/back/wait/assert/observe) and carries `expect`/`expect_gone`/`wait_stable` conditional waits — so "wait for the ad to finish and the claim button to appear" is one conditional wait inside the body instead of 14 polls from outside.

### D4: Per-round guard and a compact summary

Each loop body goes through the normal execution and recording path, reusing `BatchRunner`'s stop rules: body step failure, app switch, needing visual review, expect unsatisfied, until hit — all stop, recording which item and which step. **Key**: what the model gets at the end is a **compact summary**, not step-by-step screenshots:

```
{ done: 3, failed: 0, skipped: 0, stopped_at: null,
  per_item: [ {i:1, ok, note:"greeted"}, {i:2, ok, ...}, {i:3, ok} ] }
```

The model reads back the outcomes of N items in a single call, compressing N rounds into 1 — exactly the token payoff of "think once, execute many times".

### D5: Boundary with existing tools (one-sentence criterion taught to the model)

| Scenario | Use |
|---|---|
| A fixed few steps within one screen (fill a form) | `do_steps` |
| A fixed route across several screens, walked once | `phone_run` |
| **The same action on multiple items (open→act→return)** | **`do_each`** |
| Arbitrary program / data transforms / multiple tabs (web only) | `web_script` (escape hatch) |

`do_each` does not replace `do_steps`/`phone_run`; those are "linear" while `do_each` is "looping". `web_script` stays long-term, but once `do_each` covers ninety percent of its "click each one" uses, it degrades into a rarely used arbitrary-program hatch.

### D6: The decision layer changes wording only (no runtime changes)

1. `capabilities/batch.md` gains: "When the same action must be applied to multiple items (open each one → act → return, e.g. greeting one by one, claiming rewards one by one), use `do_each` to finish it in one go instead of one item per turn."
2. The `guard.same_state` exemption in `feedback/loop.md` changes from "If you are deliberately going through items one by one, carry on." to "…going through items one by one, **put the remaining items in one do_each call instead of doing them one at a time**."
3. New `tools/do_each.md` with a real outreach-type example (open→greet→return ×N).
4. No runtime changes: no new "3 consecutive identical actions" detection, no new planning phase.

## Risks / Trade-offs

- **"Find the next one, skip the ones done" is the engine's hardest part** (see Q1): refs change every time, so dedup must rely on stable keys (text/resourceId/position), and identically named items are ambiguous. Get the prototype working on this point before finalizing.
- **Failure semantics**: in outreach, if one greeting fails, stop or skip and continue? Default is "stop and report" (safe), with `on_error: skip` as an option (see Q4).
- **"Return to list" is not always back**: the body's last step is written by the model; the engine is only responsible for "re-locate the item after returning, and stop if it cannot be located", never guessing the return method for the model.
- **Turning "repetition" from a stuck signal into a batch signal may let real stuck states through**: the guard exemption already only applies when "going through items one by one"; after changing it to "use do_each", if the model misjudges a loop where it is actually going in circles, `do_each`'s until/max_items fallback bounds the number of rounds, so it is no worse than today.

## Migration Plan

Purely a new tool + two prompt text changes, with no existing data migration. Rollback = revert the commit; before the model is taught, it will not call `do_each`, so behavior matches today.

## Open Questions

- **Q1 Stable key (prototype-verified ✅)**: What is the key for item dedup? How are identically named items distinguished? The prototype `core/.../agent/EachCursor.kt` (+ `EachCursorTest`) works: **`role|name`, with same-name conflicts falling back to `role|name#k` (ordinal within the container)**. Key finding: `resourceId` exists in real a11y trees but **list items are isomorphic** — N cards of the same widget type have identical resourceIds, so item 1 and item 2 cannot be distinguished, hence "prefer resourceId" is rejected. refs are reshuffled on every snapshot and are unusable. The key of a uniquely named item is position-independent (unchanged even when the list reorders); only genuine same-name cases fall back to position. Remaining boundary: when the list scrolls / an item is removed, position keys become invalid — folded into Q2 (reveal).
- **Q2 reveal semantics (prototype-verified ✅)**: `ScrollCursor` works — stop condition = "just scrolled once, and this screen read produced no new contentKey". contentKey (`role|name`) is stable across scrolling: items scrolled off screen keep their keys and are not redone; `ordinal` (same-name ordinal) is position-dependent, becomes invalid after scrolling, and cannot serve as a cross-scroll key. Boundary: identically named items collapse into "do it once" — the element information is insufficient to distinguish two same-name items across scrolls, and the real fix is finer item matching to make contentKey unique, not the key itself.
- **Q3 In-place loop (prototype-verified ✅)**: Loops that do not open items (scrolling collection, batch mark-as-read/claim) **need no new mechanism** — the executor only cares "did this one succeed", and when items are removed in place the list shifts up but contentKeys stay the same, so the cursor dedups as usual. Supported directly in v1; expressing it only requires the `body` to use neither `@item` nor `back` (`EachRunnerTest.inPlaceRemovalDedupsWhenItemsShiftUp`).
- **Q4 on_error (prototype-verified ✅)**: Both `stop` (safe default: the failed item is not marked done and is left to the upper layer to decide on retry, and later items are not attempted) and `skip` (failures are also marked done and later items continue, for outreach's "one failure shouldn't block a screen") must be exposed in v1; `EachRunner.OnError` works for both. The summary contains `done/failed/stoppedAt/perItem`.
- **Q5 Relationship with do_steps**: should do_steps eventually collapse into a `count=1` special case of `do_each`, or coexist long-term? This design keeps them coexisting for now, to converge after observing usage.
