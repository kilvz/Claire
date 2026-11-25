# MCP service catalog and guided onboarding

Date: 2026-11-22
Status: Approved (2026-11-22)

## Intent and why

Claire can already act as an MCP client and connect to third-party servers, but it currently requires users to understand and fill in the name, URL, transport, auth method, command and advanced parameters themselves. The goal is not to redo the MCP client, but to provide a complete preset catalog on top of the existing capabilities: after the user picks a service, they only authorize or enter an API key and can connect; when there is no preset, they can still set up a custom server.

The catalog does not limit its launch scope by first batch or second batch. The generic onboarding mechanism is built once; services are onboarded as bulk data, covering common services as broadly as possible in the first version, with a target scale of 200–300 usable presets.

## Goals

1. After the user selects a preset service, they do not need to enter a URL, choose a transport, or understand the auth protocol.
2. Three user actions are supported: tap to authorize, enter an API key, and connect with no auth.
3. After a successful connection, the real tool catalog is read automatically and enabled by default; on failure, give the reason and an actionable next step.
4. The custom MCP server entry keeps full capability, covering services not in the catalog or ones that need to be adjusted.
5. Presets are only unified configuration templates; no per-service connection, tool or prompt logic is written.
6. Added services can be tested, started/stopped, re-authorized or re-keyed, have their tools viewed, and be adjusted and deleted.

## Non-goals

- Does not change Claire's "computer connection" direction as an MCP server.
- Does not rewrite the existing Streamable HTTP, legacy SSE, stdio, OAuth and task tool assembly.
- Does not build an online marketplace, ratings, reviews, third-party uploads, or forced updates from a remote catalog.
- Does not guarantee that third-party services stay online, and does not treat the number of presets as the number verified.
- Does not hard-code tool lists or tool parameters into presets; tools are still discovered live by MCP.
- Does not trust a service's instructions, results or tool risk annotations just because it is built in.

## Anti-goals

- One Kotlin branch, dedicated auth code or dedicated UI per service.
- Concatenating API keys into URLs, commands, ordinary config or logs.
- Showing 「已连接」 (Connected) when the user has entered a key but it has not been verified successfully.
- Flattening advanced fields such as URL, transport, tool allowlist and trust annotations onto the first screen.
- Silently changing the address or secret delivery destination of existing configs after a catalog update.
- Blocking the catalog, other services or task startup because one preset is broken.

## Constraints

- Aimed at generic Android; stdio can still only be launched in the Linux sandbox, and unsupported environments must show the reason plus setup/usage guidance.
- Reuse `McpServerStore`, `McpEndpoint`, `McpConnection` and the existing task `ResourceScope` lifecycle.
- All secrets go into encrypted storage; ordinary presets and user config must not store secrets.
- Remote presets only allow the service provider's official endpoint; community-hosted proxies are not used as secret delivery destinations, though users can still choose them through the custom path. stdio may accept official packages, or packages whose source is verifiable and which have been reviewed.
- When a stdio package needs to be downloaded for the first time, first show the package name, source and version and require explicit user confirmation, then download and cache it; never download silently, and pin the version where possible.
- The catalog ships with the app and is browsable offline; catalog data and descriptions are written in-house and do not copy text from external projects.
- All new user-visible strings cover every UI language the project currently has; service proper names need not be translated, but descriptions must be.
- New UI first gets a local HTML prototype, which is implemented in the app only after user confirmation.
- Develop on a worktree branch, do not touch main; do not change the version number outside a release.

## Options considered

### Just polish the existing config dialog

The change is smaller, but users still need to understand MCP addresses, transports and auth rules, so it does not solve the main problem. Not adopted.

### Full built-in catalog + guided auth + custom fallback (selected)

A single generic preset format carries a large number of services; the preset handles connection parameters and auth rules, and the user only completes authorization or enters a key. The custom path keeps full capability. The first-version catalog ships with the app and does not depend on a remote service.

### Build an online MCP marketplace directly in the first version

Would solve dynamic updates, but brings in review, caching, signing, supply chain and online availability problems, which is beyond the current goal. Not adopted.

## Product design

### 1. MCP home

- Added services still appear on the home page, including connection status, real tool count and a start/stop toggle.
- The primary action is 「添加 MCP 服务」 (Add MCP service).
- The empty state directly offers 「浏览服务目录」 (Browse service catalog) and 「自定义服务器」 (Custom server), instead of showing only an empty explanation card.
- One service failing does not affect other services; 「全部测试」 (Test all) is kept.

### 2. Service catalog

- The first version presents the full catalog directly, with search and a small number of generic category filters; no leaderboards or marketplace merchandising slots.
- Each entry shows only the name, a brief capability description, the category and the auth method.
- No URL, transport, command or tool schema is shown.
- Catalog entries have a unified state: available, needs local environment, temporarily unavailable. Unavailable entries can still be inspected for the reason and must not silently disappear.
- The bottom of the page always offers 「自定义 MCP 服务器」 (Custom MCP server).

### 3. Preset details and connecting

The details page shows what the service can do, the auth method, any required third-party account/cost notices, and a note that data will be sent to that service.

- OAuth: the primary button is 「授权登录」 (Sign in). Opens the system browser; after returning to Claire it verifies automatically and reads the tools.
- API key: shows only the keys declared by that preset plus other required items, help on obtaining them, and 「连接并保存」 (Connect and save); supports multiple keys, as well as ordinary fields such as tenant, region and self-hosted address.
- No auth: the primary button is 「连接」 (Connect).
- stdio: no command is shown; only the required fields and the local environment status. When a package needs to be downloaded, first show the package name, source and version, and only install and continue after the user confirms.

Connecting can be cancelled. It only reaches the success page when the handshake, auth and tool catalog read all succeed. The success page shows the real tool count and offers 「完成」 (Done) and 「查看工具」 (View tools).

On failure, keep the non-secret draft and the saved config, and clearly distinguish: authorization denied, invalid key, network failure, timeout, protocol incompatible, OAuth flow unsupported, sandbox environment unavailable. Offer retry, re-authorize, edit the key, or view technical details.

### 4. Custom server

The custom path keeps the existing three transports and full auth capability, but is re-layered:

- Basic section: address or command, auth information.
- Advanced section: transport, tool allowlist, local HTTP, trust annotations, timeout, result preview and cost budget.
- Custom services and preset services go into the same `McpServerStore`, with no source difference on the task side.

### 5. Managing added services

Tapping a service opens the management page, instead of popping up the full technical form directly:

- Status, last verification time, tool list.
- Re-test, re-authorize/replace the key, start/stop.
- Advanced settings and custom parameters.
- Before deleting, explain that it only removes the config in Claire and does not revoke the third-party account authorization; where revocation is supported, provide a clear entry point but do not fake the result.

## Generic technical design

### 1. Versioned preset catalog

The catalog uses a versioned data file and contains no secrets. Each preset contains at least:

- A stable `id`, display name, description, category, and local icon/generic icon.
- transport, plus a URL or stdio command template.
- Auth type: none, OAuth, fields.
- `fields` is an ordered dynamic form: each item has a stable field ID, user label, whether it is secret, whether it is required, validation and help; secret items declare a delivery target such as Bearer, a named request header or an environment variable, while ordinary items can declare template positions such as URL, header, argv or environment variable.
- The default security policy and whether it depends on the stdio environment; stdio packages must record source, package name and a version pinned as tightly as possible.
- Optional custom request header templates and OAuth discovery config.

Tool catalogs, descriptions and input schemas do not go into presets; they still come from live tool discovery after connecting. Preset copy is written in our own words.

User configs save the stable preset ID and the items the user actually overrides. Subsequent preset updates by default only affect newly added items; the address, transport, auth destination and command of existing items are not silently changed. Non-secret descriptions may update with the version.

### 2. Unified auth and secret delivery

On top of the existing Bearer and OAuth, fill in generic multi-field and secret targets:

- A service can declare multiple secret fields and multiple ordinary required fields; the UI shows only the items that preset actually declares.
- Secrets can be delivered to `Authorization: Bearer …`, a named secret request header, or a stdio process environment variable.
- Ordinary fields can be filled into the URL, request header, argv or environment variable templates declared by the preset; secrets must not enter the URL or argv.

Secret values are uniformly stored by encrypted storage under "service config ID + field ID". The ordinary `headers` continue to store only non-secret values; secret request headers must not fall into that field. The stdio launcher gets an explicit, minimized set of environment variables and does not inherit the Claire process or the model provider environment.

OAuth continues to reuse the existing discovery, dynamic registration, PKCE, callback and refresh. When a third party does not support the required flow, report it clearly and offer the other auth methods declared by that preset; do not implicitly downgrade to an insecure endpoint.

### 3. Config and connection transactions

Preset selection generates an ordinary `McpServerConfig`, after which it follows the same validation and connection path as custom servers:

```text
Not configured → awaiting authorization/awaiting key → connecting → connected and enabled
                                              └→ connection failed (editable/retryable)
```

- Saving config, saving secrets and connection failure must not leave a false success state.
- Failed configs are disabled by default and kept so they can be fixed; the user can delete them.
- Tasks still only read services that are enabled and actually connectable; the existing 15-second total connection budget and failure isolation are unchanged.
- Task end, cancellation and failure still close HTTP sessions or stdio processes through the existing scope.

### 4. Compatibility, backup and updates

- An old `mcp-servers.json` with no preset fields is read as a custom config, with unchanged behavior.
- New fields use default values and explicit serialization policy; when an old version reads a new config it must not expose secrets into ordinary fields.
- The existing encrypted secret backup is extended to the new secret types; after restore it still must pass a real connection verification and must not be declared usable directly.
- Catalog snapshots carry a format version; corrupted or unknown entries are skipped and logged, without affecting other entries.

### 5. Security boundaries

- Preset services and custom services use the same URL, redirect, private-network HTTP and OAuth endpoint checks.
- Built-in does not mean trusted: the default is still `Restricted`, and results and instructions are still marked untrusted.
- High-risk services do not automatically gain payment, confirmation or other extra authorization just because they are built in.
- Remote presets only allow official endpoints; verifiable stdio packages must record their source, and community-hosted proxies are not used as preset secret destinations.
- The catalog contains no custom executable install scripts; stdio only launches the declared argv, and secrets do not enter argv.
- When a stdio package needs to be downloaded, the user first confirms the package name, source and version, then it is executed through the unified package install/cache path; no preset may carry its own download command or download silently on first use.
- Catalog verification or real-service acceptance must not use the user's personal accounts, keys or task content.

## Implementation scope boundaries

Expected to touch:

- The app's preset catalog data, parsing and validation.
- Preset association, generic secret delivery and OAuth onboarding reuse in `McpServerStore` / the config model.
- Explicit environment variable passing and sandbox adaptation in the stdio launcher, plus the user-confirmed unified package install and cache path.
- The MCP home, catalog, details/connect, success and management pages.
- All existing language resources, backup and restore.
- JVM, UI, catalog integrity and real-service contract tests.

Does not modify inbound `McpRpc`, `McpTaskTools`, or the computer-connection permission policy.

## Verification and acceptance

### Automated

- Catalog format, ID uniqueness, URL/command templates, auth targets, icons and completeness of all languages.
- Secret storage, request/environment injection, backup restore and log leak prevention for each auth target; dynamic form and template validation for multiple secrets and ordinary required fields.
- Old config migration, isolation of individual corrupted catalog entries, unknown versions, and preset updates not changing existing secret destinations.
- OAuth, API key, no auth, custom, connection failure/cancel/retry state machines.
- stdio argv not going through a shell, environment allowlist, process and lease cleanup; package downloads must be confirmed first, cancellation does not execute, cache reuse is consistent with the version prompt.
- The catalog and custom services ultimately produce the same `McpConnection`, and the existing agent tool assembly regression is unchanged.
- `:core:test`, `:app:testDebugUnitTest` and related UI/androidTest; run per existing project conventions.

### Real-service acceptance

- Run bulk, secret-free endpoint and protocol checks against the catalog, distinguishing 「已验证」 (Verified) from 「尚未验证」 (Not yet verified), and do not treat catalog presence as proof of connectability.
- Use dedicated test accounts to cover representative OAuth, API key, no-auth and stdio services; do not write logic that only works for one vendor.
- Cover wrong keys, cancelled authorization, token refresh, service offline, tool pagination and large results.
- On a real device, verify the full add flow, real invocation within a task, cleanup after the task ends, and basic layout in all UI languages.

### User acceptance criteria

1. The first version can browse and search the full preset catalog, without locking services by batch.
2. Preset users do not need to enter a URL or choose a transport.
3. OAuth users mainly authorize; API key users by default only enter the key.
4. On success, show the real tool count; on failure, give a clear reason and next step.
5. The custom entry keeps full capability.
6. Any single preset failing does not affect other services or tasks.
7. Secrets do not appear in the catalog, ordinary config, argv, URL or logs.

## Confirmed decisions

1. Adopt "full built-in catalog + custom onboarding", not just form polish, and not an online marketplace in the first version.
2. The first version does not limit catalog size by first/second batch; the generic mechanism is implemented once, and services are organized and verified in bulk.
3. Users only need to understand "if there is a preset, authorize/fill in the key; if not, go custom".
4. Presets do not create another MCP implementation; they ultimately share the existing connection and task paths with custom services.
5. Confirm the local HTML prototype before implementing the app UI.
6. Remote presets only accept the service provider's official endpoints; stdio may accept official packages, or packages whose source is verifiable and which have been reviewed; community-hosted proxies only go through user customization and are not used as preset secret destinations.
7. When a stdio package needs to be downloaded, show the package name, source and version, and install and cache it uniformly after explicit user confirmation, without silent downloads.
8. The preset schema and dynamic form support multiple secrets and ordinary required fields such as tenant, region and self-hosted address; users only see the items that service needs.
