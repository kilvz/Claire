## Context

#747 (unreleased) introduced the key pool: `KeyPoolManager` distinguishes two kinds of failure — rate-limit cooldown on 429 (`markRateLimited`, automatically unfrozen after 60 seconds by default) and permanent exhaustion on 401/403 (`markExhausted`). On the model side, `GeminiModel` / `OpenAiCompatModel` call back `onRateLimit(key, retryAfterMs)` for all three states 429/401/403 and locally switch to the next key to retry once; `ProviderStore.buildModel` wires that callback to `KeyPoolManager.shared.markRateLimited` — all three states get the same cooldown.

`markExhausted` thus becomes dead code (called only by tests). An invalid key revives after 60 seconds → is picked by rotation → 401 → is cooled down again, looping forever. The callback lacks an HTTP status parameter, so the wiring site cannot route by status.

kkkaqi's open PR #758 refactors the in-model "switch key and retry" into `requestWithKeyRotation`, but the callback still takes two parameters and still calls `markRateLimited` for everything — this gap is unclaimed. This change is implemented independently on main; if #758 lands first, the status parameter can just be carried into its new helper during rebase (the change points are of the same origin, a mechanical merge).

## Goals / Non-Goals

**Goals:**
- Keys that return 401/403 are marked `Exhausted` in the pool and no longer revive automatically; 429 behavior is unchanged.
- The callback carries the HTTP status code and is named truthfully (`onKeyRejected`).
- `:core` and `:app` unit tests cover: 429 → cooldown, 401/403 → exhaustion, and a single key returning 401 still throws honestly.

**Non-Goals:**
- Do not change the rotation/unfreeze/re-registration policy (kkkaqi is doing that in #757).
- Do not change the structure of the in-model switch-key retry (kkkaqi is doing that in #758).
- Do not add a speculative "quota exhaustion is also permanent" classification for 429 — the Retry-After semantics stay as they are.

## Decisions

1. **Add `status: Int` to the callback and rename it `onKeyRejected`** — #747 is unreleased, so there is no compatibility burden; the old name `onRateLimit` is misleading for 401/403.
2. **Routing lives at the `ProviderStore` wiring site** (429 → `markRateLimited`, 401/403 → `markExhausted`), with the model side only reporting the status truthfully and applying no policy — keeping the `:core` model classes decoupled from pool policy; the existing 401 → exhaustion case in `KeyPoolManagerTest` is exactly this contract.
3. **The revival path of `Exhausted` stays as it is** (when re-saving the configuration, the state is kept if the key text is unchanged): consistent with #757's "preserve state on re-registration" direction; when all keys are exhausted, `activeApiKey` falls back to the first key and reports the error honestly, behaving the same as a single bad key.

## Risks / Trade-offs

- **Textual conflict with #758**: the same batch of functions, so a small rebase is needed if it lands first. The change is small and orthogonal in direction (it refactors the retry structure, this change only touches the callback signature and wiring), so the conflict can be resolved mechanically.
- **A sporadic 401 from an individual gateway** (auth backend jitter) permanently marks the key: consistent with `KeyPoolManager`'s own documented semantics ("401 Unauthorized / Invalid Key → permanently Exhausted"); the user can reset it by re-saving the key or re-entering settings, and in exchange a dead key no longer poisons rotation every minute — a net positive.
