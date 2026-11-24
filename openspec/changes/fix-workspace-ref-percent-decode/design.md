# Design: percent-decoding the targets of output reference links

## Context

- Output reference chain: `MarkdownModel.workspaceRefs` (commonmark-java walks Link/Image destinations) →
  `outputRefsParsed` (`SandboxPaths.normalize` filters out paths outside the workspace) → `AttachmentMessage.Item(path)` →
  card name / thumbnail (`AttachImage.Sent` → `AttachFiles.relative` + file gate) / open
  (`AttachFiles.openSent`, the same gate).
- Evidence (local JDK + the project's commonmark-java 0.30.1): `[x](/workspace/my%20file.csv)` returns the
  destination `my%20file.csv` verbatim, and the bare `[](<...>)` form also returns verbatim — the parser does not
  decode, and the application layer uses whatever it gets. The current code does not decode, so encoded
  destinations do not match the real file names.
- Repo precedent: `ProxyAddress.decodeCredential` uses `URLDecoder.decode(v.replace("+", "%2B"),
  "UTF-8")`; `WebUrlPolicy`/`SearchEngine` use `runCatching { URLDecoder.decode(...) }` for tolerance.

## Goals / Non-Goals

**Goals:**
- Escaped references the model writes following CommonMark/URL conventions, such as `%20` and `%E6%8A%A5`, show
  the correct file name, can be opened, and their thumbnails load.
- A literal `+` and a literal invalid `%` sequence in a file name are not damaged.

**Non-Goals:**
- No change to path parsing in `openSent`/thumbnails (what they receive is already the final path, so no
  two-candidate fallback is needed).
- Do not handle the boundary where a `/opt/mika/skills` reference renders but cannot be opened (a separate
  matter; this change does not touch it).

## Decisions

1. **Put the decode point where `workspaceRefs` extracts** (rather than in `outputRefsParsed` or further
   downstream). Reason: a single caller (`WorkspaceRef.kt`), one decode covers the whole chain (name,
   classification, thumbnail, open) and naturally gets the correct path; deduplication also happens after
   decoding, so `a%20b` and `a b` do not count as two cards.
2. **Use `java.net.URLDecoder` with a `+` pre-replacement, keeping the original string on failure**, following
   the `ProxyAddress` precedent:
   `runCatching { URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)`.
   - `+` must be protected: a file name can contain a literal `+`, and URLDecoder would treat it as a space.
   - On failure (an invalid `%` sequence throws IllegalArgumentException) keep the original string: a file name
     that literally contains `%` (such as `100%.csv`) can still be referenced with the bare `[](<...>)` form
     and is not dropped entirely.
3. **Decode before `SandboxPaths.normalize`**: a `%2F` that decodes to `/` takes part in normalization as a path
   separator, is still confined inside `/workspace`, and adds no new out-of-bounds surface.
4. **Wire the main answer the way `FinishedReply` is wired, in `StatusCard`**: the `Done` answer and the
   `Cancelled` hand-off (summary) are both "answer-like text", so draw `OutputRefs` below `MarkdownText` and
   above the action area (`ReplyActions`), and copy `FinishedReply`'s `AttachFiles.openSent` + failure toast for
   opening. Pass `conversationId` into `StatusCard` as a new parameter from `exchange()` (the value already
   exists there). Do not wire `Failed.reason`, which is a failure reason and not an answer; do not wire the
   streaming draft (`DraftText`) either (`FinishedReply` also only draws after speaking finishes).
   The test approach follows the maintainer's precedent for this feature (7edc0747): parser-layer JVM unit tests
   + real-device walkthrough, no Compose tests in the rendering layer.

## Risks / Trade-offs

- [A file name with a literal `%20` referenced through the bare `<>` form will be wrongly decoded into a space]
  → Accepted: inside a link destination `%` is an escape character by URI semantics (consistent with browsers);
  the probability of the model generating a literal `%` file name is far lower than that of encoding a
  space/Chinese character according to the rules. Record this boundary in the design and tests.
- [After decoding `Item.path` changes; will old cards already rendered in historical conversations break] → Old
  cards come from an old version's rendering and were already affected by the same defect (they cannot be
  opened); after decoding, re-rendering actually goes from unopenable to openable, with no regression surface.

## Migration Plan

Single-commit fix + unit tests; no data migration. Rollback means reverting one place in `workspaceRefs`.

## Open Questions

(None)
