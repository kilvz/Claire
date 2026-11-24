## 1. Fix

- [x] 1.1 `MarkdownModel.workspaceRefs`: after extracting Link/Image destinations, do lenient percent decoding
  following the `ProxyAddress` precedent (`+` pre-replaced with `%2B`, `runCatching` keeps the original string
  on failure), then deduplicate and return
- [x] 1.2 Confirm `WorkspaceRef.outputRefsParsed` needs no changes (the normalize filter still guards `..` and
  out-of-bounds paths after decoding)

## 2. Tests

- [x] 2.1 `MarkdownModelTest`: `%20`, Chinese `%E6%8A%A5`, literal `+`, invalid `%` sequences, the bare `<>` form
  unaffected, `%2F` still confined by normalize after decoding
- [x] 2.2 `WorkspaceRefTest`: the `Item.path` parsed from encoded references is the decoded path, photo
  classification is correct, out-of-bounds encoded references (such as `%2E%2E`) are dropped
- [x] 2.3 Run the relevant filters of `:app:testDebugUnitTest` and the full `:core:test`, confirm no regressions

## 3. Wiring the main answer (found in the real-device walkthrough: Done/Cancelled do not render reference cards)

- [x] 3.1 Add a `conversationId` parameter to `StatusCard` (passed at the `exchange()` call site), draw
  `OutputRefs` for the `Done` answer and the `Cancelled` summary below `MarkdownText` and above `ReplyActions`,
  and open following `FinishedReply`'s `openSent` + failure toast
- [x] 3.2 Retest on a real device with the rewired fixed build: when the task answer contains `%20`/`%E6`
  references, the cards appear, the names are decoded, and opening works; capture screenshots as evidence

## 4. Delivery

- [ ] 4.1 Commit (Chinese commit message), push the branch `fix/workspace-ref-percent-decode`, open a PR linking
  the output-reference feature background, with the unit-test results and the evidence for commonmark 0.30.1
  behaviour in the body
- [ ] 4.2 Wait for all four CI groups (app/core/cli/relay) to go green; store the real-device walkthrough
  evidence in `evidence/` and add it to the PR
