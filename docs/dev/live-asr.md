# Qwen real-time speech recognition: self-hosted service and Alibaba Cloud Bailian

This document has two parts:

- **Want to deploy the recognition service yourself** (this is where 「部署说明」 (Deploy instructions) on the settings page opens): see "How to deploy a self-hosted service" below.
- **Want to understand or modify the app's implementation of this part**: start from "Entry point and configuration" onward.

## How to deploy a self-hosted service

The app has only one requirement of a self-hosted service: it must speak the WebSocket messages described in the "Self-hosted service messages" section, and the phone must be able to reach it (usually a machine with a GPU on the local network). No account or key is needed.

### Supported models

| Model | How to deploy |
|---|---|
| Fun-ASR-Nano (`FunAudioLLM/Fun-ASR-Nano-2512`) | Use the real-time service that comes with the Fun-ASR repository; works out of the box |
| Qwen3-ASR (`Qwen/Qwen3-ASR-1.7B`) | There is no ready-made service speaking the same protocol; you have to put your own frontend in front of vLLM |

Both are configured the same way in the app: on the self-hosted tab, enter 「主机:端口」 (host:port) and tap 「连接并使用」 (Connect and use). The app does not distinguish which model is on the other end; in the UI both are called 「千问（自建）」 (Qwen (self-hosted)).

### Fun-ASR-Nano

Start the service with serve_realtime_ws.py from the [Fun-ASR repository](https://github.com/FunAudioLLM/Fun-ASR), setting up the environment as described in its docs/realtime_demo.md. The default port is 10095; when you enter only the host in the app, this port is filled in automatically.

### Qwen3-ASR + vLLM

Three steps: serve the model with vLLM, change one thing in the chat template, and put a frontend in front that speaks the app's message set. The parameters below have been run on vLLM 0.25.1.

**Step 1: Start the model**

```sh
vllm serve Qwen/Qwen3-ASR-1.7B --host 127.0.0.1 --port 10098 \
  --max-model-len 4096 --gpu-memory-utilization 0.2 --chat-template chat_template.jinja
```

- `--max-model-len`: defaults to 65536 when omitted, which will not start if the VRAM quota is small. 4096 holds about 5 minutes of audio (about 13 tokens per second), which is enough when an utterance is at most 60 seconds.
- `--gpu-memory-utilization`: the weights take about 4 GB of VRAM; the rest is cache for concurrent requests, sized according to your GPU.
- It only listens on localhost; what faces outward is the frontend from step 3.

**Step 2: Make the chat template support "what the reply starts with"**

`chat_template.jinja` is the model's own chat template (the contents of chat_template.json in the weights directory) plus two additions: in the loop that collects system text, also accumulate the text of assistant messages; and when there is an assistant message at the end, write it verbatim as the start of the reply, without an end marker.

```jinja
{%- elif m.role == "assistant" -%}
  {%- set ns.has_prefix = true -%}
  {%- set ns.prefix = ns.prefix + m.content -%}
{# …write the system and user sections the way the model's own template does… #}
{%- if ns.has_prefix -%}
{{- "<|im_start|>assistant\n" + ns.prefix -}}
{%- elif add_generation_prompt -%}
{{- "<|im_start|>assistant\n" -}}
{%- endif -%}
```

Send `"add_generation_prompt": false, "continue_final_message": true` in the request body, put the assistant's opening text in the last message, and the response contains only the text that follows it.

**Step 3: Write the frontend**

The frontend speaks the "self-hosted service messages" set to the app and calls vLLM's chat completions endpoint (/v1/chat/completions, with the whole audio clip placed in one user message). It needs to do the following:

1. **Segmentation**: use streaming VAD to cut out speech segments. A segment is only put into `sentences` once it is finished (a pause, receiving `STOP`, or 60 seconds without a pause).
2. **Preview** (about one `partial` per second): resend the audio of this segment from its start up to now in full; take the previous result, strip about the last 5 tokens and use that as the reply prefix, so the model only continues; the first two previews of each segment carry no prefix. This is the official model SDK's streaming approach, and earlier text does not flicker back and forth. `partial_start_ms` stays at the segment's start.
3. **Finalization**: after the segment ends, re-decode the whole segment once without a prefix and use that result.
4. **Language**: the model's reply starts with "language <language name>" followed by an `<asr_text>` marker, and these must be stripped; a language name of None means nothing was heard, treated as empty. First let the model decide the language itself, and only if it differs from what `LANGUAGE:` specified, prepend the language marker to the reply and re-decode (forcing the language from the start causes silence to be decoded as filler words).
5. **Hotwords**: put the words from `HOTWORDS:` into the system message.
6. **Do not truncate stutters**: "because because because" is really what the speaker said, so keep it as is; only when the same short phrase repeats dozens of times in a row should it be treated as the model looping.
7. **Follow the protocol for the rest**: `START` may be repeated to start again; `LANGUAGE:` and `HOTWORDS:` may arrive before or after `START`; a `STOP` with no audio ever sent only returns `stopped`; when the model service is unreachable, disconnect immediately rather than letting the app wait for a timeout.

**Why not just use the existing endpoints**

| Existing endpoint | Problems measured in practice |
|---|---|
| vLLM's built-in real-time endpoint (/v1/realtime, with the model architecture swapped to Qwen3ASRRealtimeGeneration) | Fixed 5-second segments, each decoded separately, no context carried, no way to specify the language: text only appears once every 5 seconds, with dropped and duplicated characters at the seams, and roughly a 30% error rate on human read-aloud. Checked the vLLM main-branch source on 2026-11-18; the segmentation and decoding approach has not changed. |
| vLLM's transcription endpoint (/v1/audio/transcriptions) | Cuts audio into 30-second pieces and splices them, adding extra punctuation at the joins. |
| The official qwen-asr package's streaming APIs (streaming_transcribe etc.) | These are in-process Python APIs, with no WebSocket and no segmentation. A thin wrapper can connect and the final results are also accurate, but the audio only grows and never shrinks, so after a few minutes it exceeds the context length and errors out (with the context set to 4096, a 5-minute recording did not finish); when a language is specified, silence is decoded as filler words; previews are one every 2 seconds by default; and the package (0.0.6) pins vLLM to 0.14.0. |

**Measured results**

Same batch of recordings, same client, comparing Fun-ASR-Nano's real-time service with the Qwen3-ASR frontend described above (several segments each of clear read-aloud, read-aloud with filler words, and multi-person chatter, each segment 30 to 50 seconds):

| | Fun-ASR-Nano | Qwen3-ASR frontend |
|---|---|---|
| Final result for clear read-aloud | No wrong characters | No wrong characters |
| Preview lag behind audio | Around 0.3 s | Around 0.1 s |
| Preview rewrites earlier text | Up to a dozen-plus times per segment | At most once per segment |

## Entry point and configuration

Under Settings › Models › Speech-to-text there is a "speech service that transcribes as you speak" group, with 「千问实时语音识别」 (Qwen real-time speech recognition) on the line below Doubao. Its settings page (`app/voice/ui/LiveSpeechSetup.kt`) has three tabs side by side at the top: 「自建」 (Self-hosted), 「百炼·北京」 (Bailian · Beijing) and 「百炼·新加坡」 (Bailian · Singapore); each manages its own settings, and opening it stops on the one currently in use (or last used) (`FunAsrTab.of`). If it has never been configured, tapping this row opens the settings page; once configured, tapping the circle reuses the previous kind (`SpeechSettings.lastLive`: the self-hosted address, or a Bailian region's model and workspace; if that kind is no longer usable, it opens the settings page), and 「设置」 (Settings) on the right opens it for editing. The second line of the row says which kind is in use, e.g. 「自建 · 192.168.1.10:10095」 or 「百炼·北京 · fun-asr-realtime」.

- **Self-hosted**: the page states that Fun-ASR-Nano and Qwen3-ASR are supported, and the 「部署说明」 row opens this document (`FunAsrProtocol.DEPLOY_GUIDE`). Enter the service address. When only a host or 「主机:端口」 is given, it is completed with `ws://`, and port 10095 is added if no port is given (the default port of serve_realtime_ws.py in the Fun-ASR repository); `http(s)://` is converted to `ws(s)://`, and addresses carrying a username and password are rejected (`FunAsrProtocol.normalize`). The address is not a key, so it is stored in the choice JSON (`SpeechChoice.FunAsr`), recorded again separately (`SpeechSettings.funAsrUrl`), and pre-filled when switching to another kind and back.
- **Bailian · Beijing / Bailian · Singapore**: the Beijing and Singapore API keys are not interchangeable; each region stores its own key, last model and workspace ID. Keys are stored separately, encrypted with `KeyCipher` (same Keystore alias as the Doubao credentials, different key), and are never filled back in; when one is already stored, leaving the field empty keeps it, and only the model or workspace is changed. The model list offers `fun-asr-realtime` (default), `paraformer-realtime-v2` and `qwen-audio-3.1-asr-flash-message`, and you can also type another model that speaks the same run-task protocol (only letters, digits and `.-_` are accepted). The workspace ID is optional: if given, it connects to that workspace's dedicated endpoint `wss://<workspace>.<region>.maas.aliyuncs.com/api-ws/v1/inference` (the region segment is `cn-beijing` or `ap-southeast-1`, as the docs recommend); if not, it connects to the general endpoint `dashscope.aliyuncs.com` / `dashscope-intl.aliyuncs.com` (still works). The ID has whitespace removed and is lowercased, and the `llm-` prefix is added if missing (IDs in the console start with it); only letters, digits and hyphens are accepted (it is spliced into the hostname, `BailianProtocol.workspace`), and the page shows the address that will be connected to this time. The choice JSON holds only the model, region and workspace ID (`SpeechChoice.Bailian`). Deleting a region's key turns off speech input if that region is in use.

All three tabs have a 「识别语言」 (Recognition language) group (`AsrLanguage`): Auto (default, unspecified, left for the service to decide), Chinese, English, Japanese, Cantonese and Korean, recorded separately per tab and stored in the choice JSON's `language` (an unrecognized code is treated as Auto, so the whole choice does not become invalid). Which languages a self-hosted service can recognize depends on the model it runs (Fun-ASR-Nano only does Chinese, English, Japanese and Chinese dialects; the multilingual version and Qwen3-ASR do more).

「连接并使用」 on all three tabs first tries a connection (`probeLiveAsr`): if it connects, sends the opening message and the server replies "started" (address, auth and model all accepted), the probe counts as passing; it then sends the closing message and disconnects, and only then is the choice stored and selected. The probe sends no audio and produces no recognition time; it does not wait for the server's closing response to an empty recognition. On failure the page states the reason (`SpeechOptions.probeFailure`: key rejected, service rejected the request (error code only), cannot connect), never the server's original message.

## Live path

`SpeechChoice.FunAsr` / `SpeechChoice.Bailian` → `SpeechResolver` → `VoiceInput` (Bailian fetches the key by region and determines the endpoint by workspace) → `LiveSpeech` (`app/voice/LiveAsr.kt`). The chat page's input box and the floating capsule share the same entry point, and after release the destination is the same as with other engines: text typed into the input box is sent by the user, while text in the capsule is sent directly to the task it is attached to. So the `Final` handed over must be the full text as finished by the server, not an incomplete intermediate result.

- Both providers use "text control messages + binary PCM audio": `LiveAsrProtocol` only handles what to send at the start, what to send at the end, and how to read the server's messages (read as `Started` / `Text(stable, tail)` / `Ended` / `None`; server errors throw `LiveAsrError` with the error code only); connecting, uploading and waiting for the final result all live in `LiveSpeech`.
- `LiveAsrTransport` is a standalone OkHttp WebSocket client: it does not follow redirects, sets no read timeout, and reads binary replies as UTF-8 text; handshake 401/403 is reported as `ModelError.Auth`, 429 as rate limiting, and anything else as network unreachable, never surfacing the server's original message. Request headers are only those given to it (Bailian uses `Authorization: Bearer <Key>`, the self-hosted service has none).
- `PcmRecorder(streaming = true)` gives 16 kHz mono PCM16, in 50 ms chunks combined into 100 ms packets (little-endian). No audio is sent before the server replies "started"; recordings made during that time stay in the recorder's queue (about 5 seconds at most). Bailian requires at least 50 ms between two packets (the official example sends one packet per 100 ms in real time, and audio accumulated before connecting is caught up at double speed); self-hosted services are not rate-limited.
- Each `Text` replaces the whole thing, never concatenated; `stable` is the sentences that are settled and `tail` is the sentence still changing, and the UI draws them as `Partial(stable, tail)`. When joining sentences, a space is added only when both sides are Latin letters or digits (`joinAsr`).
- On release, recording stops, the remaining audio is sent (a trailing partial packet is sent too) followed by the closing message, and `Final` is only sent once the server says it has ended; disconnecting, an error or a final-result timeout (10 seconds) all count as failure, and intermediate results are never passed off as the final text. The handshake and the opening each wait 10 seconds; a whole session has no limit (`SpeechSession.unlimited`): you can hold as long as you like, not bound by the 60-second cap for push-to-talk, with no countdown, and the recorder sends as it records without keeping the whole recording (`PcmRecorder`'s `unbounded`). When there is too much text, the card drops earlier finished sentences so the latest text stays visible. For a self-hosted service the final result is the server re-decoding the last segment (everything since the previous pause) in full; the server outputs at most 512 tokens per call, so the last segment may be truncated when you speak for a long time without pausing; a pause of about 3 seconds in between cuts the sentence and is unaffected. On cancel, events, recording and the connection are closed, and late results no longer surface.

### Self-hosted service messages (`FunAsrProtocol`)

Text `START` → the server replies `{"event":"started"}`; after that, audio is sent while holding, and the server replies roughly every half second with the full text so far `{"sentences":[{"text","start","end"}],"partial":"…","partial_start_ms","duration_ms","is_final":false}` (`sentences` are settled sentences cut by VAD, `partial` is the sentence being spoken, each reply is the complete set); text `STOP` → first the final result with `is_final` true (at which point `partial` is merged into `sentences`), then `{"event":"stopped"}`, and it ends only when `stopped` is received. If no audio was ever sent, the server gives no final result and replies `stopped` directly, treated as nothing heard (empty string).

When a recognition language is specified, `LANGUAGE:<Chinese language name>` is sent before `START` (Chinese, English, Japanese, Cantonese, Korean; the Fun-ASR server splices it into the prompt "speech transcription to …"), and the server replies `language_set`; nothing is sent when Auto. Confirmations such as `hotwords_set` and `language_set` do not change the text. Each push-to-talk utterance uses a new connection. The Fun-ASR server decodes serially, so several people using it at once will queue.

**How previews for long sentences are stitched (`PartialStitch`)**

When a segment runs long with no pause in the middle (VAD has not cut a sentence yet), the Fun-ASR server only decodes the most recent 15 seconds (server parameter `--partial-window-sec`) for `partial`; earlier text falls out of `partial` and only goes into `sentences` once the segment finishes. So when `partial_start_ms` grows while `sentences` is unchanged, the app stitches the successive `partial`s into the full text so far:

1. Find where the start of the new one falls among the already displayed text: compare letters and digits only, matching the first 12 characters position by position, and take the position with the most matches, allowing individual characters to have been rewritten; if fewer than half match or fewer than 3 match, estimate by time proportion.
2. In the overlapping stretch, keep the already displayed text for the first 8 characters (the start of the new one is the front of the window with earlier text cut off, which is the most likely place for errors or duplication), and switch to the new one only further on, at a point where the two share two identical characters in a row, never in the middle of an English word; if no such point is found, replace the whole thing from the start of the window.
3. When the server puts this segment into `sentences`, drop the kept characters and use the server's sentence.

This only affects the preview while holding; the final text uses only the server's final result.

### Bailian messages (`BailianProtocol`)

After connecting, send `run-task` (`header.action/task_id/streaming=duplex`, `payload.task_group=audio, task=asr, function=recognition, model, parameters{format=pcm, sample_rate=16000}, input{}`, with task_id freshly generated 32-digit hex each time), and wait for `task-started` before sending audio. In `result-generated`, `payload.output.sentence` replaces the whole sentence for the same `sentence_id`, and when `sentence_end` is true the sentence is settled and late intermediate results with the same number no longer change it; skip when `heartbeat` is true. After sending all audio, send `finish-task` (same task_id) and end only after `task-finished` — otherwise the last sentence is lost. For `task-failed`, only `error_code` is taken; auth, arrears and the like (InvalidApiKey, AccessDenied, Arrearage etc.) are reported as key rejected. The Tongyi Tingwu message edition (`qwen-audio-*`) gives no intermediate results by default, so `intermediate_result_enabled=true` is added at the start for it; this is the only place distinguished by model name (without it, no text appears while speaking). When a recognition language is specified, `parameters.language_hints` gets only a single language code (zh, en, ja, yue, ko; fun-asr-realtime only honors the first); nothing is passed when Auto.

## Verification and limitations

`FunAsrProtocolTest`, `PartialStitchTest`, `BailianProtocolTest`, `LiveSpeechTest`, `LiveAsrTransportTest` and `LiveSpeechSettingsTest` cover: message samples from both providers (the self-hosted samples are taken from real service replies, including all replies for a 40-plus-second utterance with no pause in `app/src/test/resources/voice/funasr-long-utterance.jsonl`: after stripping punctuation, every preview is a prefix of the final result), address normalization, no audio before confirmation, whole replacement, trailing packets, `Final` only after the end event, disconnect and timeout failures, cancel, probe, request headers, rejected handshake without the original message, key encryption storage/retrieval/deletion, choice read/write and fallback to Auto on bad data, the 「部署说明」 row pointing at the document in the repo, and the wording on the settings page. The WebSocket tests only connect to a local MockWebServer.

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'
```

Verified on a real device: with the self-hosted service (a Fun-ASR-Nano real-time service on the local network), holding to speak shows text as you speak and the text goes into the input box on release; with Bailian Beijing, after entering the key and workspace ID the probe passes and holding to speak likewise produces text. Bailian's billing, latency and accuracy depend on your actual account.

## References

Checked on 2026-11-07:

- [Fun-ASR repository](https://github.com/FunAudioLLM/Fun-ASR) (serve_realtime_ws.py, docs/realtime_demo.md)
- [Qwen3-ASR repository](https://github.com/QwenLM/Qwen3-ASR) (streaming example examples/example_qwen3_asr_vllm_streaming.py; checked 2026-11-18)
- [Bailian real-time speech recognition](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide), [Fun-ASR real-time speech recognition WebSocket API](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)
