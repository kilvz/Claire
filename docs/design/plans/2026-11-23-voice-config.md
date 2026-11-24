# Voice configuration redesign implementation plan

Prototype: `docs/design/prototypes/2026-11-23-voice-config.html` (accepted by the user on 11-23).

Goal: on the models page, one "Voice" group of three rows (voice input / read answers aloud / voice calls) + "Voice services"; voice features a chat account can provide appear automatically; onboarding goes through presets; protocols appear only under "Advanced → Custom"; the phone page only handles its own business, and voice follows "Voice calls" by default.

Only the migrated path is changed (`legacyMigrationComplete`). If migration fails, the old pages are kept exactly as they are.

## Current state highlights

- In-app voice calls and AI phone calls both read `selections.realtime`: "follow" is already the current state; what is missing is "specify separately for phone calls".
- The voice is stored in two places: `RealtimeBinding.speaker` and `CallConfig.speaker`, synced both ways inside `CallRuntime`.
- Chat accounts do not enter the catalog automatically; you have to register them by hand on the catalog page.
- Entry points are scattered: the read-aloud card in `RolesSection`, `SpeechSection`, `VoiceCatalogSection`, and "Voice services"/"Call configuration" on the phone page.

## A Data layer (pure Kotlin, tests first)

1. **`voice/model/ProviderVoiceOffers.kt`**: derive the voice models a chat provider can offer (official endpoints only).
   - OpenAI (`api.openai.com`): transcription, synthesis, realtime voice.
   - Gemini: inline recognition, TTS.
   - No match, no offer; the user can go through "Custom".
   - `adopt(offer)`: upsert the `provider:<id>` service and the corresponding models on demand, then return the ref. The service id stays consistent with the one used by migration.
2. **`voice/model/VoiceVoices.kt`**: the selectable voice list for each protocol (TTS + realtime). Stored custom values are preserved and shown as "Custom: xxx".
3. **Separate specification for phone calls**: `VoiceSelections` gains `callRealtime: VoiceModelRef? = null`, where null means follow. `RealtimeSelectionController` gains `callCurrent()` and `selectForCalls()`. `CallRuntime` reads `callCurrent()` instead; in-app voice calls still read `current()`.

## B Voice group + three usage pages

1. `ModelSetupScreen`: after migration, replace the read-aloud RoleCard, `SpeechSection` and `VoiceCatalogSection` with a single `VoiceHubSection` containing three rows + voice services. Each row states the current selection and state (available / go to settings).
2. Voice input page: options = the phone's built-in + STT in the catalog + offers derived from chat accounts. Each item carries labels (live text while speaking / transcribe after speaking, free / billed), with "Try a sentence" beside it. The group below holds the presets for "more can be connected".
3. Read-aloud page: TTS options as above, with "Preview" beside them. The voice becomes a dropdown (from A2), and speed, streaming read-aloud and automatic read-aloud move under "When to read".
4. New voice call page: realtime voice options + voice dropdown + "This selection is also used by: AI phone calls".

## C Voice services + preset onboarding

1. The voice services page has two groups:
   - "From chat accounts": read-only, listing capabilities per offer.
   - "Voice-only": the phone's built-in + VoiceOwned / None services in the catalog.
2. Add-page presets: Volcano Engine (recognition and realtime, two fields), Alibaba Cloud Bailian (1 Key + region), OpenAI-compatible, self-hosted recognition service. The current catalog form moves under "Advanced → Custom".
3. Test each item after saving; every item on the test result page has a "Used for…" button.

## D Phone page

1. Replace the "Voice and models" group with a single "Who speaks" row that opens the phone voice page: follow / specify separately + voice.
2. The "Call configuration" dialog keeps only the opening line, the default note and the duration; the voice id and the audio channel move out of it.
3. "This phone" group: the phone call audio page (channel + two-way live test); when auto-answer is blocked, tapping it goes straight to the live test.
4. Remove the key dialog from the phone page (credentials belong to voice services). The per-call "Voice for this call" stays.

## E Wrap-up

- New strings filled in across the 12 values folders.
- Unit tests: `./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*' ... ui.*`.
- Compile `compileDebugAndroidTestKotlin`, install on a real device and walk through it with the perf build.

Each stage is committed separately.
