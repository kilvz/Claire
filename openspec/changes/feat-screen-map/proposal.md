# Screen map: one clickable page-structure map per App, given to the model alongside APP.md

## Why

Mika can now record approaches that worked as "routes" and replay them step by step at the start of a task without calling the model (`core/learn/replay`). But routes are **flat, linear paths** one by one: each step's "before/after screen anchors" are computed and thrown away on the spot, and there is no "screen" concept that survives across tasks. Consequences:

- Switching to a route not fully walked before, even if most of the screens in the middle have been walked, replans the whole thing from scratch.
- When replay lands on the wrong screen it only reports "step N does not match", unable to say "which screen we are on now and how we can still move from this screen".
- Screen identity is only a coarse fingerprint of "package name + at most 3 stable text segments", with no confidence about "is this really the screen I think it is".

More fundamentally: many Apps have a **fixed page structure where only the content text changes**. This fixed structure (which pages exist, where clicking leads) is what the model has to explore from scratch every time — it should be distilled into a map and given to the model alongside `APP.md`.

In one sentence: **getting smarter (knowing where it is, being able to get from here to there, seeing the App's structural map) and working more reliably (known paths do not derail, wrong-screen landings are recognized and retried).**

## What Changes

- New **clickable screen map per App**: nodes = structural pages (name + anchors + level + transition edges), edges = click navigation (click X on screen A → go to screen B). The map grows by self-learning from **replaying run records**; it is deterministic and wholly rebuildable.
- Screen identification (identity): replace today's coarse matching of "the first 3 stable text segments" with "skeleton anchors + coverage + rarity" ordering; below the confidence threshold say "don't know", no guessing. **Only the fixed skeleton is recognized, not content text** (content changes every time, the skeleton does not).
- The screen map is injected into the model alongside `APP.md`: `APP.md` is the explanatory text, the screen map is the page structure; entering an App, the model gets "description + map", and after identifying the current screen it also gets "how to get around from this screen".
- Replay connects to the map: each step identifies the screen, retrying once on a wrong screen; mismatches hand back "landed on screen X + paths starting from this screen" instead of just "step N failed".
- Relative navigation: from the current screen → target screen, find a path in the map and trim the common prefix, so even targets not fully walked before can be assembled from segments.

## Capabilities

### New Capabilities

- `screen-map`: screen map — screen entity storage, deterministic rebuild from run records, screen identification (skeleton anchors + rarity + confidence), transition accounting, relative navigation, merge gate self-check.

### Modified Capabilities

- `replay`: replay changes from "following a fixed route" to "walking the map" — each step identifies the screen, wrong screens are retried, and mismatches hand back "current screen + available paths".
- Knowledge injection (the step that injects `APP.md` when entering an App): inject the screen map (page structure) as well, complementary to `APP.md` (explanations).
- The before/after anchors already present in run records' `RunEvent.ActionTarget` continue to serve as raw material for building the map (whether to extend capture with more screen fingerprints, see design D4).

## Impact

- core: new `screenmap` module (screen entities, map, identification, rebuild, navigation); `Replayer`/`RouteReplay` connect to identification and landed-screen validation; `RunLearning` replays run records to grow the map at wrap-up; `RouteAnchors`'s stable skeleton text extraction is reused for identification; the knowledge injection path gains a screen map block.
- app: screen map persistence (the writable knowledge area `files/knowledge/apps/<x>/`, peer to and complementary with `APP.md`), atomic write + revision validation.
- Behavior changes: the background learning after a task ends gains a "grow the map" step; the context on entering an App gains a "page structure map" section; the handback copy on replay mismatch changes from "step N failed" to "landed on screen X, you can go from here".

Non-Goals and anti-goals are in design.
