# app/libs

Libraries that are not on Maven and ship at a pinned version in the repository. When changing the version, update both the checksum here and `licenses/THIRD_PARTY_NOTICES.md`.

| File | Source | SHA-256 | Size |
|---|---|---|---|
| `sherpa-onnx-2.0.3-static.aar` | https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-static-link-onnxruntime-2.0.3.aar (original file name `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`, unmodified) | `7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4` | 39,999,115 B |

The sherpa-onnx AAR bundles native libraries for four architectures; `packaging.jniLibs.excludes` in `app/build.gradle.kts` keeps only `arm64-v8a`.

Verify with: `shasum -a 256 app/libs/sherpa-onnx-2.0.3-static.aar`
