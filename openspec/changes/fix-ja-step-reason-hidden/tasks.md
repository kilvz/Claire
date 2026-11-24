## 1. Implementation

- [x] 1.1 `ChatViewModel.StepRow.shouldHideRawReason`: change the check to "contains Han characters (U+4E00–U+9FA5) and no kana (U+3040–U+30FF)", and update the comment to state that kana is the Japanese/Chinese discriminator
- [x] 1.2 `StepRowLanguageTest`: add scenarios — non-Chinese UI + Japanese reason (Han characters + kana) → not hidden; non-Chinese UI + only Han characters, no kana → still hidden; Chinese UI + Japanese reason → shown

## 2. Verification

- [x] 2.1 `./gradlew :app:testDebugUnitTest` all green (existing StepRowLanguageTest scenarios do not regress)
- [x] 2.2 `./gradlew :core:test :core:cliTest` unaffected (pure app-side change, run once as a backstop)
- [x] 2.3 Evidence goes into `evidence/evidence.md`: the before/after behavior matrix of the check, and a test output summary
