# design: adjustable sandbox storage limits (follow-up to #774)

## Context

PR #780 already turned the cumulative sandbox command runtime into a tier radio option on the "Task runs" page, in this pattern:
a new FeatureFlags key (only values within the tiers are accepted, values outside fall back to the default) → call sites read flags and pass through →
a tier dialog on the settings page (`SandboxBudgetDialog` pattern) → new strings ×12 languages.
This change applies the same restructuring to the two remaining hardcoded limits from #774:

- Single file 200 MiB: `Attachments.MAX_BYTES`, checked in `copyOne` while accumulating chunk by chunk;
- Workspace 4 GB: `Attachments.WORKSPACE_MAX`, compared in `roomFailure` (chat attachments) and in
  the room checks that `WechatImageFiles`/`PhoneTaskFiles` carry themselves.

All three import paths converge on `Attachments.importInto` / `Attachments.withTaskFiles`.

## Goals / Non-Goals

**Goals**
- Both limits are adjustable by tier, and default behavior is byte-for-byte identical to the status quo (unset = 200 MiB / 4 GB).
- Tiers cover chat attachments and device file imports (#800's `DeviceWorkspaceImports`: SAF-granted directories + phone assistant),
  reading the tier fresh on each import (not a construction-time snapshot).
- The over-limit message carries the actual tier value instead of hardcoding 200 MiB.

**Non-Goals**
- Computer transfer (core `RemoteTaskFiles`'s `MAX_BYTES` 200 MiB, app `PhoneTaskFiles`'s fixed workspace check)
  is not wired to tiers — the computer-side CLI has no settings UI, and separating the limits on the two sides would mislead; it stays fixed and this is stated in the settings copy (PR #797 review).
- WeChat images are not wired to tiers — the protocol layer's `WechatImages.MAX_BYTES` (10 MiB) applies independently during download/verification, and the whole-
  `ByteArray` in-memory approach is not suited to opening up to hundreds of MB either; the app-side room check keeps the fixed `WORKSPACE_MAX`.
- The device free-space safety line `MIN_FREE` (500 MiB, device safety) is left untouched.
- Browser upload staging `UploadStaging` (web automation domain, a separate matter), the share limits
  `ShareRules.MAX_BYTES` / `SHARE_MAX_BYTES` (sandbox→user export, fool-proofing purpose), and
  `AttachSources.MAX_BYTES` (UI decode cache, not a user contract) are left untouched.
- No custom numeric input; tiers only (consistent with the tier convention for task steps/duration/token budget).

## Decisions

1. **Inject limits through a small value object, not scattered parameters.**
   Add `data class AttachmentLimits(val maxFileBytes: Long, val workspaceMaxBytes: Long)` to
   `Attachments` with a companion `DEFAULT` (= the current constants). `importInto`/`withTaskFiles`/`roomFailure` gain
   a `limits: AttachmentLimits = AttachmentLimits.DEFAULT` parameter.
   The alternative (two scattered Long parameters per method) would thread through three layers on the `withTaskFiles` chain and read poorly;
   the alternative (turning Attachments into a stateful class) touches too much. The constants themselves stay —
   `MIN_FREE`, tests, and the existing references in WechatImageFiles/PhoneTaskFiles keep using them.

2. **Tier values: single file 200/512/1024/2048 MiB, workspace 4/8/16/32 GB.**
   Like the duration tiers, "default in the first tier, doubling upwards". Capped at 32 GB: rootfs and all workspaces share
   the phone's user partition, and anything larger easily eats through the system disk; single file capped at 2 GiB: beyond that the user should be guided to
   computer connection/file manager, since the chat attachment channel is not suited to carrying several GB.
   prefs store the MB value (`attachment_max_mb`) and the GB value (`workspace_max_gb`),
   read as `takeIf { it in tiers } ?: default` — the same defense as `sandboxBudgetMinutes`.

3. **The settings entry point goes on the "Task runs" page (DuringWorkScreen), right next to the sandbox duration tier.**
   From the #774 user's perspective this is one "Sandbox limits" group of three (time/space/single file); duration already lives on
   DuringWorkScreen, and moving it would split them up. The UI copies `SandboxBudgetDialog` (radio tiers,
   no "Unlimited"). The displayed value reuses the capacity formatting already in `SandboxCopy.size`.

4. **Over-limit copy: `chat_attach_too_large` gains `%2$s` (the actual tier value), and the render point formats
   the current tier.** The render point (ChatScreen's AttachmentFailureReason.TOO_LARGE
   branch) already has context, so one FeatureFlags read is enough to format; the byte count is not carried in
   `AttachmentFailure` (the reason enum is mapped by ChatViewModel, and expanding the model
   touches too much). Reading the tier is a single SharedPreferences getInt, render frequency is low, no performance issue.

5. **Wiring points: two entry points, tier read fresh each time.**
   `MikaApp.importAttachments` (chat attachments) and `TaskRun` when constructing `DeviceWorkspaceImports` inject
   a `limits: () -> AttachmentLimits` provider; `copy()` reads it fresh on each import and passes it to `Attachments.importOne`
   (#800 consolidated device imports into this class, SAF `fromFolder` and the assistant's `fromHelper` share `copy`, so one wiring point covers both paths).
   The WeChat and computer paths are not wired (see Non-Goals). Changing the setting takes effect immediately (the next import reads the new value) — storage limits have no
   "in-progress task" consistency problem, since imports are per-file and instantaneous.

## Risks / Trade-offs

- [A larger tier fills up the user partition] → the `MIN_FREE` 500 MiB safety line is re-checked in copyOne every 16 MiB,
  unaffected by the tier, and keeps providing the fallback.
- [Adding a placeholder to `chat_attach_too_large` misses a language] → the `tools/i18n` parity
  check turns red and CI blocks it.
- [The scope is misunderstood as applying to all entry points] → the settings subtitle states "chat attachments and device file imports only; WeChat/computer keep fixed limits";
  `DeviceWorkspaceImportsTest` asserts the provider is read fresh each time (changing tiers on the same instance takes effect immediately, preventing a regression to a construction-time snapshot).

## Migration Plan

Purely new prefs keys, defaults = current behavior, no migration needed; rollback = delete the settings rows (any leftover keys in prefs are read by nobody).

## Open Questions

None — the tier set and entry-point placement follow the pattern from #780 that the maintainer already accepted.
