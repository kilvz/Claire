# Settings secondary/tertiary page subtitle slimming — Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) to implement task-by-task, TDD per task.

**Goal:** Following the confirmed high-fidelity demo (`.superpowers/sdd/settings-secondary-demo.html`), delete the subtitles in the 6 secondary/tertiary settings pages that "restate the title / list a catalog / show a static default", move "current value / count / status" to the trailing row value, and keep "risk consequences / trigger conditions / option differences / system paths".

**Architecture:** All changes stay inside the existing Compose components, either passing one fewer `subtitle` argument or moving the `subtitle` argument to a trailing `RowValue`; change no interfaces and add no strings.

**Tech Stack:** Jetpack Compose, Kotlin, stringResource / tr multilingual resources (`values*/strings_*.xml`).

**Spec:** `docs/design/specs/2026-11-22-settings-home-clarity-design.md` (home page design); the visual decisions for secondary pages are in the demo file.

## Global Constraints

- Do not change categories, icons, features or hierarchy; touch only subtitles and trailing values.
- **This plan adds no strings at all** (the "shortened subtitles" and "recovery-page collapsing" that need new copy are covered by the Ruling at the end and handled separately).
- Do not commit (following the repo rule: commit only when explicitly asked).
- Tests run on the physical device `A94VUT2C28001694` (`ANDROID_SERIAL=A94VUT2C28001694`), not an emulator.

## Decision criteria (confirmed in the demo)

- **Delete**: restated titles, catalogs listing detail pages, static default values.
- **Move right**: current value, count, switch/connection status.
- **Keep**: risk consequences, trigger conditions, option differences, system paths.

## Task breakdown

### Task 1: Phone operation (`SettingsSubPages.kt` `PhoneOpsScreen`)
- Delete `settings_a11y_sub` (the accessibility service subtitle).
- Delete `phone_repair_sub` (the repair connection subtitle).
- The Shizuku diagnostic `phone_shizuku_detail` is shown only when not ready.
- System version: when normal, the trailing value changes from 「满足」 (Meets requirements) to the version number (`settings_android`) and the subtitle is emptied; the `tooOld`/`focusWarning` branches are untouched.
- **Keep**: the built-in assistant `statusLine` (dynamic diagnostics) and `phone_unpair_sub` (dangerous consequence).

### Task 2: Task runs (`SettingsSubPages.kt` `DuringWorkScreen`)
- Delete `settings_keep_on_sub`, `settings_mute_sub`, `settings_show_task_cost_sub`, `settings_pip_operate_sub`.
- The 4 current values — step limit / maximum scheduled task runtime / sandbox time limit / token budget: from the subtitle slot → trailing `RowValue` + `RowChevron`.
- **Keep**: autonomous mode (payment risk), pocket anti-mistouch (trigger conditions), the three-level operating-pace differences, the per-app settings description and the parallel-task reason.

### Task 3: Floating capsule (`SettingsSubPages.kt` `CapsuleScreen`)
- Delete `capsule_idle_opacity_detail`, `capsule_card_size_detail`.
- **Keep**: the explanation of the three modes' differences and the two footer risk notes.

### Task 4: Notifications (`SettingsSubPages.kt` `NotificationsScreen`)
- Delete the master switch subtitle (`NOTIF_APP_SUBTITLE_ON/OFF`).
- Delete `settings_scheduled_quiet_detail`.
- **Keep**: the trigger timing of the three channels and the NOTIF_FOOTER system path.

### Task 5: Recovery and repair (`RecoverySection.kt` `RecoverySection`)
- Delete `rec_default_on` (「默认打开」 (On by default)); keep `WORKING` (busy feedback).

### Task 6: Advanced (`SettingsScreen.kt` `AdvancedSettingsContent`)
- Delete `web_search_row_sub`.
- **Keep**: the network proxy `proxyNote` (dynamic scope description), Root `rootEntryLine` (combined status, see Ruling) and `quit_row_sub` (consequence).

## Ruling (decision record, written into the ledger when executing)

- **R1** Keep the built-in assistant `statusLine`: it is dynamic diagnostics (connected/failure reason), not static restatement.
- **R2** Keep Root's `rootEntryLine`: it is a combined summary of "auto-start assistant + root command status", which is too narrow to fit into a trailing value.
- **R3** Keep the network proxy `proxyNote`: it dynamically explains which connections it covers and whether the browser is supported.
- **R4** The 4 "shortened subtitles" (`pocket_completion_lock_summary`, `settings_self_config_sub`, `settings_self_operate_sub`, `settings_external_schedule_sub`) are deferred: they need new copy ×12 languages and are done separately.
- **R5** The recovery page's "collapse the 4 paragraphs of explanation into expandable details" is deferred: it needs new copy ×12 languages + collapse state, and is done separately.

## Review Focus

- System version row: when `tooOld` (below Android 12) it still shows 「不支持」 (Not supported); the "not supported" signal must not be lost just because the subtitle was deleted.
- Shizuku row: the diagnostic must still appear when not ready and is hidden only when ready — it must not be hidden across the board, leaving abnormal states with no hint at all.
- Recovery page `busy` state: the 「正在…」 (In progress…) feedback while toggling the switch must not disappear along with the deleted 「默认打开」 (On by default).
- The 4 values on task runs: after moving from the subtitle to the right, they must still open a dialog for editing (keep `onClick`).
- Notification master switch: when `o.appAllowed` is false, the 「去打开」 (Go turn on) link must still be tappable.
