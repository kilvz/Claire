# Cross-screen loop primitive `do_each`: think once, execute many times for repetitive tasks

## Why

In real run records, for tasks that "repeat the same action across multiple items", the agent does them item by item as "observe→act→observe", resending the entire history + a new screenshot every round, with cost accumulating as O(N²). Examples:

- The real record for watching ads for rewards (`20261114-223716-79pbvk`): 35 model calls accomplished only "watch 3 ads and claim once each"; `wait`+`observe` polled 14 times, closing ads took 12 `tap`s, with 0 `do_steps`/`phone_run`; input tokens grew from 15K to 148K.
- Outreach greeting tasks "open→greet→return ×N" are the same: a cross-screen loop with no phone tool to express it.

The root causes are two layers (see design for details):

1. **Capability layer**: the phone side has no "loop" primitive — `do_steps` only batches single-screen linear work, `phone_run` is cross-screen but linear, and only the web `web_script` can loop (but requires writing a whole JS program). There is no phone tool that fills the "cross-screen loop" shape.
2. **Decision layer**: there is no mechanism that lets the model "recognize repetition and commit to a loop"; `batch.md` only teaches filling forms, and the `effect`/`guard` vocabulary reads "repetition" as "stuck" and "verification" as "virtue".

## What Changes

- New cross-screen loop primitive **`do_each`**, one tool serving both phone and web: declaratively express "for several items, execute the same steps on each", thinking once and letting the engine execute many times.
- The loop structure (find next / skip done / execute body / terminate / summarize results) is shared; the only surface-dispatched parts are "how one item is recognized" (phone a11y vs web DOM) and the action executor (already abstracted by ActionExecutor).
- The decision layer **changes wording only, no runtime changes at first**: `batch.md` gains the "repetition → do_each" teaching and a real example; the `guard.same_state` exemption is upgraded from "carry on" to "there are N similar items left, finish them in one go with do_each"; new `do_each.md`.
- Runtime repetition detection / a planning phase are future directions; this design only leaves the reporting shape (a compact summary) in the engine, without implementing detection logic.

## Capabilities

### New Capabilities

- `do-each`: a cross-screen loop primitive — item location (dispatched by surface), loop body steps (reusing do_steps step semantics), skip/termination conditions, per-round guard, compact result summary.

### Modified Capabilities

- `batch` (`batch.md` wording), `feedback` (`guard.same_state` wording) — text only.

## Impact

- core: new loop executor (same family as `BatchRunner`) — item locator interface + loop control + result summary; `Verb.DoEach`, `AgentTool` registering `do_each`, `ToolSpec` generation.
- Surface wiring: phone item location goes through the a11y tree (role/text/resourceId), web goes through the DOM (cssSelector/role); actions go through the existing execution and recording path (controlled actions like send/post/pay pass the gate as usual).
- prompts: new `tools/do_each.md`; changes to `capabilities/batch.md`, `feedback/loop.md` (`guard.same_state`).
