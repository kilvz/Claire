# Acceptance record for the five-PR scope integration

Date: 2026-11-25
Scope: `integration/pr-batch-2026-11-25`, design in `docs/design/specs/2026-11-25-pr-integration-design.md`.
Conclusion: acceptance passed within the limited scope.

## Scope and commits

This integration covers only #826, #297, #306, #827 and #830. The user has stopped the remaining 14 PRs; those PRs were not implemented and are outside this acceptance scope.

The design commits are `dc8319c62` and `2af96c9f4`. The rebased feature commits are as follows:

| PR | Feature commit | Acceptance scope |
|---|---|---|
| #826 | `195f956fe` | Title and stop-summary language |
| #297 | `ba97697ec` | Safe HTML conversation export and preview |
| #306 | `71841c351` | Per-conversation main-model override and immutable run snapshot |
| #827 | `7ecb80646` | Multi-line display for card actions |
| #830 | `9b5c17022` | Recent-task exclusion sync |

Each feature is kept as one atomic commit plus the required contributor attribution; #297 keeps two contributor attributions.

## Full CI

The following checks were actually run on the final squashed commit series and passed:

- core: 4414 tests, 0 failures, 0 errors, 5 skipped.
- app: 3824 tests, 0 failures, 0 errors, 0 skipped.
- Debug APK build and androidTest compilation passed.
- CLI: 90 Python tests, plus contract/integration checks passed.
- relay: Go and Kotlin integration tests passed.
- translation tool: 12 tests passed.
- resource check: 13 UI languages, 4396 resources in total, passed.
- logging tool: 7 tests passed.

## Device acceptance

All 63 targeted functional tests were actually run on one selected Android 14 test phone: 63 passed, 0 failed, 0 skipped, covering:

- HTML export preview.
- Model-selection labels.
- Real TaskRun execution.
- Multi-line actions, card and capsule UI.
- Recent-task exclusion.

## Review conclusion

Each task passed independent review; the fix rounds for #306 and #830 passed limited-scope re-review. The final whole-branch review first found trailing whitespace in a design document, which was removed and passed the diff whitespace check. It then corrected four #306 Important issues raised by the review — the NoModel decision for conversation overrides, binding of a shared draft's pending-save selection, retry identity after the first Desk send is rejected, and Provider ownership of the conversation-reference configuration — all implemented with fail-first regressions and fixed; targeted re-review confirmed all four were resolved with no new Critical/Important regressions. These production fixes were squashed back into #306's single atomic commit, and the final full code tree matches the code tree at the clean review.

The shared capsule TextLink centring has a slightly larger impact range than the footer; this item is an accepted MINOR conclusion and is not changed this round.

## Verification boundaries

This record does not claim that acceptance with real model providers is complete, nor that the full device instrumentation suite for the whole app has been run. The existing AGP/compileSdk and Kotlin warnings remain non-blocking, pre-existing issues.
