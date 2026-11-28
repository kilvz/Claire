# Models and sign-in

Claire doesn't come with a model; it uses a model provider you choose. Enter an API key, or sign in with an account. The main model must support tool calling.

## Add a model

Open "Models" in Settings (the app also walks you through this on first launch). Presets for many common providers are built in; the list is under "Supported model providers" in the [README](../README.md). For any other provider pick Custom, which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.

1. Add a provider: pick a preset, or pick Custom and enter the endpoint URL; enter your API key. For servers you run yourself, such as Ollama or LM Studio, the address is prefilled with the default port; change it to that machine’s address, and the key is optional.
2. Tap “Test and save” to fetch and save the provider’s model list. If there’s no main model yet (initial setup or your first provider), Claire also asks the recommended model (or the model name you typed) to call a test tool once and makes it the main model only if that works; if it fails, the provider is still saved, the reason is shown and you get a “Choose main model” link. If the provider has no catalog, enter the model ID from its documentation and choose “Don’t test, save anyway”. Whenever you later choose a main model, Claire first asks exactly that model to call a test tool and switches only if it works; otherwise the previous main model stays. Only the current main model is not tested again; switching a speech-only model back to main tests it again. Picking a vision, light or fallback model from the list makes no test call. Any model name you type yourself (for any role or fallback slot, including under “Use as” in provider details) is test-called once before it takes effect; a vision model passes with a plain reply. A main-model fallback picked from a provider saved without testing is test-called too. Choosing a model group as the main model, and the main model set automatically after an account sign-in, are not test-called.
3. The main model must support tool calling. Optional: a vision model (understands the UI and finds buttons in screenshots), a light model (anything cheap and fast; handles small jobs like judging task outcomes and extracting memories) and speech to text (see below).

To configure speech synthesis, open the model in the provider details and turn on “Speech only (TTS)”. Models marked speech only appear only in Voice model settings and are no longer used for chat; if the model is in use as a main, vision, light or fallback model, Claire first asks you to confirm which roles will be cleared. In Voice model settings, select the provider, model and voice, then play a test line. To use the model for chat again, open it in provider details and choose a role under “Use as”, or turn off “Speech only”; if the voice model was using it, you’re told the voice model was cleared.

The first three roles: the **main model** (does the work; must support tool calling), the **vision model** (describes the UI and locates things in screenshots; off by default on a fresh install, or choose "use main model"), and the **light model** (small jobs like judging outcomes, extracting memories and understanding intent; defaults to the main model). For exactly what "Test and save" tries and where the capability tags on the model list come from, see [model configuration details](../.github/CONTRIBUTING.md#model-configuration-details) in the contributing guide.

Whether a model can see images: if the provider's model list says, Claire goes by that; if it doesn't (many relays and self-hosted services don't), the preset's initial value applies, and a custom provider is assumed not to see. Tap a model on the provider's page and the sheet has a "Can see images" switch: what you set wins, even over the list. When the main model can see, screenshots go straight to it instead of costing a separate vision call. Picking the main model itself as the vision model (or choosing "use main model") also counts as saying the main model can see; switching away undoes it.

### OpenDesign Go

Subscribe to [OpenDesign Go](https://go.open-design.ai/) and create a workspace API key using its [API setup guide](https://open-design.ai/help/cloud-api/). In Claire, add the **OpenDesign Go** preset, enter that key and tap **Test and save**. The preset uses Chat Completions at `https://amr-link.open-design.ai/v1`; Claire loads the models available to your key. If you type a model name, use an exact ID from that catalog and choose a tool-capable main model. Image support depends on the selected model, so use a suitable vision model for screenshot tasks.

## Check task cost

When using the official DeepSeek API, turn on **Show task cost** under **Settings › Task running**. After a new task ends, the estimate appears in Chinese yuan next to the token count. Claire uses the official price table and the usage recorded for the task. It shows a range during possible weekday peak hours and marks estimates as partial when some requests cannot be priced. Check your DeepSeek bill for the final charge.

## Jev decision model (optional)

Configure it separately in **Settings › Models › Decision model · Jev**. It is off by default and does not change your main, vision or light model selections.

| Provider | Complete request URL | Default model ID |
|---|---|---|
| TypeSafe | `https://api.typesafe.ai/v1/systemone` | `jev-1.14.0` |
| OpenRouter | `https://openrouter.ai/api/v1/systemone` | `typesafe/jev-1.14` |
| Custom | A complete System One-compatible endpoint | As specified by the provider |

Enter that provider's API key, enable the switch and choose **Save**. Changes apply to the next task. Selecting a different provider preset clears the previous key. **Test connection** classifies a fixed greeting without sending conversations or screenshots, saving settings, or enabling the service. The provider may charge for this test. Custom endpoints require HTTPS, except for local or LAN services; a chat `/chat/completions` endpoint is not compatible.

When enabled, Jev classifies intent at the chat entry point (discussion, reading or action), and selects saved routes when experience replay is enabled. Intent classification sends the current request and a small amount of recent conversation. Route selection sends the request, candidate templates and parameters extracted from the user's exact words. The API key and settings are encrypted on the phone. The main model still plans, generates text and calls tools; Jev never operates the phone directly or replaces confirmation gates.

Missing candidates, ambiguous parameters, low probabilities, errors and timeouts fall back to the existing model flow. A confident choice not to reuse a route skips replay and lets the main model plan. Disable and save to stop Jev calls in new tasks; tasks already running keep their starting configuration. Usage is recorded by model name, and `jev_intent` / `jev_route` run events identify selection, skipped replay or fallback. This version does not use images, Box grounding, per-step UI action selection or memory generation.

## Speech to text

按住说话时用的第四个用途，在「设置 › 模型 › 语音输入」里选。默认用手机自带的语音识别，边说边出字，不花钱。手机没有、或认不准时，可以选服务商的语音模型：说完再转成字，用你的额度；列表里只列名字像语音模型的，没列出来的可以在每家下面手动填模型名。选的时候会先试转一小段静音，通了才选上，不通当场说原因。手机自带的识别如果按住后一直没有反应，Mika 会提示你来这里选一个，不会自己换成要花钱的模型。

### 离线语音包

手机没有能用的自带识别、又不想用服务商的语音模型时，可以下载一个离线语音包：识别在手机上完成，不联网也能按住说话，声音不离开手机，也不花额度。

- **在哪里**：「设置 › 模型 › 语音输入 › 离线语音包」。每个语音包一张卡片，写着能识别的语言、大小和许可；推荐给你界面语言的那个排在最前，标着「推荐」。只支持 64 位 ARM（arm64）手机，别的手机会写明不支持。
- **下载**：点「下载」。来源依次是你在「关于」里设的 GitHub 镜像（开着时）、Mika 的发布页、模型的原始仓库（HuggingFace，固定版本）；走「设置 › 网络代理」。用移动数据或热点时会先问一句、写明大小。可以随时取消，之后再下。
- **从文件导入**：手机上下不动、或者完全离线时，点「复制下载地址」，在电脑上把这些文件下好（打成一个 zip，或者分别选几个文件都行），拷到手机上，再点「从文件导入」选它们。
- **不管下载还是导入，每个文件都要和 App 里自带的大小、校验值对上**才会装上；对不上会说是哪个文件。
- **占多少空间**：SenseVoice Small 约 240 MB，Dolphin Base 约 104 MB，Whisper Base 约 161 MB，Whisper Small 约 375 MB；下载、导入前要多留 50 MB 左右的空余。语音包不进备份，换手机或从备份恢复后要重新下载，之前的选择会留着、标「需要下载」，下好就能直接用。
- **怎么用上**：语音转文字选「自动」时，手机自带识别能用就仍然用它；用不了才用装好的语音包，先用推荐给这次识别语言的，没有就用装好的任意一个。也可以在列表里直接选它（标「本机 · 离线」），或者把它放进备援链的最后，云端模型连不上时由它接手。离线识别是说完再出字，不会边说边出。
- **通话不用语音包**：应用内通话和 AI 接打电话的听写仍用原来的识别方式，选择列表里也不显示语音包。
- **繁体中文**：选繁体中文时，识别出来的字目前是简体。
- 删除语音包时，如果语音输入正选着它，会回到「自动」。

## Sign in with an account (no key needed)

At the top of "Add provider" is the group "Sign in with an account · no key needed". Tap one to read what happens, sign in in the browser, and come back to Claire to see the account and its models; you can set one as the main model right there.

- **ChatGPT** (Plus or Pro, uses your plan's usage): tap "Continue with ChatGPT" and sign in in the browser.
- **xAI / Grok** (SuperGrok or X Premium+ subscription): the browser opens xAI's confirmation page; check that it shows the same short code as your phone, then approve. This sign-in borrows the public sign-in entry of xAI's official command-line tool; xAI did not set it up for Claire and may restrict this use. Some subscription tiers still can't make calls after signing in (403); use an xAI API key in that case.
- **OpenCode Go** (OpenCode Go subscription): the browser opens OpenCode's confirmation page; check that it shows the same short code as your phone, then approve. This uses OpenCode's public command-line sign-in. Requests go straight to OpenCode's inference gateway, and each model's protocol and address come from its model list.
- **OpenRouter**: OpenRouter's official authorization for third-party apps. Once you approve, OpenRouter gives Claire a key of your own (named "Claire"), paid from your OpenRouter balance; to stop, delete it on OpenRouter's Keys page.

Signing out only clears the sign-in on this phone.

## Check current allowance

Open **Settings › Models › Token usage**. The top section shows the current allowance for each configured provider. Use the date filters below to review the token usage recorded on this device.

DeepSeek, Kimi, and SiliconFlow show the account balance. OpenRouter shows how much the current key can still spend. Tap the row to see the account balance on OpenRouter. For providers such as ChatGPT, **Manage usage** opens the official site. Tap **Refresh** to check current allowances again.

## Reasoning effort

Models on ChatGPT sign-in, xAI sign-in, and the Responses API can adjust **Reasoning effort** below each role card; OpenAI-compatible, Anthropic Messages, and Gemini models can too when the provider's model list states the levels for that model. The default leaves the choice to the provider. "Use main model" inherits its effort; explicitly picking the same model lets each role use a different effort. Options come from the provider's model list when available (the Anthropic model list states each model's levels; models that only take a thinking budget get none). Without listed levels, only Responses models get generic manual options, subject to server support; OpenAI-compatible, Anthropic, and Gemini models are not guessed. Higher effort usually takes longer and uses more tokens.

## Response speed

In **Settings → Models → Providers**, open your OpenAI (API key) or ChatGPT provider and tap a model. Under **Use as**, select **Response speed**. Default uses the server setting. Choose Fast or Ultrafast to request that tier. The choice is saved per provider and model and applies to every role that uses that model. Availability depends on the model, project, or ChatGPT plan, and faster tiers may use more of your API or plan budget. If the server rejects a tier, select Default again.

## Context window and compaction

Open **Context and compaction** under a role in **Settings › Models**. Leave the window blank to use the selected model’s window. Leave the trigger blank for automatic compaction, or enter an absolute token count. The trigger counts the whole request, including the system prompt, tools, and messages. A window of 1,000,000 tokens has an automatic trigger of 967,000. Enter 600,000 to compact earlier. Use digits only in the fields. The dialog shows the allowed range and effective values. After compaction, the request can grow by a margin before it triggers again.

The settings apply on the next task and are shared by roles using the same provider and model. Choose **Reset** and save to restore both defaults. If reselecting a model gives it a smaller window, the effective trigger stays within its new bounds. A window override sets the local budget. The provider still enforces its own context limit.

## Models on your own computer or LAN

When adding a provider choose "Custom"; the base URL may start with `http://`, e.g. `http://192.168.1.10:11434/v1` (with the phone on the same Wi-Fi). Local and LAN addresses show no warning; an unencrypted address outside your LAN shows a short warning, since your API key and chats could be seen by others on the network. Use `https://` when you can.

## Network proxy

If you need a proxy to reach a model service but don't want a system-wide VPN, enter an address in **Settings › Network proxy**: `http://host:port` or `socks5://host:port` (`socks5h` works too; host names are resolved by the proxy). For a proxy that needs a login, write `http://user:password@host:port`. The address is stored encrypted on the phone; the screen only shows the scheme, host and port.

- Goes through the proxy: model requests (chat, speech to text, connection tests), model lists, provider allowance checks, token exchange when you sign in with an account, in-app update downloads, the in-app browser, and commands in Files & computing environment (while network access is on there).
- Always direct: addresses on this phone or your LAN (localhost, 127.x, 10.x, 172.16–31.x, 192.168.x, 100.64–127.x, names ending in `.local` or `.lan`, and single-word machine names), so local models such as Ollama or LM Studio keep working.
- If the proxy can't be reached or rejects the user name or password, Claire says so instead of blaming your key or model.
- `https://` proxies (an encrypted connection to the proxy itself) are only used by commands in Files & computing environment; Claire's own connections stay direct. Use `http://` or `socks5://` to cover everything.
- The in-app browser uses the proxy only if the phone's WebView supports a per-app proxy, and it can't use a SOCKS proxy that needs a login. In those cases the settings row says the browser connects directly.

## API key pool and auto-rotation

You can add multiple API keys for any provider by separating them with newlines, commas, or semicolons in the API key field, or using the "Add API Key" button under the provider's details page.

- **Round-robin load balancing**: When multiple healthy keys are active, Claire rotates requests evenly across the pool, multiplying your rate limit and token throughput.
- **Sub-50ms failover on rate limits**: If a key hits an `HTTP 429` (rate limit) or `401`/`403` (quota exhausted or invalid), Claire traps the failure, puts the affected key on a temporary cooldown (honoring the server's `Retry-After` header or a default 60s cooldown), and seamlessly swaps to the next key in `<50ms` without interrupting your task.
- **Auto-unfreeze**: Once a key's cooldown period ends, it automatically returns to active rotation.

## Multi-model waterfall and fallback

To prevent tasks from failing when a provider encounters capacity outages, service degradations, or prompt token limits (e.g. `HTTP 413` or `5xx` errors):

- Claire supports configuring secondary and tertiary fallback models under each role.
- If all keys for your primary model fail or the provider is temporarily unavailable, execution cascades down your waterfall chain automatically.
- Once primary keys recover from their cooldown, subsequent tasks automatically ascend back to your primary model.

## ChatGPT reports a workspace access error after switching accounts

If the browser says the app is only available to members of its workspace and shows `3p_login_workspace_scope_denied`, first check that you selected the ChatGPT account and workspace originally used to authorize it. The error means the current sign-in does not meet the app registration's workspace scope; it does not by itself establish a wrong password or exhausted plan usage.

ChatGPT **Sign out** in Claire keeps the registered `client_id` so the same account can sign in again. If you intend to switch to a different account or workspace, repeatedly signing out and back in may keep using the old binding. To create a new connection:

1. Return to Claire and cancel any pending sign-in.
2. Note your main, vision and light model selections.
3. Open **Settings › Models › ChatGPT** and confirm **Delete this provider** at the bottom. This removes that provider's local credentials, model configurations and registration ID; roles using it need to be selected again. Conversations stay on the phone. You do not need to uninstall Claire or clear all app data.
4. Add ChatGPT again, tap **Continue with ChatGPT**, choose the intended account and workspace in the browser, complete authorization, and return to Claire.
5. Select the models for your roles again and send a short request to verify inference. A model list confirms sign-in and model listing, not a successful inference request.

This is for switching accounts or workspaces, not bypassing workspace access controls. If a fresh connection is still denied, check that the account has access to the workspace or contact its administrator. Include the error code and Claire version when reporting it, and redact email addresses, authorization codes and tokens.
