# Voice cascade (listen → think → speak) implementation plan

Prototype: `docs/design/prototypes/2026-11-23-voice-listen-speak.html` (approved).

## Goal

- In-app voice calls and AI phone calls both go "listen (ASR) → think (text LLM) → speak (TTS)" by default, with realtime voice models demoted to an advanced option.
- Two levels, vendor → model: enter credentials once per vendor and it brings out that vendor's listen / speak / realtime models.
- A dozen-odd vendors built in, each needing only its own fields.

## Entry points

Both kinds of call recognize only `CallRealtimeSession` (PCM in, `VolcRealtimeEvent` out):

- AI phone calls: `CallRuntime.runWork` → `RealtimeSelectionController.callProvider()` → `provider.connect(...)` → `CallPcmBridge`.
- Voice calls: `VoiceCallOverlay`'s `connect` → `VoiceCallSession` (needs the `mika_request` tool).

The cascade is built as another `CallRealtimeSession` implementation, chosen by the provider factory according to the "call mode"; event handling in `CallRuntime` / `CallPcmBridge` / `VoiceCallSession` does not change.

## Modules (`voice/cascade/`)

| Class | Responsibility |
|---|---|
| `FeedRecorder` | A `StreamingRecorder` implementation where PCM is pushed in from outside; lets every existing STT session (upload-based, Doubao streaming, FunASR, Bailian) consume call audio |
| `Pcm16` | Byte/sample conversion, linear resampling, WAV parsing |
| `UtteranceDetector` | Split speech by volume: adaptive noise floor, speech-start detection, N seconds of silence to end the utterance, per-utterance cap, leading buffer |
| `CascadeListener` | Open one STT session per utterance and feed it audio, release at the end of the utterance, take the Final |
| `SentenceSplitter` | Split on commas and periods, for segment-by-segment synthesis |
| `CascadeSpeaker` | Segment-by-segment TTS → PCM → 24 kHz, `Audio` events; can be interrupted |
| `CascadeBrain` | "Think": always `LlmBrain` (chat model + rolling history + tool loop). Phone calls give it `send_dtmf`; in-app gives it `mika_request` — the routed-back result is an English instruction written for the model to read, so it must pass through the model once more before being spoken, which is why in-app never reads it out verbatim |
| `CascadeVoiceSession` | Orchestrates the pieces above and produces the same event sequence as a realtime model; coarse interruption |
| `CascadeEngine` | Assembles listen / speak / think according to the selection, for both kinds of call |

Event conventions: the user starts speaking → `UserSpeaking`; transcript → `UserTranscript(final, complete, current)`; answer → `AssistantAudioStarted` → `AssistantTranscript` → `Audio`… → `AssistantTextEnded` → `AssistantAudioEnded`; interruption → `AssistantInterrupted`; `sendGreeting(text)` is synthesized verbatim.

## Directories and selection

`VoiceSelections` gains:

- `engine` (standard / realtime, standard by default)
- `think` (light model / main model)
- `silenceMs` (default 1500)
- `bargeIn` (on by default)
- Phone-only: `callEngine`, `callStt`, `callTts`, `callTtsVoice`; when null they follow

## Vendors (`voice/vendors/`)

`VoiceVendors` registry: each vendor declares its fields, models (listen / speak / realtime) and default voice; saving generates a `VoicePresetPlan`.

Multi-field credentials map onto the three existing `VoiceCredentials` slots without changing the storage format:

- Tencent: SecretId → appId, SecretKey → apiKey
- iFlytek: APPID → appId, APIKey → apiKey, APISecret → accessKey
- Azure: the region goes into binding.region

New protocols:

| Capability | Protocol |
|---|---|
| Listen | ElevenLabs, Deepgram, Azure short audio, Tencent one-sentence recognition, iFlytek dictation |
| Speak | MiniMax T2A, Volcano TTS, Bailian Qwen TTS, ElevenLabs, Deepgram, Azure, Tencent, iFlytek |

The OpenAI-compatible few (Groq, SiliconFlow, Zhipu) go straight through `AudioTranscriptions` / `OpenAiSpeech` plus an endpoint.

## UI

- Voice: home ① listen / speak / call mode / voice services; ② listen, ③ speak, ④ call mode, ⑤ realtime models, ⑥ vendor list, ⑦ vendor details, ⑧ add, ⑨ enter credentials, ⑩ result.
- Phone: ⑪ the answer calls / make calls / history three tabs plus ⚙ and the readiness card; ⑫ listen-think-speak status during a call; ⑬ preparation checklist; ⑭ settings; ⑮ listen and speak used for phone calls; ⑯ call audio; ⑰ rules; ⑱ opening line; ⑲ record details.
- New strings present in all 12 language folders.

## Order

1. Directory selection and the vendor registry (JVM tests)
2. New protocols (JVM tests, following the official docs)
3. The cascade engine (JVM tests: utterance splitting, sentence splitting, event sequence, interruption)
4. Wire into both kinds of call and the readiness checks
5. UI and strings
6. Full unit tests, perf build, walk the screens and make one voice call on a real device

## Known limitations

- The phone's built-in recognition can only listen to the microphone, so phone calls' "listen" cannot use it; the preparation checklist points this out.
- New realtime protocols (Bailian Omni, Gemini Live) are not wired this round; realtime is still Volcano and OpenAI.
- Every vendor interface is implemented from public documentation; for those without a real key only request/parse unit tests were done.
