# Third-party notices

## Call feature (calls/) implementation

- Source: https://github.com/DzsGatsby/FlashPhone (Mika's Apache-2.0 fork)
- License: Apache-2.0
- Usage: The call feature (dialing, answering, hanging up, AI answering, manual takeover, PCM audio bridge, real-time voice client) is incorporated under the upstream
  Apache-2.0 license, covering:
  - `app/src/main/java/com/wangzan101/mika/calls/`
  - `app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellCallAudio.kt`
  - `core/src/main/kotlin/com/wangzan101/mika/core/calls/`
  - `app/src/test/java/com/wangzan101/mika/calls/`
  - `app/src/test/java/com/wangzan101/mika/cap/phone/shell/ShellCallAudioTest.kt`
  - `core/src/test/kotlin/com/wangzan101/mika/core/mcp/McpCallToolsTest.kt`
- Modifications: Product copy such as the default greeting was adjusted to "Mika AI call assistant" for Mika, and the related test assertions were updated accordingly.
- Additional port: FlashPhone v1.1.6–v1.1.12 (through `8bcd8d89a20141f47d4d24c61debc42dabaa2676`):
  foreground voice briefing (`calls/briefing/`, `calls/ui/CallVoiceBriefingHost.kt`), call-bound DTMF
  (`CallDtmfExecutor`, `calls/ui/CallDtmfKeypad.kt`), protocol/ASR fixes and their unit/UI tests.
  Adapted to Mika voice profiles, provider interface, existing task form, theme and translations;
  FlashPhone standalone navigation, recording and remote-device services are not included.
- Follow-up port: FlashPhone v1.1.13 (`554e69207de151329f71821f915cde997f8e69fc`),
  per-carrier-call task binding and compact latest-transcript context; adapted to Mika's
  existing call card and transcript role labels, with ownership/lifetime regressions.
- Follow-up port: FlashPhone v1.1.14 (`4f61f27fc3fc7c5b71262e775090ee9724d8bb6b`) and v1.1.15
  (`9b7b8fb75d0323e33a2bdd6a38ebb417b058eb78`, including `b19eed6a73505adbfa766ec6f1e117ae1ed0df78`), background standby
  recovery: a periodic, persisted JobScheduler check (`calls/CallStandbyRecoveryJob.kt`) that re-requests
  only the incoming standby the owner already granted, one start rule shared with the visible-resume
  path (`calls/CallStandbyPolicy.kt`), translated helper guidance, and the blocked-standby notification
  opening the existing standby checklist (`calls/CallStandbyService.kt`, `calls/ui/CallSettingsScreen.kt`,
  `ui/MainActivity.kt`, with `app/src/androidTest/java/com/wangzan101/mika/calls/ui/CallStandbyNotificationRouteTest.kt`).
  Adapted to Mika's split call runtime, full-exit semantics and existing translations, with policy unit tests.
- Follow-up port: FlashPhone v1.1.16 (`2a46887f26a6f09f7fc664702d4e193c0b81e5b4`), dialer and
  call-history interactions: a collapsible dial pad whose keys and backspace edit at the
  cursor/selection (`calls/ui/CallDialNumberEdit.kt`, `calls/ui/CallTaskScreen.kt`), clearing
  the one-off task draft with an in-memory undo, and clearing the history search / resetting
  filters (`calls/ui/CallHistorySection.kt`), with their unit/UI tests. Adapted to Mika's existing
  task form, theme and translations; SIM choice, outgoing review and locked dialling are unchanged.
  FlashPhone's standalone dialer layout, recording storage/playback and persisted drafts are not included.
- Service: 实时语音经火山引擎（Volcengine）的 WebSocket 接口,凭证由用户在通话设置页填写。

## scrcpy

- Project: https://github.com/Genymobile/scrcpy
- License: Apache-2.0
- Usage: The approach of obtaining a system context as the shell user (ActivityThread / a Context with a disguised package name) follows scrcpy.
  The source file headers retain the attribution:
  - `app/src/main/java/com/wangzan101/mika/shell/ShellContext.kt`

## Apache Commons Compress

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-compress:1.28.0`
- License: Apache-2.0
- Usage: Used as a library dependency to stream-extract the tar.gz of the sandbox root filesystem
  (`core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/RootfsExtractor.kt`).
- NOTICE:

```
Apache Commons Compress
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Codec

- Project: https://commons.apache.org/
- Artifact: `commons-codec:commons-codec:1.19.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons Codec
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons IO

- Project: https://commons.apache.org/
- Artifact: `commons-io:commons-io:2.20.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons IO
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Lang

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-lang3:3.18.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Kotlin standard library

- Project: https://kotlinlang.org/
- Artifacts: `org.jetbrains.kotlin:kotlin-stdlib:2.1.20`, `kotlin-stdlib-common:2.1.20`, `kotlin-stdlib-jdk7:1.9.10`,
  `kotlin-stdlib-jdk8:1.9.10`; `org.jetbrains:annotations:23.0.0`
- License: Apache-2.0 (per each POM)
- Usage: Used as a library dependency, packaged into the APK.

## kotlinx.coroutines

- Project: https://github.com/Kotlin/kotlinx.coroutines
- Artifacts: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`, `kotlinx-coroutines-core-jvm:1.9.0`, `kotlinx-coroutines-android:1.9.0`
- License: Apache-2.0 (per each POM)
- Usage: Used as a library dependency, packaged into the APK.

## kotlinx.serialization

- Project: https://github.com/Kotlin/kotlinx.serialization
- Artifacts: `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`, `kotlinx-serialization-json:1.7.3` (and their `-jvm` variants)
- License: Apache-2.0 (per each POM)
- Usage: Used as a library dependency, packaged into the APK.

## OpenTelemetry

- Project: https://github.com/open-telemetry/opentelemetry-proto-java
- Artifacts: OTLP trace, common, resource and collector schemas from `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha` (build-time only).
- License: Apache-2.0
- Usage: Generate lite OTLP trace messages.

## Protocol Buffers

- Project: https://github.com/protocolbuffers/protobuf
- Artifact: `com.google.protobuf:protobuf-javalite:4.36.2`
- License: BSD-3-Clause (see `app/src/main/assets/licenses/Protocol-Buffers.txt`).
- Usage: Official OTLP messages.

## OkHttp

- Project: https://square.github.io/okhttp/
- Artifact: `com.squareup.okhttp3:okhttp:4.12.0`
- License: Apache-2.0 (per the POM)
- Usage: Used as a library dependency to access model APIs.

## Okio

- Project: https://github.com/square/okio/
- Artifacts: `com.squareup.okio:okio:3.6.0`, `okio-jvm:3.6.0`
- License: Apache-2.0 (per each POM)
- Usage: A dependency of OkHttp.

## AndroidX (including Jetpack Compose)

- Project: https://developer.android.com/jetpack/androidx
- License: Apache-2.0 (per each POM; `androidx.compose:compose-bom:2024.12.01` is only a version list and contains no code)
- Artifacts (all AndroidX components on the release runtime classpath):
  - Compose 1.7.6: `compose.runtime:runtime`, `runtime-saveable`; `compose.ui:ui`, `ui-geometry`, `ui-graphics`, `ui-text`,
    `ui-unit`, `ui-util`; `compose.foundation:foundation`, `foundation-layout`; `compose.animation:animation`, `animation-core`;
    `compose.material:material-icons-core`, `material-ripple` (each of the above also has a `-android` variant)
  - `compose.material3:material3:1.3.1`
  - `activity:activity`, `activity-ktx`, `activity-compose`: 1.9.3
  - `window:window`, `window-core`: 1.3.0 (fold posture and hinge information)
  - `lifecycle:*` (common, common-java8, livedata-core, process, runtime, runtime-compose, runtime-ktx, viewmodel,
    viewmodel-ktx, viewmodel-savedstate): 2.8.3
  - `core:core`, `core-ktx`: 1.13.1; `annotation:annotation`: 1.8.1; `annotation-experimental`: 1.4.1;
    `arch.core:core-common`, `core-runtime`: 2.2.0; `collection:collection`, `collection-ktx`: 1.4.4;
    `savedstate:savedstate`, `savedstate-ktx`: 1.2.1; `emoji2:emoji2`: 1.3.0; `profileinstaller`: 1.3.1;
    `startup:startup-runtime`: 1.1.1; `concurrent:concurrent-futures`: 1.1.0; `autofill`: 1.0.0;
    `customview:customview-poolingcontainer`: 1.0.0; `graphics:graphics-path`: 1.0.1; `interpolator`: 1.0.0;
    `tracing`: 1.0.0; `versionedparcelable`: 1.1.1; `webkit:webkit`: 1.12.1 (built-in browser)
- Usage: UI and system component libraries, packaged into the APK.

## PRoot

- Project: PRoot
- Copyright: Copyright (C) 2015 STMicroelectronics (author list in `AUTHORS` inside the source archive)
- License: GPL-2.0-or-later, full text in `licenses/GPL-2.0.txt`
- Source: https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz
- Version: 5.4.0
- SHA-256 (source archive): 29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68
- Usage: A standalone executable. `tools/sandbox/build-native.sh` compiles it from the above sources into
  `app/src/main/jniLibs/arm64-v8a/libproot.so` and its loader `libproot_loader.so`, shipped with the APK;
  Mika only runs it as a subprocess and does not link against it. The source address, version and checksum are also recorded in `tools/sandbox/sources.lock`.
  `libproot.so` does not embed the loader (it only embeds an empty placeholder); at runtime it uses `libproot_loader.so` via `PROOT_LOADER`.
- Modifications: Four patches were applied, also released under GPL-2.0-or-later:
  `tools/sandbox/patches/proot-0001-exitkill.patch` (adds `PTRACE_O_EXITKILL` to the tracing options: when PRoot exits, the kernel kills all traced processes);
  `tools/sandbox/patches/proot-0002-seccomp-trap-enosys.patch` (system calls intercepted with SIGSYS by a seccomp filter outside PRoot are changed to return
  `-ENOSYS` and the signal is cancelled; on arm64 the faccessat2 call carrying only the `AT_EACCESS` flag is reissued as faccessat);
  `tools/sandbox/patches/proot-0003-arm64-new-path-syscalls.patch` (adds to the arm64 syscall number table the path-taking calls introduced after statx:
  faccessat2 and fchmodat2 do path translation, while execveat, openat2, io_uring_setup, the new mount interfaces and *xattrat/file_*attr return `-ENOSYS`;
  it also fixes the out-of-bounds check in the syscall number lookup table);
  `tools/sandbox/patches/proot-0004-link2symlink-fstat-nonfile.patch` (the link2symlink extension no longer reads an uninitialized stat struct when fstat targets a pipe, socket or other non-file descriptor,
  and turns the successful call into a failure).
- Bundled component uthash (BSD license, Copyright (c) Troy D. Hanson): the PRoot source tree references it as a submodule,
  and only the `care` extension uses it; that directory is empty in the source archive, and Mika's build does not include `care`, so `libproot.so` contains no uthash code.

## talloc

- Project: talloc (Samba)
- Copyright: Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan Metzmacher 2006
- License: LGPL-3.0-or-later, full text in `licenses/LGPL-3.0.txt`; LGPL-3.0 adds terms on top of GPL-3.0,
  whose full text is in `licenses/GPL-3.0.txt`
- Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- Version: 2.4.3
- SHA-256 (source archive): dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
- Usage: Only `talloc.c` is compiled (unmodified) and statically linked into the standalone executable `libproot.so`; Mika itself does not link against it.
  All sources and build scripts needed to relink are in `tools/sandbox/`.

## Alpine Linux

- Project: https://alpinelinux.org/
- Version: 3.22.7 (aarch64 minimal root filesystem), plus Python 3.12, Node 22, Pillow, pypdf and their dependencies
- License: Each package's own license (GPL, LGPL, MIT, BSD, MPL, etc.); the name, version and license of each package are in
  `app/src/main/assets/runtime/packages.json`, and the app's "Open-source licenses" page also lists them one by one
- Usage: **Shipped with the APK** as `app/src/main/assets/runtime/rootfs.tar.xz`, generated by `tools/sandbox/build-runtime.py`
  from the official Alpine sources listed in `tools/sandbox/runtime/sources.lock`, after signature verification; Mika only runs the programs inside it as subprocesses and does not link against them.
  The corresponding source for GPL/LGPL/MPL packages (Alpine build recipes and upstream sources) is placed with each release into `mika-<版本>-gpl-sources.tar.gz`;
  see `tools/sandbox/README.md` for how this is done.

## Guava ListenableFuture (pulled in by AndroidX libraries)

- Project: https://github.com/google/guava
- Artifact: `com.google.guava:listenablefuture:1.0` — Apache-2.0 (per the Maven POM)
- Usage: Used as a library dependency, with no changes to its code.

## XZ for Java

- Project: https://tukaani.org/xz/java.html
- Version: 1.10 (org.tukaani:xz)
- License: 0BSD (BSD Zero Clause License); Copyright (C) The XZ for Java authors and contributors
- Usage: Extracts the bundled runtime on first launch.

## ZXing

- Project: https://github.com/zxing/zxing
- Artifact: `com.google.zxing:core:3.5.3`
- License: Apache-2.0 (per the Maven POM)
- Usage: Used as a library dependency, with no changes to its code; only used to generate the WeChat binding QR code locally on Android.

## commonmark-java

- Project: https://github.com/commonmark/commonmark-java
- Artifacts: `org.commonmark:commonmark:0.30.1`, `commonmark-ext-gfm-tables:0.30.1`, `commonmark-ext-gfm-strikethrough:0.30.1`
- License: BSD-2-Clause
- Usage: Used as a library dependency, with no changes to its code; it parses the Markdown of answers in the conversation into a syntax tree, which Mika's own UI code then displays
  (`app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`).
- License text:

```
Copyright (c) 2015, Atlassian Pty Ltd
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## xterm.js

- Project: https://github.com/xtermjs/xterm.js
- Version: `@xterm/xterm` 5.5.0, `@xterm/addon-fit` 0.10.0
- License: MIT
- Usage: The terminal emulator for the user terminal page, packaged into the APK at `app/src/main/assets/terminal/`.

```
Copyright (c) 2017-2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
Copyright (c) 2014-2016, SourceLair Private Company (https://www.sourcelair.com)
Copyright (c) 2012-2013, Christopher Jeffrey (https://github.com/chjj/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Shizuku API

- Project: https://github.com/RikkaApps/Shizuku-API
- Artifacts: `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`
- License: Apache-2.0
- Usage: Library dependency used to optionally bind a local privileged user service when Shizuku is already installed, running, and authorized by the user.
## MCP service brand logos (mcp/mcp-logos.json)

- Source: https://www.npmjs.com/package/simple-icons (Simple Icons, pinned to versions 16.38.1 + 14.11.0 + 11.15.0)
- License: CC0 1.0 (the icon glyphs themselves); each brand mark (GitHub, Slack, OpenAI, Google, Notion, etc.) remains the property of its brand and is used here only for the "Connectable services" label.
- Usage: The brand outline paths and official brand colors in `app/src/main/assets/mcp/mcp-logos.json`, generated by `tools/mcp/build_logos.py`, are rendered by `McpLogos` as self-coloring vector icons on the service catalog/list/management pages.

## MCP service brand logo supplement (mcp/mcp-logos.json)

- Source: https://www.npmjs.com/package/@lobehub/icons-static-svg (Lobe Icons, pinned to version 1.106.0), covering only the AI/search services that the previous icon set lacks (Cohere, Groq, xAI, Tencent Cloud, etc.)
- License: MIT; each brand mark remains the property of its brand and is used here only for the "Connectable services" label.
- Usage: Same as above; `tools/mcp/build_logos.py` takes the monochrome path and writes it into `app/src/main/assets/mcp/mcp-logos.json`.

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## sherpa-onnx

- Project: https://github.com/k2-fsa/sherpa-onnx
- Artifact: `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`(GitHub Release v2.0.3,未修改;放在 `app/libs/sherpa-onnx-2.0.3-static.aar`,SHA-256 见 `app/libs/README.md`)
- License: Apache-2.0;Copyright (c) Xiaomi Corporation and the k2-fsa/sherpa-onnx contributors
- Usage: 以库的形式依赖,未修改其代码;本机离线语音识别(`OfflineRecognizer`)。只打包 arm64-v8a 的原生库。

## ONNX Runtime

- Project: https://github.com/microsoft/onnxruntime
- Version: 由 sherpa-onnx 2.0.3 官方构建静态链接进 `libsherpa-onnx-jni.so`
- License: MIT;Copyright (c) Microsoft Corporation
- Usage: sherpa-onnx 的推理引擎,随 sherpa-onnx 一起发布,未修改。

```
MIT License

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## SenseVoice Small(离线识别语音包)

- Project: https://github.com/FunAudioLLM/SenseVoice(FunAudioLLM,阿里巴巴集团通义实验室);ONNX 转换:k2-fsa/sherpa-onnx
- License: FunASR Model License 1.1(FunASR 模型开源协议 1.1);Copyright (C) 2023-2028 Alibaba Group
- Usage: 不随 APK 发布;用户在设置里选择下载 `sensevoice-small-int8` 语音包后,模型文件和这份许可证一起存到手机上,用于本机离线识别。模型名称和出处按协议 2.2 保留。

```
FunASR Model Open Source License Agreement

Version: 1.1

Copyright (C) [2023-2028] [Alibaba Group]. All rights reserved.

Thank you for choosing the FunASR open-source model. The FunASR open-source model includes a range of free and open industrial models for you to use, modify, share, and learn from.

To ensure better community collaboration, we have established the following agreement, and we hope you will read and comply with its terms.
Definitions

In this agreement, [FunASR Software] refers to FunASR open-source model weights and their derivatives, including finetuned models; [You] refers to individuals or organizations using, modifying, sharing, and learning from [FunASR Software].

2 License and Restrictions

2.1 License

You are free to use, copy, modify, and share [FunASR Software] under the terms of this agreement.

2.2 Restrictions

When using, copying, modifying, and sharing [FunASR Software], you must attribute the source and author information and retain relevant model names in [FunASR Software].

3 Responsibility and Risk

[FunASR Software] is provided for reference and learning purposes only, and Alibaba Group assumes no responsibility for any direct or indirect losses resulting from your use or modification of [FunASR Software]. You should assume all risks associated with using and modifying [FunASR Software].

4 Community Conduct Guidelines

4.1 Encouraged Behavior

The community welcomes developers and users to engage in discussions about [FunASR Software]. Participants are encouraged to interact in a friendly, polite, and respectful manner to foster constructive discussion and collaboration.

4.2 Prohibited Behavior

Individual or organizational users shall not engage in unjustified denigration, malicious smearing, or baseless insults against [FunASR Software]. Such behavior is considered a violation of the spirit of community cooperation. If a user is found to be engaging in the prohibited behavior mentioned above, it will be considered an automatic forfeiture of all licenses under this agreement.

5 Termination

If you violate any terms of this agreement, your license will automatically terminate, and you must cease using, copying, modifying, and sharing [FunASR Software].

6 Revisions

This agreement may be updated and revised occasionally. The revised agreement will be published in the official repository of [FunASR Software] and will take effect automatically. Continuing to use, copy, modify, and share [FunASR Software] indicates your acceptance of the revised agreement.

7 Miscellaneous

This agreement is governed by the laws of [Country/Region]. If any provision is deemed illegal, invalid, or unenforceable, that provision shall be considered severed from this agreement, and the remaining provisions shall continue to be valid and binding.

If you have any questions or comments regarding this agreement, please contact us.

Copyright © [2023-2028] [Alibaba Group]. All rights reserved.


FunASR 模型开源协议

版本号：1.1

版权所有 (C) [2023-2028] [阿里巴巴集团]。保留所有权利。

感谢您选择 FunASR 开源模型。FunASR 开源模型包含一系列免费且开源的工业模型，让大家可以使用、修改、分享和学习该模型。

为了保证更好的社区合作，我们制定了以下协议，希望您仔细阅读并遵守本协议。

1 定义

本协议中，[FunASR 软件]指 FunASR 开源模型权重及其衍生品，包括 Finetune 后的模型；[您]指使用、修改、分享和学习[FunASR 软件]的个人或组织。

2 许可和限制

2.1 许可

您可以在遵守本协议的前提下，自由地使用、复制、修改和分享[FunASR 软件]。

2.2 限制

您在使用、复制、修改和分享[FunASR 软件]时，必须注明出处以及作者信息，并保留[FunASR 软件]中相关模型名称。

3 责任和风险承担

[FunASR 软件]仅作为参考和学习使用，不对您使用或修改[FunASR 软件]造成的任何直接或间接损失承担任何责任。您对[FunASR 软件]的使用和修改应该自行承担风险。

4 社区行为准则

4.1 欢迎交流

社区欢迎开发者与用户对[FunASR 软件]进行交流讨论。交流中请注意保持友好、礼貌和文明，以促进建设性的讨论和合作。

4.2 禁止行为

个人或组织用户不得对[FunASR 软件]进行无端诋毁、恶意抹黑或凭空谩骂。此类行为被视为违反社区合作精神。如被认定从事上述禁止行为，将视为自动放弃本协议下的所有许可。

5 终止

如果您违反本协议的任何条款，您的许可将自动终止，您必须停止使用、复制、修改和分享[FunASR 软件]。

6 修订

本协议可能会不时更新和修订。修订后的协议将在[FunASR 软件]官方仓库发布，并自动生效。如果您继续使用、复制、修改和分享[FunASR 软件]，即表示您同意修订后的协议。

7 其他规定

本协议受到[国家/地区] 的法律管辖。如果任何条款被裁定为不合法、无效或无法执行，则该条款应被视为从本协议中删除，而其余条款应继续有效并具有约束力。

如果您对本协议有任何问题或意见，请联系我们。

版权所有© [2023-2028] [阿里巴巴集团]。保留所有权利。
```

## Dolphin Base(离线识别语音包)

- Project: https://github.com/DataoceanAI/Dolphin(DataoceanAI);CTC 分支与 ONNX 转换:k2-fsa/sherpa-onnx
- License: Apache-2.0;Copyright 2025 DataoceanAI
- Usage: 不随 APK 发布;用户下载 `dolphin-base-ctc-int8` 语音包后,模型文件和许可证全文一起存到手机上,用于本机离线识别。

## Whisper(离线识别语音包)

- Project: https://github.com/openai/whisper;ONNX 转换:k2-fsa/sherpa-onnx
- License: MIT;Copyright (c) 2022 OpenAI
- Usage: 不随 APK 发布;用户下载 `whisper-base-int8` 或 `whisper-small-int8` 语音包后,模型文件和这份许可证一起存到手机上,用于本机离线识别。

```
MIT License

Copyright (c) 2022 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
