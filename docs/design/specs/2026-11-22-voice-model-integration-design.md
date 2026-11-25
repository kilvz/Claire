# Unified voice model integration design

Date: 2026-11-22  
Status: Approved (2026-11-22)  
Working branch: `worktree-voice-model-integration`

## 1. Goals and rationale

Claire's voice input, answer read-aloud and realtime voice call should just be three ways of using things:

```text
Voice input → STT: audio → text
Answer read-aloud → TTS: text → audio
Realtime call → Realtime: audio ↔ audio + events
```

The problem today is not that the features themselves are complex, but that the three kinds of models are each wired up separately:

- STT has different config shapes: chat provider models, streaming services with independent credentials, self-hosted services and system recognition; chat provider models also try multiple protocols in turn using one second of silence.
- TTS reuses the chat provider, but needs a global "read-aloud only" flag written on the provider's model, which affects chat, image reading, fallback and model groups.
- Realtime has its own separate config, credentials, selection and test state.

This time we do only one thing: **integrate STT, TTS and Realtime through the same voice model catalog and adapter mechanism, and then let the three features all take models from there.**

The goal is that onboarding a voice model in the future is handled only once: configure the service, declare capabilities and protocols, test, and be used by features. Feature code no longer knows about specific providers, nor guesses protocols.

## 2. Scope

### Do

- Establish unified data structures for voice services, voice models and model selection.
- Establish the registration and creation mechanism for the three adapter types, STT, TTS and Realtime.
- Migrate all existing voice models, protocols, credential references, selections and test state.
- Provide a unified voice model management page; the existing voice input, read-aloud and realtime settings pages read from the same catalog.
- Remove the silent auto-probing of protocols and the global "read-aloud only" runtime rule.
- Also register Android system recognition and system TTS as built-in voice models; system TTS uses the standard `TextToSpeech` API with an added non-streaming adapter.
- Keep the actual request and response behavior of the existing protocols, and add config, migration, adapter and settings-page tests.

### Don't

- Do not rewrite the recorder, the player, audio focus, Bluetooth routing or the background service.
- Do not redo the realtime call, carrier calls, task execution or approval logic.
- Do not add new cloud providers.
- Do not do local model downloads, wake words or continuous-conversation features.
- Do not fix existing media bugs in ordinary read-aloud and realtime calls as part of this; handle those in a separate task.
- Do not refactor the chat model main loop, and do not merge chat and image models into the voice catalog.

## 3. Option selection

### A. Keep extending the existing config

Keep the three write paths `SpeechChoice`, `VoiceChoice` and `CallVoiceProfile`, and just add a unified entry on the UI.

Pros: fewer changes. Cons: underneath there are still three sets of config and three sets of rules, a new model still has to be handled separately, and the global "read-aloud only" coupling remains. Not adopted.

### B. A separate voice catalog that reuses the existing account and protocol implementations (adopted)

Create a new voice catalog that uniformly stores voice services, model capabilities, protocols and selections. Existing chat providers can be referenced as account sources; services owned by voice are still stored by the voice catalog. The existing STT/TTS/Realtime protocol implementations are progressively attached to the unified adapter registry.

Pros: voice truly has only one way of integrating models, while the chat account system and the existing protocols do not need rewriting. Cons: it needs one complete migration of old config, and migration compatibility code is kept temporarily.

### C. Cram all voice protocols into the chat `Provider`

Keep expanding the chat provider's protocol types and model purposes.

Pros: on the surface, one fewer storage class. Cons: voice-specific protocols would in turn affect chat testing, model groups and purpose selection, which is exactly where the current coupling comes from. Not adopted.

## 4. Core model

### 4.1 Separate service, model and selection

The three concepts are no longer mixed into a single `Choice`:

```text
VoiceService: who the service is, and where the account and credentials come from
VoiceModel: which model in this service, what it can do, and which protocol each capability uses
VoiceSelection: who voice input, read-aloud and realtime calls currently pick, plus usage preferences such as voice/language
```

The suggested data shapes are as follows; field names may be adjusted during implementation to match the project style:

```kotlin
enum class VoiceCapability { STT, TTS, REALTIME }

data class VoiceModelRef(
    val serviceId: String,
    val modelId: String,
    val capability: VoiceCapability,
)

data class VoiceService(
    val id: String,
    val name: String,
    val account: VoiceAccount,
)

sealed interface VoiceAccount {
    data class ExistingProvider(val providerId: String) : VoiceAccount
    data class VoiceOwned(val secretId: String) : VoiceAccount
    data object None : VoiceAccount
}

data class VoiceModel(
    val serviceId: String,
    val id: String,
    val displayName: String,
    val stt: SttBinding? = null,
    val tts: TtsBinding? = null,
    val realtime: RealtimeBinding? = null,
)
```

Capabilities are derived directly from non-null bindings, with no separately stored boolean that could conflict with the protocol. The same model can have STT, TTS and Realtime bindings at the same time, or only one of them. `VoiceModelRef` carries the capability, so the same model still has an explicit config when used for different capabilities.

### 4.2 Protocols are explicit config

Each binding contains an explicit protocol, no longer guessed from the model name or probe results:

```text
SttBinding
├── AndroidSystem
├── AudioTranscriptions
├── ChatInputAudio (explicitly records the data form and whether a transcript instruction is attached)
├── GeminiInline
├── DoubaoStreaming
├── FunAsrStreaming
└── BailianStreaming

TtsBinding
├── AndroidSystem
├── OpenAiSpeech
└── GeminiTts

RealtimeBinding
├── VolcDuplex
└── OpenAiRealtime
```

Non-secret parameters the protocol needs go into the corresponding binding, such as address, region, resource ID and workspace. Secrets are only supplied by the account source and do not enter `VoiceModel`.

The five existing `TranscribeMethod` values are each mapped to explicit config during migration, without re-probing. If a custom chat audio protocol has different forms, choose explicitly in advanced settings; do not rotate automatically.

### 4.3 Usage preferences are not part of the model definition

Voice, speaking rate, recognition language, the auto read-aloud toggle and so on remain usage settings:

- `SpeechSettings` keeps the system health information and STT usage preferences, and the current model becomes a `VoiceModelRef` selection.
- `VoiceSettings` keeps `enabled`, `autoRead`, `speakTool`, voice, speaking rate, streaming preference and the character cap, and the current model becomes a `VoiceModelRef`.
- Realtime calls keep their own usage policy and current Realtime selection.

These settings no longer store a copy of the protocol; the protocol always comes from the model in the catalog.

## 5. Unified catalog and accounts

### 5.1 `VoiceCatalogStore`

Add the single voice catalog, responsible for:

- CRUD for services, models and account sources.
- Connection tests and capability verification status for each model.
- Reading and saving the current STT, TTS and Realtime selections, or providing a unified read/write interface to the existing usage settings.
- Invalidating old verification status after config changes.
- Atomic writes of non-secret config; secrets are still stored encrypted through the existing `KeyCipher`.
- Providing the same state stream to the settings pages and the adapter factory, so different pages do not each read their own copy.

When the catalog is corrupted, do not clear the old config or silently fall back to defaults. Keep using the old path until migration completes; after migration, corruption should show 「语音配置读取失败」 (Could not read voice config) and keep a repair entry.

### 5.2 Reuse chat provider accounts, but not chat usage rules

`ExistingProvider(providerId)` means using the address, key, request headers and model catalog of an existing chat provider:

- The voice catalog does not copy this key.
- When adding a voice model, you can directly select an existing provider, then select the STT/TTS/Realtime capability and protocol.
- Provider saving is separate from chat model testing. When only voice is configured, no chat tool-call test is sent.
- After a chat provider is modified, voice models referencing it use the new account information, and verification status is invalidated by config fingerprint.
- Before deleting a provider, list the voice models that reference it; after the user confirms, delete those voice models too, and the selections in use return to a safe unconfigured state with a clear notice. Do not leave dangling references that cannot be explained.

`VoiceOwned` is for voice-owned accounts, such as the existing standalone streaming ASR and Realtime configs. They are still managed uniformly by the voice catalog, without opening a separate settings entry.

`None` is for Android system models and self-hosted services that need no credentials.

## 6. Unified adapter

### 6.1 One registry, three session interfaces

Add `VoiceAdapterRegistry`, which finds an adapter by "capability + protocol". Callers submit only the model reference and this use's parameters:

```text
STT adapter     → create the existing SpeechSession
TTS adapter     → create the existing TtsModel / StreamingTtsModel
Realtime adapter → create the existing CallRealtimeSession
```

The three interfaces stay different; do not build one universal interface containing every method. What is unified: config, registration, creation, testing and error classification.

The existing `VoiceInput`, `ReadAloud` and Realtime sessions no longer choose an implementation by provider or model name; instead:

```text
Current VoiceModelRef
→ VoiceCatalogStore reads the model and account
→ VoiceAdapterRegistry finds the adapter
→ returns the original session/model object
→ runs the existing feature logic
```

When the system has no RecognitionService or TTS engine, the model stays in the catalog but its state is unavailable, with setup guidance; it must not silently disappear, nor secretly switch to a paid model.

### 6.2 Migrate the existing implementations

- STT: keep `SystemSpeech`, `ProviderSpeech`, `DoubaoSpeech`, `LiveSpeech` and the existing protocol parsing. `Transcriber`'s request construction is reused by explicit protocol and no longer calls `detect`.
- TTS: keep the existing OpenAI Speech, Gemini TTS and streaming capability. Add the system TTS adapter.
- Realtime: keep the existing two realtime protocols, events and session implementation, and attach the connection config to the unified adapter factory.

For the first version, system TTS uses `TextToSpeech.synthesizeToFile` + `UtteranceProgressListener` to generate a complete WAV, then hands it to the existing `VoicePlayer`. This avoids a second playback path and does not change the existing read-aloud logic. Initialization, missing language data, missing engine, synthesis failure, cancellation and `shutdown` are all handled inside the adapter; streaming capability is declared false.

## 7. Unified testing and errors

### 7.1 Distinguish saving, connection testing and capability verification

- **Saving**: only local validation; it can be saved as "unverified" and does not force a network call.
- **Connection test**: only proves the address, auth and protocol handshake work; the UI must not claim "voice is working now".
- **Capability verification**: do real verification per model type.

Capability verification methods:

- STT: the user holds to say one sentence and the real transcript is shown. Do not use one second of silence to determine the protocol automatically.
- TTS: synthesize one test sentence and play it through the real playback path; synthesis failure and playback failure are reported separately.
- Realtime: a successful connection only records connection state; audio verification continues to use the existing real-device verification record and cannot be replaced by the handshake.

After config, account or protocol changes, return to unverified. Keep the existing Realtime audio verification records and migrate them under the new model references.

### 7.2 Unified error classification

The adapter boundary maps existing errors into generic voice categories:

```text
Incomplete config / missing credentials / auth failure / model or capability unsupported /
network failure / timeout / rate limiting / protocol response error /
system capability unavailable / user cancelled
```

Each protocol keeps the raw server error for diagnostics, but feature pages only handle the unified categories. Cancellation is not a failure. Recognition service not responding, microphone in use, and auth failure must not all be lumped into 「麦克风不可用」 (Microphone unavailable).

## 8. Settings page

Add a 「语音模型」 (Voice models) catalog page, grouped by service, with each model row showing its `STT`, `TTS` and `Realtime` capabilities.

When adding or editing, show the corresponding fields by capability:

1. Select an existing provider account, or create a new voice-owned service.
2. Fill in the model and declare one or more capabilities.
3. Explicitly choose a protocol for each capability; preset services come with default protocols filled in, and custom services can change them in advanced settings.
4. Save as unverified, or run the corresponding test.

The selection pages for voice input, read-aloud and realtime calls still exist, but are only responsible for "which available model to pick + usage preferences", and no longer each create their own service config.

When the model-list API has explicit modality information it may be used for display; when it does not, manual entry is allowed. Do not decide capabilities by model name, and do not call multiple protocols in turn just to determine capabilities.

## 9. Old config migration

Use a one-time, retryable, atomically committed migration:

1. Read the existing `SpeechChoice`, `VoiceChoice`, `CallVoiceProfile` and their account information.
2. Create new services and models. Shared chat accounts use `ExistingProvider`; voice-owned configs use `VoiceOwned` and have their credentials migrated securely.
3. Keep the original IDs stably, or generate deterministic migration IDs, so retries do not produce duplicate services.
4. Map the original STT protocol value by value; TTS keeps the protocol, voice, speaking rate and streaming preference; Realtime keeps all config, the current selection, connection test and audio verification records.
5. Only after the new catalog, selections and migration marker are all written successfully does the runtime switch to the new path.
6. If any step fails, keep using the old path and retry on next start; never let half the features use the new config while the other half quietly fall back to defaults.
7. After migration, keep the old data as a read-only rollback copy and stop writing to it. Once the new version is verified, delete the compatibility code and old data in a separate cleanup task.

After migration completes:

- `Provider.ttsModels` is only a migration input for reading old data and no longer participates in chat, image reading, fallback, model groups or voice runtime decisions.
- Whether a chat model is usable is decided by chat capability information and the actual chat test; whether a voice model is usable is decided by the voice catalog's declarations and verification.
- `Transcriber.detect` and the silence-probing path are no longer called by settings pages or the runtime.

## 10. Acceptance

### 10.1 Catalog and migration

- The same service can register STT, TTS and Realtime models, and a single model can declare multiple capabilities.
- After migrating each existing STT, TTS and Realtime config, the protocol request parameters and the current selection match the pre-migration state.
- Multiple Realtime configs, independent credentials, region config and audio verification records are all preserved.
- An interrupted migration can be retried, without duplicates, lost selections or leaked plaintext keys.
- Account modification, deletion, catalog corruption and missing references all have clear behavior.

### 10.2 Integration and use

- Add a TTS-only service without sending any chat test request during the whole flow; after saving it can be used for read-aloud.
- When adding an STT model, only the protocol the user explicitly selected is used; no silence probing or multi-protocol rotation happens.
- All three usage entries create implementations through the same catalog and adapter factory, with no branching by model name.
- The existing protocols' golden request/response tests keep passing.
- When a capability is unsupported, block it before selection or mark it clearly in the catalog, instead of waiting until runtime to report an unclear error.

### 10.3 System models and real devices

- When a usable system TTS exists, read-aloud works without a key; missing engine, missing language data and synthesis failure all have setup guidance.
- System recognition and system TTS are both detected through public capabilities, with no vendor or build-number branching.
- Verify on a real Android device with the official performance build: the integration and selection migration of system STT, system TTS, one batch STT, one streaming STT, one streaming TTS and the existing Realtime.
- This time only verify integration and basic functionality; do not use it to replace full acceptance of audio routing, interruption and long calls.

## 11. Suggested implementation split

The detailed task plan is written separately after design approval, in this fixed order:

1. Voice catalog, model references, adapter registry and tests.
2. Wire in the existing STT/TTS/Realtime adapters; the runtime reads only the new catalog.
3. Atomic old-config migration and old-path fallback.
4. Unified settings page and separation of account saving/testing.
5. Built-in system TTS adapter.
6. Remove silence probing and the global "read-aloud only" runtime rule, then clean up the old path after the regression passes.

Every step stays revertible; do not squash all changes into one unverifiable giant commit. The implementer may adjust class names and file locations, but must not change the following settled directions: a unified voice catalog, three adapter types, explicit protocols, reusing accounts without reusing chat usage rules, lossless old-config migration, and not rewriting the media and realtime call logic.
