# fix-ja-step-reason-hidden evidence

Date: 2026-11-21 · Branch: `fix/ja-step-reason-hidden` (based on main 7101e997, v0.1.50)

## Locating the defect (code reading + character-range facts)

- Introducing commit: f010acab (#765, released in v0.1.50).
- `ChatViewModel.kt`'s original `shouldHideRawReason`: on a non-Chinese UI, `reason.any { it in '\u4e00'..'\u9fa5' }` hides it.
- `languageDirective("ja")` (introduced in the same commit) requires the model to write every reason in Japanese; written Japanese necessarily mixes in kanji (e.g. search/settings/contacts …, all within U+4E00–9FA5), and kana (U+3040–30FF) is the only reliable character-level signal for telling Japanese from Chinese.
- Conclusion: on a Japanese UI the reasons the model writes as instructed are almost always hidden, and `StepRow.text` (both entry points: the step row and the collapsed-state summary) degrades to the localized title.

## Behavior matrix (before fix → after fix)

| UI | Reason | Before | After |
|---|---|---|---|
| ja | Japanese (kanji + kana) | hidden | shown |
| en and other non-Chinese | Chinese | hidden | hidden (unchanged) |
| en and other non-Chinese | Japanese | hidden | shown (rare instruction-breaking case; showing loses less information) |
| zh Simplified/Traditional | any | shown | shown (unchanged) |
| non-Chinese | Han only, no kana (settings check) | hidden | hidden (cannot be distinguished from Chinese, kept faithfully) |

## Test verification

Added/modified: `app/src/test/java/com/wangzan101/mika/ui/StepRowLanguageTest.kt`
- `testShouldKeepJapaneseReasonInNonChineseLocale`: on a non-Chinese UI, "tap the search button to find a contact" and "open the WeChat app" (original was Japanese) are not hidden; on a Chinese UI, Japanese is shown as usual.
- `testShouldHideKanjiOnlyReasonInNonChineseLocale`: "settings check" (original was Japanese) is still hidden (residual limitation).
- `testStepRowTextShowsJapaneseReasonUnderJapaneseUi`: with ui_locale=ja, `StepRow.text` returns the Japanese reason directly (end-to-end assertion).

**Counterfactual verification** (guard against a no-op test): after temporarily reverting the predicate to the old version `reason.any { it in '\u4e00'..'\u9fa5' }`,
`StepRowLanguageTest > testShouldKeepJapaneseReasonInNonChineseLocale FAILED`,
`StepRowLanguageTest > testStepRowTextShowsJapaneseReasonUnderJapaneseUi FAILED` (BUILD FAILED in 8s);
after restoring the fixed version the same tests were all green (BUILD SUCCESSFUL in 6s). The temporary change was applied and reverted by exact string replacement, without git checkout; a later `git diff` review showed only the real change remained (4 lines of implementation + 34 lines of tests).

## Full local test run

- `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` → BUILD SUCCESSFUL (5m; cli 89 tests OK)
- `python3 -m unittest discover -s tools/i18n` → 12 tests OK
- `python3 tools/i18n/translations.py check` → 3699 resources across 12 UI languages passed
- relayTest (Go) was not run locally: the change only touches the app-side display predicate, not core/relay code, so CI covers it.

## Not done (stated faithfully)

- No device walkthrough: it would need a Japanese UI plus a real model writing reasons in Japanese for an end-to-end task, which is costly and depends on model behavior; the predicate logic is already covered by unit tests with an injected ui_locale stub (including the counterfactual).
