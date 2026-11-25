package com.wangzan101.mika.core.model.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Signs in with an OpenCode Go subscription through OpenCode Console's device code grant, then calls OpenCode Go's inference
 * gateway directly with the access token.
 *
 * The client identity is OpenCode's public command-line client (`opencode-cli`); no client secret is involved. The device code and
 * token endpoints live on [HOST], and the verification page is returned as a relative path that is resolved against [consoleBase]
 * and checked against the allowed hosts before it is opened. Refresh tokens go to the same token endpoint.
 *
 * Unlike xAI, a model list assigns each model its own protocol and base URL, so calls may use any of Claire's four adapters; the
 * access token is handed to each adapter in the header that protocol expects.
 */
class OpenCodeAuth(
    private val http: OkHttpClient,
    private val consoleBase: String = CONSOLE_BASE,
    private val clientId: String = CLIENT_ID,
    /** Hosts allowed for the device code, token, identity and verification endpoints. Only opencode.ai by default; tests swap in the local machine. */
    private val allowedHosts: Set<String> = setOf(HOST),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = consoleBase.trimEnd('/')
    private val deviceUrl = "$root/console/auth/device/code"
    private val tokenUrl = "$root/console/auth/device/token"

    suspend fun signIn(
        openBrowser: (String) -> Unit,
        onChallenge: (DeviceChallenge) -> Unit,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
        clock: () -> Long = now,
    ): AccountSession {
        // OpenCode has no scope to request; org-wide access is declared with its own flag instead
        val client = DeviceCodeClient(
            http = http, deviceUrl = deviceUrl, tokenUrl = tokenUrl, clientId = clientId,
            allowedHosts = allowedHosts, extraDeviceForm = mapOf(EXTRA_ORG_SCOPE to "true"),
        )
        val grant = client.start().also { published ->
            val challenge = published.challenge.withAbsoluteUrls()
            onChallenge(challenge)
            openBrowser(challenge.verificationUriComplete ?: challenge.verificationUri)
        }
        val tokens = awaitDeviceGrant(grant, clock, sleep) { client.poll(it) }
        val who = identity(tokens.accessToken)
        return AccountSession(
            provider = PROVIDER,
            accountId = who.first.ifBlank { "opencode" },
            label = who.second,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            expiresAtMs = clock() + tokens.expiresInSec * 1000,
            scopes = tokens.scopes,
            apiBase = API_BASE,
            clientId = clientId,
            issuer = root,
            tokenUrl = tokenUrl,
        )
    }

    /** Exchanges the refresh token for a new set. The address follows the one recorded at sign-in; the host must still be on the allow list. */
    suspend fun refresh(session: AccountSession): OauthTokenResult {
        if (session.refreshToken.isEmpty()) return OauthTokenResult.Rejected("no_refresh_token", terminal = true)
        val url = try {
            OauthUrls.check(session.tokenUrl.ifBlank { tokenUrl }, allowedHosts)
        } catch (e: AccountSignInException) {
            return OauthTokenResult.Rejected(e.code ?: "host", terminal = true)
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", session.refreshToken)
            .add("client_id", session.clientId.ifBlank { clientId })
            .build()
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return OauthTokenResult.Failed(e)
        }
        if (raw.status in 500..599) return OauthTokenResult.Failed(ModelError.Server(raw.status))
        val body = jsonObject(raw.body)
        val err = body?.str("error")
        if (raw.status != 200 || err != null || body == null) {
            val code = err ?: "http_${raw.status}"
            return OauthTokenResult.Rejected(code, terminal = code in TERMINAL_REFRESH || raw.status == 401)
        }
        val access = body.str("access_token") ?: return OauthTokenResult.Rejected("no_access_token", terminal = false)
        return OauthTokenResult.Ok(
            OauthTokens(
                accessToken = access,
                refreshToken = body.str("refresh_token"),
                expiresInSec = (body["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: 3600,
                scopes = body.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
                apiBase = API_BASE,
            ),
        )
    }

    /**
     * The verification page comes back as a path such as `/console/device`; resolve it against [consoleBase] and keep only
     * addresses whose host is allowed. The complete address already carries the user code.
     */
    private fun DeviceChallenge.withAbsoluteUrls(): DeviceChallenge {
        val verify = absolute(verificationUri)
        if (!OauthUrls.allowed(verify, allowedHosts)) throw AccountSignInException(AccountSignInFailure.BadIssuer, "verify")
        val complete = verificationUriComplete?.let(::absolute)?.takeIf { OauthUrls.allowed(it, allowedHosts) }
        return copy(verificationUri = verify, verificationUriComplete = complete)
    }

    private fun absolute(uri: String): String {
        val trimmed = uri.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else root + "/" + trimmed.trimStart('/')
    }

    /** Identity for display only: `GET /console/api/user` when it answers; empty when it does not. Never used for authorization. */
    private suspend fun identity(accessToken: String): Pair<String, String> {
        val raw = try {
            HttpSupport.execute(
                http,
                Request.Builder().url("$root/console/api/user").header("Accept", "application/json")
                    .header("Authorization", "Bearer $accessToken").get().build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "" to ""
        }
        if (raw.status != 200) return "" to ""
        val root = jsonObject(raw.body) ?: return "" to ""
        // The console wraps payloads in `data` or `user` in some versions; accept either, otherwise read the object itself
        val user = root.obj("data")?.obj("user") ?: root.obj("user") ?: root
        val id = user.str("id") ?: user.str("sub") ?: user.str("userId").orEmpty()
        val label = user.str("email") ?: user.str("username") ?: user.str("name") ?: user.str("displayName").orEmpty()
        return id to label
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    companion object {
        const val PROVIDER = "opencode-go"
        const val HOST = "opencode.ai"
        const val CONSOLE_BASE = "https://opencode.ai"
        /** The public client of OpenCode's command-line tool; a device flow needs no client secret. */
        const val CLIENT_ID = "opencode-cli"
        /** The listing base for OpenCode Go; each model in the list carries the base URL of the protocol Claire should use. */
        const val API_BASE = "https://opencode.ai/zen/go/v1"
        /** OpenCode Go requires a stable per-conversation session ID in this header. */
        const val SESSION_HEADER = "x-opencode-session"
        private const val EXTRA_ORG_SCOPE = "supports_org_scope"

        private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
