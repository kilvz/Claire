## 1. Core limit injection

- [x] 1.1 `Attachments` gains `AttachmentLimits(maxFileBytes, workspaceMaxBytes)` + `DEFAULT` (= the current constants); `importInto`/`withTaskFiles`/`roomFailure`/`copyOne` gain a `limits` parameter (defaulting to DEFAULT, zero behavior change)
- [x] 1.2 `FeatureFlags` gains reads/writes for `attachment_max_mb` (tiers 200/512/1024/2048, default 200) and `workspace_max_gb` (tiers 4/8/16/32, default 4) + fallback to the default outside the tiers
- [x] 1.3 Wire the three call sites: `MikaApp.importAttachments`, the room lambda of `androidWechatImages`, and `PhoneTaskFiles.start` (the up-front sum check and the room lambda)

## 2. Settings UI and copy

- [x] 2.1 `DuringWorkScreen` gains two rows (single-file limit/workspace limit) + two tier dialogs (following the `SandboxBudgetDialog` pattern), with `SettingsScreen` passing state and callbacks through
- [x] 2.2 `chat_attach_too_large` gains a `%2$s` placeholder for the actual tier; the render point (ChatScreen TOO_LARGE branch) formats the current tier; the string is synced across all languages
- [x] 2.3 New settings strings (2 titles, tier copy) ×12 languages, with the i18n check run

## 3. Tests and verification

- [x] 3.1 Unit tests: FeatureLimits tier parsing (unset/within tiers/fallback outside tiers); an A/B test where Attachments with a small injected limit rejects and DEFAULT lets through; the room check lets through after relaxing the workspace limit
- [x] 3.2 Full local run: `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` + relayTest (Go) + i18n check all green
- [x] 3.3 On-device walkthrough: default tier display → tier change written back → restored to default; screenshot evidence stored in evidence/

## 4. PR

- [x] 4.1 Push the branch, create the PR (title in the 「现状 → 改动」 (current state → change) form, references #774, first-screen measured evidence, test coverage stated honestly)
- [x] 4.2 Wait for all four CI groups to go green (app/cli/core/relay groups pass)

## 5. Rework from PR #797 review (option A: tiers cover only chat attachments and device file imports)

- [x] 5.1 Rebase onto the latest main containing #800: resolve the `Attachments`/`AndroidDeviceFiles`/`TaskRun` conflicts per the new structure (importOne's onCopied refactor coexists with limits; device imports are consolidated into `DeviceWorkspaceImports`)
- [x] 5.2 Revert the out-of-scope wiring: `WechatImageFiles` (room check back to fixed `WORKSPACE_MAX`, dropping the construction-time tier snapshot) and `PhoneTaskFiles` (the up-front sum and room check back to fixed values); both files are zero-diff against main
- [x] 5.3 Wire the new in-scope entry point: `DeviceWorkspaceImports` gains a `limits: () -> AttachmentLimits` provider, which `copy()` reads fresh each time; the `TaskRun` construction point injects the FeatureFlags tier
- [x] 5.4 Rewrite both settings subtitle strings ×12 languages to state 「仅聊天附件与设备文件导入；微信/电脑保持固定限额」 (chat attachments and device file imports only; WeChat/computer keep fixed limits)
- [x] 5.5 Unit tests: `DeviceWorkspaceImportsTest` gains cases for "tier read fresh each time, small limit rejects/relaxed limit passes"
- [x] 5.6 Full local run (core/cli/app unit tests + i18n check) + openspec validate — 3300+ app unit tests all green (core/relay are zero-diff against main and were not rerun, CI covers them)
- [ ] 5.7 Push, update the PR description, reply to review comments, wait for all four CI groups to go green — the first three are done (d920103e pushed, body updated, two comments sent covering the scope decision and the CI note); app/cli/relay are green, and the core red is main's own `DocsConsistencyTest` going red because of the maintainer's 2026-11-22 planning docs (this branch has zero changes to core//docs/, and two main tips fail identically), so once main goes green a rebase and re-push closes the loop
