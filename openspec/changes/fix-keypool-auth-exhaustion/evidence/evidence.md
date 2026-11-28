# fix-keypool-auth-exhaustion evidence

## 2026-11-20 local verification (JVM layer)

Baseline: `main` @ `0c258ac9` (#747 merged, not released); branch `fix/keypool-auth-exhaustion`.

1. **Locating the defect**
   - `grep -rn markExhausted --include="*.kt" core/ app/`: zero calls in production code (only `KeyPoolManagerTest` uses it).
   - `ProviderStore.buildModel`'s two wiring sites (OpenAiCompat/Gemini) always call `markRateLimited` for 429/401/403 (60s default cooldown), so a bad key revives when the cooldown expires and keeps being selected by rotation.
   - #747's commit message states "Automatically mark rate-limited **or exhausted** keys on HTTP 429, 401, 403", but the exhaustion semantics were never wired.

2. **Tests after the fix** (`./gradlew` locally, JDK 17)
   - `:core:test --tests …KeyPoolManagerTest --tests …OpenAiCompatModelTest --tests …GeminiModelTest`: passed (5 new cases: 401 → exhausted and does not revive, 403 → exhausted, 429 → cooldown recovers, OpenAi callback carries status + switches key, Gemini callback carries status + switches key).
   - `:app:testDebugUnitTest --tests …ProviderStoreTest`: 90/90 pass (new real-wiring case `authFailureMarksKeyExhaustedThroughWiredModel`: MockWebServer returns 401/200; asserts that after `sk-bad` is marked exhausted, `getActiveKey` has only `sk-good`, and the two requests' Authorization are sk-bad/sk-good respectively).
   - Full regression: `:core:test :app:testDebugUnitTest`, `:core:cliTest`, `python3 -m unittest discover -s tools/i18n` (12 OK), `python3 tools/i18n/translations.py check` (3667 resources OK) all pass.

3. **Reproducing the behavior before the fix** (the same cases' semantics on main)
   - main has no test covering this wiring; before the fix a 401 only entered a 60s cooldown (the behavior of `KeyPoolManagerTest.rateLimitedKeyBypassedAndAutoRecovers` holds for 401 too), and after expiry `getActiveKey` selected the bad key again.

## Notes

- The model HTTP layer is pure JVM code with no device-specific behavior, so no dedicated device test was done (real-device provider config is the user's daily data, left untouched as usual).
- No overlap with the open PR #758 (`requestWithKeyRotation` refactor) although in the same area: it keeps the two-argument callback and does no status routing; if it merges first, a small rebase is needed.
