## Why

Self-built OpenAI-compatible endpoints on a corporate intranet (GPUStack / vLLM and the like) issue certificates from an internal company CA, and Mika cannot connect: once `network_security_config` explicitly trusts only system certificates, Android no longer accepts user-installed CAs (#361). The existing config already opens global cleartext http for the same scenario (LAN model services), and the HTTPS self-signed route should take the same stance — trusting a CA the user actively installed carries lower risk (installation itself requires the user to confirm in system settings).

## What Changes

- `app/src/main/res/xml/network_security_config.xml`: add `<certificates src="user"/>` to the trust-anchors of `base-config`, while system certificates continue to be trusted as before.
- Update the XML comments to explain why user CAs are accepted (the same reason as the cleartext http precedent: the user's own device, the user's own service).
- Unit test: a guard test asserting the config declares both `system` and `user` anchors (preventing future accidental removal from regressing #361).

## Capabilities

### New Capabilities
- `tls-trust`: which certificate anchors outbound HTTPS connections trust (system + user-installed), covering the certificate trust baseline of all outbound channels such as model APIs, search, downloads and the browser.

### Modified Capabilities

(None — `openspec/specs/` is currently empty; this is the repository's first spec.)

## Impact

- Code: a single declaration in `app/src/main/res/xml/network_security_config.xml`, with no Kotlin behavior changes; the trust chain takes effect at the same time for all outbound HTTPS (model APIs, web search, APK updates, browser, MCP, OTLP and other OkHttp channels).
- Security trade-off: after accepting user-installed CAs, someone who has installed a malicious CA on the device can man-in-the-middle decrypt Mika's traffic — compared with the already-existing global cleartext http permission the attack surface does not expand (cleartext has no encryption at all), and installing a CA requires the user to actively confirm in system settings.
- What is not done: no settings toggle (OkHttp clients are scattered across 12 places, and the scope of a runtime switch would be uncontrollable; options 2/3 of issue #361 are left for maintainers to do when needed); the core module is not changed.
