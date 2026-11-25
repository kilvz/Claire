<div align="center">

<img src="docs/media/logo.png" alt="Claire" width="88" />

# Claire

**An AI assistant on your phone, in English from the first tap: say what you need, and it works your apps and the web until it's done.**

Claire is a fork of [Mika](https://github.com/wangzan101/Mika) by [Wang Zan](https://github.com/wangzan101), kept under the same [Apache-2.0](LICENSE) license. It keeps Mika's engine and gives it its own defaults: English first, and OrcaRouter as a built-in model provider.

It works on a hidden background screen, so you can keep using your phone.

<br/>

<a href="https://github.com/kilvz/Claire/releases/latest"><img src="https://img.shields.io/badge/Download%20APK-Android%2011%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" /></a> <a href="https://github.com/kilvz/Claire"><img src="https://img.shields.io/github/stars/kilvz/Claire?style=for-the-badge&logo=github&label=Star&color=FFD700&logoColor=white" alt="Star this repo" /></a>

<br/>

<a href="https://github.com/kilvz/Claire/releases"><img src="https://img.shields.io/github/downloads/kilvz/Claire/total?label=downloads&color=2ea043" alt="downloads" /></a> <a href="https://github.com/kilvz/Claire/releases/latest"><img src="https://img.shields.io/github/v/release/kilvz/Claire?include_prereleases&label=version" alt="version" /></a> <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" /></a>

<br/>

<img src="docs/media/wechat-send-side.gif" alt="From one sentence, Claire opens WeChat on its background screen, finds the contact and sends the message" width="640" />

<sub>"Send a message to File Transfer": Claire's chat on the left, each step on its background screen on the right (4× speed; chat list blurred)</sub>

</div>

## What makes Claire different

- **English by default.** Claire starts in English, and the agent's own instructions are written in English. The app UI is still available in many languages — switch it in Settings › Language.
- **OrcaRouter built in.** One API key reaches over 200 models through an OpenAI-compatible endpoint, including a free model (orcarouter/free) and one that picks the model for you (orcarouter/auto). Get a key at [orcarouter.ai/console/token](https://www.orcarouter.ai/console/token) and add it under Settings › Model.
- **Its own versioning.** Claire 2.2.2 is based on upstream Mika 0.1.51 (commit `6d364993f`). See [VERSION.md](VERSION.md) for how versions are numbered.
- **Same engine.** Everything upstream Mika does — operating apps on a background screen, browsing the web, the Linux sandbox, skills and memory — works the same here.

## Get things done with one sentence

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/wechat-group.gif" width="230" alt="Reads through a WeChat group and summarizes today's chat" /><br/><b>"What did the group talk about today?"</b><br/><sub>Scrolls through the chat in the background, then sums it up</sub></td>
<td align="center" width="33%"><img src="docs/media/amazon-cart.gif" width="230" alt="Picks a charger on a shopping site and adds it to the cart" /><br/><b>"A charger under $30, rated 4.5 stars or more"</b><br/><sub>Adds the pick to the cart and stops before checkout</sub></td>
<td align="center" width="33%"><img src="docs/media/xhs-summary.gif" width="230" alt="Summarizes a creator's recent posts" /><br/><b>"What has this creator posted lately?"</b><br/><sub>Opens the app, reads post by post, then sums it up</sub></td>
</tr>
</table>

<details>
<summary>More demos</summary>

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/boss-greet.gif" width="230" alt="Greets recruiters for 3 jobs on a job site" /><br/>Greets recruiters for 3 jobs on a job site</td>
<td align="center" width="33%"><img src="docs/media/google-flights.gif" width="230" alt="Finds the cheapest nonstop flight" /><br/>Finds the cheapest nonstop from San Francisco to New York</td>
<td align="center" width="33%"><img src="docs/media/hacker-news.gif" width="230" alt="Reads out the top 5 stories and their points" /><br/>Reads out the top 5 stories on a news site</td>
</tr>
<tr>
<td align="center"><img src="docs/media/job-greet.jpg" width="230" alt="Finds jobs and greets recruiters" /><br/>Finds jobs and greets recruiters</td>
<td align="center"><img src="docs/media/music-vip.jpg" width="230" alt="Watches ads to unlock music perks" /><br/>Watches ads to unlock music perks</td>
<td align="center"><img src="docs/media/food-order.jpg" width="230" alt="Orders takeout with stacked discounts and asks you before paying" /><br/>Orders takeout with stacked discounts, asks before paying</td>
</tr>
</table>

</details>

<sub>Several demos use apps popular in China: WeChat is the main messaging app there, and Xiaohongshu (RED) is a lifestyle and social platform. Claire isn't tied to them; it works with any app or website on your phone.</sub>

## Why Claire

| | |
|---|---|
| 📱 **Leaves your screen alone** | Opens apps, taps, types and scrolls on a separate background screen while you use your phone as usual; tap the small live view whenever you want to watch |
| 🧩 **Works with any app** | Looks at the screen and finds buttons the way a person does: no app APIs, no per-app integration |
| 🌐 **Call it from any web page** | In the built-in browser, say what you want on the page you're reading and it works right there; touch the page and it stops |
| 🧠 **Gets better with use** | Remembers the preferences you tell it, and turns tasks it finishes into skills so next time is faster and steadier |
| 🔒 **Your data stays on your phone** | There is no Claire server; use your own model API key, or sign in with a ChatGPT, xAI, OpenCode Go or OpenRouter account |
| 🆓 **Open source and free** | Apache-2.0, all code public |

## Get started in three steps

1. **Install**: download the APK from [Releases](https://github.com/kilvz/Claire/releases/latest).
2. **Connect a model**: in Settings › Model, pick OrcaRouter and paste your API key, or pick any other provider, or just sign in with an account ([models and sign-in](docs/models.md)).
3. **To operate phone apps**: follow the "Set up phone control" wizard in Settings and pair wireless debugging on the phone once ([setup guide](docs/setup-guide.md)). Skip this if you only want chat, web and files.

Then try saying: "Set alarms for 9:40 and 10:00 tomorrow morning" or "Check the weather at 8 every morning and tell me only if it will rain".

> 💡 Have a computer handy? Send this to the AI agent on it and it will set everything up for you:<br/>
> `Follow https://github.com/kilvz/Claire/blob/main/docs/install-with-agent.md to install Claire on the Android phone connected over USB.`

## What it can do

| | |
|---|---|
| **Operate apps** | Reads long lists and chat histories, taps and types, shares text into apps; hands logins, verification codes and payments to you |
| **Browse the web** | A built-in desktop-class browser that keeps you signed in, reads pages, downloads and uploads; paces itself and hands block pages to you |
| **Your current screen** | Double-tap the floating capsule to send the screen you're looking at: "how should I reply?", "note this down" |
| **Schedules and conditions** | "Remind me at 9 tomorrow", "do this every Friday"; runs on time in the original conversation |
| **Jump in or stop anytime** | Keep talking while a task runs to add or correct things (long-press to withdraw or edit one Claire hasn't picked up yet); after a stop it reports where it got to, and "continue" picks up from there |
| **Hold to talk** | Hold the chat input to speak and edit the words before sending; long-press the floating capsule to speak and release to send. Uses the phone’s built-in speech recognition, or a provider’s speech model if it has none |
| **Files and computing** | A Linux sandbox per conversation runs Python, Node and shell on the files you send (arm64) |
| **From your computer** | AI tools on your computer can hand Claire tasks through CLI + Skill or MCP ([use Claire from a computer](docs/computer.md)) |

By default Claire runs in **Ask mode**: it checks with you only before spending money (placing orders, paying) and just does everything else. You can switch to **Autonomous mode** instead. See [Ask and Autonomous](docs/modes.md).

For how each of these works and the finer details, see [features in detail](docs/features.md).

<details>
<summary>Apps and websites with built-in usage knowledge (anything not listed still works)</summary>

The names are shown as they appear in the knowledge files.

<!-- generated:knowledge:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
| Category | Built in |
|---|---|
| **Apps** | Alibaba, Alipay, Amazon, Android Settings, Baidu, Baijiahao, Bilibili, BOSS Zhipin, ChatGPT, Chrome, Dedao, Dianping, Discord, Douyin, Feishu, GitHub, Gmail, Google Calendar, Google Maps, Instagram, JD, Jike, Kuaishou, LinkedIn, Meituan, Meituan Waimai, Netflix, Pinduoduo, QQ, QQ Mail, QQ Music, Reddit, Slack, Spotify, Taobao, Telegram, TikTok, Toutiao, Uber, Uber Eats, WeChat, Weibo, WhatsApp, X, Xianyu, Heybox, Xiaohongshu, YouTube, Zhihu |
| **Websites** | China Railway 12306, 1688, Amazon, Baidu Search, Bilibili, Google Calendar, Ctrip, Dianping, Douban, Douyin, GitHub, Instagram, JD.com, Gmail, Google Maps, Meituan, Taobao, Telegram, Baidu Tieba, Baidu Hot Search, Weibo, Sogou WeChat, WhatsApp, Xiaohongshu, Xiaoyuzhou, QQ Music, YouTube, Zhihu, BOSS Zhipin |
| **How-to skills** | Change Claire's own settings, Turn content into text, Work with PDF documents, Get to the bottom of something, Messages and email, Write a skill |
<!-- generated:knowledge:end -->

</details>

<details>
<summary>Supported model providers</summary>

<!-- generated:providers:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
Presets for DeepSeek, Qwen, Kimi, Zhipu, Zhipu Coding Plan, SiliconFlow, Volcengine Ark, MiniMax, StepFun, Tencent Hunyuan, Baidu Qianfan, OpenRouter, OrcaRouter, OpenDesign Go, OpenCode Go, Anthropic, Gemini, OpenAI, xAI, Mistral, Groq, Cloudflare Workers AI, Ollama, LM Studio and more; for any other provider pick Custom, which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.
<!-- generated:providers:end -->

You can also skip the key and sign in with a ChatGPT (Plus / Pro), xAI / Grok, OpenCode Go or OpenRouter account. Steps for adding a model, and what to know about each sign-in, are in [models and sign-in](docs/models.md).

The quickest start is OrcaRouter: one key at [orcarouter.ai/console/token](https://www.orcarouter.ai/console/token), then pick the free model orcarouter/free, or orcarouter/auto to let it choose the model per prompt.

</details>

## Which phones

| | Requirement |
|---|---|
| Chat, web, files | Android 11 or later |
| Operating phone apps | Android 12 or later; **Android 14+ recommended** (the background screen has its own focus, so it doesn't get in the way of your typing on the main screen) |
| Linux sandbox | arm64 devices |

Not sure your phone is up to it? Run Settings › Recovery & repair › **Compatibility self-check**: 12 checks in about a minute, and you can [send us the results](https://github.com/kilvz/Claire/issues/new?template=device-report.yml) in one tap.

## Privacy & safety

- **There is no Claire server**: what you say and what's on your screen go only to the model provider you set up.
- API keys and memories are stored encrypted on the phone; conversations and run records stay on the device; update checks only contact GitHub.
- Operating third-party apps and websites automatically may break their terms of service, so use your own judgment. For anything important, use Ask mode and check the results. See the [disclaimer](docs/disclaimer.md).

<details>
<summary><b>FAQ</b></summary>

**Wireless debugging turned itself off?** Android turns it off when you change Wi-Fi or restart. With your permission, Claire turns it back on by itself; if it still can't connect, go back to "Set up phone control" and pair again.

**Can't find wireless debugging on my phone?** Some phone systems leave it out. You can start the phone helper from a computer over USB instead; see the [setup guide](docs/setup-guide.md).

**Some screens can't be read or captured?** Pages like payments and password entry block screenshots, so Claire asks you to take over there.

**Can I use a model running on my own computer?** Yes: pick "Custom" as the provider and enter its address on your local network.

**Is the app in English?** Yes. The app UI comes in English, Arabic, Persian, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, Portuguese, Russian, Indonesian and Vietnamese, follows your system language, and can be switched in Settings › Language. The built-in app and website knowledge is written in Chinese, but that doesn't limit you: the model reads it fine, and you can talk to Claire in English.

**Where do updates come from?** Only from this repository's [Releases](https://github.com/kilvz/Claire/releases); you can also upgrade in the app from About › Check for updates.

More answers are in the [FAQ](docs/faq.md).

</details>

## Community & contributing

- **Docs**: all docs and a suggested reading order are in the [docs index](docs/README.md)
- **Community**: join the [X group chat](https://x.com/i/chat/group_join/g2116926829198246025/48B52j5svL); to reach the original author on WeChat, scan the QR code in the app under About › Contact the author (mention Claire in your request)
- **Follow the author**: X [@wangzan101](https://x.com/wangzan101)
- **Report a problem**: [open an issue](https://github.com/kilvz/Claire/issues/new/choose); About › Send feedback in the app can attach diagnostics
- **Contribute**: add usage knowledge for an app or website (plain text, no code needed), or contribute code; see the [contributing guide](.github/CONTRIBUTING.md)

## License

[Apache-2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party components](licenses/THIRD_PARTY_NOTICES.md)
