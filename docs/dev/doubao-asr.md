# Doubao streaming speech recognition 2.0

## Entry point and configuration

Under Settings › Models › Speech-to-text, the "streaming speech service" group has only one row, "Doubao streaming speech recognition model 2.0": when no credential is saved it shows "Not set", and tapping it opens a separate settings page (`app/voice/ui/SpeechModelScreen.kt`); after one is saved, tapping the circle selects it, and the "Settings" on the right lets you change the credential, switch the billing mode or delete it. At the top of the settings page, a segmented control switches between the new console (speech service API Key) and the old console (App ID + Access Token), and the billing mode is either the hourly version `volc.seedasr.sauc.duration` or the concurrency version `volc.seedasr.sauc.concurrent`; the account must have activated the corresponding service first. When only the billing mode is changed without re-entering the credential, the saved credential is reused. The Ark chat provider, the chat model list and their credentials do not participate in this flow.

Saving only stores the credential encrypted on the device, does not call an online service, and does not mark the configuration as verified. A connection is established and audio sent only while the microphone is held, which may incur service charges. The credential can be deleted separately; deleting the Doubao credential currently in use turns off voice input. System recognition and the existing whole-clip audio transcription keep their own paths and do not automatically switch to a paid service.

The credential is encrypted with the existing `KeyCipher` / Android Keystore under a separate alias, and the selection JSON contains only the resource ID. The input field does not echo the key back, and no persistable Compose state is used. Diagnostics record only the engine and resource ID; server error messages are not shown directly, to avoid accidentally echoing credentials or audio content.

## Real-time path

`SpeechChoice.Doubao` → `SpeechResolver` → `VoiceInput` → `DoubaoSpeech`. Chat input and the floating capsule share the same entry point.

- `PcmRecorder(streaming = true)` provides 16 kHz, mono, PCM16 samples in real time; the existing non-streaming mode can still return a whole recording.
- `DoubaoTransport` uses a separate OkHttp WebSocket client to connect to `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`. Credentials are sent only to the fixed official endpoint; the production client does not follow redirects.
- After the handshake, a gzip JSON initialization is sent first, and audio is sent after the server's first acknowledgment. 50 ms sample blocks are merged into 200 ms audio packets; cached audio is also sent at 200 ms intervals. PCM uses little-endian, while protocol lengths and optional sequence numbers use big-endian.
- Audio sending and result receiving run in parallel. `result_type=full` is requested, and each `Partial` covers the previous full text rather than being concatenated, which avoids duplication. `definite` is a flag for a stable clause and cannot serve as the end condition for the whole session.
- Releasing stops recording, keeps the samples already queued, sends a final packet (flag 2, containing the tail under 200 ms, or an empty final packet), continues waiting for the server's final flag/negative sequence number, and then sends `Final`. Disconnecting without receiving the final packet fails, and incomplete text is not treated as the final result.
- Cancelling closes the events, audio stream and connection, and suppresses late results. Automatic end of recording also sends the final packet. The handshake/initialization each take 10 seconds, waiting after the final packet is sent takes at most 10 seconds, and the whole session is at most 75 seconds; the existing UI's hold-to-record limit remains 60 seconds.
- The audio queue holds at most about 5 seconds, and the network send queue and response queue also have limits. Exceeding a limit fails and releases the microphone rather than silently dropping audio. The decoded gzip response is limited to 1 MiB.

No device IMEI/MAC, chat history or images are sent. The bidirectional interface handles language according to the service's default recognition capability, and does not force `audio.language`, which only one-way interfaces support, into it.

## Verification and limitations

Added `DoubaoProtocolTest`, `DoubaoSpeechTest`, `DoubaoTransportTest`, `DoubaoSettingsTest`, covering protocol samples, real-time upload, full-text replacement, the final packet, timeouts, cancellation, two generations of request headers, authentication failure, and AES/GCM credential saving/restoration/deletion. All fixtures are simulated samples and fake credentials; WebSocket tests connect only to a local MockWebServer.

Runs in a normal Android development environment:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'
./gradlew :app:assembleDebug
```

The permissions, charges, latency and accuracy of a real Volcano account need to be verified by someone with an account; without an account, only the settings page, credential saving/deletion and failure messages can be confirmed.

## References and implementation sources

Official protocol checked on 2026-11-01:

- [Large-model streaming speech recognition API (binary frames)](https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh)
- [Real-time speech recognition (current parameters and 2.0 resources)](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)
