# Read-only Web console: phone-side read-only endpoints (Claire Anywhere P0)

This is the first phase of "Claire Anywhere". This document covers only the **phone side**: the three read-only interfaces used by the Web console. The browser page and the gateway session are in another change; see upstream issue #372 for the design and phasing discussion, and PR #374 for the gateway side (another change in `companions/relay`).

Read-only — no dispatching work, no answering, no cancelling, and no touching any settings on the phone.

## Scope

- **Three new read-only endpoints on the phone**: conversation list, the turns of one conversation (paginated), and running tasks.
- **No change to the execution path**: no new tools, no loosening of approvals, no touching payments or takeover; answering, dispatching work and cancelling still go through the existing task API.
- **Gateway side**: the `/app` page, the login session and the `/web/api` read-only proxy are provided by another change in `companions/relay`; the phone is responsible only for the endpoints below.

## Trust boundary

```text
browser ──HTTPS/Cookie──> relay gateway ──WSS (paired device channel)──> phone
                                   │
                          the gateway holds the session's phone token in memory
```

- The phone still only accepts `Authorization: Bearer <phone access token>` and **rejects requests carrying an `Origin`** (to prevent DNS rebinding). The gateway is responsible for stripping the `Origin` and Cookie sent by the browser, swapping in the token it holds, and forwarding.
- The gateway therefore changes from "transparent forwarding only" to "holding the phone token for the duration of the session"; the token is not written to disk and becomes invalid when the gateway restarts. The gateway can still see the forwarded content (as with the existing CLI / MCP, this is not end-to-end encryption), so using your own trusted gateway is a prerequisite.
- These three endpoints add no new trust decisions: they use the same `RemoteAccess` as the existing `/api/v1`, with the same failure cooldown and private-address restrictions.

## HTTP contract

Authentication is the same as the task API (`RemoteAccess`), with the same failure cooldown and private-address restrictions. The read-only endpoints live under `/api/v1`:

### `GET /api/v1/conversations?limit=20`

`limit` 1–50, default 20. Most recent first; conversations with no content are not listed.

```json
{
  "conversations": [
    { "conversation": "8f…", "title": "Book a high-speed train ticket", "updated_at_ms": 1762400000000,
      "pinned_at_ms": 1762400001000, "turn_count": 6 }
  ]
}
```

### `GET /api/v1/conversations/{id}?from=0&limit=10`

- `limit` 1–10, default 8; when `from` is omitted the last segment is returned (so opening a conversation shows the latest turns first).
- The returned `from` is the actual starting point and `total` is the total number of turns; to page backwards use `from = current from − limit`.
- Over-long fields are truncated and marked with `truncated`: user text 2,000 characters, answer 4,000 characters, notes 500 characters, a single interjection 500 characters, and at most 10 interjections. Truncation is by code point and never cuts an emoji in half.
- At most 10 turns per page, with each turn's fields totaling about 35 KB (estimated at 3 bytes per CJK character), so a whole page stays within the relay's single-frame 1 MiB limit.

```json
{
  "conversation": "8f…", "title": "Book a high-speed train ticket", "total": 6, "from": 4,
  "turns": [
    { "index": 4, "user_text": "Move my booking to the afternoon", "outcome": "Changed…", "status": "succeeded",
      "run_id": "…", "steers": ["Pick a window seat too"], "truncated": false }
  ]
}
```

`status` is the same as in the conversation file: `succeeded` / `unverified` / `failed` / `cancelled`.

### `GET /api/v1/running`

Tasks that are running, queued, or waiting for the user right now, **including those started on the phone**. `status` follows the task API's wording (`running`, `queued`, `paused`, `waiting_for_answer`, `waiting_on_phone`); `needs_you` means the user needs to be present to handle it (answer, confirm, take over). Goal 1,000 characters, progress 500 characters, over-long marked `truncated`; at most 20 entries.

```json
{
  "running": [
    { "conversation": "8f…", "goal": "List the action items from yesterday's meeting", "status": "running", "run_id": "…",
      "step": 4, "progress": "Reading the meeting notes", "needs_you": false, "truncated": false }
  ]
}
```

`/api/v1`'s `capabilities` will additionally include `conversations` and `running`.

## Gateway side

The browser page, login session and `/web/api` read-only proxy are in another change in `companions/relay` (PR #374). Key points: the browser logs in once with the same phone access token, and the token is stored only in gateway memory; only these read-only paths are allowed, and only GET; the console is enabled only when `conversations` and `running` appear in the phone's `/api/v1` `capabilities`, otherwise it prompts the user to upgrade the phone side.

## Security red lines (this phase and beyond)

- Payment confirmation and takeover are still phone-only; remote runs always start by asking.
- Model keys, OAuth tokens, lock-screen PINs and ADB keys are never sent to the browser.
- A Web session cannot open or expand phone capabilities (accessibility, wireless debugging, Root, sandbox networking, app access list).
- The console can only read; it cannot dispatch work, answer, interject or cancel through these endpoints.

## Known limitations

- Read-only: cannot send messages, dispatch work, answer, interject, cancel, or change settings.
- No screen image, files, file cards, run progress, scheduled tasks, or memory/skill settings.
- Conversation content is read as-is: screen markers, attachment lines and the like that the user sent at the time are still in the text; each turn is truncated (see the limits above), and the full content remains only on the phone.
- No streaming output; the caller sees an answer only after it is written into the conversation.
- Lists are ordered by the conversation file's modification time; there is no search, pinned-first ordering or task-based filtering yet.

## Verification

```sh
# Kotlin read-only API and App-side reader
./gradlew :core:test --tests "com.wangzan101.mika.core.remote.CompanionApiTest" \
  :app:testDebugUnitTest --tests "com.wangzan101.mika.chat.RemoteConversationReaderTest"

# Compile
./gradlew :app:compileDebugKotlin
```

## Next steps

P1 needs to let the browser send messages/dispatch work, interject, answer and cancel: besides the gateway's controlled POST allowlist and CSRF, it must first decide how the task API operates ordinary conversations on the phone — the existing remote entry point only recognizes conversations with the `mcp-` prefix and cannot write directly into conversations on the phone. Screen preview, file chunking and SSE come later.
