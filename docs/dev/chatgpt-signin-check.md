# Desktop check for "Sign in with ChatGPT"

Before going to the phone, first get the OpenAI side working on a computer (macOS). This check uses the same code as the App: sign-in is in `core/src/main/kotlin/com/wangzan101/mika/core/model/chatgpt/`, the model list is `ModelCatalog`, and calls are `ResponsesModel`; the entry point is in `core/src/chatgptCheck/` and is not part of the App.

## Command

At the repo root:

```
./gradlew :core:chatgptSignInCheck
```

Optional arguments:

- `-Ptimeout=<seconds>`: how long to wait for the browser callback, default 300 (5 minutes).
- `-Pmodel=<slug>`: which model to use; if not given, pick the first reasoning model in the list (or the first one if there is none).
- `-Pkeep`: save the sign-in at the end (see "Privacy" below).

## What happens

1. Create a directory under `~/.mika-dev/chatgpt-check/` (permissions 0700); the local identity and the registered client_id are recorded in host.json inside; later runs reuse the same registration, as the App does. The first run registers a new client.
2. Open a callback listener on 127.0.0.1, print the authorization URL and open it in the default browser (if it cannot be opened, copy the URL yourself).
3. Sign in to the ChatGPT account in the browser and agree to the authorization. The terminal shows in sequence: callback received, token exchanged, ID token verified, `chatgpt.tokens.use.direct` granted, and the granted permissions, email, and token validity.
4. List the models (slug and display name).
5. Send "Reply with the single word: pong" once with one model, printing the answer and usage.
6. Tool-call round trip: the model calls `get_time`, the result is returned to it as function_call_output, and the final answer is printed; it also states whether encrypted reasoning is replayed with the call (store:false).
7. Exchange the refresh token for a new token once.
8. Finally a summary: ✓ or ✗ for each step; if there is a ✗ the process exits non-zero (Gradle shows BUILD FAILED; just look at the summary above).

Common errors are explained in plain language, for example: `subscription_sharing_user_not_eligible` (the account plan or region does not allow using quota in other apps), `usage_limit_exceeded` (quota reached), `unsupported_capability` (it writes out which item in the request), `access_denied` (cancel was tapped on the authorization page), client_id mismatch (delete the clientId in host.json and try again).

To check only whether startup is normal, use `-Ptimeout=5`: print the authorization URL, open the browser, and time out after 5 seconds.

## Privacy

- Access tokens, refresh tokens, authorization codes and ID tokens are never printed. The authorization URL does not carry the ID token.
- By default the token lives only in this run's memory and is gone when the process exits.
- Adding `-Pkeep` writes it to `~/.mika-dev/chatgpt-check/session.json` (permissions 0600); the next run only uses the email in it as a sign-in hint. Delete the file when you no longer need it.
- These files are all under the home directory, not in the repo.
