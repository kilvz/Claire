package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.oauth.AccountSession
import com.wangzan101.mika.core.model.oauth.AccountTokens
import com.wangzan101.mika.core.model.oauth.OauthTokenResult
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.ListedModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.wangzan101.mika.settings.ProviderStore.Companion.CHATGPT_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.OPENROUTER_NAME
import com.wangzan101.mika.settings.ProviderStore.Companion.OPENROUTER_SIGN_IN
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_ACCOUNT
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// Provider storage: ChatGPT, xAI and OpenRouter account sign-in and token refresh
/** ChatGPT provider sign-in (stored in the same place as the API key, encrypted the same way); null when absent or undecryptable. */
fun ProviderStore.chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

/** The connected ChatGPT provider (there is only one). */
fun ProviderStore.chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

/** Sign-in succeeded: stores the sign-in and the listed models; if a ChatGPT provider already exists, that one is updated. */
fun ProviderStore.connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider = synchronized(this) {
    connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models) }

/**
 * Sign out: first revoke the refresh token on the server (clear it locally anyway if that cannot be confirmed), then clear the token on this device; the registration (client_id) and account are kept and reused on the next sign-in.
 * Returns whether the server confirmed the revocation. The provider, its models and its roles are left untouched.
 */
suspend fun ProviderStore.signOutChatGpt(providerId: String): Boolean {
    val s = chatGptSession(providerId) ?: return true
    val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
    saveChatGptSession(providerId, s.signedOut())
    return revoked
}

/**
 * This device's host identifier on ChatGPT's side and the client_id it registered. The identifier is generated on first use and never changes afterwards; the client_id is recorded once registered,
 * kept after signing out (reused on the next sign-in, so no re-registration), and forgotten when the ChatGPT provider is deleted. Neither is a secret, so both are stored in plaintext.
 */
fun ProviderStore.chatGptRegistration(): Pair<String, String?> {
    return synchronized(this) {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }
}

/** Records the registered client_id (recorded before the token exchange, so a failed exchange does not force a re-registration next time). */
fun ProviderStore.rememberChatGptClient(clientId: String) {
    synchronized(this) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }
}

internal fun ProviderStore.saveChatGptSession(providerId: String, session: ChatGptSession) {
    synchronized(this) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }
}

internal fun ProviderStore.tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
    chatGptTokens.getOrPut(providerId) {
        ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
    }
}

/** Subscription account sign-in (stored in the same place as the API key, encrypted the same way); null when absent or undecryptable. */
fun ProviderStore.accountSession(providerId: String): AccountSession? = AccountSession.fromJson(providerKey(providerId))

fun ProviderStore.xaiProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.Xai }

/**
 * Sign-in succeeded: stores the sign-in and the listed models. An account sign-in for one vendor has only one provider, so signing in again updates it.
 */
fun ProviderStore.connectAccount(session: AccountSession, models: List<ListedModel>): Provider {
    return synchronized(this) {
        val (name, kind, base) = when (session.provider) {
            XaiAuth.PROVIDER -> Triple(XAI_NAME, ProviderConfig.Kind.Xai, XaiAuth.API_BASE)
            else -> throw IllegalArgumentException("unknown account provider")
        }
        val existing = _providers.value.firstOrNull { it.kind == kind }
        if (existing == null) return connect(name, kind, base, session.toJson(), models)
        val p = existing.copy(name = name, baseUrl = base, models = models)
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        _configs.value = _configs.value.map { if (it.providerId == p.id) it.copy(baseUrl = base, name = name) else it }
        setKey(p.id, session.toJson())
        writeConfig()
        return p
    }
}

/** Sign out: clears only the token on this phone. Authorization inside the xAI account has to be disconnected by the user in xAI's settings. */
fun ProviderStore.signOutAccount(providerId: String) {
    synchronized(this) {
        val s = accountSession(providerId) ?: return
        saveAccount(providerId, s.signedOut())
    }
}

internal fun ProviderStore.saveAccount(providerId: String, session: AccountSession) {
    synchronized(this) {
        if (provider(providerId) == null) return
        setKey(providerId, session.toJson())
        val base = session.apiBase
        if (base.isNotBlank() && provider(providerId)?.baseUrl != base) {
            _providers.value = _providers.value.map { if (it.id == providerId) it.copy(baseUrl = base) else it }
            _configs.value = _configs.value.map { if (it.providerId == providerId) it.copy(baseUrl = base) else it }
            writeConfig()
        }
    }
}

/** The provider connected via OpenRouter account authorization; null when there is none. */
fun ProviderStore.openRouterAccount(): Provider? = _providers.value.firstOrNull { it.signIn == OPENROUTER_SIGN_IN }

/**
 * Authorization produced a key: store it as an ordinary OpenAI-compatible provider following the OpenRouter preset (the key is encrypted as usual), marked as obtained via authorization.
 * If that authorized provider already exists it is updated; an OpenRouter entry where the user entered the key themselves is left alone and the two coexist.
 */
fun ProviderStore.connectOpenRouter(apiKey: String, models: List<ListedModel>): Provider = synchronized(this) {
    connect(OPENROUTER_NAME, ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, apiKey, models, signIn = OPENROUTER_SIGN_IN) }

/** Signs out of OpenRouter: deletes the key on this phone; the provider, models and roles are kept. The key on OpenRouter's side has to be deleted by the user on the Keys page. */
fun ProviderStore.signOutOpenRouter(providerId: String) {
    synchronized(this) {
        if (provider(providerId)?.signIn == OPENROUTER_SIGN_IN && providerId in keys) setKey(providerId, "")
    }
}

/**
 * Writes back the tokens obtained from a refresh, but only if the stored sign-in has not changed in the meantime: a token exchange waits on the network, and the user may have signed out or signed in again by then,
 * in which case the stale refresh result must not overwrite it (otherwise a signed-out account would be quietly signed back in, or a fresh sign-in would be displaced by an old token).
 */
internal fun ProviderStore.saveRefreshed(providerId: String, before: AccountSession, after: AccountSession) {
    synchronized(this) {
        val now = accountSession(providerId) ?: return
        if (now.refreshToken != before.refreshToken || now.accessToken != before.accessToken) return
        saveAccount(providerId, after)
    }
}

internal fun ProviderStore.accountTokensFor(providerId: String): AccountTokens = synchronized(accountTokens) {
    accountTokens.getOrPut(providerId) {
        AccountTokens(
            account = XAI_ACCOUNT,
            refresh = { session ->
                when (session.provider) {
                    XaiAuth.PROVIDER -> xaiAuth.refresh(session)
                    else -> OauthTokenResult.Rejected("unknown", terminal = true)
                }
            },
            load = { accountSession(providerId) },
            save = { before, after -> saveRefreshed(providerId, before, after) },
        )
    }
}
