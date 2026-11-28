## 1. Model callbacks carry the HTTP status

- [x] 1.1 `GeminiModel`: change `onRateLimit: ((String, Long?) -> Unit)?` to `onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?`, and pass the actual status code into both callbacks (complete / completeStreaming); the switch-key retry logic is untouched.
- [x] 1.2 `OpenAiCompatModel`: same as 1.1 (two call sites in total, complete / completeStreaming).

## 2. Routing by status at the wiring site

- [x] 2.1 `KeyPoolManager.markRejected(providerId, key, status, retryAfterMs)`: 429 → `markRateLimited`, 401/403 → `markExhausted`; both wiring sites in `ProviderStore.buildModel` call it instead.
- [x] 2.2 Keep the current behavior where `activeApiKey` falls back to the first key when the whole pool is exhausted (a single bad key reports the error honestly; the test `authFailureMarksKeyExhaustedThroughWiredModel` does not change that path).

## 3. Tests

- [x] 3.1 `:core` model layer: MockWebServer returns 401/403/429, assert the callback receives the corresponding status code and switches key to retry (OpenAiCompatModelTest +2, GeminiModelTest +1).
- [x] 3.2 `:app` wiring layer: `ProviderStoreTest.authFailureMarksKeyExhaustedThroughWiredModel` — the real buildModel goes through MockWebServer, and after a 401 only the good key remains in the pool.
- [x] 3.3 Regression: `./gradlew :core:test :app:testDebugUnitTest :core:cliTest` + all 12 items of `tools/i18n` + `translations.py check` green.

## 4. Verification and wrap-up

- [x] 4.1 Behavioral evidence for the mock endpoint goes into `evidence/evidence.md` (the model HTTP layer is pure JVM code, so no real-device task applies).
- [ ] 4.2 Commit the branch, push the fork, open a PR (title following the "current state → change" convention), and wait for all four CI groups to go green.
