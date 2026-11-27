# Claire versioning

Claire follows the same versioning scheme as Kai-custom: `a.b.c` with a
recorded base version.

- **Claire version:** 2.7.0 (versionCode 77)
- **Base version:** upstream Mika 0.1.52, merged through commit `1812eabef`
- **Package:** `com.kilv.claire` since 1.1.0 — installs alongside Mika
  (1.0.0 was still `com.wangzan101.mika` and could not install next
  to it).

## Bump rules

- Minor fix: bump `c` — 1.0.0 → 1.0.1
- Feature add / major fix: bump `b` — 1.0.0 → 1.1.0
- Rebase onto a new upstream Mika (or rebuild): bump `a`, reset `b`/`c` — 1.x → 2.0.0
- `versionCode` in `app/build.gradle.kts` increments on every release
  (it continues upstream's numbering).

## Releases

- 1.0.0: first release (OrcaRouter, English by default).
- 1.1.0: own applicationId `com.kilv.claire` + app label Claire, so
  it installs alongside Mika instead of conflicting with it.
- 1.2.0: full Claire identity — the app calls itself Claire
  everywhere (settings, captions, prompts, phone-call greetings),
  the in-app updater checks `kilvz/Claire` releases, the author
  points at Kilv (`@KilZT`, his WeChat QR), and its own folders are
  `Pictures/Claire`, `Download/Claire`, `Music/Claire`.
- 1.2.1: release-signed with Claire's permanent keystore (GitHub
  secret `ANDROID_KEYSTORE_BASE64`, local backup
  `~/workspace/keystores/claire-release.keystore`). 1.2.0 was
  debug-signed and could not install over 1.1.0 — GitHub runners
  generate a fresh debug key per run, so the signatures never
  matched. From 1.2.1 on, releases install over earlier
  `com.kilv.claire` builds; 1.1.0 still needs one manual uninstall.
- 1.3.0: Antigravity provider — sign in with a Google account (your
  own OAuth client id/secret from a Google Cloud project or your own
  Antigravity install) and use its quota; Telegram bot channel in
  Settings, directly below WeChat (bot token + chat id, send/receive
  via the Bot API).
- 1.3.1: removed the Antigravity provider (Google sign-in) again;
  the Telegram bot channel stays.
- 1.3.2: the Telegram bot shows "typing..." while a channel task runs
  (refreshed every 4 s, stops when the task ends), and the "Got it —
  your task is registered." notice is sent only for the very first
  task after the bot is set up, not for later tasks.
- 1.3.3: Telegram gets its own paper-plane icon (`MikaIcons.Telegram`,
  drawn in the same hand-drawn stroke style) on the Settings home row
  and the Telegram settings header — it no longer shares the two-bubble
  `Chats` icon with WeChat.
- 2.0.0: rebase onto upstream Mika 0.1.51 — all 198 upstream commits
  since v0.1.50 ported (main `a85b8a7b`, 23 Nov 2026), cherry-picked in
  order: in-app voice calls (live subtitles, 13 voice vendors), MCP
  guided catalog (74 services, one-click auth), WeChat result push +
  intent gate, teach/learning, new providers (Cloudflare Workers AI,
  OpenDesign Go, OpenAI Fast/Ultrafast, model groups with failover),
  sandbox limit/timeout fixes, multi-file share to draft, and the
  23 Nov file splits + directory restructures (ui files into feature
  `ui/` packages, core packages regrouped, repo root layered).
  Claire's own work survives untouched: full rebrand, OrcaRouter
  preset, Telegram channel (typing indicator, first-only "Got it",
  paper-plane icon), permanent-keystore signing. No release tag yet —
  2.0.0 is merged to main; the release is a later call.
- 2.0.1: the "Voice input isn't ready" sheet now explains what the
  built-in speech recognition does and offers to install it — a button
  opens Speech Services by Google on the Play Store, so phones that
  ship without a recognizer can get one instead of only being told to
  pick a cloud provider.
- 2.1.0: English-first throughout. Every LLM prompt, tool description,
  call script, skill, built-in knowledge note, plugin manifest and doc
  is English, so the assistant no longer drifts into Chinese when the
  user writes in English. Chinese and the other UI languages remain as
  translations for users who choose them; Chinese-specific matching
  data and test fixtures are unchanged.
- 2.1.1: voice fixes. The on-device and streaming speech tests can be
  stopped — while a live engine is listening the test button becomes
  Stop, so "Say a sentence" finishes and verifies instead of running
  forever with the button disabled. Sherpa models can be downloaded
  directly from the speech selection page, a missing model explains
  that it needs downloading, and an archive without all four model
  files fails without leaving a partial model behind. Google speech
  services use their current Play Store name, Speech Recognition &
  Synthesis.
- 2.1.2: the multilingual on-device speech model loads as the
  zipformer2 export it is, so testing or using it no longer closes the
  app. Downloads keep running after leaving the page and show an
  unpacking step once the bytes arrive; the speech test waits for that
  to finish instead of reporting the model as missing. While listening,
  microphone updates no longer replace what was already heard, and a
  finished test shows the transcript. When Google's speech app is
  installed but Android offers no recognition service, the missing-voice
  sheet opens its settings instead of a store page that says Installed.
- 2.1.3: built-in speech recognition now uses a Google recognizer that is
  installed even when the phone has no default recognition service set,
  instead of showing install instructions. It looks for the service in
  Speech Recognition & Synthesis, the Google app and Android System
  Intelligence, tries them by name when Android offers no default, and
  moves on to the next one when the current one fails. On-device speech
  models load in the background, so the listening card appears at once
  instead of the voice button freezing while a large model loads, and
  speech from that moment is kept.
- 2.2.0: ported upstream Mika through commit `7f7a93155` (24 Nov 2026).
  The new front desk answers in a sentence or two while background tasks
  do the work: in chat and voice calls it dispatches tasks, takes
  additions and corrections, answers their questions, reports progress
  from the task board, and stops them — always with the user's whole
  original sentence. Voice calls now live in the conversation timeline
  with call records, tasks can be branched or re-asked from any finished
  turn, phone calls can ask the owner a question with a reply
  notification, and there is a new file library. Existing English copy,
  the international default provider, and the Google speech fix are
  kept.
- 2.2.1: ported upstream Mika through commit `6d364993f` (24 Nov 2026).
  The chat and settings pages are now a plain, flat style: white chat
  page with solid bubbles and no translucent layers, a three-block top
  bar, settings and AI call pages as light-grey groups with bare icons,
  and one shared borderless input field. The sidebar opens with browser,
  Teach Claire and AI calls as three cards and a lighter chat list; the
  plus panel groups camera, album, files and clipboard above Teach
  Claire, Skills and Saved. Only Claire is visible to the user now: chat,
  call and phone prompts, tool text and earlier turns never mention a
  front desk or background task, and the newest result Claire gave is
  kept in full so follow-ups about it still work. Take over, allow once,
  continue and stop happen directly on the card in the chat, progress
  lives in the bar above the message box, and cards appear only when
  something needs the user. AI call setup is redone automatically after a
  restart when Android dropped it, and web links from settings, About and
  answers open in Claire's own browser in a new tab. Existing English
  copy, the international default provider, and the Google speech fix are
  kept.
- 2.2.2: documentation and computer-tools pass. Every doc now calls the
  product Claire instead of upstream Mika: the README, feature guide,
  setup guide, install-with-an-agent guide, MCP and CLI pages, FAQ,
  computer pages, and the developer docs. Install commands point at
  `kilvz/Claire` releases and use the real package `com.kilv.claire`,
  APK name `Claire-<version>.apk`, accessibility component and external
  file paths; the CLI/Skill docs use Claire's `v2.2.x` tags. The bundled
  `mika` CLI now defaults to the `com.kilv.claire` package, so `mika
  connect` finds Claire without `--package`, and its messages say Claire.
  Existing English copy, the international default provider, and the
  Google speech fix are kept.
- 2.3.0: ported upstream Mika through commit `7ff1703f6` (25 Nov 2026)
  and added OpenCode Go sign-in. The model screen now offers OpenCode Go
  in the account sign-in group: Claire shows a short code, OpenCode's page
  confirms it, and each model then calls the protocol and address named in
  OpenCode's own model list. The interface speaks Vietnamese, the empty
  chat page is redesigned around a larger greeting with a swipeable promo
  card and two rows of example pills, and the composer turns its send
  arrow into a stop button while a reply or task is running. Settings
  pages keep their scroll position on back, task pause, voice-call step
  cards and followed-run controls are steadier, proactive listening
  re-registers after the system drops it, shared drafts honor their size
  tiers, and the sandbox source pack includes copyleft pip wheels.
  Existing English copy, the international default provider, and the
  Google speech fix are kept.

- 2.3.1: MCP tools now carry readable names that include the connector,
  so calls to GitHub, filesystem and other servers stay distinct even
  when two connectors share a label; a failed tool call is shown under
  the connector it belongs to instead of its internal name. The MCP
  catalog's Everything preset starts correctly and every preset
  description is translated. Run records, cancellation summaries and
  attachment lines now use English markers (older conversations still
  open), and the voice-briefing authorization phrase follows the
  interface language.

- 2.3.2: the shell transport picker's labels are centered and no
  longer clipped, so Auto, wireless debugging and Shizuku stay readable
  at any text size or display density. The build is warning-clean
  again: deprecated Android calls were replaced with their current
  equivalents (parcelable reads, call state, screen-reader checked
  state, predictive back, display metrics), the test compilations opt
  in to the coroutine and serialization APIs they use, and the few
  calls with no replacement are suppressed with the reason documented.
  The Gradle end-of-life notice is informational and the pinned
  Gradle/AGP/Kotlin versions still match upstream.

- 2.3.3: conversation titles stay in the language of the first message.
  An English chat no longer gets a Chinese title: the title prompt now
  asks for the message's language first, and a title that switches
  scripts is ignored, with the chat showing the user's first message
  instead. English titles are no longer cut off mid-word by the old
  16-character limit.

- 2.4.0: version bump only; no functional changes from 2.3.3.

- 2.5.0: the Telegram settings page shows a QR code for the saved bot
  once its @username is known (fetched once from Telegram with `getMe`
  and cached). Scan it with another phone to open the bot in Telegram,
  then tap Start. The token and chat ID setup is unchanged.

- 2.6.0: the Telegram settings page can scan a QR code to set the bot
  up — point the camera at a code holding the bot token from @BotFather
  (optionally `token|chatId`) and the bot is saved automatically, no
  manual token entry. A token alone fills the token field; the chat ID
  is still asked for. CameraX + ML Kit barcode scanning.

- 2.7.0: merges the latest upstream Mika. New: pause everything from
  Settings; AI phone calls with realtime voice, auto-answer rules,
  summaries and follow-up drafts; video generation and video tools;
  system, on-device and provider voices with per-language selection; a
  rebuilt schedule editor with execution windows and reliable runs
  while the screen is off or locked; live settings search; conversation
  export; provider nicknames and a visual fallback editor; and Telegram
  setup by QR code. Fixes: the Telegram bot no longer crashes Claire
  when a message arrives ("unknown channel telegram"), and new
  wireless-debugging pairings are introduced as `claire@<model>`
  instead of `mika@<model>` (a phone paired earlier keeps the old entry
  until it is unpaired and paired again).

A release is cut by pushing a tag `v<version>` (e.g. `v1.1.0`).
`.github/workflows/release.yml` then builds the release APK on GitHub
Actions and publishes it as a GitHub Release with the APK attached
(`Claire-<version>.apk` + SHA256SUMS.txt). The APK is release-signed
with the permanent key. Since 1.1.0 its package is `com.kilv.claire`, so it installs
alongside Mika — and alongside Claire 1.0.0, which was still
`com.wangzan101.mika` (uninstall that one first if it is on the
phone). It installs over earlier `com.kilv.claire` builds from 1.2.1
onward (1.1.0 was debug-signed with a throwaway CI key and needs one
manual uninstall first).
