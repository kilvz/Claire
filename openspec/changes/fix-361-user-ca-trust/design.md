## Context

`app/src/main/res/xml/network_security_config.xml` currently has `base-config`: `cleartextTrafficPermitted="true"` (plaintext opened up for LAN http model services, see the comments and the `LocalHosts` hint), and trust-anchors declares only `system`. Android's rule: once anchors are declared explicitly, user CAs are never trusted, so even with a corporate CA installed the self-signed endpoint cannot be reached (#361). There are many outbound HTTPS channels (model APIs, search, downloads, browser, MCP, OTLP…), and OkHttp clients are built separately in 12 places (there is a `NetProxy.shared.client()` wrapper point, but not everyone goes through it).

## Goals / Non-Goals

**Goals:**
- CAs installed by the user are trusted by every outbound HTTPS channel, so self-signed endpoints on a corporate intranet work without root.
- Minimal change (one declarative line + comments), without touching any Kotlin network code.

**Non-Goals:**
- No settings toggle / per-endpoint pinned trust (issue options 2/3): client construction is scattered, and the scope of a runtime hot-switch would be uncontrollable; maintainers can tighten on top of this when needed.
- Do not touch the existing cleartext http permission, and do not touch the core module.

## Decisions

**Add `<certificates src="user"/>` to `base-config`, rather than a runtime custom TrustManager.**
- One declaration covers all channels (including clients added in the future), with no gaps; the TrustManager approach would require changing 12 places client by client, and missing one would create the intractable "some connect, some don't" problem.
- Consistent with existing precedent: the config already opens global cleartext http for "user-run services"; trusting user CAs is the same stance and a smaller relaxation (installing a CA requires the user to actively confirm in system settings, whereas cleartext has no threshold at all).
- debug/release share this single config in main, so debugging and liveness checks benefit too.

**The guard test goes in `app/src/test` (Robolectric/JUnit reading the resource and parsing the XML), not behavior-level on-device automation.**
- Certificate trust behavior is determined by the system network stack parsing this XML and cannot be reproduced in a unit test; on-device manual testing (install CA → connect to self-signed endpoint) carries the behavioral verification, and the test only guards against regressions where "this line is accidentally deleted in the future."

## Risks / Trade-offs

- [Traffic can be decrypted after a malicious user CA is installed on the device] → The precondition for the attack is that the attacker can already install a certificate in the victim device's system settings; under the same precondition the cleartext permission already allows unencrypted direct connections, so the attack surface does not expand. Write this trade-off explicitly for maintainers in the PR description.
- [Maintainers may prefer a settings toggle (default off)] → This change does not conflict with that: when the toggle approach lands, the anchor can be changed back to system and user hung off the toggle; state this in the PR.
- [Whether Robolectric can read xml/ when parsing resources] → xml/ resources can be read in unit tests through `XmlResourceParser` or a plain file stream; verify the reading path during implementation, and if it does not work, degrade to asserting on a file read via a repo-relative path (still guarding the same fact).

## Migration Plan

Takes effect on merge, no data migration; rollback = delete that one line.
