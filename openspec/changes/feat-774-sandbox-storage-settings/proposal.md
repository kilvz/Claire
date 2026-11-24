## Why

issue #774 reported three hardcoded sandbox limits: budget time (PR #780 already made it an adjustable tier), workspace space (4 GB),
single uploaded file size (200 MiB). The latter two are still hardcoded in `Attachments` constants: chat attachments / WeChat images /
files sent with tasks from a computer all hit the 200 MiB single-file and 4 GB workspace limits, with no way for users to relax them, leaving them to stare at
「上传失败：超过 200 MiB」 (Upload failed: over 200 MiB). Following the same pattern as the already-merged sandbox duration tiers, these two storage limits are also opened up as
tier radio options in settings.

## What Changes

- New FeatureFlags keys and reads: `attachment_max_mb` (tiers 200/512/1024/2048, default 200, zero change to existing behavior),
  `workspace_max_gb` (tiers 4/8/16/32, default 4).
- `Attachments`'s single-file limit and workspace limit change from object constants to injectable parameters (defaults = the current constants, so existing callers and
  tests behave unchanged when they pass nothing). Tiers cover only the two device-side import entry points: chat attachments (MikaApp.importAttachments) and
  device file imports (#800's `DeviceWorkspaceImports`, both the SAF-granted directories and the phone assistant), reading the tier fresh on each import;
  computer transfer (`RemoteTaskFiles`/`PhoneTaskFiles`) and the WeChat protocol layer (`WechatImages` 10 MiB) are out of scope and
  keep fixed limits (the scope decision made explicit in PR #797 review).
- The 「任务运行」 (Task runs) settings page gains two tier radio rows (following the SandboxBudgetDialog pattern): single-file limit and workspace limit.
- The failure copy in chat for 「超过 200 MiB 限制」 (over the 200 MiB limit) changes to carry the actual limit value (`chat_attach_too_large` gains a placeholder, with
  all translations kept in sync).
- The device free-space floor `MIN_FREE` (500 MiB) is a device safety line and is not opened up.

## Capabilities

### New Capabilities

- `sandbox-storage-limits`: the single uploaded file size limit and the workspace space limit are user-adjustable in settings by fixed tier,
  with defaults matching the existing 200 MiB / 4 GB; tiers constrain only the two entry points of chat attachments and device file imports, while computer transfer and WeChat images
  keep fixed limits, and the settings copy states the scope.

### Modified Capabilities

(None — `sandbox-time-budget` is a capability merged in the previous slice; this change does not touch its requirements.)

## Impact

- `app/src/main/java/com/wangzan101/mika/cap/sandbox/Attachments.kt`: constants → injected parameters.
- `app/src/main/java/com/wangzan101/mika/settings/FeatureFlags.kt`: two new keys + tier validation.
- `app/src/main/java/com/wangzan101/mika/MikaApp.kt`: the chat attachment call site wires to FeatureFlags.
- `app/src/main/java/com/wangzan101/mika/task/DeviceWorkspaceImports.kt` (added in #800): inject a
  `limits: () -> AttachmentLimits` provider at construction; `copy()` reads it fresh each time and passes it to `Attachments.importOne`;
  the `TaskRun.kt` construction point wires to FeatureFlags. The WeChat/computer paths are unchanged (`WechatImageFiles.kt`, `PhoneTaskFiles.kt`
  are zero-diff against main).
- `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsSubPages.kt`, `SettingsScreen.kt`: settings rows + tier dialogs.
- `app/src/main/res/values*/strings_chat.xml`: `chat_attach_too_large` gains a `%2$s` placeholder, synced across all languages.
- `app/src/main/res/values*/strings_settings.xml`: new strings ×12 languages.
- Unit tests: FeatureFlags tier parsing, Attachments limit injection (small limit rejects / passes after relaxation), copy formatting.
