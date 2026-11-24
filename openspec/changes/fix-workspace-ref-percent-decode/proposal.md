# Proposal: the `/workspace` link targets of output references cannot be decoded from percent escapes

## Why

main `b63f358d` (output references, not yet shipped in a release) has two gaps, hit one after another during the
real-device walkthrough (PR #748):

1. **The main answer does not render reference cards at all**: `OutputRefs` is only wired into interjection
   replies (`FinishedReply`); task answers (`ExchangeStatus.Done`) and the hand-off after stopping
   (`Cancelled.summary`) only draw `MarkdownText`, even though prompt `6e927608` plainly tells the task sandbox to
   "deliver result files as Markdown references" — the model complied, but the user cannot see the cards. The
   walkthrough measured: the task answer contained
   `![…](/workspace/%E6%8A%A5%E5%91%8A.png)` and `[my report.csv](/workspace/my%20report.csv)`,
   yet the UI showed only inline text, no thumbnails, no file cards.
2. **Link targets with percent escapes are not decoded**: `MarkdownModel.workspaceRefs` does not percent-decode
   Markdown link/image destinations (commonmark-java 0.30.1 measured returning `my%20file.csv` and
   `%E6%8A%A5%E5%91%8A.png` verbatim). When the model references a file name with spaces according to
   CommonMark rules it must write `%20` (a bare space is invalid inside a `[](...)` destination), and when it
   references a Chinese file name following URL habits it often writes the `%E6%8A%A5` form — even if such
   references render, they are cards whose names carry the encoded string, and opening goes through the file
   gate, which cannot find the file by the literal path, so 「文件不存在」 (File not found) always pops up; image
   thumbnails are empty placeholders. Fix it before the release.

## What Changes

- `StatusCard` (`ChatScreen`) additionally wires `OutputRefs`: the `Done` answer and the `Cancelled` hand-off
  (summary) render reference cards below `MarkdownText`, and opening goes through the existing
  `AttachFiles.openSent` (the way `FinishedReply` does it, with `conversationId` passed as a new parameter).
- The link/image destinations extracted by `MarkdownModel.workspaceRefs` first get **lenient percent decoding**
  (`%HH` decoded as UTF-8; invalid escapes kept as-is), then go into deduplication and the subsequent
  `SandboxPaths.normalize` filter.
- Decoding follows the repo's existing precedent (`ProxyAddress.decodeCredential`): replace `+` with `%2B` before
  `URLDecoder`, so a literal `+` in a file name is not turned into a space; when decoding throws, keep the
  original string (a literal `%` file name can still be referenced with the bare `[](<...>)` form).
- Add `MarkdownModelTest` (decoding shapes) and `WorkspaceRefTest` (decoded path lands in `Item.path`, photo
  classification correct); wiring follows the maintainer's test approach for this feature (parser-layer unit
  tests + real-device walkthrough).

## Capabilities

### New Capabilities
- `workspace-ref-parsing`: extraction and pre-render parsing of `/workspace` output references in answers
  (including percent-escape decoding semantics and out-of-bounds filtering).

### Modified Capabilities

(None — this feature just landed on main, and there is no corresponding spec under openspec/specs yet.)

## Impact

- `app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt` (one place in workspaceRefs, about +10 lines)
- `app/src/test/.../MarkdownModelTest.kt`, `WorkspaceRefTest.kt` (new cases)
- Downstream `WorkspaceRef.kt`/`ChatAttachments.kt`/`AttachSources.openSent` need no changes; once they get the
  decoded path, display and opening recover.
