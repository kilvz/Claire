# Tasks: screen map

> **Basis for implementation**: `openspec/changes/feat-screen-map/design.md` (D1–D10); this plan corresponds to it item by item.
> **Branch**: `worktree-screen-map` (a separate worktree, leaving main untouched). Done in one pass, not phased.
> Tasks are ordered by dependency; each is independently testable and committed separately; use TDD (write the failing test first, then implement).

## Global conventions

- New modules: `com.wangzan101.mika.core.screen.map` on the core side; persistence under `app/src/main/java/com/wangzan101/mika/knowledge/` on the app side.
- Screen identification reuses the `RouteAnchors.texts(snapshot)` definition (fixed skeleton: drop digits, drop list/scrollable content, drop input fields, drop text the user typed, keeping only title bars/tabs/buttons).
- Deterministic screen identity: screen id = the first 16 hex chars of `sha256(app + "\u0000" + firstSkeletonAnchorSignature)`; rebuilding twice from the same batch of run records → byte-for-byte identical (the whole thing can be `rebuild`-ed).
- Serialization uses kotlinx.serialization (precedent: `RouteCodec` in `core/learn/replay/Route.kt`).
- Test commands: core unit tests `./gradlew :core:test`; app unit tests `./gradlew :app:testDebugUnitTest`.
- Chinese commit messages, one commit per task.

## Review Focus (inputs the spec does not cover but that bite the hardest; each is pinned by a test in the owning task)

1. **Screen misidentification**: when two screens share generic text like "OK/Back/Settings", that must never be used to judge them as the same screen — rarity must down-weight it (task 2 test).
2. **No guessing below the threshold**: when a screen has only vague anchors, or the screen is not in the map, `identify` must return None rather than force-matching the nearest screen (task 2 test).
3. **One page, two structures**: when a banner/empty state is missing the skeleton still matches (coverage threshold < 1.0); when the skeletons are genuinely disjoint, split into two screens instead of merging wrongly (tasks 2, 3 tests).
4. **An old route after a page update**: replay lands on the wrong screen, retries once, still wrong → Diverged, and never keeps clicking (task 7 test).
5. **A read/write failure equals no map**: when the map file is corrupt/conflicting, identification and injection spin as no-ops and the task is unaffected (task 6 test).

---

### Task 1: Screen entity + deterministic screen id (core)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenNode.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenNodeTest.kt`

**Interfaces:**
- Produces (used by subsequent tasks):
  - `enum class TransitionEffect { NAVIGATED, STATE_CHANGED, NONE, LEFT_APP, UNKNOWN }`
  - `data class Transition(val action: String, val target: String?, val effect: TransitionEffect, val to: String?, val count: Int = 0, val offTrack: Int = 0)`, `key()` = `(action, target, effect, to)`
  - `data class ScreenNode(val id: String, val app: String, val name: String = "", val anchors: Map<String, Int> = emptyMap(), val levels: Map<String, Int> = emptyMap(), val transitions: Map<String, Transition> = emptyMap(), val visits: Int = 0, val status: String = "provisional")`
  - `object ScreenId { fun of(app: String, signature: String): String }` — the first 16 hex chars of sha256
  - `object ScreenNodeCodec { fun encode(n: ScreenNode): String; fun decode(s: String): ScreenNode }`

- [ ] **1.1 Write the failing test**: `ScreenNodeTest` — `ScreenId.of` gives the same result twice for the same input, differs for different app/signature, and is stable (does not change over time); `ScreenNodeCodec` round-trip (encode→decode equals the original object); `Transition.key()` is consistent with the 5-tuple.
- [ ] **1.2 Run the test to confirm it fails** (the class does not exist). Run: `./gradlew :core:test --tests "*ScreenNodeTest"`, expecting FAIL.
- [ ] **1.3 Implement `ScreenNode.kt`**: the types from the table above + `@Serializable` annotations; `ScreenId.of` uses the first 16 hex chars of `sha256(app + "\u0000" + signature)`, and `key()` is computed inside `Transition`.
- [ ] **1.4 Run the test to confirm it passes**.
- [ ] **1.5 Commit**: `feat(screenmap): screen entities and deterministic screen ids`

---

### Task 2: Screen identification `ScreenRecognizer` (core, pure functions with zero model calls)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenRecognizer.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenRecognizerTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`, `RouteAnchors.texts` (`core/learn/replay/Route.kt`, signature `fun texts(s: ScreenSnapshot): List<String>`)
- Produces:
  - `data class Recognition(val id: String, val confidence: Double, val matched: Set<String>)`
  - `fun identify(texts: Set<String>, nodes: List<ScreenNode>, confidenceMin: Double = 0.5): Recognition?` — sort by `(overlap count, coverage, rarity)` and take the closest; returns null when confidence is below the threshold; see design D2.

**Rarity**: `rarity(text) = 1.0 / max(1, how many nodes' anchors contain this text)`; `confidence = coverage` (|S∩anchors| / |anchors|) combined with rarity as a weighted score, with initial weights of `0.5*coverage + 0.5*normalizedRarity`, to be tuned on device (design D10).

- [ ] **2.1 Write the failing test**: `ScreenRecognizerTest` covers —
  - multiple anchors on the same screen hit → identified correctly; one anchor missing (coverage still sufficient) → still identified correctly (the "missing banner" of one page with two structures);
  - generic text ("OK", "Back") appearing on multiple screens → down-weighted by rarity, and not used to judge the two screens as one (Review Focus #1);
  - only vague anchors / the screen is not in the map → return null (Review Focus #2).
- [ ] **2.2 Run the test to confirm it fails**.
- [ ] **2.3 Implement `identify`**: first filter out the skeleton text set by the `RouteAnchors.texts` definition, then compute the overlap/coverage/rarity ordering; return null below `confidenceMin`.
- [ ] **2.4 Run the test to confirm it passes**.
- [ ] **2.5 Commit**: `feat(screenmap): screen recognition (skeleton anchors + coverage + rarity + confidence)`

---

### Task 3: Map building `ScreenMapBuilder` (deterministic rebuild from run records)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilder.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilderTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`, `Transition`, `RunEvent.ActionTarget` (in `core/run/record/RunEvent.kt`, containing `before/after: Anchors`, `tool`, `target`), `RouteAnchors`
- Produces:
  - `class ScreenGraph(val app: String, val nodes: Map<String, ScreenNode>)`, `fun nodes(): List<ScreenNode>`
  - `class ScreenMapBuilder { fun apply(runId: String, events: List<RunEvent>): Map<String, ScreenGraph> }` — returns "app slug → map"; visits/creates screens and adds transition edges (effect classification see design D1).

**effect classification** (two adjacent anchors of a `RunEvent.ActionTarget`): before package ≠ after package → LEFT_APP; after screen id ≠ before screen id → NAVIGATED; effective and same screen → STATE_CHANGED; otherwise NONE.

- [ ] **3.1 Write the failing test**: given a synthetic `RunEvent.ActionTarget` sequence (two screens, one click switching screens), assert the produced map: screen ids deterministic, transition edges' action/target/effect/to correct, visits counted correctly; **running the same batch of events twice → the two `Map`s are byte-for-byte identical after serialization** (determinism, design D4).
- [ ] **3.2 Run the test to confirm it fails**.
- [ ] **3.3 Implement `apply`**: visit before/after in event order (`ScreenId.of(app, firstSkeletonAnchorSignature)` creates the screen; the signature = the stable skeleton texts sorted and joined); on matched, status provisional→confirmed, anchors count +1, levels voted; transition edges are deduplicated and counted by key.
- [ ] **3.4 Run the test to confirm it passes**.
- [ ] **3.5 Commit**: `feat(screenmap): deterministic screen map rebuild from run records`

---

### Task 4: Relative navigation `ScreenGraph.path`

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapBuilder.kt` (or split out `ScreenGraph.kt`)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenGraphTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph` (task 3)
- Produces:
  - `fun ScreenGraph.path(from: String, to: String): List<Transition>?` — BFS shortest path; returns null if unreachable
  - `fun commonPrefix(a: List<Transition>, b: List<Transition>): Int` — common prefix length (for replay to trim the common part when connecting to the map, design D6)

- [ ] **4.1 Write the failing test**: an existing path returns the correct transition sequence; unreachable returns null; when two paths share a common prefix, `commonPrefix` gives the correct length.
- [ ] **4.2 Run the test to confirm it fails**.
- [ ] **4.3 Implement**.
- [ ] **4.4 Run the test to confirm it passes**.
- [ ] **4.5 Commit**: `feat(screenmap): relative navigation and common prefix on the map`

---

### Task 5: Map rendering `ScreenMapRender` (compact map text for the model)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapRender.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapRenderTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph`, `ScreenNode`
- Produces:
  - `fun renderPosition(graph: ScreenGraph, currentId: String?): String` — "you are on screen X" (or empty when unidentifiable)
  - `fun renderMap(graph: ScreenGraph, currentId: String?, maxScreens: Int = 12, maxChars: Int = 1200): String` — screen list + transition edges starting from the current screen ("tap X → go to Y"); truncated when over the limit (must not crowd out the element list, design D8)

- [ ] **5.1 Write the failing test**: renders "screen names + edges from the current screen"; truncates over `maxScreens`/`maxChars`; returns an empty string or a "no map" placeholder when the map is empty/unidentifiable.
- [ ] **5.2 Run the test to confirm it fails**.
- [ ] **5.3 Implement**.
- [ ] **5.4 Run the test to confirm it passes**.
- [ ] **5.5 Commit**: `feat(screenmap): render the screen map as a compact map for the model`

---

### Task 6: Storage `ScreenMapStore` / `FileScreenMapStore` (core interface + app persistence)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapStore.kt` (interface)
- Create: `app/src/main/java/com/wangzan101/mika/knowledge/FileScreenMapStore.kt`
- Test: `app/src/test/java/com/wangzan101/mika/knowledge/FileScreenMapStoreTest.kt`

**Interfaces:**
- Consumes: `ScreenGraph`, `ScreenNodeCodec`
- Produces:
  - `interface ScreenMapStore { fun load(app: String): ScreenGraph?; fun save(app: String, graph: ScreenGraph); fun apps(): List<String> }`
  - `FileScreenMapStore(dir: File)` — persists to `files/knowledge/apps/<app-slug>/screens.json`, atomic write + revision validation (precedent `AppKnowledgeFiles`); read failure/corruption → `load` returns null

- [ ] **6.1 Write the failing test**: save→load round-trip; atomic writes (a half write does not corrupt the old file); revision conflicts do not overwrite; a corrupt file → load returns null without throwing (Review Focus #5).
- [ ] **6.2 Run the test to confirm it fails**.
- [ ] **6.3 Implement the interface and persistence**.
- [ ] **6.4 Run the test to confirm it passes**.
- [ ] **6.5 Commit**: `feat(screenmap): persist the screen map (atomic write + revision)`

---

### Task 7: Replay connecting to the map (`Replayer` screen identification + wrong-screen retry + handback copy)

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/learn/replay/Replayer.kt`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/RouteReplay.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/learn/replay/ReplayerScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenRecognizer.identify` (task 2), `ScreenGraph`/`ScreenMapStore` (tasks 3/6), the existing `Replayer` (`core/learn/replay/Replayer.kt`)
- Produces: `Replayer` uses `identify` on each step to check the before screen (the identified screen == expected, or the anchors still match); after the action, `identify` the landed screen, retrying once if unexpected (still wrong → Diverged); `RouteReplay`'s handback copy changes from "step N failed" to "landed on screen X + paths starting from this screen" (`ScreenMapRender.renderMap`).

- [ ] **7.1 Write the failing test**: landing on the wrong screen → retry once → still wrong means Diverged with correct `executed` semantics (Review Focus #4); matching → Completed; the handback copy contains "landed on screen X / you can go from here".
- [ ] **7.2 Run the test to confirm it fails**.
- [ ] **7.3 Implement**: wire `identify` into the before/after check of each step in `Replayer.run`; add the wrong-screen retry; splice `renderMap` into the Diverged branch of `RouteReplay.run`.
- [ ] **7.4 Run the test to confirm it passes** (and run the full `:core:test` to confirm the existing replay tests are not broken).
- [ ] **7.5 Commit**: `feat(screenmap): replay connects to the map (screen recognition + wrong-landing retry + hand back the map)`

---

### Task 8: Knowledge injection (give the screen map together with APP.md)

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt` (`injectKnowledge` and the `appendRuntimeState` area)
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/ContextBuilder.kt` (if injection lives here)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/AgentLoopScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenMapStore.load`, `ScreenRecognizer.identify`, `ScreenMapRender.renderPosition/renderMap`
- Produces: on entering an App (that already has a map), a screen map block (page structure) is appended after `APP.md`; when the current screen is identifiable, first give "where you are", then "screen list + how to get around from here".

- [ ] **8.1 Write the failing test**: given an App that already has a map and one identifiable snapshot, assert the injected context contains APP.md text + screen map text; when the map is empty/read fails, nothing is injected and the task proceeds as usual.
- [ ] **8.2 Run the test to confirm it fails**.
- [ ] **8.3 Implement**: chain `renderMap` after the existing `injectKnowledge` path (the `APP.md` injection point); carry `renderPosition` in `runtimeState`.
- [ ] **8.4 Run the test to confirm it passes**.
- [ ] **8.5 Commit**: `feat(screenmap): inject page structure with APP.md when entering the App`

---

### Task 9: Wrap-up map growth (`RunLearning` replays run records to update the map)

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/RunLearning` (or the corresponding wiring in `app/.../task/RunLearning.kt`)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt` (where `RunAftermath` is handed out, pass `events` to the map growth)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/RunLearningScreenMapTest.kt`

**Interfaces:**
- Consumes: `ScreenMapBuilder.apply` (task 3), `ScreenMapStore.save` (task 6), `RunAftermath.events`
- Produces: in the task wrap-up background phase, replay this run's `events` to get the **screen/edge fragments seen in this run**, and **merge them into the stored map by screen id** (same id → anchors/visits/transitions counts accumulate, status provisional→confirmed; new id → append) → persist; failures are only counted and do not affect the task (design D4).

- [ ] **9.1 Write the failing test**: given a successful run's `events`, assert the App's map gains the corresponding screens/transition edges after wrap-up; empty `events` or a map write failure does not throw and does not affect the task outcome.
- [ ] **9.2 Run the test to confirm it fails**.
- [ ] **9.3 Implement**: call `ScreenMapBuilder.apply` + `ScreenMapStore.save` in `RunLearning.learn` (or the wrap-up background coroutine), wrapped in `guarded`.
- [ ] **9.4 Run the test to confirm it passes**.
- [ ] **9.5 Commit**: `feat(screenmap): replay run records at task teardown to grow the map`

---

### Task 10: merge gate self-check (wrong-merge / wrong-owner down to zero)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapMergeGate.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/screen/map/ScreenMapMergeGateTest.kt`

**Interfaces:**
- Consumes: `ScreenNode`, `ScreenRecognizer` (task 2)
- Produces:
  - `fun wrongMerge(nodes: List<ScreenNode>, labeled: Map<String, String>): List<Pair<String, String>>` — returns the pairs "identified as the same screen but labeled with different names" (should be empty)
  - `fun wrongOwner(events: List<RunEvent>, graphs: Map<String, ScreenGraph>): List<String>` — returns the entries "event attributed to the wrong App" (should be empty)

- [ ] **10.1 Write the failing test**: construct two nodes labeled differently whose anchor overlap is high enough that `identify` would call them the same screen → `wrongMerge` is non-empty (the gate catches it); an event attributed to the wrong App → `wrongOwner` is non-empty; normal data → both empty.
- [ ] **10.2 Run the test to confirm it fails**.
- [ ] **10.3 Implement**.
- [ ] **10.4 Run the test to confirm it passes**.
- [ ] **10.5 Commit**: `feat(screenmap): merge gate self-check`

---

## Wrap-up

- [ ] Full regression: `./gradlew :core:test :app:testDebugUnitTest` (plus `:core:cliTest` if needed) green.
- [ ] On-device smoke test (before release): run a task that involves the map once, then review to confirm the three points work: "screen map injected on entering the App / replay walking the map / map growth at wrap-up"; put the evidence in `evidence/`.
- [ ] The Chinese commit history is split per task; proposal/design/tasks are all three present under `openspec/changes/feat-screen-map/`.
