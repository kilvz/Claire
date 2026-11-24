# Settings home page information slimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the confirmed settings home page prototype into single-line entries while keeping all dynamic status and exception-handling information.

**Architecture:** Modify only the home page's `ListRow` call sites and the success status card's parameters, without changing shared components. Secondary and tertiary pages keep using their own subtitles as they are.

**Tech Stack:** Kotlin, Jetpack Compose, Android Compose UI instrumentation tests.

**Spec:** `docs/design/specs/2026-11-22-settings-home-clarity-design.md`

## Global Constraints

- Change only the settings home page; do not touch other pages.
- Keep features, categories, navigation, dynamic values and exception-status descriptions.
- Do not delete string resources that other pages still use.
- Do not commit code unless the user asks separately.

## Review Focus

- The success status card should become concise, but exception states still keep problem details and fix actions; covered by the existing `statusCardTurnsIntoFixPrompt`.
- Static subtitles must not linger even when all optional entries appear at once; covered by the test added in this task.
- Dynamic values such as model, phone operation, notifications, version, capsule and archive must not disappear; covered by the existing status tests and the new assertions.
- Home page tap and switch behavior must not be affected by the visual pruning; covered by the existing `homeRowsOpenTheirPages`.
- The descriptions on task runs and other subpages must not change; constrain the files and call sites through the final diff, and run the whole `SettingsScreenTest`.

---

### Task 1: Slimming the home page's static subtitles

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt:477-580,714-727`
- Test: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: the existing `SettingsContent`, `StatusSummary`, `ListRow` and `StateCard` interfaces.
- Produces: no new or changed interfaces; only fewer optional subtitles passed in by the home page.

- [ ] **Step 1: Write the failing test**

Add `homeOmitsStaticEntrySubtitles`, rendering the home page with all optional callbacks, `onPersonalStart` and `autoArchiveDays = 7`. Assert that none of the following text exists: success status details, model catalog, call method, WeChat description, proactive-capability description, task runs catalog, capsule description, persona description, notification description, personal-assistant recap, memory catalog, backup description, archive description, common tasks description, app access description, SMS default status, advanced catalog. Also assert that `DeepSeek`, `可用` (Available), `正常` (Normal), `7 天` (7 days) and the version value still exist.

- [ ] **Step 2: Run the test and confirm it fails**

Run:

```bash
ANDROID_SERIAL=A94VUT2C28001694 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.settings.ui.SettingsScreenTest
```

Expected: the new test fails because the home page still shows static subtitles.

- [ ] **Step 3: Implement the home page slimming**

In `SettingsContent`, omit the static subtitles for model, AI call answering, WeChat, proactive capability, task runs, floating capsule, persona, notifications, open personal assistant on launch, memory & skills, backup & data, auto-archive conversations, common tasks, app access, SMS verification-code sign-in and the advanced entry. Keep the `first`, callback, switch and trailing parameters. In `StatusSummary`, the success branch no longer passes `settings_all_ready_detail` when calling `StateCard`, while the exception branch stays unchanged.

- [ ] **Step 4: Run the target test and confirm it passes**

Run:

```bash
ANDROID_SERIAL=A94VUT2C28001694 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.settings.ui.SettingsScreenTest
```

Expected: all of `SettingsScreenTest` passes, including the new slimming test, the original navigation tests and the exception-status tests.

- [ ] **Step 5: Check the scope of changes**

Run: `git diff --check` and `git diff --stat` to confirm there are no whitespace errors, that production code changed only `SettingsScreen.kt`, that tests changed only `SettingsScreenTest.kt`, and that no secondary or tertiary pages were touched. Do not commit.
