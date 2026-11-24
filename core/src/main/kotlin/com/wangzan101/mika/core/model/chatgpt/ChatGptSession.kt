package com.wangzan101.mika.core.model.chatgpt

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.oauth.LoopbackCallback
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sign-in of one ChatGPT account: the [clientId] returned by registration, the verified identity ([sub], [email]) and the tokens.
 * The whole thing is stored encrypted like an API Key. After signing out the tokens are cleared ([signedIn] is false), the registration and identity are kept, and signing in again reuses the same client_id.
 */
@Serializable
data class ChatGptSession(
    val clientId: String,
    val sub: String,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** The user agreed to call with plan quota. */
    val planUsage: Boolean get() = ChatGptAuth.PLAN_SCOPE in scopes

    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): ChatGptSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The access token used for calls: when it is about to expire (less than [refreshAheadMs] left) or was rejected (fresh in [BearerToken.get]), a new set is obtained with the refresh token,
 * and the new refresh token is stored together with the access token ([save]). Only one refresh runs at a time (a refresh token is invalidated once used, concurrent refreshes would cancel each other out).
 * Refresh token no longer usable: clear the tokens and throw [ModelError.SignInExpired] (signing in again is required); a transient network or server error is thrown as usual and the tokens are kept.
 */
class ChatGptTokens(
    private val auth: ChatGptAuth,
    private val load: () -> ChatGptSession?,
    private val save: (ChatGptSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ModelError.SignInExpired()
        }
        when (val r = auth.refresh(s.clientId, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    idToken = t.idToken ?: s.idToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                )
                save(next)
                if (!next.planUsage) throw ModelError.ChatGptNotEligible()
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired()
                }
                // Client configuration problems and the like: retrying does not help, but the token is not invalid either, so do not clear
                throw ModelError.Auth()
            }
            is TokenResult.Failed -> throw r.error
        }
    }
}

/**
 * Run through a sign-in: open the local callback → [openBrowser] opens the authorization page → wait for the callback → verify → exchange the token → verify the ID token → check whether plan quota was granted.
 * The steps after the callback arrives (at most [finishTimeoutMs]) are completed before the result page is returned to the browser: [page] receives null on success, otherwise the reason for the failure.
 * [clientId] is one registered previously (null the first time; once registration succeeds it is handed out via [onRegistered], and should be stored before the token exchange);
 * [previous] is the previous sign-in (used to pass the ID token and email, skipping account selection). Throws [SignInException] on failure.
 */
suspend fun signInWithChatGpt(
    auth: ChatGptAuth,
    hostId: String,
    clientId: String?,
    previous: ChatGptSession?,
    openBrowser: (String) -> Unit,
    onRegistered: (String) -> Unit,
    page: (SignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
    now: () -> Long = System::currentTimeMillis,
): ChatGptSession {
    var outcome: Result<ChatGptSession>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        val hint = previous?.takeIf { it.signedIn && it.clientId == clientId }
        openBrowser(auth.authorizeUrl(attempt, clientId, hostId, hint?.idToken, previous?.email?.takeIf { clientId != null }))
        cb.await(timeoutMs, { it["state"] == attempt.state }) { params ->
            val r = finishSignIn(finishTimeoutMs) { completeSignIn(auth, params, attempt, clientId, onRegistered, now) }
            outcome = r
            page(r.exceptionOrNull() as SignInException?)
        } ?: throw SignInException(SignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}

/** The steps after the callback, at most [timeoutMs]: a timeout counts as a connection failure; other exceptions are turned into [SignInException]; cancellation is rethrown as usual. */
private suspend fun finishSignIn(timeoutMs: Long, block: suspend () -> ChatGptSession): Result<ChatGptSession> = try {
    Result.success(withTimeout(timeoutMs) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(SignInException(SignInFailure.Network, "no answer in ${timeoutMs / 1000}s", e))
} catch (e: CancellationException) {
    throw e
} catch (e: SignInException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(SignInException(SignInFailure.Network, e.message.orEmpty(), e))
}

private suspend fun completeSignIn(
    auth: ChatGptAuth,
    params: Map<String, String>,
    attempt: SignInAttempt,
    clientId: String?,
    onRegistered: (String) -> Unit,
    now: () -> Long,
): ChatGptSession {
    val (code, issued) = auth.readCallback(params, attempt, clientId)
    if (issued != clientId) onRegistered(issued)
    val tokens = when (val r = auth.exchange(issued, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw ChatGptAuth.rejected(r)
        is TokenResult.Failed -> throw SignInException(SignInFailure.Network, r.error.message.orEmpty(), r.error)
    }
    val idToken = tokens.idToken ?: throw SignInException(SignInFailure.IdToken, "no id_token")
    val who = auth.verifyIdToken(idToken, issued, attempt.nonce)
    if (ChatGptAuth.PLAN_SCOPE !in tokens.scopes) throw SignInException(SignInFailure.NoPlanScope, "scope", code = "no_plan_scope")
    return ChatGptSession(
        clientId = issued, sub = who.sub, email = who.email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(), idToken = idToken,
        expiresAtMs = now() + tokens.expiresInSec * 1000, scopes = tokens.scopes,
    )
}
