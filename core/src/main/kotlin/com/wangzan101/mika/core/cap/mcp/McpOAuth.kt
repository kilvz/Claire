package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.model.oauth.Pkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** OAuth failure; the message only carries the error category/code, never a token or response body. */
class McpOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Random values for one OAuth login. [redirectUri] is the local callback address. */
data class McpOAuthAttempt(
    val state: String,
    val verifier: String,
    val redirectUri: String,
) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/** Endpoints of the authorization server (RFC 8414 / RFC 9728 discovery result). */
data class McpOAuthServer(
    val authorizationEndpoint: HttpUrl,
    val tokenEndpoint: HttpUrl,
    val registrationEndpoint: HttpUrl?,
    val resource: HttpUrl?,
    val scope: String? = null,
)

/** Public client obtained via dynamic registration (RFC 7591). */
data class McpOAuthRegistration(val clientId: String, val clientSecret: String?)

/** Encrypted-at-rest tokens; refreshing only needs tokenEndpoint, clientId, and refreshToken. */
@Serializable
data class McpOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpoint: String,
    val resource: String? = null,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs == null || nowMs < expiresAtMs - 60_000
}

/**
 * OAuth 2.1 client for MCP (RFC 9728 resource metadata → RFC 8414 authorization server metadata → RFC 7591
 * dynamic registration → PKCE authorization code).
 *
 * Public clients only: registration uses `token_endpoint_auth_method=none` and no secret is stored (a few servers
 * still hand out a client_secret, which is passed along as-is).
 * All endpoints must be HTTPS; HTTP for local/private addresses is accepted only in tests and localhost
 * development. Tokens go only into memory/encrypted storage, never into logs.
 */
class McpOAuth(
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = http.newBuilder()
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Discover the authorization server from the MCP server address; when there is no resource metadata, fall back to the server's own origin. */
    suspend fun discover(serverUrl: HttpUrl): McpOAuthServer {
        val resourceMetadata = fetchMetadata(
            listOfNotNull(
                serverUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource").query(null).build(),
                serverUrl.newBuilder().encodedPath("/.well-known/oauth-protected-resource" + serverUrl.encodedPath).query(null).build()
                    .takeIf { serverUrl.encodedPath.isNotEmpty() && serverUrl.encodedPath != "/" },
            ),
        ) ?: probeResourceMetadata(serverUrl)
        val authServer = (resourceMetadata?.get("authorization_servers") as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?.toHttpUrlOrNull()
            ?: serverUrl.newBuilder().encodedPath("/").query(null).build()
        val metadata = fetchMetadata(authServerMetadataUrls(authServer)) ?: throw McpOAuthException("oauth_server_metadata_missing")
        val authorization = metadata["authorization_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw McpOAuthException("oauth_authorization_endpoint_missing")
        val token = metadata["token_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
            ?: throw McpOAuthException("oauth_token_endpoint_missing")
        requireSafeEndpoint(authorization)
        requireSafeEndpoint(token)
        val registration = metadata["registration_endpoint"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()?.also(::requireSafeEndpoint)
        val resource = (resourceMetadata?.get("resource") as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() ?: serverUrl
        val scopes = (resourceMetadata?.get("scopes_supported") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotEmpty() }
        return McpOAuthServer(authorization, token, registration, resource, scopes)
    }

    /** Dynamically register a public client; the redirect URI is the local callback. */
    suspend fun register(server: McpOAuthServer, redirectUri: String, clientName: String = "Claire"): McpOAuthRegistration {
        val endpoint = server.registrationEndpoint ?: throw McpOAuthException("oauth_registration_unsupported")
        requireSafeEndpoint(endpoint)
        val body = JsonObject(
            mapOf(
                "client_name" to JsonPrimitive(clientName),
                "redirect_uris" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(redirectUri))),
                "grant_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))),
                "response_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
                "token_endpoint_auth_method" to JsonPrimitive("none"),
            ),
        )
        val (status, text) = postJson(endpoint, body.toString(), "application/json")
        if (status !in 200..299) throw McpOAuthException("oauth_registration_http_$status")
        val root = parseObject(text) ?: throw McpOAuthException("oauth_registration_invalid")
        val clientId = root["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpOAuthException("oauth_registration_no_client")
        return McpOAuthRegistration(clientId, root["client_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
    }

    /** Authorization page URL (PKCE S256 + state + RFC 8707 resource). */
    fun authorizeUrl(
        server: McpOAuthServer,
        registration: McpOAuthRegistration,
        attempt: McpOAuthAttempt,
        resource: HttpUrl?,
    ): String = server.authorizationEndpoint.newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", registration.clientId)
        .addQueryParameter("redirect_uri", attempt.redirectUri)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", attempt.state)
        .apply { resource?.let { addQueryParameter("resource", it.toString()) } }
        .apply { server.scope?.let { addQueryParameter("scope", it) } }
        .build().toString()

    /** Exchange the authorization code for tokens. */
    suspend fun exchange(
        server: McpOAuthServer,
        registration: McpOAuthRegistration,
        code: String,
        attempt: McpOAuthAttempt,
        resource: HttpUrl?,
    ): McpOAuthTokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", attempt.redirectUri)
            .add("client_id", registration.clientId)
            .add("code_verifier", attempt.verifier)
            .apply { resource?.let { add("resource", it.toString()) } }
            .build()
        return readTokens(postForm(server.tokenEndpoint, form), registration, server.tokenEndpoint, resource)
    }

    /** Exchange the refresh token for a new access token; if the server issues no new refresh token, keep the old one. */
    suspend fun refresh(tokens: McpOAuthTokens): McpOAuthTokens {
        val refresh = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: throw McpOAuthException("oauth_refresh_missing")
        val endpoint = tokens.tokenEndpoint.toHttpUrlOrNull()?.also(::requireSafeEndpoint)
            ?: throw McpOAuthException("oauth_token_endpoint_invalid")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", tokens.clientId)
            .apply { tokens.clientSecret?.let { add("client_secret", it) } }
            .apply { tokens.resource?.let { add("resource", it) } }
            .build()
        val registration = McpOAuthRegistration(tokens.clientId, tokens.clientSecret)
        val next = readTokens(postForm(endpoint, form), registration, endpoint, tokens.resource?.toHttpUrlOrNull())
        return next.copy(refreshToken = next.refreshToken ?: refresh)
    }

    /** User denied, or the callback carries an error: only accept it when state matches. */
    fun readCallbackError(params: Map<String, String>, attempt: McpOAuthAttempt): McpOAuthException? {
        val state = params["state"]
        if (state != attempt.state) return McpOAuthException("oauth_state_mismatch")
        val code = params["code"]?.takeIf { it.isNotBlank() }
        if (code != null) return null
        return McpOAuthException("oauth_denied_" + (params["error"]?.take(40) ?: "no_code"))
    }

    /** Take the authorization code from the callback; state must match. */
    fun readCode(params: Map<String, String>, attempt: McpOAuthAttempt): String {
        if (params["state"] != attempt.state) throw McpOAuthException("oauth_state_mismatch")
        return params["code"]?.takeIf { it.isNotBlank() } ?: throw McpOAuthException("oauth_no_code")
    }

    fun newAttempt(redirectUri: String) =
        McpOAuthAttempt(Pkce.state(), Pkce.verifier(), redirectUri)

    private suspend fun readTokens(
        response: OAuthHttp,
        registration: McpOAuthRegistration,
        tokenEndpoint: HttpUrl,
        resource: HttpUrl?,
    ): McpOAuthTokens {
        if (response.code !in 200..299) {
            val error = runCatching { parseObject(response.body)?.get("error")?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw McpOAuthException("oauth_token_http_${response.code}" + (error?.let { "_$it" } ?: ""))
        }
        val root = parseObject(response.body) ?: throw McpOAuthException("oauth_token_invalid")
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpOAuthException("oauth_token_missing")
        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull
        return McpOAuthTokens(
            accessToken = access,
            refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            expiresAtMs = expiresIn?.takeIf { it > 0 }?.let { now() + it * 1000 - 30_000 },
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            tokenEndpoint = tokenEndpoint.toString(),
            resource = resource?.toString(),
        )
    }

    /**
     * Addresses of the authorization server metadata, tried in the order the MCP spec prescribes. When the issuer
     * has a path (such as `https://auth.example.com/tenant1`), RFC 8414 inserts well-known between the host and the
     * path, while OpenID first inserts it the same way and then appends it after the path per OIDC Discovery; with
     * no path these are just the two locations at the root.
     */
    private fun authServerMetadataUrls(issuer: HttpUrl): List<HttpUrl> {
        val path = issuer.encodedPath.trimEnd('/')
        fun at(p: String) = issuer.newBuilder().encodedPath(p).query(null).build()
        return listOfNotNull(
            at("/.well-known/oauth-authorization-server$path"),
            at("/.well-known/openid-configuration$path"),
            if (path.isEmpty()) null else at("$path/.well-known/openid-configuration"),
        )
    }

    private suspend fun fetchMetadata(urls: List<HttpUrl>): JsonObject? {
        for (url in urls) {
            if (url.isHttps) Unit
            else if (isLoopbackHost(url.host)) Unit
            else throw McpOAuthException("oauth_insecure_endpoint")
            val response = try {
                get(url)
            } catch (e: IOException) {
                continue
            }
            if (response.code !in 200..299) continue
            return parseObject(response.body)
        }
        return null
    }

    /** When resource metadata is not at the well-known location, look for `resource_metadata` in the 401's WWW-Authenticate header per the MCP spec. */
    private suspend fun probeResourceMetadata(serverUrl: HttpUrl): JsonObject? {
        val response = try {
            executeRaw(
                Request.Builder().url(serverUrl)
                    .header("Accept", "application/json, text/event-stream").get().build(),
            )
        } catch (e: IOException) {
            return null
        }
        if (response.code != 401) return null
        val header = response.headers["WWW-Authenticate"] ?: return null
        val match = Regex("""resource_metadata=(?:"([^"]+)"|([^,\s]+))""").find(header) ?: return null
        val url = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toHttpUrlOrNull() ?: return null
        return fetchMetadata(listOf(url))
    }

    private fun requireSafeEndpoint(url: HttpUrl) {
        if (url.isHttps || isLoopbackHost(url.host)) return
        throw McpOAuthException("oauth_insecure_endpoint")
    }

    private suspend fun get(url: HttpUrl): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json").get().build(),
    )

    private suspend fun postForm(url: HttpUrl, form: FormBody): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json").post(form).build(),
    )

    private suspend fun postJson(url: HttpUrl, body: String, type: String): OAuthHttp = executeRaw(
        Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody(type.toMediaType())).build(),
    )

    private suspend fun executeRaw(request: Request): OAuthHttp = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { OAuthHttp(it.code, it.body?.string().orEmpty(), it.headers) }
    }

    private data class OAuthHttp(val code: Int, val body: String, val headers: okhttp3.Headers)

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

}
