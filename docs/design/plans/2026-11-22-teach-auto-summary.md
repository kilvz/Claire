# Teach Claire Auto-Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a demonstration ends, automatically turn the trace into a skill: mechanical human steps such as the home screen, icons and the address bar are folded into Claire's direct actions, and the summary page shows the finished skill for the user to accept.

**Architecture:** Add a deterministic `TeachRoute.fold` in core (it folds only launcher steps and fails open when detection fails); the prompt and `TeachDraft` receive the folded route and instruct the model to rewrite mechanical browser/search actions into direct actions. In the app layer, `TeachApps` uses system intents to detect the home screen and the default browser, `TeachRuntime.end()` triggers summarizing automatically, and `TeachScreen` becomes a stateless group of pages (intro / demonstrating / summarizing / summary / learned), with the summary body reusing `SkillReadingView`.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, Gradle(`:core` pure JVM, `:app` unit/androidTest).

**Spec:** `docs/design/specs/2026-11-22-teach-auto-summary-design.md`

## Global Constraints

- Generic Android: look only at system intents and package names; never hard-code any launcher/browser/app package name.
- Do not store the raw demonstration input; password fields and sensitive matches keep using the existing redaction in `TeachTrace`, which is not relaxed.
- Sync every added/changed string across the 12 language files (base en, zh, b+zh+Hant, ja, ko, ar, fa, es, pt, ru, in, tr); `StringResourcesTest` must be green.
- Develop on a worktree branch, never touch main; do not change the version number outside a release.
- Use `./gradlew :app:testDebugUnitTest` for unit tests (not bare `:app:test`, which OOMs); use `./gradlew :core:test` for core.
- Do not add screenshots/screen recordings; do not touch robot execution or the "post-task self-learning" pipeline.

## Review Focus

1. With a third-party launcher (Nova, Lawnchair), or when the home screen cannot be detected: the route must be kept as-is, and in-app steps must not be dropped by mistake.
2. When the demonstration starts inside an app (there are no home-screen steps): do not synthesize a "Launch" step from the package name, and the skill entry must still name the right app.
3. Switching apps mid-demonstration: each switch folds into one "switch to / back to" line, home-screen taps must not be written into the skill, and nothing may stack up repeatedly.
4. Draft saved but session not persisted (the process is killed while summarizing): on recovery, reuse the existing pending draft by `learned_from=<traceId>`; do not regenerate and do not lose the trace.
5. No model, or summarizing failed: the summary page gives a structured on-device skill plus a clear explanation + retry/start over, and never falls back to the raw step list.

---

### Task 1: Prototype sign-off (design gate, not code)

**Files:**
- Create: `/tmp/mika-teach-redesign.html` (local prototype, not committed)

**Interfaces:**
- Consumes: the summary page's content and copy from this spec.
- Produces: the user's verbal sign-off on the prototype; the Task 5 UI implementation does not start without it.

- [x] **Step 1: Build the five-state prototype**

A single-page HTML prototype that reuses the existing Claire visual tokens (one light and one dark version, phone width): intro, demonstrating, summarizing, summary (with the fold note and the five-section body), learned. Clickable to switch between states.

- [x] **Step 2: Show it to the user and wait for sign-off**

Send the user a screenshot + the local file path; ask explicitly whether the information hierarchy and the actions on the summary page are right. Start Task 5 only after the sign-off; Tasks 2–4 can start in parallel.

- [x] **Step 3: Record the sign-off**

Confirmed 2026-11-22: intro / summarizing / summary / learned follow the prototype; demonstrating keeps the existing implementation (top hint + existing controls) and the layout is not redone. The prototype stays at `/tmp/mika-teach-redesign.html` and is not committed.

---

### Task 2: Route folding (deterministic)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/TeachRoute.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachRouteTest.kt`

**Interfaces:**
- Consumes: `TeachStep(id, atMs, packageName, window, kind, nodeName, viewId, insufficient, sensitiveOmitted, needsClarification, ...)`, `TeachKind`, and the existing `teachLine(step)`.
- Produces:
  - `data class RouteStep(val app: String?, val text: String, val kind: TeachKind?)` — `kind == null` means a synthesized launch/switch step; `text` is for the prompt and for humans to read.
  - `data class RouteApp(val packageName: String, val label: String, val browser: Boolean)`
  - `data class TeachRoute(val steps: List<RouteStep>, val apps: List<RouteApp>, val folded: List<String>)`
  - `object TeachRoute { fun fold(steps: List<TeachStep>, launcherPackages: Set<String>, browserPackages: Set<String>, label: (String) -> String): TeachRoute }`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun foldsLauncherStepsIntoOneDirectLaunch() {
    val steps = listOf(
        step(0, "com.android.launcher3", TeachKind.Window, title = "Home screen"),
        step(1, "com.android.launcher3", TeachKind.Click, node = "Xiaohongshu"),
        step(2, "com.xingin.xhs", TeachKind.Window, title = "Home"),
        step(3, "com.xingin.xhs", TeachKind.Click, node = "Search"),
    )
    val route = TeachRoute.fold(steps, setOf("com.android.launcher3"), emptySet()) { it }
    assertEquals(2, route.steps.size)
    assertTrue(route.steps[0].text.startsWith("Launch"))
    assertTrue(route.steps[1].text.contains("tapped 'Search'"))
    assertEquals(2, route.folded.size)
    assertTrue(route.folded.any { it.contains("Xiaohongshu") })
    assertEquals(listOf("com.xingin.xhs"), route.apps.map { it.packageName })
}

@Test fun appSwitchMidDemoBecomesOneSwitchStep() { /* A → launcher → B ⇒ "Switch to B"; consecutive home-screen steps fold only once */ }

@Test fun unknownLauncherLeavesEveryStepUntouched() {
    // launcherPackages is empty: the step count maps one-to-one to text lines, folded is empty
}

@Test fun demoStartedInsideAnAppDoesNotInventALaunchStep() { /* the first step is already inside an app ⇒ no launch step is synthesized */ }

@Test fun marksTheDefaultBrowserForThePrompt() { /* steps contain a browser package ⇒ browser=true in apps */ }
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.learn.teach.TeachRouteTest'`
Expected: FAIL, `TeachRoute` is not defined.

- [ ] **Step 3: Implement `TeachRoute.fold`**

Rule: iterate over the steps; drop any step whose package is in `launcherPackages` and record it in `folded` (using `teachLine`'s human wording, e.g. "tapped 'Xiaohongshu' on the home screen"); when the flow lands from a launcher onto a non-launcher package and that package differs from the previous kept step's package, insert a synthesized step before the kept step — "Launch <label>" the first time, "Switch to <label>" when switching, and "Back to <label>" when returning to a previous package; dedupe `apps` in order of appearance, and set `browser` from a hit in `browserPackages`. `label` is provided by the caller (an empty label falls back to the package name).

- [ ] **Step 4: Run the test and confirm it passes**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/TeachRoute.kt core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachRouteTest.kt
git commit -m "feat(teach): fold launcher steps into direct app launches"
```

---

### Task 3: Point the prompt and the draft at the folded route

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/Teach.kt:312-421` (`TeachDraft`)
- Modify: `core/src/main/resources/prompts/roles/teach_skill.md`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachDraftTest.kt`

**Interfaces:**
- Consumes: `TeachRoute / RouteStep / RouteApp` from Task 2.
- Produces:
  - `TeachDraft.request(session: TeachSession, route: TeachRoute): String`
  - `TeachDraft.assemble(session: TeachSession, route: TeachRoute, name: String): String`
  - `TeachDraft.draft(session: TeachSession, route: TeachRoute, model: ChatModel?, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String>, nameTaken: (String) -> Boolean, nowMs: Long): PersonalSkill?`
  - The new `TeachSkill.PREFIX` usage is unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun requestTellsTheModelToUseDirectLaunchesAndLinks() {
    val req = TeachDraft.request(session(), route())
    assertTrue(req.contains("Launch com.xingin.xhs"))
    assertTrue(req.contains("open directly"))
    assertTrue(req.contains("do not write") && req.contains("home screen"))
}

@Test fun requestCarriesTheFoldedHumanSteps() {
    assertTrue(TeachDraft.request(session(), route()).contains("omitted thanks to Claire's direct actions"))
}

@Test fun localAssemblyNeverMentionsTheDesktop() {
    val md = TeachDraft.assemble(session(), route(), "xhs-search")
    assertFalse(md.contains("home screen"))
    assertTrue(md.contains("Launch com.xingin.xhs"))
}

@Test fun localAssemblyKeepsTheEvidenceCaveats() {
    val md = TeachDraft.assemble(session(), route(), "xhs-search")
    assertTrue(md.contains(TeachSkill.NOT_ABSENT))
    assertTrue(md.contains(TeachSkill.NOT_TRIAL_RUN))
    assertTrue(md.contains(TeachSkill.STOP_IF_UNMATCHED))
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.learn.teach.TeachDraftTest'`
Expected: FAIL (compile error: signature mismatch).

- [ ] **Step 3: Change `request` and `assemble`**

- `request`: output "Goal", "App: <label>(<pkg>)<browser? yes>", "already omitted thanks to Claire's direct actions: <folded>" and the route steps (synthesized steps use their text); remove the output of raw launcher steps; keep "the typed text was not saved".
- `assemble`: the entry section becomes "Claire launches <first app> directly"; steps use `route.steps`; the success criteria still take the package/window of the last kept step; caveats are kept as-is; the home screen/icons no longer appear at the start or the end.
- `draft`: takes one more `route` parameter and passes it to both.

- [ ] **Step 4: Rewrite the rules section of the `teach_skill.md` prompt**

Append after the existing output format and the five requirements (replacing the "not a step-by-step macro" item with something more specific):

```
Some of the demonstrator's actions are only there because a human was doing them; they do not count as knowledge:
- Going back to the home screen, finding an icon, opening an app → write as "Launch/open <App>"; do not write about the home screen and icons.
- Opening a browser, tapping the address bar, typing, tapping search → write as "Open <link>" or "Search on <site>"; the specific link/keywords come from the request, and the demonstration did not save them.
- Tapping an input field, typing, submitting → combine into one step, "Enter <content> and submit".
Keep what really decides the outcome: entries, specific buttons, options, criteria, and when to stop.
Do not write waiting on the same screen, repeated scrolling, or meaningless back-navigation.
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/Teach.kt core/src/main/resources/prompts/roles/teach_skill.md core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachDraftTest.kt
git commit -m "feat(teach): summarize demonstrated routes with direct actions"
```

---

### Task 4: Automatic summarizing and accepting

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/teach/TeachApps.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/teach/TeachRuntime.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/teach/ui/TeachScreen.kt` (change only the call sites so it compiles; the layout is redone in Task 5)
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/TeachPending.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachPendingTest.kt`

**Interfaces:**
- Consumes: Tasks 2/3; `PersonalSkills.drafts()[id]`, `approve(id)`, `reject(id)`; `ConfirmationNotifier.appLabel(context, pkg)`.
- Produces:
  - `object TeachApps { fun launchers(context: Context): Set<String>; fun defaultBrowsers(context: Context): Set<String>; fun label(context: Context, packageName: String): String }`
  - `object TeachPending { fun find(drafts: Map<String, PersonalSkill>, traceId: String): Pair<String, PersonalSkill>? }` (matches `learned_from == TeachSkill.PREFIX + traceId`)
  - `TeachUi(session, notice, hint, draft: PersonalSkill? = null, folded: List<String> = emptyList(), basic: Boolean = false, acceptedName: String? = null)`
  - `TeachSession(..., acceptedName: String? = null)`
  - `TeachRuntime.end()` summarizes automatically; `TeachRuntime.accept()`; delete `generate()/annotate()/remove()`.

- [ ] **Step 1: Write the failing `TeachPending` test**

```kotlin
@Test fun findsTheDraftAlreadyStoredForTheSameTrace() {
    val drafts = mapOf("d1" to skill(learnedFrom = "teach:t-1", name = "a"), "d2" to skill(learnedFrom = "teach:t-2", name = "b"))
    assertEquals("d1", TeachPending.find(drafts, "t-1")?.first)
}
@Test fun ignoresDraftsFromOtherTraces() { assertNull(TeachPending.find(mapOf("d1" to skill(learnedFrom = "teach:t-2")), "t-1")) }
```

Run: `./gradlew :core:test --tests '*TeachPendingTest'` → FAIL; then implement → PASS.

- [ ] **Step 2: Implement `TeachApps`**

`launchers`: `queryIntentActivities(intent, 0)` for `ACTION_MAIN + CATEGORY_HOME`; on exception fall back to an empty set. `defaultBrowsers`: `queryIntentActivities(intent, MATCH_DEFAULT_ONLY)` for `ACTION_VIEW` + `http://`; an empty set is legal. `label` delegates straight to `ConfirmationNotifier.appLabel`. Everything is `internal`.

- [ ] **Step 3: Change `TeachRuntime`**

- `end()`: set the phase to `Drafting` and immediately `scope.launch { summarize(snapshot) }`, instead of only stopping at Reviewing.
- `summarize` = the body of the old `generate()`: first reuse via `TeachPending.find(skills.drafts(), snapshot.id)`, otherwise `build(snapshot)`; `build` uses `TeachRoute.fold(snapshot.steps, TeachApps.launchers(app), TeachApps.defaultBrowsers(app), { TeachApps.label(app, it) })`; when done, the phase is `Reviewing`, `draftId` is persisted, and `_ui` carries `draft/folded/basic` (basic = no model configured).
- `accept()`: verify the draft still exists → `skills.approve(draftId)` → write `acceptedName` into the session, phase `Saved`, save and publish; on failure emit `TeachNotice.DraftFailed`.
- `recover()`: re-summarize for `Drafting` and for "`Reviewing` with no draftId"; when `Reviewing` has a draftId, fetch it back from `drafts()` and publish it; if the draft is not found → stay `Reviewing` + TeachNotice.DraftFailed.
- Delete `generate()/annotate()/remove()` and the UI references to `TeachEdit`; `discard()` still rejects the draft and deletes the trace.

- [ ] **Step 4: Change `TeachScreen`'s call sites (temporary version)**

Remove the `generate()`/`annotate()`/`remove()` calls and the per-step editor; `Drafting` shows "Writing this into a skill…", and when `Reviewing` has a draft it first shows `SkillReadingView` directly plus a "Keep this skill" button (minimum viable; Task 5 redoes the layout and copy per the prototype).

- [ ] **Step 5: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/teach/TeachApps.kt app/src/main/java/com/wangzan101/mika/teach/TeachRuntime.kt app/src/main/java/com/wangzan101/mika/teach/ui/TeachScreen.kt core/src/main/kotlin/com/wangzan101/mika/core/learn/teach/TeachPending.kt core/src/test/kotlin/com/wangzan101/mika/core/learn/teach/TeachPendingTest.kt
git commit -m "feat(teach): summarize automatically after demonstration"
```

---

### Task 5: Page redesign and copy

**Files:**
- Rewrite: `app/src/main/java/com/wangzan101/mika/teach/ui/TeachScreen.kt`
- Modify: `app/src/main/res/values*/strings_teach.xml` (12 files)
- Test: `app/src/androidTest/java/com/wangzan101/mika/ui/TeachScreenTest.kt` (new)

**Interfaces:**
- Consumes: `TeachUi` from Task 4, `TeachRuntime.accept/discard/resume/end/pause`; `SkillReadingView(markdown)`, `skillPreviewBody(markdown)`, `FrontMatter.parse`.
- Produces (stateless, testable):
  - `internal data class TeachSummaryView(val display: String, val description: String, val body: String, val folded: List<String>, val basic: Boolean)`
  - `internal fun TeachSummaryPage(view, onAccept, onRedo, onDiscard)`
  - `internal fun TeachIntroPage(goal, hint, notice, onGoal, onStart)`
  - `internal fun TeachDonePage(name, description, onBackToChat, onTeachAgain)`
  - `TeachScreen` only dispatches state and calls the runtime.

- [ ] **Step 1: Write the failing UI test**

```kotlin
@Test fun summaryShowsTheSkillSectionsAndFoldedNote() {
    compose.setContent { MikaTheme { TeachSummaryPage(fixture(), {}, {}, {}) } }
    compose.onNodeWithText("Entry").assertIsDisplayed()
    compose.onNodeWithText("What counts as done").assertIsDisplayed()
    compose.onNodeWithText("2 manual steps folded away automatically").assertIsDisplayed()
    compose.onNodeWithTag("teach-accept").assertHasClickAction()
}
@Test fun summaryWithoutFoldedStepsHidesTheNote() { /* folded = emptyList ⇒ the node does not exist */ }
@Test fun summaryWithoutModelExplainsTheLocalSummary() { /* basic = true ⇒ the on-device summary note appears */ }
@Test fun acceptButtonCallsBack() { /* tap teach-accept ⇒ the callback runs once */ }
@Test fun introStartCallsBack() { /* tap teach-start ⇒ callback */ }
@Test fun donePageOffersChatAndTeachAgain() { /* both buttons are shown */ }
```

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest`
Expected: FAIL (TeachSummaryPage is not defined).

- [ ] **Step 2: Write the copy (zh as the baseline)**

Add/replace (`strings_teach.xml`):
- `teach_intro_body` "Do it once in front of me. When you finish, I'll write it into a skill you can use next time."
- `teach_disclosure_short` "Only what happens on this phone is read; passwords, verification codes and payment details are not recorded. Details"
- `teach_summarizing` "Writing this into a skill…"
- `teach_summary_head` "Written the way Claire would do it, not a replay of your taps."
- `teach_folded_n` "%1$d manual steps folded away automatically"
- `teach_basic_note` "No model was connected this time, so this was written by local rules. Once a model is configured it can understand things in more detail."
- `teach_accept` "Keep this skill"
- `teach_redo` "Demonstrate again"
- `teach_retry` "Try writing it again"
- `teach_accepted_title` "Learned"
- `teach_accepted_body` “%1$s” is ready. Next time you mention something similar, I'll think of it first.
- `teach_back_chat` "Back to chat"
- `teach_teach_again` "Teach another"
- `teach_no_steps_again` "No actions were seen this time. Try again?"
- Delete the now-unused `teach_review`, `teach_generate`, `teach_correct`, `teach_clarify`, `teach_remove`, `teach_insufficient_note`, `teach_draft_ready`, `teach_open_drafts`.

Translate the other 11 languages with the same meaning; watch plural forms in Arabic, and keep every `%1$s/%1$d` placeholder consistent.

- [ ] **Step 3: Rewrite `TeachScreen.kt`**

- Intro: top bar + a small explanatory card + goal input (the app's existing input style) + "Start demonstration"; after starting, `moveTaskToBack` stays as it is.
- Demonstrating and interrupted: keep the existing `Live`/`Interrupted` layout and buttons (already confirmed not to be redone); show no step list.
- Summarizing: explanatory text + cancel; show no step list.
- Summary: TeachSummaryPage(view, onAccept, onRedo, onDiscard); `display/description` come from `FrontMatter.parse(draft.markdown)` and the body uses `skillPreviewBody`.
- Learned: TeachDonePage(name, description, onBackToChat, onTeachAgain); back to chat uses `onBack`.
- Demonstrate again: `runtime.discard()`, then `prefill` the goal back into the intro.
- `testTag`: `teach-form/teach-goal/teach-start/teach-summary/teach-folded/teach-accept/teach-redo/teach-discard/teach-done`.

- [ ] **Step 4: Run the tests and i18n**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest` → PASS.
Run: `./gradlew :app:testDebugUnitTest` → PASS (including `StringResourcesTest`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/teach/ui/TeachScreen.kt app/src/main/res/values*/strings_teach.xml app/src/androidTest/java/com/wangzan101/mika/ui/TeachScreenTest.kt
git commit -m "feat(teach): show an auto-written skill instead of raw steps"
```

---

### Task 6: Real-device end-to-end and regression

**Files:** no code changes (unless a bug is found).

**Interfaces:**
- Consumes: every artifact of the first five tasks.
- Produces: the device verification conclusions; any bug found goes back to the matching task to be fixed.

- [x] **Step 1: Install and run a real-device demonstration**

`./gradlew :app:installDebug`; on A94VUT2C28001694 start from Teach Claire and demonstrate "open Xiaohongshu → search for renovation → open the first result → favorite → done". Confirm with a screenshot: the first step of the summary is "Launch Xiaohongshu" and contains no "home screen/icons"; the fold note count is reasonable; all five sections of the body are present.

- [x] **Step 2: Accepting and usability**

Tap "Keep this skill" → the learned page; go to Settings › Memory › Skills and confirm it exists in trial state; go back to the chat and say "search once more with the skill you just learned", and confirm the skill can be retrieved/read.

- [ ] **Step 3: Web example (limited on this machine, could not be completed on a real device)**

Demonstrate a web action once more (a search in the default browser) and confirm the summary is written as "open/search" with no address-bar steps; if the model did not rewrite it, record the prompt evidence and fix Task 3.

Measured on this machine: taps inside Edge/WebView pages do not produce TYPE_VIEW_CLICKED, so demonstrating with only the browser captures nothing but Window steps; after folding there are no real actions and the page reports "no steps were left" (a window-only unit test was added to lock this behavior). The browser-rewrite rules are currently covered only by `TeachDraftTest`; add this real-device case once the capture layer can receive WebView events.

- [x] **Step 4: Failures and recovery**

- 0 steps: end immediately after starting → shows "no actions were seen", and you can start over.
- Killed while summarizing: press back / force-kill Claire during summarizing, then reopen Teach Claire → it resumes summarizing automatically or shows retry, and the trace is not lost.
- No model: temporarily switch to a profile with no model configured? If that is costly, substitute Task 3's on-device summarization unit test + code review, and only check the with-model path on a real device.

- [x] **Step 5: Regression**

Run: `./gradlew :core:test :app:testDebugUnitTest` → PASS.
Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest,com.wangzan101.mika.chat.ui.ChatAttachmentsTest` → PASS.
Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` → PASS.
Do not run the full connectedAndroidTest.

- [x] **Step 6: Commit (if there are small fixes)**

```bash
git add -A
git commit -m "fix(teach): device-test follow-ups"
```
