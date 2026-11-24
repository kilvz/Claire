# Proactive WeChat result push + on-device task unlock opt-in Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tool to Ui tasks for "when the intent is explicit, the model may send the result to the owner's WeChat"; add a 「锁屏时运行本机任务」 (Run on-device tasks while the screen is locked) switch that defaults to off in Settings, and complete the localEnabled unlock authorization chain.

**Architecture:** F1 follows the capability-plugin pattern: a new WechatOwnerCapability in core (port interface + intent gate + tool definition), implemented on the app side by AndroidWechatOwnerPort (snapshot/re-verify/Outbox idempotency), injected by TaskRun gated on source + intent. F2 reuses the TaskRelock/guard system already merged today and only fills in the localEnabled configuration chain (DeviceAccess→DeviceAccessSettings→TaskUnlockPolicy→Settings UI).

**Tech Stack:** Kotlin, coroutines, Jetpack Compose, the existing Outbox/WechatStore/TaskRelock foundation.

**Spec:** `docs/design/specs/2026-11-21-wechat-owner-push-local-unlock-design.md`
**Research notes (interface and guardrail details):** research notes inside the session (not committed); all required interfaces and guardrails have been copied into the Spec above, which is authoritative

## Global Constraints

- Generic AOSP, no brand special cases; no external project names appear in code/comments/docs.
- Every new user-visible string must be complete across the 11 language directories: values, values-zh, values-b+zh+Hant, values-ar/es/fa/in/ja/ko/pt/ru/tr.
- Tool descriptions go into `core/src/main/resources/prompts/tools/`; the golden regeneration command must include both filters: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots` (sizes.txt belongs to PromptLayoutTest).
- Regression command: `./gradlew :core:test :app:testDebugUnitTest` (bare `:app:test` is forbidden, OOM).
- Reuse the existing TaskRelock/guard/recovery chain; do not bring in another relock implementation.
- A new ToolLabels label must pass full ToolLabelsTest coverage; new Risk.Write tools go on the ToolRiskTest allowlist.

## Review Focus

1. An explicit English intent ("send the result to my WeChat") must hit the gate, and negated sentences ("不要发我微信" (don't send to my WeChat) / "don't send to my wechat") must not — the fork only had the Chinese pattern, and this plan fixes that (Task 1 tests).
2. Sending the same text a second time within the same run must not deliver it again; the previous status is returned (Task 2 sender tests).
3. If the WeChat channel is rebound/disabled mid-run, sending must return unavailable and must not send even once (Task 2 sender tests).
4. If the user turns the on-device switch off during wake-up, the flow must abort before credentials are entered (Task 3 DeviceAccess tests).
5. Turning the on-device switch on must not drag the remote switch along, and vice versa (Task 3 settings persistence tests).

---

### Task 1: F1 core — WechatOwnerCapability and the intent gate

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerCapability.kt`
- Create: `core/src/main/resources/prompts/tools/wechat_send_owner.md`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/tool/AgentTool.kt` (Verb enum)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerRequestTest.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerToolTest.kt`

**Interfaces:**
- Produces (later tasks depend on these; the signatures are fixed):
  - `interface WechatOwnerPort { suspend fun send(text: String): WechatOwnerResult }`
  - `data class WechatOwnerResult(val status: Status, val message: String? = null) { enum class Status { Delivered, Uncertain, Failed, Unavailable } }`
  - `fun explicitWechatOwnerRequest(goal: String): Boolean`
  - `class WechatOwnerCapability(private val port: WechatOwnerPort?) : CapabilityProvider` (the id is `"wechat_owner"`; when port is null no tool is provided and sections are empty too)
  - tool name `wechat_send_owner`, parameter `text` (1–4000 characters), Risk.Write, offScreen, logged
  - `Verb.WechatSendOwner` (add one entry to the AgentTool.kt enum, placed next to its alphabetical neighbors)

- [ ] **Step 1: Write the failing WechatOwnerRequestTest**

Assertions (both the Chinese and English patterns + negation exclusion + long sentences not truncated):
- `explicitWechatOwnerRequest("把今天的新闻摘要发到我的微信")` == true
- `explicitWechatOwnerRequest("查一下快递进度,结果发我微信")` == true
- `explicitWechatOwnerRequest("summarize my emails and send the result to my WeChat")` == true
- `explicitWechatOwnerRequest("把结果发到我的微信,顺便订个闹钟明早八点提醒我带上伞出门")` == true (a long sentence longer than an 18-character window still hits)
- `explicitWechatOwnerRequest("不要把结果推送到我的微信")` == false
- `explicitWechatOwnerRequest("don't send anything to my wechat")` == false
- `explicitWechatOwnerRequest("帮我看看微信里有什么新消息")` == false (reading WeChat ≠ sending to WeChat)
- `explicitWechatOwnerRequest("今天天气怎么样")` == false

- [ ] **Step 2: Run `./gradlew :core:test --tests '*WechatOwnerRequest*' -q` and confirm everything fails to compile (the class does not exist)**

- [ ] **Step 3: Implement WechatOwnerCapability.kt**

Three parts, with all comment wording of our own:
1. `WechatOwnerPort` / `WechatOwnerResult` (signatures as in Interfaces).
2. `explicitWechatOwnerRequest(goal)`: a Chinese pattern (a send-verb group × "my WeChat") + an English pattern (send/deliver × my wechat), each with its own negation-prefix exclusion (`不`/`别`/`无需`/don't/do not/never); **no character-distance window** (fixing the fork's defect of missing long sentences). Use two Regex groups in the implementation, and keep the pattern table as a file-level private val so more languages can be added later.
3. `WechatOwnerCapability`: when the port is non-null and the tool is supplied, sections give a self-written code of conduct (use it only once when the user explicitly asks; send only the final result and any uncertainty; accepted by the interface ≠ read; report failures/uncertainty truthfully and never resend automatically; never use the WeChat app or a browser as a stand-in). execute calls port.send, maps status to ok true/false, and passes message straight through.

- [ ] **Step 4: Write the tool description `prompts/tools/wechat_send_owner.md`**

2–3 lines of English (in the same style as the existing tool descriptions): send the final result to the owner's bound WeChat private chat; use it only once when the user explicitly asks; do not resend the same text; delivered is not the same as read, and report failures truthfully.

- [ ] **Step 5: Add `WechatSendOwner` to the Verb in AgentTool.kt; write WechatOwnerToolTest and get it green**

WechatOwnerToolTest: with port null, tools() is empty; with a fake port, tools() includes wechat_send_owner with Risk.Write; text over 4000 characters is rejected; when the port returns Unavailable the result is isError and the message is truthful.
Run: `./gradlew :core:test --tests '*WechatOwner*' -q` all green.

- [ ] **Step 6: Commit**

`feat(wechat): owner result push capability and explicit intent gate (core)`

### Task 2: F1 app — sender, binding lookup, TOCTOU re-check, assembly

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/wechat/WechatOwnerSender.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/wechat/WechatStore.kt` (add boundOwnerPeer at the end)
- Modify: `app/src/main/java/com/wangzan101/mika/wechat/WechatChannel.kt` (add an accepts re-check before sending on the text path)
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/assembly/TaskTools.kt` (add a wechatOwner parameter to toolDispatcher + register the provider)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt` (gated injection at the tool assembly point)
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt` (ToolLabels +1 line)
- Modify: `app/src/main/res/values*/strings_tools.xml` (11 directories, +1 entry each)
- Test: `app/src/test/java/com/wangzan101/mika/wechat/WechatOwnerSenderTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/wechat/WechatStoreTest.kt` (+1), the ToolRiskTest allowlist

**Interfaces:**
- Consumes: Task 1's `WechatOwnerPort`/`WechatOwnerResult`/`explicitWechatOwnerRequest`/`WechatOwnerCapability`.
- Produces: `fun WechatStore.boundOwnerPeer(): WechatPeer?` (four conditions: enabled + has an account + the owner's contextToken is non-empty + a thread already exists; otherwise it returns null).

- [ ] **Step 1: Write the failing WechatOwnerSenderTest**

Use a fake store/fake channel (the same kind of fakes the project's existing tests use; see WechatStoreTest for the style):
- Normal path: the owner binding exists at snapshot time and still exists at send time → status Delivered, the Outbox delivery id carries a prefix of the form `owner-send:<runKey>:`.
- Rebinding invalidates: swap the bound account after the snapshot → send returns Unavailable and the channel is never called (assert 0 calls with a counting fake).
- Idempotency: call send twice with the same runKey and the same text → the second returns the first call's status, and the channel is called only once.
- uncertain is not resent: the first delivery returns Uncertain, and calling again with the same text → still Uncertain, the channel still called once.

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*WechatOwnerSender*' -q` and confirm it fails to compile**

- [ ] **Step 3: Implement**

- `WechatStore.boundOwnerPeer()` (~10 lines, semantics as in Interfaces).
- `WechatOwnerSender.kt`: `class AndroidWechatOwnerPort(app: MikaApp, private val runKey: String) : WechatOwnerPort` — snapshot expectedAccount+expectedPeer at construction; re-read the store and compare at send time (mismatch → Unavailable, send nothing); delivery id `owner-send:<runKey>:<sha256(threadId+text).take(16)>`; idempotent through outbox.deliver; Delivered/Uncertain/Failed mapped from the delivery result.
- `WechatChannel.kt`: on the text send path, inside the IO block before the API is actually called, add `if (!store.accepts(peer, account)) return <failure>` (the image path already has the same thing; copy its check form; keep the entry isAlive check untouched).

- [ ] **Step 4: Assembly and gating**

- `TaskTools.toolDispatcher(...)`: add the parameter `wechatOwner: WechatOwnerPort? = null`; add `WechatOwnerCapability(wechatOwner)` to the providers list the way the existing `object : CapabilityProvider` entries are attached; append to allTools (following the neighboring capabilities).
- `TaskRun.kt` at the tool assembly point: `wechatOwner = if (provenance.origin == RunOrigin.Ui && !conditionCheck && explicitWechatOwnerRequest(task)) AndroidWechatOwnerPort(app, runKey) else null` (runKey uses the unique key this run already has; if there is none, use conversationId+start time).
- `ChatViewModel.kt` ToolLabels: `"wechat_send_owner" to R.string.tool_wechat_owner`.
- Add `tool_wechat_owner` to strings_tools.xml in each of the 11 values directories (the default English meaning is "send to my WeChat"; translate for each language following the style of the existing entries in the same file).

- [ ] **Step 5: Run tests + regenerate goldens**

```
./gradlew :app:testDebugUnitTest --tests '*Wechat*' -q
./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots -q
./gradlew :core:test -q
```
All green (if ToolRiskTest blocks the new tool, register it through the allowlist mechanism).

- [ ] **Step 6: Commit**

`feat(wechat): owner result push sender and gated assembly (app)`

### Task 3: F2 — the localEnabled unlock authorization chain and the settings switch

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/device/DeviceAccess.kt` (add localEnabled/beginLocalAttempt to the store interface; split unlock into the local branch)
- Modify: `app/src/main/java/com/wangzan101/mika/settings/DeviceAccessSettings.kt` (add the field to config, setLocalEnabled, beginLocalAttempt, persistence)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskUnlockPolicy.kt` (add the localUnlockEnabled parameter + a Ui branch)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt` (the unlock block picks unlockLocal by source; the failure copy has three branches)
- Modify: `app/src/main/java/com/wangzan101/mika/device/ui/DeviceAccessSection.kt` (add a localTasks variant)
- Modify: the Settings 「任务运行」 (Task runs) page (`SettingsSubPages.DuringWorkScreen` and `SettingsScreen`; attach the on-device switch)
- Modify: `app/src/main/res/values*/strings_device_access.xml` (11 directories, +5 keys each: device_local_group/title/sub/foot/unlock_failed)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/device/DeviceAccessTest.kt` (+3)
- Test: `app/src/test/java/com/wangzan101/mika/settings/DeviceAccessSettingsTest.kt` (+1)
- Test: `app/src/test/java/com/wangzan101/mika/task/TaskUnlockPolicyTest.kt` (+2)

**Interfaces:**
- Produces: `DeviceAccessStore.localEnabled: Boolean`, `DeviceAccessStore.beginLocalAttempt(): Boolean` (the interface default implementations both return false); `TaskUnlockPolicy(localUnlockEnabled: () -> Boolean = { false }, …everything else unchanged)`.

- [ ] **Step 1: Write the failing tests (core DeviceAccessTest +3)**

- Independent local authorization: with remote enabled=false and localEnabled=true, allowed() on the local branch is true and on the remote branch is false.
- Revoking authorization mid-wake: allowed() is re-read before every operation — when the first read is true and the second is false, the flow aborts before credentials are entered (the fake credential injection count is 0).
- Screen off but unlocked counts too: when the screen is off but there was no security lock to begin with, performedUnlock is true (our `woke || !wasSecure` semantics; add a case to pin this down).

- [ ] **Step 2: Write the failing tests (two places in app)**

DeviceAccessSettingsTest +1: localEnabled persists independently (writing true does not affect enabled; the blocked slots of beginAttempt and beginLocalAttempt do not leak into each other).
TaskUnlockPolicyTest +2: authorization for the Ui source is granted and withdrawn in real time with localUnlockEnabled (the lambda is re-checked on every call); the Mcp source is never authorized under any combination of switches.

- [ ] **Step 3: Run the three test classes and confirm compile/assertion failures**

`./gradlew :core:test --tests '*DeviceAccess*' -q; ./gradlew :app:testDebugUnitTest --tests '*DeviceAccessSettings*' --tests '*TaskUnlockPolicy*' -q`

- [ ] **Step 4: Implement the core + app authorization chain**

- `DeviceAccess.kt`: add two members to the store interface (default false); merge `unlock(authorized)`/`unlockLocal(authorized)` into a private `unlockUsing(local, …)`; `allowed()`/`prepare()` branch by local, and go through the corresponding begin*Attempt before credentials are entered; do not add a "local returns early" branch (our performedUnlock semantics already cover it).
- `DeviceAccessSettings.kt`: add `localEnabled` to config (default false; persistence is backward compatible with its absence); `setLocalEnabled` (on a write failure, switch it back off, delete the file and then throw); `beginLocalAttempt` (a separate blocked key).
- `TaskUnlockPolicy.kt`: add `localUnlockEnabled: () -> Boolean = { false }`; `origin == Ui && localUnlockEnabled()` → authorized (the predicate lambda is re-checked on every call and never cached).
- `TaskRun.kt` unlock block: `origin ∈ {Ui, Trigger} && settings.localEnabled` goes through unlockLocal; add a `device_local_unlock_failed` branch to the failure copy (guard/relock/recovery logic stays untouched in all cases).

- [ ] **Step 5: Settings UI + strings**

- `DeviceAccessSection.kt`: add a `localTasks: Boolean = false` parameter variant (the group name/switch row/footnote use the copy of the 5 new keys; the credential editing area is reused as a whole).
- Attach the on-device switch to the 「任务运行」 (Task runs) page (pass DeviceAccessSettings; the footnote makes clear that it does not affect the 「连接电脑」 (Connect to computer) remote switch).
- Add 5 keys to strings_device_access.xml in each of the 11 languages.

- [ ] **Step 6: Get all related tests green**

`./gradlew :core:test --tests '*DeviceAccess*' -q; ./gradlew :app:testDebugUnitTest --tests '*DeviceAccess*' --tests '*TaskUnlockPolicy*' -q`

- [ ] **Step 7: Commit**

`feat(device): on-device task wake-screen unlock opt-in switch and authorization chain`

### Task 4: Full regression

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest --console=plain -q` all green (exit 0)
- [ ] **Step 2:** If TerminalHubTest times out at 15s on its own and the diff does not touch app/terminal: treat it as a known environment flake (the terminalhub-test-local-flake memory) and re-run that class to confirm
