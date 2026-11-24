package com.wangzan101.mika.core.model.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Random values for one OpenRouter authorization: newly generated each time. [callbackUrl] is the callback address of this local listener. */
data class OpenRouterAttempt(val state: String, val verifier: String, val callbackUrl: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * Sign in with an OpenRouter account: OpenRouter's official OAuth PKCE flow for third-party apps. The authorization page opens in
 * the browser; after the user agrees it returns with `code` to the local callback, and `code` plus the verifier are exchanged for
 * an API key belonging to this user. The result is an ordinary OpenRouter key, used afterwards just like an OpenRouter provider
 * with a pasted key (listing models and calls both go to [API_BASE]).
 * Keys never reach the logs; exception text carries only the category and the error code.
 */
class OpenRouterAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val keysUrl: String = KEYS_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt(callbackUrl: String) = OpenRouterAttempt(Pkce.state(), Pkce.verifier(), callbackUrl)

    /** The authorization page URL: callback address, S256 challenge, key name (prefilled, the user may change it), and state. */
    fun authorizeUrl(attempt: OpenRouterAttempt): String = authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", attempt.callbackUrl)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", KEY_LABEL)
        .addQueryParameter("state", attempt.state)
        .build().toString()

    /**
     * Whether the local callback accepts this attempt: a matching state is accepted; a callback with no state, no code, or an
     * `error` (OpenRouter omits the state when the user declines on the authorization page) is also accepted and treated as not
     * approved. Anything else (a code with a missing or mismatched state, or nothing at all) is rejected: it was not started by
     * this sign-in, so respond 400 and keep waiting.
     */
    fun accepts(params: Map<String, String>, attempt: OpenRouterAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** Extracts `code` from the callback; its absence means the user did not approve (or the authorization page reported an error). */
    fun readCallback(params: Map<String, String>, attempt: OpenRouterAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /**
     * Exchanges `code` and the verifier for a key (`POST /api/v1/auth/keys`). 400 means the challenge method does not match; 403
     * means the code or the verifier is wrong, or the code has expired (10 minutes).
     * On error only the status code is reported, never the response body.
     */
    suspend fun exchange(code: String, attempt: OpenRouterAttempt): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", attempt.verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody(JSON_TYPE)
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(keysUrl).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "http", e)
        }
        when {
            raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_403")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        return (root?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** The API base used with the exchanged key, the same as the OpenRouter preset. */
        const val API_BASE = "https://openrouter.ai/api/v1"
        /** The page where the user manages (deletes) keys on OpenRouter. */
        const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
        /** The key name prefilled on the authorization page; the user recognizes this key by it on the Keys page. */
        const val KEY_LABEL = "Claire"
        /** The callback address uses localhost (OpenRouter states that any port works for local callbacks); the listener binds only 127.0.0.1. */
        const val CALLBACK_HOST = "localhost"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * Runs the whole OpenRouter sign-in: open the local callback → [openBrowser] opens the authorization page → wait for the
 * callback → exchange the key.
 * The result page is returned to the browser only after the key exchange (at most [finishTimeoutMs]): [page] receives null on
 * success, otherwise the reason for the failure.
 * Failure throws [AccountSignInException]; cancellation is rethrown as usual. Returns the exchanged API key.
 */
suspend fun signInWithOpenRouter(
    auth: OpenRouterAuth,
    openBrowser: (String) -> Unit,
    page: (AccountSignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): String {
    var outcome: Result<String>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri(OpenRouterAuth.CALLBACK_HOST))
        openBrowser(auth.authorizeUrl(attempt))
        cb.await(timeoutMs, { auth.accepts(it, attempt) }) { params ->
            val r = try {
                Result.success(withTimeout(finishTimeoutMs) { auth.exchange(auth.readCallback(params, attempt), attempt) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSignInException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
            }
            outcome = r
            page(r.exceptionOrNull() as AccountSignInException?)
        } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}
