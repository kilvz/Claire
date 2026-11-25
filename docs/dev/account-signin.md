# Signing in with an account

At the top of 「添加服务商」 (Add provider) is a 「用账号登录 · 不用填 Key」 (Sign in with an account · no key needed) group: ChatGPT, xAI / Grok, OpenRouter. Tapping one first opens an explanation panel (what will happen, what to watch out for), and sign-in only starts after that; after signing in successfully, that provider's details open: account, status, available models (refreshable), set as main model, sign out (clears this device only).

「用 ChatGPT 登录」 (Sign in with ChatGPT) stays as it is (`core/model/chatgpt/`, authorization code + PKCE, local callback). This document covers the other account sign-ins, and the reasons for what is deliberately not done. The local callback (`core/model/oauth/LoopbackCallback.kt`) and PKCE (`core/model/oauth/Pkce.kt`) are shared by ChatGPT and OpenRouter.

Signing in, encrypted storage, refresh and calling each provider's own API are four separate steps. Access tokens, refresh tokens and device codes do not go into logs, diagnostics, or any text in the UI other than the user code. The session JSON and API keys live in the same place (`provider-keys.json`), with the same encryption.

## xAI / Grok (device code)

- Reads `https://auth.x.ai/.well-known/openid-configuration`. The issuer must be `https://auth.x.ai`, and the device-code and token endpoints must be https with host `auth.x.ai`. On a mismatch it stops and the request is not sent to another host.
- The device code (RFC 8628) scope is `openid profile email offline_access grok-cli:access api:access`. The user opens the verification page in a browser and checks the short code, and the app polls to exchange the token. If the verification page's host is not in the allowlist (`auth.x.ai`, `accounts.x.ai`), it opens the fixed official page instead.
- The client is the public sign-in entry of xAI's official command-line tool (public client_id `b1a00492-073a-47ea-816f-4c329264a828`). This is not an app that xAI registered for Claire, and xAI may restrict this usage, with a risk that the account gets limited. The UI says so before signing in, and nothing happens unless 「继续登录」 (Continue sign-in) is tapped.
- Calls go through xAI's own Responses API: `https://api.x.ai/v1/responses` (`ResponsesModel`), and are not translated into another protocol.
- When the access token is about to expire, the refresh token is used to get a new one, and only one refresh happens at a time. If the refresh is rejected and cannot be used again, the local token is cleared and 「登录已失效」 (Sign-in expired) is reported.
- Sign-in succeeds but calls return HTTP 403: this means the subscription tier cannot use this API (`ModelError.AccountPlanNotAllowed`), and there is no retry; both the details page and the task outcome suggest switching to an xAI API key.
- Signing out only clears tokens on this phone. Authorization in the xAI account must be disconnected by the user in xAI's account settings (there is no usable public revocation).

## OpenRouter (official OAuth PKCE)

OpenRouter's official sign-in method for third-party apps (docs: https://openrouter.ai/docs/use-cases/oauth-pkce), which exchanges for an ordinary OpenRouter API key.

- Local callback: `LoopbackCallback` opens a free port chosen by the system on `127.0.0.1`, and the callback address is written as `http://localhost:<port>/auth/callback` (OpenRouter documents that any local callback port is fine).
- Open `https://openrouter.ai/auth?callback_url=…&code_challenge=…&code_challenge_method=S256&key_label=Claire&state=…` in the system browser. The verifier is generated fresh each time and stays on this device only.
- The callback brings back `code` and `state`: if state does not match, return 400 and keep waiting; if neither code nor state is present (the user denied on the authorization page), treat it as not agreed.
- `POST https://openrouter.ai/api/v1/auth/keys`, JSON `{code, code_verifier, code_challenge_method: "S256"}`, returns `key`. 403 means the code or verifier is wrong, or the code expired (10 minutes); 400 means the verification method does not match. On error, keep only the status code, not the body. Only after the exchange is done is the result page returned to the browser.
- Storage: following the OpenRouter preset, it is stored as an OpenAI-compatible provider (`https://openrouter.ai/api/v1`), and the key is encrypted by `KeyCipher` and stored in `provider-keys.json` like any other API key; the provider records `signIn = "openrouter"` (`services[].signIn` in `providers.json`), and the details show it as an account sign-in. The authorized OpenRouter and a user-entered-key OpenRouter are separate providers (in the list, the authorized one is written as 「OpenRouter（账号登录）」 (OpenRouter (account sign-in))), authorization does not override a key the user entered themselves, and signing in again only updates the authorized one. Model listing and calls are the same as with an entered key.
- Signing out only deletes the local key; the provider, models and purposes remain; the UI points the user to OpenRouter's Keys page (`https://openrouter.ai/settings/keys`) to delete the key named Claire.

## Not done

### Claude subscription

Anthropic states that OAuth is only for its own apps; third-party products must use Console API keys or cloud provider credentials, third-party apps are not allowed to offer Claude.ai sign-in, and subscription quota may not be attached to their own requests. Claire keeps using the Anthropic preset with an API key.

### MiniMax account

MiniMax's official docs offer no OAuth for third-party apps: there is no entry for third parties to register their own client, and no client_id published for third-party use. Every account sign-in method that can be found borrows a client registered by someone else, and Claire does not do that. The legitimate approach MiniMax documents for third-party tools is the Token Plan (Coding Plan) API key; in Claire, just use the MiniMax preset and enter a key.

### Google account (Gemini)

Google does not allow third-party software to use the OAuth client of its own tools to access the backend. Third parties must use an AI Studio or Vertex AI API key. Claire keeps using the Gemini preset with an API key.
