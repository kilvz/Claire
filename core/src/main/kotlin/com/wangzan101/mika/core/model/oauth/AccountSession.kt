package com.wangzan101.mika.core.model.oauth

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Why a subscription account sign-in failed. The UI turns it into plain language. Exception text carries only the category and
 * the error code, never tokens, authorization codes or device codes.
 * [NoSubscription] means the account does not have this subscription; [BadIssuer] means the discovered address is not on an
 * allowed host, so no request is sent.
 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, NoSubscription, Network, BadIssuer }

/** [code] is the server's error code (not a secret). */
class AccountSignInException(
    val reason: AccountSignInFailure,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(buildString {
    append(reason.name)
    if (!code.isNullOrBlank()) append(": ").append(code)
}, cause)

/** The set of tokens obtained from an exchange. Fields the refresh did not return stay null and the caller keeps the old ones. */
data class OauthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSec: Long = 3600,
    val scopes: List<String> = emptyList(),
    /** The API base obtained from the exchange; null if none was returned. */
    val apiBase: String? = null,
)

/** Result of a token exchange. When rejected, a true [terminal] means this authorization can no longer be used: it must be cleared and the user must sign in again. */
sealed interface OauthTokenResult {
    data class Ok(val tokens: OauthTokens) : OauthTokenResult
    data class Rejected(val code: String, val terminal: Boolean) : OauthTokenResult
    data class Failed(val error: ModelError) : OauthTokenResult
}

/**
 * A single account sign-in (currently xAI), stored encrypted as a whole the same way as an API key.
 * [accessToken] is the access token used to call models, [refreshToken] is used to obtain new ones.
 * After signing out the tokens are cleared and the account name is kept for display.
 */
@Serializable
data class AccountSession(
    val provider: String,
    val accountId: String = "",
    val label: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val apiBase: String = "",
    val clientId: String = "",
    val issuer: String = "",
    /** The token URL read from the discovery document at sign-in with its host verified; used for refresh. Sessions saved by older versions lack it, so refresh derives it from the issuer. */
    val tokenUrl: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    fun signedOut() = copy(accessToken = "", refreshToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AccountSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The access token used for calls. When it is about to expire, or the previous one was rejected, a new one is obtained via
 * [refresh]; only one refresh runs at a time.
 * New tokens are [save]d before use; [save] also receives the copy from before the refresh, so storage can tell whether the
 * user signed out or signed in again meanwhile (in which case it must not be written back).
 * If the authorization is no longer usable the tokens are cleared and [ModelError.SignInExpired] is thrown; transient network
 * or server errors are thrown as usual and the tokens are kept.
 */
class AccountTokens(
    /** Which provider's sign-in this is (its name in the UI), told to the user when the login expires. */
    private val account: String,
    private val refresh: suspend (AccountSession) -> OauthTokenResult,
    private val load: () -> AccountSession?,
    private val save: (before: AccountSession, after: AccountSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired(account)
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        when (val r = refresh(s)) {
            is OauthTokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                    apiBase = t.apiBase ?: s.apiBase,
                )
                save(s, next)
                next.accessToken
            }
            is OauthTokenResult.Rejected -> {
                if (r.terminal) {
                    save(s, s.signedOut())
                    throw ModelError.SignInExpired(account)
                }
                throw ModelError.Auth()
            }
            is OauthTokenResult.Failed -> throw r.error
        }
    }
}
