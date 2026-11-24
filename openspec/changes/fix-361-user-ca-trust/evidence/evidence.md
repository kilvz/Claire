# fix-361-user-ca-trust on-device verification record

Date: 2026-11-11 · Device: Huawei Mate X2 (TET-AN50, API 31, unlocked) · Branch: `fix/361-user-ca-trust` (based on origin/main c8178d21)

## Method

Simulating the corporate intranet scenario of issue #361, entirely on a real device:

1. On the computer, use openssl to create a "corporate internal CA" (`CN=Mika Test Internal CA`, fingerprint see ca-fingerprint.txt) plus a server certificate it issues (SAN: IP 127.0.0.1); mock-endpoint.py starts an OpenAI-compatible HTTPS endpoint (GET /v1/models, POST /v1/chat/completions) listening on 127.0.0.1:8443.
2. `adb reverse tcp:8443 tcp:8443`, then add a custom provider in Mika on the phone pointing at `https://127.0.0.1:8443/v1`, so the link does not depend on WiFi.
3. The only controlled variable is whether the user CA is installed (system settings › security › more security settings › encryption and credentials › install from storage).

## Timeline

| Time | Step | Result |
|---|---|---|
| 15:14 | Build the debug APK containing the fix and install it, add a provider pointing at the self-signed endpoint (**no CA installed**) | **「连接失败：连不上这个地址,请检查网络和接口地址」** ("Connection failed: cannot reach this address, please check the network and endpoint address"), with no HTTP request on the server side (TLS handshake rejected). Screenshot `02-before-ca-install.png` |
| 15:18 | Install the test CA in system settings; `cn=mika test internal ca` (user credential) appears in the credential list | — |
| 15:20 | Same App, same address, "Test and save" again | **「已连接 · 1 个模型」** ("Connected · 1 model"), the provider was saved successfully; the server received `GET /v1/models` and `POST /v1/chat/completions` (the TLS trust chain works). The hint **「internal-test-model 没测试通过：它不会调用工具」** ("internal-test-model did not pass the test: it does not call tools") was because the mock at that time could not yet return tool_calls; that belongs to model capability validation and is unrelated to the certificate; after upgrading the mock the final state was still connected. Screenshot `03-fixed-apk-ca-installed-connected.png` |
| 15:26 | (Supplementary control) Build the same-source APK with `<certificates src="user"/>` removed, install it, and replay the add-provider flow | Automated taps on the Compose form missed 「测试并保存」 ("Test and save") several times, so no stable UI conclusion was obtained; this control was not included in the evidence chain. After reinstalling the fixed build the final state was re-verified as 「已连接」 (Connected). Note: "explicitly declaring the system anchor rejects user CAs" is documented Android platform behavior, and the issue author has also measured it, so it is credible without this control |

## Conclusion

- On the fixed build, the only variable before and after installing the CA is the extra test CA in the user trust store, and the behavior flipped from 「连接失败」 (connection failed) to 「已连接」 (connected), corroborated by the server logs — after adding `<certificates src="user"/>` to `base-config`, endpoints issued by a corporate internal CA are reachable without root.
- Public endpoint regression: before and after installing the CA, the user's 「智谱 Coding Plan」 (Zhipu Coding Plan) provider was always 「已连接 · 12 个模型」 (Connected · 12 models) and unaffected.

## Cleanup (completed)

The test provider has been deleted (only the user's original entries remain in the provider list); the test CA has been removed from user credentials; `/sdcard/Download/mika-test-ca.crt` has been deleted; `adb reverse` has been removed; the mock endpoint has been stopped. The CA private key exists only in /tmp on the local machine and was not committed.
