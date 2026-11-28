# Design: screen map — one clickable page-structure map per App, given to the model alongside APP.md

## Context

Current pipeline (exploration conclusions):

```
Task start → RouteMatcher finds a route (template literal similarity + one model/decision call to select + slot extraction)
  └─ Replayer replays step by step without model calls: each step reads the screen → compares "package name + before-screen anchors (up to 3 stable text segments) + unique element selector match"
       → ActionExecutor executes → compares "post-action change / after-screen anchors"; on mismatch, Diverged(i) is handed back to the model
Task wrap-up → RunLearning: draft skills / extract memories / draft App knowledge (trial/promote/archive)
Run records → RunEvent.ActionTarget{step, tool, argsJson, target(Selector), before/after(Anchors), effective, ...}
Anchors → RouteAnchors.texts(snapshot): package name + stable text (drop digits, drop list content, drop input fields, drop text the user typed, take the first 3)
Knowledge → knowledge/apps/<x>/APP.md (explanatory text) + L2 injection (given to the model when entering the App)
```

Half of it already exists (replay + anchors + self-learning + screen element model + knowledge injection). What is missing is **one clickable screen map per App**: screen entities, survival across tasks, relative navigation, identity confidence, and **giving it to the model as "page structure" together with APP.md**.

## Core insight (determines the identity definition and storage)

1. **The page structure is fixed; what changes is the content text**: a screen's identity = the fixed skeleton (title bar, tabs, buttons, navigation), not the content (list items, inputs, text the user types). Each screen on the map is a **structural page**, unaffected when content changes.
2. **The map is for the model**: the screen map is the App's "page structure", injected together with `APP.md`; `APP.md` is the explanatory text (what this App is, pitfalls, tricks), while the screen map is the page structure (which pages exist, where clicking leads). The two are complementary.
3. **One independent map per App**, grown by self-learning, becoming more complete with use.

## Goals / Non-Goals

**Goals:**

- One clickable screen map per App: nodes = structural pages (name + anchors + level + transition edges), edges = click navigation (click X on screen A → go to screen B, recording "which element was clicked + which screen it led to + how many times it succeeded").
- The map is deterministically rebuilt from run records; identifying a screen costs zero model calls; names/levels are labeled once by the model and cached; levels by vote count.
- The map is injected into the model alongside APP.md: after identifying which screen it is on, provide "where you are + the paths starting from this screen + this App's screen list and how to get around".
- Replay connects to the map: a known full path costs zero model calls; each step identifies the screen, retrying once if it lands on the wrong screen; mismatches are handed back as "landed on screen X + where you can go from here".
- Relative navigation: from the current screen → target screen, find a path in the map and trim the common prefix, so even targets not fully walked before can be assembled from segments.
- Misidentification (wrong merge / wrong ownership) must be 0.

**Non-Goals:**

- No offline crawler pre-crawling Apps (no clicking through the whole App in advance; it grows as it is used).
- No device-level / home-screen map (Android can launch directly by package name, so it is unnecessary).
- Does not replace the existing curated knowledge (APP.md) or the route system — the screen map is a "page structure" layer complementary to APP.md (explanations), and routes keep working.

**Anti-goals (doing these counts as failure):**

- Misidentifying a screen / attributing to the wrong App: a wrong identification is worse than none — it points the model to the wrong place, whereas none merely fails to help.
- Identity determined by the model labeling every screen, causing runaway cost: screen identification costs zero model calls; names/levels are labeled once and cached, not labeled per screen.
- The screen map storing text the user typed / content / coordinates as identity: only the fixed skeleton + semantic targets are stored.

## Decisions

### D1: Screen entities and the map

Screen node (per App, one structural page):

```
Screen {
  id           // hash(app + firstAnchorSignature), deterministic, rebuilt wholesale byte-for-byte identical
  app          // package name slug
  name         // semantic name (labeled once by the model and cached; by default a placeholder name can be taken from the anchors)
  anchors      // stable skeleton text set (merged across visits) + landing count for each
  levels       // home/top/inner → vote counts (majority decides, ties None)
  transitions  // (action, semantic target, effect, which screen) → count + off_track
  visits, status // provisional | confirmed
}
```

Transition edge effect ∈ { navigated, state_changed, none, left_app, unknown }: changed after the action but did not switch screens = state_changed, switched screens = navigated, jumped to another App = left_app.

### D2: Screen identification (identity) — fixed skeleton + rarity, zero model calls

Extract a **stable skeleton text set S** from the current `ScreenSnapshot` (reusing `RouteAnchors.texts`: drop digits, drop list/scrollable content, drop input fields, drop text the user typed, keeping only title bars/tabs/buttons), compare against stored screens, sort by `(overlap count, coverage, rarity)`, and take the closest; below the confidence threshold, return None.

- Coverage = |S ∩ anchors| / |anchors|; rarity = Σ 1 / max(1, how many screens this text appears on) — "OK/Back" is everywhere and is hardly evidence.
- Purely programmatic, no model calls; below the threshold means "don't know", no guessing.
- The skeleton definition is the core of D: content changes, the skeleton does not, so both identification and map building recognize only the skeleton.

### D3: Semantic names / levels — not in this version (decided)

Screen identity is **self-describing** through skeleton anchors (the "About phone" anchor is this screen's name), so there is no need to have the vision model label a semantic name for each screen. The `ScreenNode.name` / `ScreenNode.levels` fields are kept, left empty in this version, for labeling later if needed. Levels (home/top/inner) likewise are not voted on in this version.

### D4: Building the map — replaying run records, deterministic, read-only in real time

- At wrap-up (the same background phase as `RunLearning`), replay this run's `RunEvent` stream: each before/after of an `ActionTarget` visits / creates a screen and adds transition edges. The real-time side runs the same logic in memory (no disk writes) and only persists at wrap-up — both sides agree because "there is only one replay logic".
- Screen ids use `hash(app + firstAnchorSignature)`; replay order is fixed → a full rebuild is byte-for-byte identical (verifiable, and the whole thing can be redone with `rebuild`).
- The raw material for building the map is the skeleton anchors of `ActionTarget.before/after`; if rarity needs "the full skeleton text set of each screen", extend run-record capture (adding fields in a way compatible with the existing record format).

### D5: Replay connecting to the map

- Each replay step: read the screen → `identify(snapshot)` to identify the current screen, cross-check with the route step's `before` anchors (the identified screen == the expected screen, or the anchors still match); after executing the action, `identify` the landed screen, retrying once if the landed screen is unexpected (still wrong → Diverged).
- The handback message on mismatch is upgraded: from "step N does not match" to "landed on screen X (or could not identify it); starting from this screen, these paths have been walked / these target screens are reachable".

### D6: Relative navigation

Given the current screen `a` and the target screen `b` (from the route target, or a target screen named by the task), find the shortest path `a→b` in the map:

- If two routes share a common prefix, trim the common part (splice in from the current screen) — switching routes does not require starting over.
- This is the core of "getting smarter": a route is one notation for a complete path, while the map is the assemblable intermediate state.

### D7: merge gate / confidence

Two gates that must be 0 (as `screen-map` self-checks / tests, not runtime metrics):

- **wrong-merge**: two screens labeled with different semantic names are identified as the same screen.
- **wrong-owner**: an event is attributed to the wrong App (attributing to system / unknown is allowed, it is just not recorded).

Returning None for an identification below the confidence threshold is the runtime expression of this gate.

### D8: Storage and injection — given to the model alongside APP.md

- **Storage**: one structure file per App, kept alongside `APP.md` (`knowledge/apps/<x>/`). The map is machine-grown and writable (stored separately from the read-only curated `APP.md`, see below).
- **Injection**: when the model enters that App (the step that currently injects `APP.md`), **inject together**:
  - `APP.md` — the explanatory text (what it is, pitfalls, tricks).
  - the screen map — the page structure (which pages exist, where clicking leads), rendered as a compact "map" text block, truncated when too long (it must not crowd out the element list).
- **Writing**: atomic writes + revision validation (following the `AppKnowledgeFiles` / `FileRouteStore` precedent); a read/write failure means "no screen map" and the task proceeds as before — all failures are only counted and do not affect the task.
- Concrete on-disk layout: curated lives in `resources/knowledge/apps/<x>/` (read-only), while self-learning-grown screen maps are written to the writable area `files/knowledge/apps/<x>/` (peer to AppKnowledgeFiles's `files/knowledge/app-drafts/`), and both are merged at injection time.

### D9: Page updates and "one page, multiple structures"

**Pages change (updates/iterations) — relying not on version numbers but on "unrecognizable → safe fallback + self-healing":**

- Each replay step identifies the screen + requires a unique element selector match + verifies the landed screen: an old route against a new page does not click wrong, it merely "cannot proceed → handed back to the model"; consecutive misses disable the route and LRU evicts it (already in place).
- A screen-identification mismatch → return "don't know" and fall back to normal screen exploration; the new structure is re-learned as a new screen during later normal tasks (self-healing).
- Screen identity is vote-based (anchor occurrence counts, level votes): old evidence is naturally superseded by new evidence, with no explicit "version/expiry" field.
- When needed, rebuild wholly from the "last N runs" window (deterministic), letting old screens that have not reappeared for a long time drop out naturally.

**One page, two structures — handled in three layers by degree of difference:**

1. What changed is content (empty list vs with content): the skeleton is the same, only the skeleton is recognized, unaffected.
2. What changed is structure but the skeleton is shared (banner present/absent, logged-in state, collapsed/expanded, A/B variants): a coverage threshold < 1.0 tolerates a missing part; anchors = union + counts, with stable parts weighted high and occasional parts down-weighted by rarity → identified as the same screen.
3. Structures are genuinely disjoint (the skeletons all differ, e.g. two navigation sets before/after login): split into two screens (better to split than to merge wrongly); the map says "this page has A/B looks", and whichever is identified is the one used.

**Why better to split than to merge wrongly**: over-splitting only creates redundant nodes (harmless, converging later by anchor counts); merging wrongly = two different pages treated as one and navigation going wrong (dangerous). The merge gate (wrong-merge must be 0) only blocks the "merge wrongly" direction; the split direction has no threshold.

### D10: Decisions left to the builder (may be decided independently, does not affect direction)

- The concrete format of the screen map structure file (JSON, serialized into compact text at injection) and the file name.
- How the screen id's "anchor signature" is derived (hash of the first few segments concatenated / hash of the full skeleton set).
- Initial values for the identification confidence threshold and the "overlap / coverage / rarity" weights (start with defaults, tune on device).
- The truncation/collapse strategy for the screen map at injection (full skeleton vs only the currently reachable few screens).
