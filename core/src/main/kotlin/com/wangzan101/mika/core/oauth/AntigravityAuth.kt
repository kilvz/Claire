package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.chatgpt.TokenResult
import com.wangzan101.mika.core.chatgpt.TokenSet
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom

/** One sign-in attempt's fresh values: checked again at the callback and at the token exchange. */
data class AntigravityAttempt(val state: String, val verifier: String, val redirectUri: String) {
    /** PKCE S256 (see [Pkce.challenge]). */
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * Why an Antigravity sign-in failed; the UI says it in plain words.
 * [NoClient] means no OAuth client id has been entered yet, so sign-in cannot start.
 */
enum class AntigravitySignInFailure {
    Denied, StateMismatch, NoClient, TimedOut, Exchange, CodeExpired, Blocked, Network,
}

/** [code] is the server's error code (not a secret, safe to show and to keep in diagnostics). */
class AntigravitySignInException(
    val reason: AntigravitySignInFailure,
    detail: String = "",
    cause: Throwable? = null,
    val code: String? = null,
) : Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause)

/**
 * One Google account signed in for Antigravity (Google Cloud Code Assist).
 * The whole thing is stored the same way an API key is stored (encrypted).
 * [clientId]/[clientSecret] are the user's own OAuth client (Desktop-app type) from their own
 * Google Cloud project, or the client from their own Antigravity install; they are never logged.
 * [projectId]/[tier] come from the Cloud Code `loadCodeAssist` call and are cached here so the
 * first model call does not have to discover them again. Signing out clears the tokens but
 * keeps the client id/secret and the email, so signing in again needs no re-typing.
 */
@Serializable
data class AntigravitySession(
    val clientId: String = "",
    val clientSecret: String = "",
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val projectId: String = "",
    val tier: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** The OAuth client is entered, so "Sign in with Google" can start. */
    val hasClient: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun signedOut() = copy(accessToken = "", refreshToken = "", expiresAtMs = 0)

    fun withProject(projectId: String, tier: String) = copy(projectId = projectId, tier = tier)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AntigravitySession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * "Sign in with Google" for Antigravity, using the user's own OAuth client:
 * standard Google OAuth 2.0 with PKCE (S256), authorization code exchanged at Google's token
 * endpoint. The client secret is only ever sent to that token endpoint, never logged, and
 * stored encrypted together with the tokens.
 *
 * Whether Google's Cloud Code backend accepts a self-registered OAuth client (as opposed to
 * Antigravity's own client) is NOT verified: if the server refuses, its error is surfaced
 * verbatim through [TokenResult.Rejected]/[AntigravitySignInException] instead of being guessed at.
 * The callback address is `http://127.0.0.1:<port>/auth/callback` (port varies), via [LoopbackCallback].
 */
class AntigravityAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val tokenUrl: String = TOKEN_URL,
    private val userinfoUrl: String = USERINFO_URL,
    private val revokeUrl: String = REVOKE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt(redirectUri: String) = AntigravityAttempt(randomString(), randomString(48), redirectUri)

    /** The authorization page address, with the user-entered client id. */
    fun authorizeUrl(attempt: AntigravityAttempt, clientId: String, loginHint: String? = null): String =
        authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", attempt.challenge)
            .apply { if (!loginHint.isNullOrEmpty()) addQueryParameter("login_hint", loginHint) }
            .build().toString()

    /**
     * Read the callback parameters and return the authorization code. The state must match this
     * attempt; a refusal, a mismatching state, or a missing code throws [AntigravitySignInException].
     */
    fun readCallback(params: Map<String, String>, attempt: AntigravityAttempt): String {
        if (params["state"] != attempt.state) throw AntigravitySignInException(AntigravitySignInFailure.StateMismatch)
        params["error"]?.let { err ->
            val f = failureOf(err, params["error_description"])
            throw AntigravitySignInException(if (f == AntigravitySignInFailure.CodeExpired) f else AntigravitySignInFailure.Denied, err, code = err)
        }
        return params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AntigravitySignInException(AntigravitySignInFailure.Exchange, "no code", code = "no_code")
    }

    suspend fun exchange(clientId: String, clientSecret: String, code: String, attempt: AntigravityAttempt): TokenResult = token(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
            .add("client_secret", clientSecret).add("code", code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri).build(),
    )

    /** No scope sent: keep the scopes already granted. */
    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): TokenResult = token(
        FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId)
            .add("client_secret", clientSecret).add("refresh_token", refreshToken).build(),
    )

    /** Revoke the refresh token; true when the server confirms (HTTP 200; an already-dead token is also 200). */
    suspend fun revoke(refreshToken: String): Boolean = try {
        val form = FormBody.Builder().add("token", refreshToken).build()
        HttpSupport.execute(http, Request.Builder().url(revokeUrl).post(form).build()).status == 200
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * The account's email, for display only. Returns null when userinfo cannot be read;
     * sign-in does not fail over it.
     */
    suspend fun fetchEmail(accessToken: String): String? {
        return try {
            val raw = HttpSupport.execute(
                http,
                Request.Builder().url(userinfoUrl).header("Authorization", "Bearer $accessToken").get().build(),
            )
            if (raw.status != 200) return null
            val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull() ?: return null
            (root["email"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun token(form: FormBody): TokenResult {
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(tokenUrl).post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return TokenResult.Failed(e)
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        if (raw.status in 500..599) return TokenResult.Failed(ModelError.Server(raw.status))
        if (raw.status != 200) {
            val code = root?.let { (it["error"] as? JsonObject)?.str("code") ?: it.str("error") ?: it.str("code") } ?: "http_${raw.status}"
            val detail = root?.let { it.str("error_description") ?: (it["error"] as? JsonObject)?.str("message") }
            return TokenResult.Rejected(code, code in TERMINAL_REFRESH_ERRORS, detail, nonJson = root == null)
        }
        val access = root?.str("access_token") ?: return TokenResult.Failed(ModelError.BadResponse("token response has no access_token"))
        return TokenResult.Ok(
            TokenSet(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    private fun randomString(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        const val SCOPES = "openid email profile https://www.googleapis.com/auth/cloud-platform"
        /** The Cloud Code API root (the Antigravity preset's base URL). */
        const val API_BASE = "https://cloudcode-pa.googleapis.com/v1internal"
        /** Where the user creates and manages the OAuth client used here. */
        const val CREDENTIALS_URL = "https://console.cloud.google.com/apis/credentials"
        private const val DEFAULT_EXPIRES_SEC = 3600L

        /** The refresh token cannot be used again: clear the tokens and sign in again. */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        /** A user refusal, an expired/used code, anything else is a plain exchange failure. */
        fun failureOf(code: String, detail: String?, nonJson: Boolean = false): AntigravitySignInFailure {
            val text = "$code ${detail.orEmpty()}"
            return when {
                nonJson && (code == "http_403" || code == "http_429") -> AntigravitySignInFailure.Blocked
                code == "invalid_grant" -> AntigravitySignInFailure.CodeExpired
                code in setOf("access_denied", "consent_required", "login_required", "interaction_required") -> AntigravitySignInFailure.Denied
                text.contains("unsupported_country", ignoreCase = true) || text.contains("region", ignoreCase = true) -> AntigravitySignInFailure.Denied
                else -> AntigravitySignInFailure.Exchange
            }
        }

        /** The exception for a rejected token exchange: cause by [failureOf], carrying the server's error code. */
        fun rejected(r: TokenResult.Rejected): AntigravitySignInException = AntigravitySignInException(
            failureOf(r.code, r.detail, r.nonJson), listOfNotNull(r.code, r.detail).joinToString(" — "), code = r.code,
        )

        fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * The access token used for calls: refreshed ahead of expiry (or when the last one was refused,
 * [BearerToken.get]'s fresh), the new tokens saved ([save]). Only one refresh runs at a time
 * (a refresh token is single-use; concurrent refreshes would invalidate each other).
 * A refresh token that can no longer be used clears the tokens and throws [ModelError.SignInExpired]
 * (sign in again); a transient network/server failure throws as-is and the tokens stay.
 */
class AntigravityTokens(
    private val auth: AntigravityAuth,
    private val load: () -> AntigravitySession?,
    private val save: (AntigravitySession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired("Antigravity")
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ModelError.SignInExpired("Antigravity")
        }
        when (val r = auth.refresh(s.clientId, s.clientSecret, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                )
                save(next)
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired("Antigravity")
                }
                // Wrong client configuration and the like: retrying is useless, but the tokens are not dead, keep them.
                throw ModelError.Auth()
            }
            is TokenResult.Failed -> throw r.error
        }
    }
}

/**
 * One sign-in: open the local callback → [openBrowser] opens the authorization page → wait for
 * the callback → check it → exchange the code → read the account email (display only).
 * The steps after the callback (at most [finishTimeoutMs]) finish before the result page goes back
 * to the browser: [page] gets null on success, otherwise the reason for failure.
 * [previous] is the last sign-in (its email is offered as the login hint; its cached project/tier
 * are kept). Fails with [AntigravitySignInException].
 */
suspend fun signInWithAntigravity(
    auth: AntigravityAuth,
    clientId: String,
    clientSecret: String,
    previous: AntigravitySession?,
    openBrowser: (String) -> Unit,
    page: (AntigravitySignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
    now: () -> Long = System::currentTimeMillis,
): AntigravitySession {
    var outcome: Result<AntigravitySession>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        openBrowser(auth.authorizeUrl(attempt, clientId, previous?.email?.takeIf { it.isNotBlank() }))
        cb.await(timeoutMs, { it["state"] == attempt.state }) { params ->
            val r = finishAntigravitySignIn(finishTimeoutMs) {
                completeAntigravitySignIn(auth, params, attempt, clientId, clientSecret, previous, now)
            }
            outcome = r
            page(r.exceptionOrNull() as AntigravitySignInException?)
        } ?: throw AntigravitySignInException(AntigravitySignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}

/** The steps after the callback, at most [timeoutMs]: a timeout counts as unreachable; other failures are wrapped. */
private suspend fun finishAntigravitySignIn(timeoutMs: Long, block: suspend () -> AntigravitySession): Result<AntigravitySession> = try {
    Result.success(withTimeout(timeoutMs) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(AntigravitySignInException(AntigravitySignInFailure.Network, "no answer in ${timeoutMs / 1000}s", e))
} catch (e: CancellationException) {
    throw e
} catch (e: AntigravitySignInException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(AntigravitySignInException(AntigravitySignInFailure.Network, e.message.orEmpty(), e))
}

private suspend fun completeAntigravitySignIn(
    auth: AntigravityAuth,
    params: Map<String, String>,
    attempt: AntigravityAttempt,
    clientId: String,
    clientSecret: String,
    previous: AntigravitySession?,
    now: () -> Long,
): AntigravitySession {
    val code = auth.readCallback(params, attempt)
    val tokens = when (val r = auth.exchange(clientId, clientSecret, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw AntigravityAuth.rejected(r)
        is TokenResult.Failed -> throw AntigravitySignInException(AntigravitySignInFailure.Network, r.error.message.orEmpty(), r.error)
    }
    val email = auth.fetchEmail(tokens.accessToken) ?: previous?.email.orEmpty()
    return AntigravitySession(
        clientId = clientId, clientSecret = clientSecret, email = email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(),
        expiresAtMs = now() + tokens.expiresInSec * 1000,
        projectId = previous?.projectId.orEmpty(), tier = previous?.tier.orEmpty(),
    )
}
