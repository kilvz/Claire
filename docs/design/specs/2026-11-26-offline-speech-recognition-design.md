# On-Device Offline Speech Recognition Design

> Date: 2026-11-26 · Status: Implemented (2026-11-27; approved 2026-11-26) · Scope: offline recognition for voice input (push-to-talk, capsule). Excludes read-aloud, real-time calls, file transcription

## 1. Why

- **Many Android phones cannot use voice input.** Claire's current voice input has only two sources:
  - The phone's built-in recognition service (`SystemSpeech`). Many models do not have this service, or have it but it does not respond.
  - Cloud models (`ProviderSpeech` and others). The user has to configure a provider themselves.

  When the phone has no usable recognition service and the user has not configured a speech model, `SpeechResolver` can only return `Why.NoSystemNoModel` or `Why.SystemNoResponse`, and voice input is entirely unavailable.
- **The fallback chain lacks an ending that cannot fail.** #880 added the voice input fallback chain, but every entry on the chain is a cloud model, so they all hit network outages, rate limits and unpaid balances together. An on-device model works offline and is not rate-limited, so it suits the end of the chain.
- **Privacy.** Audio never leaves the phone.

**What it looks like when done**: on any arm64 Android phone, downloading a roughly 100–240 MB speech pack enables offline push-to-talk. The pack can be selected on its own or used only as a fallback.

## 2. Goals, non-goals, anti-goals

**Goals**
1. Add a new voice input source, "on-device offline model": hold to talk, release to recognize the whole utterance.
2. Speech packs are downloaded on demand and not bundled into the APK. Download sources are reliable, reachable from mainland China, and support offline import.
3. Every file is verified against a SHA-256 written into the APK; no matter where it comes from, only files that pass verification are accepted.
4. Fit into the existing voice catalog:
   - It can be selected as a voice input model.
   - It can go into the `sttFallbacks` fallback chain.
   - "Auto" mode uses it when the phone's built-in recognition is unavailable.
5. Cover Claire's 13 UI languages, with a recommended speech pack for each language.

**Non-goals (not in the first version)**
- Streaming offline recognition that emits text while speaking.
- Offline read-aloud (TTS). System TTS is almost always available, so priority is low.
- Dictation for real-time calls and AI phone calls (`callStt`).
- Transcribing existing audio files (`batchModel`). Long audio needs segmentation first and will be done separately.
- 32-bit devices (armeabi-v7a) and x86 devices.
- GPU and NPU acceleration.

**Anti-goals (any of these counts as failure, even if the feature runs)**
- Any model weights in the APK, or the APK size ballooning because of them (see §7 budget).
- Downloading and loading executable code from the network (`.so`, dex). The recognition library must ship with the APK.
- A file that failed verification being loaded.
- Downloading hundreds of MB over mobile data without the user knowing.
- "Auto" quietly switching to the offline model when the phone's built-in recognition works, making the experience worse.

## 3. Constraints

- **Generic Android**: use only public APIs, target no brand (CLAUDE.md).
- **Licensing**:
  - Each speech pack must carry its own license file and provenance.
  - Register it in `licenses/THIRD_PARTY_NOTICES.md` and on the open-source licenses page in the app.
  - The recognition library sherpa-onnx is Apache-2.0.
- **Release channel**:
  - Speech packs live in a dedicated Release of the `wangzan101/Mika` repository.
  - This Release **must not become the "latest release"**: in-app updates read `/releases/latest` (`MikaLinks.LATEST_RELEASE_API`, `UpdateProbe`). Mark it as a pre-release, or set `make_latest=false` when publishing.
- **Storage**:
  - Store it in the app-internal directory, not in backups. Right now `BackupEngine.excludedDirs` contains only `sandbox` and `library-attachments`; the speech pack directory must be added.
  - Check free space before downloading.

## 4. Approach (selected)

| Option | Approach | Trade-off |
|---|---|---|
| A. Connect to upstream directly | Download files individually from HuggingFace, or download sherpa-onnx's whole bundle on GitHub | Direct HuggingFace access does not work from mainland China; the bundle contains both fp32 and int8 sets plus test audio (the SenseVoice bundle is 1 GB while only about 230 MB is needed); it breaks when upstream renames things; licenses are scattered |
| B. Claire self-hosts | Put the trimmed files in a Release of its own repository, with a manifest inside the APK | Reuses the existing GitHub mirror chain and verification; requires maintaining the manifest and confirming each model may be redistributed |
| **C. B as the main path, plus backup sources and manual import (selected)** | Each file in the same manifest lists several sources: Claire Release (through the mirror chain) → official HuggingFace (pinned to a commit) → manual import | Trust is anchored only in the SHA-256 inside the APK, so multiple sources are possible; there is a way out when the main source is down or the device is fully offline |

The maintainer selected C on 2026-11-26 and confirmed that models may be placed in a Release of the project's own repository.

## 5. Design

### 5.1 Speech packs and manifest

**A speech pack** is the set of files for one model. The first version prepares four, of which `whisper-small-int8` is optional:

| Pack id | Model | Files (int8 only) | Size (including license file) | Recommended for which UI languages | License |
|---|---|---|---|---|---|
| `sensevoice-small-int8` | SenseVoice Small (FunAudioLLM / Alibaba) | `model.int8.onnx`, `tokens.txt` | 240 MB | zh-Hans, zh-Hant, en, ja, ko (also recognizes Cantonese) | FunASR Model License 1.1: may be used, copied, modified and shared, provided provenance and author are stated and the model name is kept |
| `dolphin-base-ctc-int8` | Dolphin base CTC (DataoceanAI) | `model.int8.onnx`, `tokens.txt` | 104 MB | vi (limited quality), fa (not tested, limited quality) | Apache-2.0 |
| `whisper-base-int8` | Whisper base (OpenAI) | `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt` | 161 MB | id; es, pt, ru, tr, ar (limited quality); and the fallback for all languages | MIT (OpenAI whisper) |
| `whisper-small-int8` | Whisper small (OpenAI) | `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt` | 375 MB | None yet. Optional pack: more accurate but slower and uses more memory (see §8.1) | MIT (OpenAI whisper) |

The table above has been revised according to the measurements in §8 (2026-11-26). "Limited quality" means the measured accuracy did not meet the §7 requirement, but it is the best of the first three packs. Exact repositories, commits, file sizes and hashes are in §8.

**The manifest** is `app/src/main/assets/voice-models/catalog.json`, shipped with the APK, and is the trust anchor. Actual format (generated by `tools/voice-models/publish.py` from `packs.json`; parsing is in `voice/ondevice/VoicePackCatalog.kt`):

```json
{
  "schema": 1,                       // manifest schema version; a mismatch is a packaging error
  "release": "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/",  // Release download prefix shared by all packs
  "packs": [
    {
      "id": "sensevoice-small-int8",
      "version": 1,
      "backend": "sherpa-onnx",         // which inference backend runs it (§5.4.1)
      "family": "sense_voice",          // model type within the backend; in sherpa-onnx this maps to the offline recognition model config
      "displayName": "SenseVoice Small",
      "languages": ["zh", "en", "ja", "ko", "yue"],      // languages the model can recognize (ISO 639 language part)
      "recommendedFor": ["zh-Hans", "zh-Hant", "en", "ja", "ko"],  // app UI language codes, consistent with AppLanguage (Indonesian is id, not in)
      "files": [
        { "name": "model.int8.onnx", "role": "model", "size": 239233841, "sha256": "c71f0ce0…cd51" },
        { "name": "tokens.txt", "role": "tokens", "size": 315894, "sha256": "f449eb28…a1dc" },
        { "name": "LICENSE", "role": "license", "size": 5306, "sha256": "7dba975a…84f8",
          "url": "https://raw.githubusercontent.com/modelscope/FunASR/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE" }
      ],
      "license": { "name": "FunASR Model License 1.1", "file": "LICENSE", "attribution": "SenseVoice Small by FunAudioLLM (Tongyi Lab, Alibaba Group), …" },
      "upstream": "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/"
    }
  ]
}
```

- The Release address is the top-level `release` plus `<pack-id>--<file-name>`. The upstream address is the pack's `upstream` plus the file name; when the file is not there (license files), write a separate `url` on that file, also pinned to a commit.
- The license file is itself listed as a file in `files`, verified along with the rest and placed in the install directory along with them.
- The manifest is updated only when a new app version ships. The app does not read the manifest from the network, so no one else can change what it downloads.
- To change a model or correct a hash, ship a new app version.

**Publishing script** `tools/voice-models/publish.py` (a development script, under `tools/`):
1. Fetch the required files from HuggingFace at the pinned commits and check them against the upstream SHA-256.
2. Attach the license files.
3. Upload to the `voice-models-v1` Release, marked as a pre-release, or with `make_latest=false`.
4. Generate the sizes and hashes in `catalog.json`.

Release file names carry the pack id prefix (for example `sensevoice-small-int8--model.int8.onnx`), because GitHub Release assets do not support directories.

### 5.2 Download

Reuse the download machinery in `about/`: the approach of `ProxiedApkDownload`, the user mirror list of `UpdateMirrors`, and the app proxy of `NetProxy`. Extract a **generic verified downloader** shared by app updates and speech packs, rather than writing a second one for speech.

The current `ProxiedApkDownload` is "one file, only one download at a time per process; `start` cancels the previous one", so this step is a **rework**, not just a move. After the rework it must satisfy:
- One job can download multiple files.
- App updates and speech packs are independent jobs that do not cancel each other.
- Only one job at a time per speech pack.

- **Source order**:
  1. The user's GitHub mirrors (`UpdateMirrors`, in the user's order) plus the Release address.
  2. The official Release address.
  3. The HuggingFace address at the pinned commit.

  When a source fails or verification does not pass, move to the next one.

- **URL verification**: `ApkIntegrity.requireTrustedDownloadUrl` currently accepts only URLs ending in `.apk`. It needs generalizing, or a second, equally strict version that accepts only manifest addresses and "mirror prefix + manifest address".
- **Per-file download**: write `<file>.part` first and rename only when both size and SHA-256 match. Each file switches sources independently; the whole pack need not come from one source.
- **Whole-pack atomic activation**:
  1. Download into `voice-models/.staging/<pack-id>-<version>/`.
  2. Once all files pass, rename to `voice-models/<pack-id>/<version>/`.
  3. Then delete the old version.

  There is no separate "installed" record file: if the version directory exists and every manifest file is present with a matching size, it counts as installed (see §5.2 "verification timing").

  On failure midway, staging is cleared and the installed old version is unaffected.
- **Network**: a speech pack starts only when the user taps "Download"; it never downloads automatically in the background, so it does **not bind to Wi-Fi**. The approach matches a manual app update download: go through `NetProxy` and do not bind a network.
  - Before starting, check with `ConnectivityManager.isActiveNetworkMetered()`: on a metered network (mobile data, hotspot, etc.) first show a dialog stating the size and download only after the user confirms.
  - Because Wi-Fi is not bound, users on a SOCKS app proxy can also download (the Wi-Fi-bound path does not accept SOCKS; see `wifiBoundClient` in `ProxiedApkDownload.kt`).
- **Space**: check free space before downloading; there must be at least the pack size plus some margin (the exact value is up to the implementer). If not enough, show a message and do not start.
- **Progress and interruption**: the settings page shows progress and allows cancelling. If the process is killed, the download counts as failed and the user can retry. Whether to support `Range` resumable downloads is up to the implementer; the first version may skip it.
- **Verification timing**: verify once after download. After that, each load checks only the size and does not recompute the hash every time, because 230 MB is too slow. If loading fails, show "The speech pack is damaged. Please download it again."

### 5.3 Manual import

For users who are fully offline, or for whom every source is unreachable.

- The speech pack card on the settings page has "Import from file".
- Use the system file picker to choose a zip containing the pack's files, with or without a pack-id subdirectory. Selecting multiple files separately is also allowed; the interaction is up to the implementer.
- On import, check each file name, size and SHA-256 against the manifest. Install only when everything matches, using the same staging and rename flow as §5.2.
- Ignore files not in the manifest; reject when a file is missing or a hash is wrong, and state which file.
- The card offers "Copy download address" so the user can download on a computer and copy it to the phone.

### 5.4 Recognition engine

- **Dependency**: the sherpa-onnx Android AAR, at a pinned version (v2.0.3 was the latest when this design was written).
  - Choose the "statically linked onnxruntime" build and keep only `arm64-v8a`.
  - The AAR is not on Maven: put it in the repository (for example `app/libs/`) and record its SHA-256, or build from source. The implementer decides, but the version must be pinned and reproducible.
  - Add it to `THIRD_PARTY_NOTICES.md`.
- **Non-arm64 devices**: do not offer this source and state in the UI "This device does not support offline recognition." Follow the sandbox's handling of arm64 (the comment in `app/build.gradle.kts`).
- **Usage**: sherpa-onnx's offline recognizer (`OfflineRecognizer`); configure the `sense_voice`, `whisper` or `dolphin` model config from the manifest's `family` field, and map files to positions in the config by `role`, not by file name. Input is 16 kHz mono PCM; `PcmRecorder` and `Wav` already use this format, which must be confirmed during implementation.
- **Language parameter**:
  - SenseVoice and Whisper support a specified language as well as automatic.
  - What is passed at runtime is a recognition language tag (for example `zh-CN`, from `SystemSpeech.speechTag(...)` in `VoiceInput`), not a UI language code.
  - Take the language part of the tag (`zh`, `en`, …) and compare it with the manifest's `languages`: if present, specify that language; if not, use automatic detection.
- **Timeout**: the existing whole-utterance recognition waits at most 30 seconds each time (`ProviderSpeech.TRANSCRIBE_TIMEOUT_MS`) and records a timeout as `ModelError.Network`. On-device recognition must not reuse this, or slow machines would show a false "cannot connect" and offline devices would be switched to a cloud model.
  - On-device recognition uses its own limit: 3× the audio duration, at least 30 seconds.
  - The first model load time does not count against the limit. Loading starts in the background when the user presses to talk, in parallel with recording.
  - Record a timeout as an on-device error (see §5.5 "Failure classification"), not as a network error.
- **Loading and memory**:
  - Load the model on a background thread on first use, then keep it resident.
  - Release it after being idle for a while or on the system `onTrimMemory`. How long counts as idle is up to the implementer.
  - Convert load failures (damaged files, out of memory) into `VoiceModelError`.
- **Threads**: recognition runs on `Dispatchers.Default` with a thread count of "the smaller of the CPU core count and 4". The implementer may tune it further based on the measurements in §8.

### 5.4.1 Extensible: can integrate any speech-to-text model

The first version ships only three packs, but the structure is not tied to those three. Adding a new model falls into three cases, with more work the further down you go:

| New model | What to change |
|---|---|
| A type sherpa-onnx already supports (besides the three packs there are Paraformer, Zipformer, Moonshine, FireRedASR, NeMo and others) | Add one entry to `catalog.json` and ship an app version; no code |
| A type sherpa-onnx supports but that is not wired up here yet | Add a config mapping for one `family` in `SherpaOnnxBackend` (one branch) |
| Another inference library (such as whisper.cpp, Vosk) | Write a new `OnDeviceSttBackend` implementation and register it in the registry; the library ships with the APK (anti-goal: no downloading executable code) |

**Interfaces** (all in the `voice/ondevice/` package of `app`; the pure Kotlin parts are testable on the JVM):

```kotlin
/** An inference backend, such as sherpa-onnx. Found by the manifest's backend field. */
interface OnDeviceSttBackend {
    val id: String                                   // "sherpa-onnx"
    fun supports(family: String): Boolean            // whether this backend recognizes this kind of model
    fun abiSupported(): Boolean                      // whether this device can run it (arm64 check)
    /** Load an installed, verified pack; throws OnDeviceSttException on failure. May be slow; call on a background thread. */
    fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine
}

/** A loaded model, resident in memory until closed. */
interface OnDeviceSttEngine : AutoCloseable {
    /** 16 kHz mono PCM in, text out. language is the ISO 639 language part; null means automatic detection. */
    suspend fun transcribe(pcm: ShortArray, language: String?): String
}

data class EngineOptions(val threads: Int)

/** A pack from the manifest plus its install location; files are looked up by role. */
data class InstalledVoicePack(val entry: VoicePackEntry, val dir: File) {
    fun file(role: String): File
}
```

- `OnDeviceBackends` is the backend registry, done the same way as `VoiceAdapterRegistry`: pass a list at construction and substitute fakes in tests.
- `OnDeviceSttAdapter` (§5.5) knows only these two interfaces and is unaware that sherpa-onnx exists.
- When parsing the manifest, hide packs whose `backend` is not in the registry, or for which `supports(family)` is false (an old app reading a new manifest will not crash).
- **Not in the first version**: users importing models that are not in the manifest (custom models). That would require users to vouch for file trust themselves; the trust anchor would no longer be only the hash in the APK, and this needs a separate design. The interfaces do not block this path: a custom pack is still a `VoicePackEntry`, just from a different source.

### 5.5 Integrating with the voice catalog

- **New protocol**: add `OnDevice` to `SttProtocol` (serialized name fixed).
- **Service and model stored in the catalog snapshot**:
  - When installing a speech pack, write into the catalog snapshot (`VoiceCatalogSnapshot`):
    - The fixed service `VoiceService("on-device", …, VoiceAccount.None)`, written on first install.
    - The `VoiceModel` for this pack: `stt = SttBinding(SttProtocol.OnDevice, endpoint = <pack-id>)`, `origin = Builtin`.
  - Stored in the snapshot like any other model, so `requireModel`, `backupsFor` and `VoiceCatalogStore`'s tidying logic (`fallbackUsable`, `pruneSelections`) need no special handling.
  - It must not be generated on the fly each time like `android-system`: doing so would remove it from the fallback chain on every tidy-up.
- **When files are absent** (restored from backup: the snapshot is backed up but `voice-models/` is not; or files were deleted or damaged):
  - **Keep** the model and selection in the snapshot; **do not silently delete them**.
  - When used, report "speech pack not installed": `VoiceModelError.IncompleteConfig`, with a reason the UI can distinguish.
  - The UI marks "Needs download" in the selection list and on the speech pack card; after the user downloads, it works without reselecting.
- **When the user deletes a speech pack on the card**: delete the files and remove the corresponding model from the snapshot.
  - If it was selected: go back to "Auto", **not** "Off", which is what deleting a model does today (`VoiceCatalogStore.pruneSelections` currently sets `SttSelection.Off`).
  - Rationale: the existing rule sets Off so the app does not quietly switch to a provider that costs money; "Auto" uses only the phone's built-in recognition or another on-device speech pack, which costs nothing and stays on the phone.
  - So this needs a special case, pinned by a test.
  - If it is in the fallback chain, remove it there per the existing rule.
- **Adapter**: add an `OnDeviceSttAdapter` to `SttAdapters`.
  - `utterance()` returns a "WAV in, text out" transcription, so it automatically works with `transcribeChain` and `VoiceFallbacks`.
  - `start()` uses `ProviderSpeech` (`PcmRecorder` records, transcription after release), consistent with existing whole-utterance recognition.
  - When the recognized text is empty, handle it as the existing "did not catch that" case.
- **Fallback chain**: add `OnDevice` to `UTTERANCE_STT_PROTOCOLS` so it can be both the head and a fallback. It does not go through the key pool because it is `VoiceAccount.None`.
- **Failure classification**: every on-device failure moves to the next model (`VoiceFailover.canTryNext` is true). Rationale: when an on-device model heads the chain, users who configured cloud fallbacks want someone to take over when the on-device model cannot cope.

  | On-device failure | Mapped to | Move to next? |
  |---|---|---|
  | Speech pack not installed or files missing | `VoiceModelError.PackMissing` (category `INCOMPLETE_CONFIG`) | Yes (existing rule: `INCOMPLETE_CONFIG` is true) |
  | Damaged files, model load failure, out of memory | `VoiceModelError.OnDeviceFailed` (category `SYSTEM_UNAVAILABLE`) | Yes (existing rule is true) |
  | Recognition timeout (§5.4) | Same as above, **not** `ModelError.Network` | Yes |
  | Empty recognized text | Not a failure; handled as "did not catch that" | No |

  When it is at the end of the chain, a failure shows this error to the user. When it is not the head, `VoiceFailover.run` skips an incompletely configured entry before trying it, which is also existing behavior.
- **"Auto" does not use the fallback chain**: keep the status quo. `backupsFor` returns a fallback chain only when a specific model is selected. The fallback chain belongs to the "specific model selected" usage; "Auto" already chooses between the phone's built-in recognition and on-device speech packs by itself.
- **Calls do not use on-device speech packs** (first version):
  - Like `SYSTEM_STT_REF`, exclude `OnDevice` models in `CascadeEngine.listenRef`.
  - Speech packs do not appear in the dictation model selection list for in-app calls and AI phone calls.
  - Rationale: Whisper takes about 4 seconds to produce text per 5 seconds of speech, which is too much latency for a call. Users who have only offline speech packs get `StandardBlocker.NoListenModel` for calls, the same as users who today rely only on the phone's built-in recognition. Enable it separately later if measured speed is sufficient.
- **"Auto" order**: change the `SpeechChoice.Auto` branch of `SpeechResolver`, and the model that Auto maps to when `VoiceInput` opens a conversation.
  1. Phone built-in recognition works → use it (unchanged; it emits text while speaking).
  2. Otherwise, if an installed speech pack is recommended for the current recognition language → use it.
  3. Otherwise, if any installed speech pack exists → use it (use automatic detection when the model does not support the language).
  4. Otherwise, keep the status quo (`Why.SystemNoResponse` or `Why.NoSystemNoModel`) but add an entry to the message: "Download offline speech pack".

  This satisfies the anti-goal: fall back to the offline model only when the phone's built-in recognition does not work.

### 5.6 UI

- **Voice settings** (`voice/ui/`) adds an "Offline speech packs" section with one card per pack. The card shows:
  - Name, supported languages, size, license.
  - Status: not downloaded / downloading (progress, cancel) / installed (version, space used, delete) / damaged (download again).
  - "Import from file", "Copy download address".
  - The pack recommended for the current UI language comes first and is marked "Recommended".
- **In the voice input model selection list**, installed packs appear alongside other models, labeled "On-device · Offline".
- **In the `Why.NoSystemNoModel` / `Why.SystemNoResponse` messages**, add a direct entry point.
- **Do not push downloads on first launch.**
- **Copy**: translate into all 13 languages and pass `python3 tools/i18n/translations.py check`.
- **Accessibility**: screen readers must be able to read progress and status.

### 5.7 Storage location and cleanup

- Install directory: `filesDir/voice-models/<pack-id>/<version>/`; the staging directory is `filesDir/voice-models/.staging/`.
- Add `voice-models` to `BackupEngine.excludedDirs`, and note in the backup description "Speech packs are large, so they are not backed up; download them again after restore."
- On app start, clean up leftover staging directories and directories that are not the current version.
- When a pack's version rises in the manifest, the installed old version keeps working; the card shows "New version available" and the user decides whether to update. No automatic download.

## 6. Tests

- **Unit tests (JVM)**
  - Manifest parsing and the manifest's internal consistency:
    - Every pack carries a license file and hashes are 64-digit hexadecimal.
    - The `recommendedFor` values of all packs together cover all 13 `AppLanguage` UI language codes (`zh-Hans`, `id`, etc.).
  - Mapping from recognition language tags (`zh-CN`, `id-ID`, etc.) to model languages.
  - On-device recognition timeouts and failure classification (§5.5 table).
  - After restoring from backup with the referenced speech pack absent: the selection is kept and "Needs download" is shown.
  - Deleting the selected speech pack returns to "Auto"; calls exclude speech packs.
  - Downloader: sources switch in order; a failed verification counts as a source failure; staging and rename atomicity; cancellation; insufficient space.
  - Import: missing files, extra files, wrong hash, with or without subdirectory.
  - URL verification allows only manifest addresses and "mirror prefix + manifest address".
  - Catalog integration: uninstalling clears the selection and fallback chain; `eligible` includes `OnDevice`; the four-step Auto order.
  - Backup exclusion.
- **Instrumented tests (devices)**
  - Speech pack card state transitions and screen reader copy.
  - Run "WAV in, text out" once with a small test-fixture model or a real model.
  - In a fallback chain, the head cloud model fails deliberately and the offline model takes over.
- **Publishing script**: unit tests under `tools/voice-models` covering hash checking and file name assembly.

## 7. Budget and acceptance

- **APK size**: arm64 builds grow by ≤ 20 MB (the arm64 part of statically linked onnxruntime); measure during implementation and record it in the PR. The increase is the same when the universal build ships only the arm64 `.so`.
- **Recognition speed** (mid-range arm64 device, roughly Snapdragon 7 series or equivalent): 5 seconds of speech produces text in ≤ 1.5 seconds on SenseVoice and ≤ 4 seconds on Whisper base (first model load time excluded). If not met, record the measured data in the PR and then decide whether to change models or accept it.
- **Memory**: resident increase after loading ≤ 400 MB (SenseVoice int8). Low-memory devices may have it reclaimed by the system, covered by the load-failure message.
- **Accuracy**: record 10 everyday commands per UI language (for example "Open settings", "Send Mom a message saying I'll be late"), and the recommended pack largely recognizes them correctly. For languages that do not meet the bar, recommend another pack or label it "limited quality".

## 8. Verification needed before implementation

1. Dolphin's actual quality on vi, in, ar, fa, tr, ru, and Whisper base's on es, pt. Correct the recommendation table in §5.1 accordingly.
2. Obtain the exact HuggingFace repositories and pinned commits for the three packs, plus the exact file lists and sizes.
3. Confirm the actual size of the arm64 part in the statically linked sherpa-onnx v2.0.3 AAR, and whether its Kotlin API is exactly `OfflineRecognizer` plus `OfflineModelConfig`.
4. Confirm SenseVoice's output form for Traditional Chinese (Simplified or Traditional). If conversion is needed, skip it in the first version and explain in the PR.

### 8.1 Verification results (2026-11-26)

**How it was tested**
- No human recordings. Ran the sherpa-onnx 2.0.3 Python package on a Mac with 4 threads.
- Audio was synthesized with macOS `say` and converted to 16 kHz mono WAV. One system voice per language.
- 10 everyday commands per language with the same meanings (open settings, send Mom a message saying I'll be late, turn on Bluetooth, …).
- Error rate is by character (CER) for Chinese, Japanese and Korean, and by word (WER) for other languages. Punctuation was removed and case unified before comparing; both sides of Chinese were converted to Simplified first.
- "Nearly correct" means at most 20% error in a sentence. Meeting the bar means at least 7 of 10 sentences are nearly correct.
- **Synthesized speech is cleaner than real speech, so real-world quality will be worse.** Speed and memory on the phone were not measured at this step; they belong to device testing during implementation.

**1. Quality per language** (each cell: error rate / number of nearly correct sentences; bold is the recommended pack)

| UI language | SenseVoice | Dolphin | Whisper base | Conclusion |
|---|---|---|---|---|
| zh-Hans | **0.00 / 10** | 0.00 / 10 | 0.06 / 8 | Meets the bar |
| zh-Hant | **0.00 / 10** | 0.00 / 10 | 0.04 / 8 | Meets the bar, but output is Simplified (see 4) |
| en | **0.02 / 10** | Not supported | 0.00 / 10 | Meets the bar |
| ja | **0.03 / 10** | 0.10 / 9 | 0.17 / 7 | Meets the bar |
| ko | **0.04 / 9** | 0.18 / 6 | 0.11 / 7 | Meets the bar |
| id | Not supported | 0.26 / 5 | **0.16 / 7** | Meets the bar |
| ru | Not supported | 0.33 / 5 | **0.21 / 6** | Limited quality |
| es | Not supported | Not supported | **0.33 / 5** | Limited quality |
| pt | Not supported | Not supported | **0.33 / 4** | Limited quality |
| tr | Not supported | Not supported | **0.32 / 4** | Limited quality |
| vi | Not supported | **0.21 / 5** | 0.34 / 4 | Limited quality |
| ar | Not supported | 0.73 / 1 | **0.61 / 0** | Limited quality; mostly wrong |
| fa | — | — | — | Not tested: macOS has no Persian voice and none of the three model repositories has Persian sample audio |

- fa recommends Dolphin: Dolphin's official language list includes fa and it was trained for Middle Eastern languages. Not tested, so labeled "limited quality".
- Dolphin does not actually support en, es, pt, tr. Its output for these languages is garbled and cannot serve as their fallback.
- Whisper base's errors are mostly mishearing similar-sounding words, not truncation.
- Whisper small was also tested (int8, 375 MB, included in the first version as the optional pack `whisper-small-int8`): es 0.10 / 8, pt 0.06 / 9, ru 0.09 / 9, vi 0.11 / 7, all meeting the bar; tr 0.29 / 5 and ar 0.55 / 0 still do not. It is about 2.7× slower than base on the Mac, so 5 seconds of speech would take roughly 10 seconds on a phone. It is not recommended for any language for now; whether to switch es, pt, ru, vi to it will be decided by device speed tests during implementation.
- Device measurements during implementation (flagship-class arm64 processor, 4 threads): 5 seconds of speech takes 2.0–2.9 seconds on Whisper small (es 2.7, pt 2.0, ru 2.9, vi 2.7); on the same batch of sentences, es 0.04 / 9, pt 0.09 / 8, ru 0.09 / 9, vi 0.09 / 8, clearly better than Whisper base (es 0.33, pt 0.26, ru 0.21) and Dolphin (vi 0.21). But recognition adds about 0.85 GB of process memory (the budget is 400 MB; Whisper base adds about 0.42 GB, also slightly over), and speed on mid-range devices was not tested. **Decision**: the default recommendations are unchanged; es, pt, ru still recommend Whisper base and vi still recommends Dolphin; Whisper small stays as an optional pack and is not recommended for any language. Whether to switch to it later is up to the maintainer, and a capability check by memory size is an option (recommend it only on phones with enough memory).

**2. Repositories and files** (all exports by sherpa-onnx author csukuangfj on HuggingFace, pinned to the commits below)

| Pack id | Repository | Commit | Files (bytes) |
|---|---|---|---|
| `sensevoice-small-int8` | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | `2365baeacb507f821a0c8120fcee3d484dba7a07` | `model.int8.onnx` 239,233,841; `tokens.txt` 315,894 |
| `dolphin-base-ctc-int8` | `csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02` | `1f3a53d0ecf658f8b0974e2cfde368eee40732fa` | `model.int8.onnx` 103,729,802; `tokens.txt` 504,662 |
| `whisper-base-int8` | `csukuangfj/sherpa-onnx-whisper-base` | `bb53ee204431c90d314c1cc08d28d23e5b7927cc` | `base-encoder.int8.onnx` 29,120,534; `base-decoder.int8.onnx` 130,672,026; `base-tokens.txt` 816,730 |
| `whisper-small-int8` (optional; more accurate but slower and uses more memory; not recommended for any language) | `csukuangfj/sherpa-onnx-whisper-small` | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` | `small-encoder.int8.onnx` 112,442,483; `small-decoder.int8.onnx` 262,226,114; `small-tokens.txt` 816,730 |

- Checked one by one after download: large model files match the LFS SHA-256 given by HuggingFace, and small files match the git blob hash. The publishing script writes each file's SHA-256 into the manifest.
- SenseVoice also has an `…-int8-2025-09-09` repository. It is a version fine-tuned by ASLP-lab on Cantonese data (WSYue-ASR), not the official general version, and is not used.
- **License files are not in the HuggingFace repositories**; take them from the models' original repositories, pinned to commits:
  - SenseVoice: `MODEL_LICENSE` in `modelscope/FunASR`, commit `58830eca4012644aac0c3218c3ccc7d98f003fda`. The `LICENSE` in the HuggingFace repository is a single line with a link and cannot serve as the license file.
  - Dolphin: `LICENSE` in `DataoceanAI/Dolphin` (Apache-2.0), commit `bb70b9c0cab1235b8458141819c0ba92773d3b41`.
  - Whisper: `LICENSE` in `openai/whisper` (MIT), shared by both Whisper packs, commit `6e3be77e1a105e59086e3e21ff5f609fd6fa89a5`.
  - So license files have no backup source on HuggingFace. The publishing script must record the source address per file rather than appending everything to `upstream`.

**3. sherpa-onnx AAR**
- v2.0.3 is the latest stable release at the moment (2026-11-22). The statically linked build's file name is `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`, 39,999,115 bytes, SHA-256 `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4` (matching what GitHub shows).
- arm64-v8a has only one `libsherpa-onnx-jni.so`, 24,914,464 bytes, already stripped. About 9.3 MB compressed. Claire uses `useLegacyPackaging = true`, so the `.so` is compressed inside the APK; arm64 builds therefore grow by roughly 9–10 MB, within the 20 MB in §7.
- The AAR also carries armeabi-v7a, x86 and x86_64 libraries (about 30 MB compressed in total); exclude them when packaging and keep only arm64-v8a.
- The Kotlin API matches the design; package `com.k2fsa.sherpa.onnx`:
  - `OfflineRecognizer(assetManager = null, config)`, loading by file path. Construction throws when the config is wrong.
  - `createStream()`, `decode(stream)`, `getResult(stream).text`, `release()`.
  - `OfflineStream.acceptWaveform(samples: FloatArray, sampleRate)`: wants floats from -1 to 1, not `kotlin.ShortArray`, so conversion is needed.
  - `OfflineModelConfig` has `senseVoice`, `whisper`, `dolphin`, plus the shared `tokens` and `numThreads`.
  - `senseVoice.language`: an empty string means automatic; it can also be zh, en, ja, ko, yue. `whisper.language`: an empty string means automatic. `dolphin` has no language parameter and is always automatic.
- **Whisper needs a stream option**: v2.0.3 outputs at most 6 tokens per second of speech by default, so short commands get truncated. Call `setOption("max_tokens_per_second", "25")` on each stream before `decode`. The table above was measured this way.
- Two Whisper language codes differ from ISO: Hebrew is `iw` and Javanese is `jw`.

**4. Traditional Chinese**: SenseVoice and Dolphin both output Simplified, in all 10 sentences. Per the original plan, the first version does not convert; explain this in the PR. Whisper base mixes Simplified and Traditional when zh is specified.

**Speed reference** (Mac; reference only): processing time per second of speech is about 0.015 s for SenseVoice, 0.008 s for Dolphin, 0.08 s for Whisper base and 0.23 s for Whisper small.

## 9. Decisions made after review (the designer's judgment; the maintainer may overturn them)

A reviewer read through from an implementer's perspective and raised 6 questions; the designer settled all of them following existing code conventions, and they are written in the corresponding sections above:

1. Speech pack models are stored in the catalog snapshot rather than generated on the fly; when files are absent, keep the selection and mark "Needs download" (§5.5).
2. Deleting the selected speech pack returns to "Auto" instead of setting "Off" like the existing delete-model rule (§5.5).
3. Calls do not use speech packs in the first version (§5.5).
4. On-device recognition has its own timeout (audio duration × 3, at least 30 seconds, excluding loading), and a timeout is not recorded as a network error (§5.4).
5. Downloads do not bind Wi-Fi, metered networks require confirmation first, and SOCKS proxies keep working (§5.2).
6. "Auto" does not use the fallback chain (§5.5).

Three more things were corrected against the code:
- Language codes now use `zh-Hans` and `id`.
- On-device failure classification now spells out the mapping instead of saying "per the existing rule".
- The downloader needs reworking, not just extracting.

## 10. Left to the implementer

- How to extract the generic downloader from `about/` and which package it goes in (app layer, such as `net/` or `download/`).
- Whether the AAR goes into the repository or is built from source.
- Release asset naming rules, resumable downloads, spare space, idle release time, recognition thread count.
- The exact import interaction: one zip or multiple files.
- The exact speech pack card layout, reusing existing components such as `ListRow(…)`.

## 11. Documentation

- During implementation, update §16 "Voice input" in `docs/dev/architecture.md`: add the offline source, speech pack manifest, download and import, and the Auto order.
- Add sherpa-onnx and the three models to `licenses/THIRD_PARTY_NOTICES.md`.
- Write an "Offline speech packs" section in the user docs: where to download, how to import, how much space they take.
