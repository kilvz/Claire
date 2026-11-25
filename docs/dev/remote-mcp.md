# Self-hosted remote CLI / MCP

First-version scope: single user, single device, single gateway process. The gateway provides HTTPS CLI / MCP forwarding, and the phone connects outbound over WSS; no inbound public port or extra VPN on the phone is needed. Deployment code and English instructions are in [companions/relay/README.md](../../companions/relay/README.md).

## Deployment and pairing

Prepare a domain pointing at the server, Docker Compose, and inbound TCP 80/443. Do not publish the gateway's HTTP port 8787 directly to the internet.

```sh
cd companions/relay
cp .env.example .env
# Edit .env, set MIKA_DOMAIN to your own domain only, without protocol or path
docker compose build
docker build --target caddy-check .
docker compose run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose up -d
docker compose cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

View the owner-key file privately on this machine. It is an admin key, not an MCP token, and is not entered into client configuration. Initialization does not overwrite an old key. Caddy obtains certificates automatically and proxies WebSockets; only Caddy publishes host ports. The domain and ports must be reachable; keep the data and certificate volumes.

Open the gateway's HTTPS address in a browser, enter the admin key, and tap Create pairing code. A pairing code is valid for five minutes and can be used only once. On the phone, 「设置 → 连接电脑 → 远程」 (Settings → Connect computer → Remote), enter the address and pairing code; after pairing you still need to explicitly turn on 「允许远程访问」 (Allow remote access). Before switching phones, the old pairing must be revoked.

### The server already has Nginx

When 80/443 is already used by an existing Nginx, use the separate [compose.nginx.yaml](../../companions/relay/compose.nginx.yaml) instead, and do not merge it with the default Compose file. Configure the same .env and run with a fixed project name:

```sh
docker compose -p mika-relay -f compose.nginx.yaml build
docker compose -p mika-relay -f compose.nginx.yaml run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose -p mika-relay -f compose.nginx.yaml up -d
docker compose -p mika-relay -f compose.nginx.yaml cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

The gateway is published only to the server's 127.0.0.1:18787; Nginx must be on the same host. Change the domain and certificate paths in [nginx.conf.example](../../companions/relay/nginx.conf.example) to actual values, and add it to the existing http configuration as a separate virtual host. The certificate must cover the domain, and renewal plus smooth reload after renewal must already be in place. First check that the domain, map names and port 18787 have no conflicts, then nginx -t, and after it passes reload smoothly; on failure, roll back only the new configuration. The example keeps WebSocket upgrade and disables buffering, proxy retries and access logging of forwarded requests.

All subsequent Compose commands use the same project name and -f argument. To roll back, disable the new virtual host, validate and smoothly reload Nginx, then docker compose -p mika-relay -f compose.nginx.yaml down; keep the data volumes. Do not change the backend port to a public binding, and do not start the default Compose file's Caddy in this deployment.

## Client and unified token

The phone's existing `McpSettings` access token is used for LAN, remote CLI and MCP alike. The gateway does not generate, validate, digest-store or sync that token; it only forwards `Authorization` transparently, and each request is validated by the phone's `RemoteAccess`. When the LAN switch is off, an explicitly enabled remote connection can still use this token. Temporary USB tokens remain for loopback entries only and cannot be used through remote access.

- MCP: on the phone choose 「MCP → 远程」 (MCP → Remote), and copy the configuration into a client that supports a custom Authorization request header; the address is `https://<your-domain>/mcp`.
- CLI: on the phone choose 「CLI / Skill → 远程」 (CLI / Skill → Remote), copy the access token into a private file on the computer, and run the connection command shown on the page. The command does not carry the token in plain text.

```sh
chmod 600 /path/to/token
mika connect --url https://mika.example.com --token-file /path/to/token
mika --url https://mika.example.com --token-file /path/to/token doctor
mika --url https://mika.example.com --token-file /path/to/token task list --limit 5
```

Every remote call must pass `--url` and `--token-file`; `mika connect` only verifies the connection and does not save remote configuration. Both protocols access the same task service on the phone, and task creation, querying, waiting, answering, steering and cancel behave the same as on the LAN.

### Wake and unlock through the gateway: off by default

Remote wake and unlock let whoever holds the token enter the lock-screen PIN/password on the phone. On a LAN, only devices on the same network can reach the phone; through the gateway it is the entire internet, and the gateway itself can see the token. So requests coming through the gateway by default **cannot see** screen capabilities (`mika_device_state`, `mika_wake_device`, `mika_unlock_device` and `/api/v1/device`; `/api/v1`'s capabilities do not list `device_state` either) — the tools and endpoints simply do not appear rather than appearing and then being refused. To wake and unlock through the gateway, three things must all be turned on on the phone: 「允许远程访问」, the remote page's 「允许经网关唤醒和解锁」 (Allow waking and unlocking through the gateway) (off by default, stored in the encrypted gateway configuration, resetting to off on pairing or re-pairing), and the existing 「允许远程亮屏与解锁」 (Allow remote wake and unlock). LAN and USB behavior are unchanged.

After the phone changes its access token, old tokens for LAN and remote stop working immediately, with no gateway sync needed; clients must update their configuration. Unpairing only revokes the device channel and does not change the access token; after re-pairing, clients holding that token can still access. To invalidate those clients, change the token on the phone.

The gateway does not provide OAuth authorization discovery, registration, authorization or refresh. Clients that only accept the OAuth flow and cannot configure a Bearer request header are not supported at present. Browser Origin requests are still refused by the phone; the existing MCP uses stateless JSON and does not add SSE.

## Architecture and credential responsibilities

| Credential | Purpose | Where it is kept |
| --- | --- | --- |
| Phone access token | Unified CLI / MCP request authentication | Phone encrypted storage, user-configured clients |
| Device channel credential | A paired phone establishing WSS, unpairing | Phone encrypted storage, gateway digest |
| Admin key | Creating pairing codes, managing unpairing | Gateway private file, in-process digest |

`RemoteAccess.handle` handles authentication and protocol errors uniformly, then enters `RemoteEndpoints`; the local listener and the remote channel share the task service. The LAN private-address check is kept. Remote requests use a non-loopback source and do not gain USB privileges. Failure cooldown is counted separately per caller: the gateway passes the caller address it sees (an IPv4 address, or the /64 prefix of an IPv6) along with the request to the phone, and the phone counts by "gateway + caller", separately from the local listener's address. A caller that repeatedly guesses wrong only cools down itself; during cooldown even the correct token is not let through, so "guessed right" is not revealed to it. When the gateway is behind a reverse proxy all connections come from the proxy, so `-trust-proxy` must be added, making the gateway take the caller from the last X-Forwarded-For appended by the proxy (read only when the immediate peer is a loopback or private address); both Compose files have it added, and the Nginx example overrides that header with `$remote_addr`. The gateway's own admin key, pairing and forwarding rate limits are also counted by the same caller. `McpRuntime` provides the phone's unified token for the remote channel and refuses access when remote is off; pairing itself does not enable remote.

`RelayGateway` validates the HTTPS address and the pairing response and does not follow redirects. `RelayClient` whitelists GET/POST/DELETE for `/mcp`, `/api/v1` and `/api/v1/…`, keeping the query and the Authorization, Origin, X-Claire-Client and protocol headers separately; methods, parameters and task permissions for each endpoint are still checked by the phone. The gateway does not proxy arbitrary URLs or TCP.

`RelaySettings` encrypts and atomically saves the device configuration with a separate Keystore key, and the state flow contains only non-secret fields. `RelayNetwork` detects network switches, and `McpService` reuses the foreground service and notification stop action. The app tutorials use the existing components and Simplified/Traditional Chinese resources; switching tutorials does not change permissions, and the token is read only when copying.

The gateway Web UI uses Go templates and embedded local CSS/JS, with no framework, Node build or CDN. Colors, font sizes, spacing, grouped cards and button sizes map to the app's semantic values, adapting to light/dark and narrow screens. Three dictionaries — English, Simplified Chinese and Traditional Chinese — cover the UI, errors, form validation and confirmation dialogs; by default it follows the browser, can be selected separately, and the cookie stores only the language. The admin key is not filled back in, pairing management does not use browser credential storage, and it can be submitted and confirmed even without JS.

## Disconnection and revocation

Turning off remote immediately closes the channel and keeps the pairing; unpairing closes first, then asks the gateway to delete the device identity, and when the network is down it keeps the closed state so it can be retried. The Web UI's 「解除手机配对」 (Unpair phone) must be confirmed before it runs.

Connections are re-established with backoff, and operations are not replayed; each request has its own number, and a repeated number on the same connection is not executed. At most eight requests at a time, a 1 MiB body limit, 8 KiB each for query and headers, a 65-second timeout, covering the phone's state waits of up to 50 seconds. When a phone result is too large an error is returned, and the channel is not revoked because of it.

`device_offline` means no channel is available; `delivery_unknown` means it may have executed but the result was lost; you should query task status and not automatically resend tasks, steers or unlocks. For HTTP 5xx on write requests, the CLI conservatively reports `outcome_unknown`. Cancelling a wait cannot undo an action already performed. Commands are not queued while offline.

## Verification and operations

```sh
cd companions/relay
go test -race ./...
go vet ./...
cd ../..
./gradlew :core:test :app:testDebugUnitTest :core:cliTest :core:relayTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall
```

The cross-language test starts a real Go gateway and, through the production Kotlin tunnel and phone authentication, calls the real Python CLI and MCP, verifying shared tasks, unified token rotation, USB token rejection and unpairing. Ordinary JVM tests do not require Go. UI tests are `RelaySectionTest` and `ComputerAccessScreenTest`; the development build is isolated from the release via `devInstall`.

`/healthz` only proves the gateway process is up, not that the phone is online. The proxy must preserve Host, WebSocket and protocol headers, override X-Forwarded-For with the caller address, use a timeout greater than 65 seconds, and disable automatic request retries. By default it does not log bodies or credentials, and the reverse proxy must do the same. Backups contain the device identity, and restoring an old backup may restore revoked devices; rotating the admin key requires replacing the private file and restarting, without changing the phone token.

HTTPS terminates at the gateway/proxy, and the gateway can technically read request headers and task contents, so a trusted gateway is required; this scheme is transparent forwarding with phone-side authentication, not end-to-end encryption from client to phone. PIN/password are still handled only on the phone. Android background restrictions, force stop, network loss and reboots affect availability; certificates, standby, network switching and first-unlock restrictions must be verified separately.
