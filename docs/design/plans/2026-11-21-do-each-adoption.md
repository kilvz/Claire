# do_each adoption rate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the model works item by item (open detail → act → go back, repeated ≥3 times) it gets a mechanism-level do_each suggestion, plus revive the dead same_state/state_cycle guard code and give the do_each description a realistic example.

**Architecture:** Add a pure state machine `EachLoopDetector` (core/agent) that recognizes repetition from the shape of the verb sequence; AgentLoop feeds it at the end of each turn, and on a hit it appends a reminder through `ctx.notice` and calls `track.markRecovery()` so the next step returns to the main model. At the same time, wire the two existing `arrived()`/`cycle()` checks back into `OperationGuards.frame()` (the UI-state dimension, complementing the shape dimension).

**Tech Stack:** Kotlin, JUnit4, Gradle (`:core` pure JVM module).

**Spec:** `docs/design/specs/2026-11-21-do-each-adoption-design.md`

## Global Constraints

- Generic AOSP behavior; look only at the shape of the verb sequence and the UI-state hash; **no app/brand special cases**.
- Prompt changes only add and never modify already-sent messages (prefix cache); the few-shot increment is <200 token.
- A feedback key is unique between `feedback/loop.md` and `feedback/tools.md`, and every key must be referenced in code as a `"<key>"` literal (enforced by `PromptLayoutTest.noOrphanFeedbackKeys`).
- Test names must match the semantics of their assertions; leave no test whose name does not reflect what it checks.
- Work on the `worktree-do-each-adoption` branch (worktree), do not touch main; do not change the version number (not a release).
- Run full regression with `./gradlew :app:testDebugUnitTest` (**do not use bare `:app:test`, it will OOM**).

## Review Focus

1. In `frame()`, `trail` must be updated before `arrived()` — with the order reversed, the cycle suppression inside `arrived()` uses the old trail (a timing bug that a test asserting only the final conclusion cannot catch, so it needs dedicated cases).
2. `EachLoopDetector` must fire as soon as the sequence is exactly `REPEATS*l` long (with L=2 it fires on the 6th verb; it must not wait for the window of 15 to fill).
3. The pure-scrolling feed `[Observe, Scroll]×N` and the two-screen comparison `[Tap?, Back]` must never fire (false-positive protection).
4. After the first rung of `cycleLadder` goes 2→3, confirm nothing else in the repo depends on the old threshold of 2 (grep every reference to `cycleLadder`).
5. When batches (do_steps) and single steps interleave, `eachLoop.reset()` is not missed: a half-formed sequence from before a batch must not be spliced onto what comes after it.

---

### Task 1: Revive the same_state / state_cycle guards

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/safety/guard/OperationGuards.kt` (the frame() 169-189 line region)
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/safety/guard/GuardRules.kt` (cycleLadder line 57 + the looping line in the class comment)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/safety/guard/OperationGuardsTest.kt` (the three tests at lines 114-183)

**Interfaces:**
- Consumes: the existing `OperationGuards.frame(id: String, hash: Long): List<GuardVerdict>`, `GuardRules.cycle(trail: List<Long>): Cycle?`, `GuardVerdict(code, severity, evidence, nudge)`.
- Produces: `GuardRules.cycleLadder(budget: Int?): List<Int>` changed to `[3, 4, lateStop(budget, 5, 6)]` (Task 3 does not depend on this task's interface, but uses the same test file).

- [ ] **Step 1: Rewrite the failing tests**

Rewrite the three tests in `OperationGuardsTest.kt` (assert the real semantics so that assertions match the test names; note that the cycle gate in `arrived()` stays as it is — while looping, same_state stays silent and only state_cycle speaks, so the first test must use an **irregular** revisit sequence):

```kotlin
@Test fun returningToTheSameScreenWithoutAnythingNewNudgesAtThree() {
    val g = OperationGuards()
    g.frame("s0", 1L)
    // each time it returns to the same screen via a different new page (an irregular revisit, not a cycle): the second arrival at 1 does not nudge, the third does
    val out1 = listOf(2L, 3L, 4L, 1L).mapIndexed { i, h -> g.noteAction(); g.frame("a$i", h) }.flatten()
    assertTrue(out1.toString(), out1.none { it.code == "same_state" || it.code == "state_cycle" })
    val out2 = listOf(5L, 6L, 7L).mapIndexed { i, h -> g.noteAction(); g.frame("b$i", h) }.flatten()
    assertTrue(out2.toString(), out2.isEmpty())
    g.noteAction()
    val last = g.frame("b3", 1L)
    assertEquals(listOf("same_state"), last.map { it.code })
    assertEquals(Severity.Warn, last.single().severity)
    assertTrue(last.single().nudge!!.contains("do_each"))
}

@Test fun threeStateLoopIsCaught() {
    val g = OperationGuards()
    g.frame("s0", 1L)
    // when 1→2→3 completes three full rounds (trail has grown to 9) it nudges about looping; a periodic loop produces no same_state at all (that is spoken only by the cycle channel)
    val seq = listOf(2L, 3L, 1L, 2L, 3L, 1L, 2L, 3L)
    val verdicts = seq.mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }
    assertTrue(verdicts.dropLast(1).flatten().none { it.code == "state_cycle" || it.code == "same_state" })
    assertEquals(listOf("state_cycle"), verdicts.last().map { it.code })
    assertEquals(Severity.Warn, verdicts.last().single().severity)
}

@Test fun cycleStopFollowsTheStepBudget() {
    val g = OperationGuards(stepBudget = 30)
    g.frame("s0", 1L)
    // budget 30 → ladder [3, 4, max(6, 30/5)=6]: frames 5 (rounds 3) and 7 (rounds 4) nudge, frame 11 (rounds 6) stops
    var stopAt = 0
    val warnAt = mutableListOf<Int>()
    for (i in 1..11) {
        g.noteAction()
        val v = g.frame("s$i", if (i % 2 == 1) 2L else 1L)
        if (v.any { it.code == "state_cycle" && it.severity == Severity.Warn }) warnAt += i
        if (g.stop != null) { stopAt = i; break }
    }
    assertEquals(listOf(5, 7), warnAt)
    assertEquals(11, stopAt)
    assertEquals("state_cycle", g.stop?.code)
}
```

and keep `backAndForthBetweenTwoStatesDoesNotStopProgress` as is (after the first rung is raised to 3, 3 frames with rounds=2 do not trigger, so asserting empty lives up to its name).

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :core:test --tests '*OperationGuardsTest*' --console=plain -q`
Expected: FAIL — `returningToTheSameScreen…` does not get same_state (dead code), `threeStateLoopIsCaught` does not get state_cycle, and `cycleStop…` has stopAt of 0.

- [ ] **Step 3: Wire arrived()/cycle() into frame() and change the ladder**

Change the `if (!same)` block in `OperationGuards.frame()` to (the order is the semantics: update trail first, then arrived, then cycle):

```kotlin
if (!same) {
    pageChanged()
    if (trail.lastOrNull() != hash) {
        trail.addLast(hash)
        while (trail.size > TRAIL_KEEP) trail.removeFirst()
    }
    if (seenStates.add(hash)) progress() else out += arrived(hash)
    out += cycle()
}
```

The body of `arrived()` stays **untouched** (the cycle gate keeps: "while looping, cycle speaks; do not repeat it here").

Change `GuardRules.cycleLadder` to `listOf(3, 4, lateStop(budget, 5, 6))`; in the class-comment table, change the nudge column of the row "looping among two or three states (full-round count) | 2, 4 |…" to "3, 4"; change the row "the Nth return to the same state without ever reaching a new state" to "returning to the same state (not repeated while looping; the looping row speaks it)".

- [ ] **Step 4: grep to confirm nothing else depends on the old first rung of 2**

Run: `grep -rn "cycleLadder" core/src --include="*.kt"`
Expected: only two places, `GuardRules.kt` (definition) and `OperationGuards.kt` (call); no other assumptions about the threshold.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :core:test --tests '*OperationGuardsTest*' --console=plain -q`
Expected: PASS (including every existing test, such as the retained `backAndForth…` and `manyActionsWithoutANewScreen…`).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/safety/guard/OperationGuards.kt core/src/main/kotlin/com/wangzan101/mika/core/safety/guard/GuardRules.kt core/src/test/kotlin/com/wangzan101/mika/core/safety/guard/OperationGuardsTest.kt
git commit -m "fix(guard): restore same_state/state_cycle verdicts, looping nudge first rung 2→3"
```

---

### Task 2: The EachLoopDetector pure state machine

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/agent/EachLoopDetector.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/agent/EachLoopDetectorTest.kt`

**Interfaces:**
- Produces:
  - `internal class EachLoopDetector`, `fun feed(verb: Verb?, ok: Boolean): Boolean` (returns true when it nudges, at most once per task), `fun reset()`
  - constants `EachLoopDetector.REPEATS = 3`, `MAX_MOTIF = 5`, `WINDOW = REPEATS * MAX_MOTIF`
- Consumes: `com.wangzan101.mika.core.tool.Verb`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EachLoopDetectorTest {
    private fun EachLoopDetector.feedAll(varbs: List<Verb>, ok: Boolean = true): List<Boolean> =
        varbs.map { feed(it, ok) }

    @Test fun openActBackThreeTimesFiresOnceAtTheNinth() {
        val d = EachLoopDetector()
        val rounds = List(4) { listOf(Verb.Observe, Verb.Tap, Verb.Back) }.flatten()
        val fired = d.feedAll(rounds)
        assertEquals(fired.indices.filter { fired[it] }, listOf(8))
    }

    @Test fun tapBackPairFiresAtTheSixth() {
        val d = EachLoopDetector()
        val fired = d.feedAll(List(4) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun feedScrollingNeverFires() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(6) { listOf(Verb.Observe, Verb.Scroll) }.flatten()).any { it })
    }

    @Test fun twoScreenCompareNeverFires() {
        val d = EachLoopDetector()
        // the motif must contain exactly one Back and at least one Tap: [Observe, Back] contains no Tap, so it does not fire
        assertFalse(d.feedAll(List(4) { listOf(Verb.Observe, Verb.Back) }.flatten()).any { it })
    }

    @Test fun failureBreaksTheStreak() {
        val d = EachLoopDetector()
        assertFalse(d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
        assertFalse(d.feed(Verb.Tap, false))
        // after a failure it must accumulate 3 more to fire (proving the half-formed run before the failure was not counted)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun usedDoEachDisarms() {
        val d = EachLoopDetector()
        d.feed(Verb.DoEach, true)
        assertFalse(d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten()).any { it })
    }

    @Test fun nonScreenVerbBreaksTheStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.feed(Verb.WebSearch, true)
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }

    @Test fun resetClearsHalfFormedStreak() {
        val d = EachLoopDetector()
        d.feedAll(List(2) { listOf(Verb.Tap, Verb.Back) }.flatten())
        d.reset()
        val fired = d.feedAll(List(3) { listOf(Verb.Tap, Verb.Back) }.flatten())
        assertEquals(listOf(5), fired.indices.filter { fired[it] })
    }
}
```

(`assertEquals` requires the `org.junit.Assert.assertEquals` import.)

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :core:test --tests '*EachLoopDetectorTest*' --console=plain -q`
Expected: FAIL — `EachLoopDetector` does not exist (compile error).

- [ ] **Step 3: Implement `EachLoopDetector`**

Semantics (the only reasonable implementation):

```kotlin
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb

/**
 * Detecting item-by-item work: when the "open detail → act → go back" motif in the recent verb sequence fully repeats [REPEATS] times, nudge once about do_each.
 * Only the shape matters, not the content; failures and non-screen verbs all break the run; if the model uses do_each itself, disarm. Pure state machine, one instance per task.
 */
internal class EachLoopDetector {
    private val seq = ArrayDeque<Verb>()
    private var fired = false
    private var disarmed = false

    fun reset() = seq.clear()

    /** Feed one round's verb ([ok] is whether this round succeeded); returns true when it nudges (at most once per task). */
    fun feed(verb: Verb?, ok: Boolean): Boolean {
        if (verb == Verb.DoEach) disarmed = true
        if (verb == null || !ok || verb !in COUNTED) { reset(); return false }
        seq.addLast(verb)
        while (seq.size > WINDOW) seq.removeFirst()
        if (fired || disarmed) return false
        if (!repeating()) return false
        fired = true
        return true
    }

    /** The last [REPEATS]×l verbs form a period of l, with the motif containing exactly one Back and at least one Tap (the signature of open detail → act → go back). */
    private fun repeating(): Boolean {
        for (l in 2..MAX_MOTIF) {
            if (seq.size < REPEATS * l) continue
            val window = seq.toList().takeLast(REPEATS * l)
            val motif = window.take(l)
            if (window.chunked(l).any { it != motif }) continue
            if (motif.count { it == Verb.Back } == 1 && Verb.Tap in motif) return true
        }
        return false
    }

    companion object {
        const val REPEATS = 3
        const val MAX_MOTIF = 5
        const val WINDOW = REPEATS * MAX_MOTIF
        private val COUNTED = setOf(
            Verb.Observe, Verb.ListElements, Verb.Tap, Verb.TypeText, Verb.ClearText,
            Verb.Scroll, Verb.Swipe, Verb.Back, Verb.PressKey,
        )
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :core:test --tests '*EachLoopDetectorTest*' --console=plain -q`
Expected: PASS (8 cases).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/agent/EachLoopDetector.kt core/src/test/kotlin/com/wangzan101/mika/core/agent/EachLoopDetectorTest.kt
git commit -m "feat(agent): EachLoopDetector recognizes the verb shape of item-by-item work"
```

---

### Task 3: AgentLoop wiring + loop.repeating_items copy

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt` (around line 515 near `val track = StepTrack()`, around line 779 on the single-step path `track.after(...)`, around line 955 at the start of `batch()`)
- Modify: `core/src/main/resources/prompts/feedback/loop.md` (append the new key at the end)
- Modify (regenerate): `core/src/test/resources/prompt-surface/feedback-loop.txt`

**Interfaces:**
- Consumes: Task 2's `EachLoopDetector.feed/reset`, `EachLoopDetector.REPEATS`; `ctx.notice(source: String, text: String): Message` (ContextBuilder.kt:160); `Feedback.format(key, vararg vars)`; `track.markRecovery()` (StepTrack).
- Produces: the feedback key `loop.repeating_items`, with the placeholder `{{count}}`.

- [ ] **Step 1: Add the feedback copy**

Append to the end of `prompts/feedback/loop.md` (self-contained, no leading or trailing whitespace, English):

```
## loop.repeating_items
Your last steps repeat the same cycle — open an item, act on it, go back — {{count}} times in a row. If items remain, finish them in one do_each call instead of one item per turn: item says how to recognize one entry (role/text/resourceId), body is the steps for ONE item with "@item" as targetText, ending with back.
```

- [ ] **Step 2: Wire it up (three small changes)**

a) After `val track = StepTrack()` add: `val eachLoop = EachLoopDetector()`
b) After the single-step path `track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)` (around line 779) add:

```kotlin
if (eachLoop.feed(verb, !isError)) {
    history += ctx.notice(EACH_LOOP_SOURCE, Feedback.format("loop.repeating_items", "count" to EachLoopDetector.REPEATS))
    track.markRecovery()
}
```

c) At the start of the `batch()` function (before `val (parsed, problem) = BatchRunner.parse(...)`) add: `eachLoop.reset()` (Review Focus #5).

Add to the companion object: `private const val EACH_LOOP_SOURCE = "each loop"`. The `Feedback` import is already in the file (StuckAdvice uses it), and `EachLoopDetector` is in the same package so it needs no import.

- [ ] **Step 3: Run PromptLayoutTest to confirm the key is referenced and there are no orphans**

Run: `./gradlew :core:test --tests '*PromptLayoutTest*' --console=plain -q`
Expected: PASS (`noOrphanFeedbackKeys` finds `"loop.repeating_items"` in the code).

- [ ] **Step 4: Regenerate the prompt goldens and run all prompt tests**

Run: `./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots --console=plain -q && ./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' --console=plain -q`
Expected: the first command regenerates `feedback-loop.txt`; the second passes.
Then run `git diff --stat core/src/test/resources/prompt-surface/` to confirm only `feedback-loop.txt` changed (if any other file changed, stop and verify why).

- [ ] **Step 5: Run the AgentLoop-related tests**

Run: `./gradlew :core:test --tests '*AgentLoop*' --tests '*StuckAdvice*' --console=plain -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/agent/AgentLoop.kt core/src/main/resources/prompts/feedback/loop.md core/src/test/resources/prompt-surface/feedback-loop.txt
git commit -m "feat(agent): nudge towards do_each when item-by-item work repeats 3 times, back to the main model after the nudge"
```

---

### Task 4: do_each.md few-shot

**Files:**
- Modify: `core/src/main/resources/prompts/tools/do_each.md` (the example sentence at the end)
- Modify (regenerate): `core/src/test/resources/prompt-surface/tools.txt` (and any golden that renders the do_each description)

**Interfaces:**
- Consumes: none (copy only); the op set of a do_each body follows the enum at `DoEach.kt:50` (tap/type/clear/scroll/swipe/key/back/wait/assert/observe; `wait` takes `whenText`/`timeoutMs`).
- Produces: no interface.

- [ ] **Step 1: Swap the example**

The existing example sentence in `do_each.md`:

`Example — open each item, read its page, go back: do_each(item={"role":"Button"}, body=[{"op":"tap","targetText":"@item"},{"op":"observe"},{"op":"back"}], reveal="scroll").`

Replace it with (a realistic 4-step body that includes a wait):

`Example — open each entry, tap Like on its page, go back: do_each(item={"role":"Button"}, body=[{"op":"tap","targetText":"@item"},{"op":"wait","whenText":"Like","timeoutMs":3000},{"op":"tap","targetText":"Like"},{"op":"back"}]).`

Leave the rest of the text alone; the increment for the whole section is <200 token.

- [ ] **Step 2: Regenerate the goldens and run the prompt tests**

Run: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots --console=plain -q && ./gradlew :core:test --tests '*PromptSurface*' --console=plain -q`
Note: the `sizes.txt` golden belongs to `PromptLayoutTest.layerSizesRendered`, so regeneration must include `--tests '*PromptLayoutTest*'`; running only `*PromptSurface*` will not update it.
Expected: PASS; `git diff --stat core/src/test/resources/prompt-surface/` only touches files that contain the do_each description (tools.txt, and possibly a layer-class golden if it includes the full tool text; check each diff to confirm the content really is just this example sentence).

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/prompts/tools/do_each.md core/src/test/resources/prompt-surface/
git commit -m "docs(tools): replace the do_each example with a realistic 4-step body that includes a wait"
```

---

### Task 5: Full regression

**Files:** none (verification task).

- [ ] **Step 1: core full run**

Run: `./gradlew :core:test --console=plain -q`
Expected: PASS.

- [ ] **Step 2: app full run (note: not bare :app:test)**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: PASS.

- [ ] **Step 3: Commit (if there were fixes) and report**

If no commit is needed, just report: both test tasks are green, and the commit list of the `worktree-do-each-adoption` branch (`git log --oneline main..HEAD`). Re-testing with a LiveRun on a real device (adoption rate ≥1/3) is not part of this plan; it needs the user present and is done separately.
