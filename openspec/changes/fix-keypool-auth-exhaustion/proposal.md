## Why

#747 wired dynamic rotation into the key pool: `GeminiModel` / `OpenAiCompatModel` call back `onRateLimit` on HTTP 429, 401 and 403, and the `ProviderStore` wiring site always calls `KeyPoolManager.markRateLimited` (auto-revives after a 60-second cooldown). But `KeyPoolManager.markExhausted` (permanent invalidation on 401/403; #747's commit message says "Automatically mark rate-limited **or exhausted** keys on HTTP 429, 401, 403") has **zero calls** in production code — a bad 401/403 key is only cooled down for 60 seconds, revives when it expires, gets picked by rotation again, gets 401 again, forever.

Consequence: when a multi-key pool contains one invalid key (expired/deleted account/mistyped), every turn at that key wastes a request round trip; once per 60 seconds, never self-healing. The callback signature `(key, retryAfterMs) -> Unit` carries no HTTP status, so the wiring site cannot distinguish them even if it wanted to.

## What Changes

- The callbacks of `GeminiModel` / `OpenAiCompatModel` carry the HTTP status code: `onRateLimit: ((key: String, retryAfterMs: Long?) -> Unit)?` becomes `onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?` (#747 is unreleased, so the rename has no compatibility burden; the old name was already misleading for 401/403). The trigger set is still 429/401/403, and the in-model switch-key retry logic is unchanged.
- The `ProviderStore.buildModel` wiring site routes by status: 429 → `markRateLimited(key, retryAfterMs)`; 401/403 → `markExhausted(key)`, actually connecting the "exhausted" semantics.

## Capabilities

### New Capabilities

- `key-pool-auth-exhaustion`: the key pool permanently marks keys that return 401/403 (authentication failure/ban) as exhausted, instead of repeatedly reviving them through the 60-second rate-limit cooldown; 429 still temporarily avoids a key per Retry-After/default cooldown.

### Modified Capabilities

(None — #747 has not been released, and `key-pooling`'s external behavior is being corrected before release, so this does not constitute a change to a released capability.)
