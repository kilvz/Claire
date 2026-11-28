package com.wangzan101.mika.settings

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.cap.mcp.HttpMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.LegacySseMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpOAuth
import com.wangzan101.mika.core.cap.mcp.McpOAuthException
import com.wangzan101.mika.core.cap.mcp.McpOAuthTokens
import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.core.cap.mcp.McpPresetResolver
import com.wangzan101.mika.core.cap.mcp.McpStdioLauncher
import com.wangzan101.mika.core.cap.mcp.StdioMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.parseStdioCommand
import com.wangzan101.mika.core.cap.mcp.McpClientException
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.model.oauth.LoopbackCallback
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.mcp.McpPackageConsent
import com.wangzan101.mika.mcp.McpPackageProvisioner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One external MCP server configuration; the token is not part of this object and is stored separately, encrypted. */
@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** restricted | trust-annotations */
    val trust: String = "restricted",
    val timeoutSec: Int = 60,
    val previewChars: Int = 4000,
    val maxCostUnits: Long? = null,
    val allowLocalHttp: Boolean = false,
    /** streamable-http | http-sse (legacy 2024-11-05) | stdio (local process inside the sandbox) */
    val transport: String = "streamable-http",
    /** Command line for stdio; it does not go through a shell and is executed directly as argv. */
    val command: String = "",
    /** token | oauth (token means a manually supplied Bearer/custom header; oauth goes through OAuth 2.1 sign-in) */
    val auth: String = "token",
    /** Expose only these tools; null means all of them. */
    val allowedTools: List<String>? = null,
    /** Non-secret custom request headers; Authorization is stored separately. */
    val headers: Map<String, String> = emptyMap(),
    /** Preset id (from the catalog); null means a custom configuration. */
    val presetId: String? = null,
    /** Secret-free connection snapshot; catalog updates do not change the address, transport, auth destination or command of an existing configuration. */
    val presetSnapshot: McpPreset? = null,
    /** Stores only non-secret field values; secret fields go through [McpFieldSecrets]. */
    val fieldValues: Map<String, String> = emptyMap(),
)

data class McpHealth(val ok: Boolean, val message: String?, val tools: List<String>, val atMs: Long)

data class McpConnections(
    val connections: List<McpConnection>,
    val close: () -> Unit,
    val failures: List<Pair<String, String>> = emptyList(),
)

data class McpTestResult(val serverName: String?, val instructions: String?, val tools: List<String>)

/**
 * Settings for external MCP servers: configurations are stored in `files/settings/mcp-servers.json`, tokens are stored separately, encrypted with [KeyCipher].
 * [connectionsFor] connects all enabled entries when a task starts (the whole phase has an overall time limit); failures and timeouts do not affect other tasks or other servers.
 */
class McpServerStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val http: OkHttpClient = NetProxy.shared.client(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS),
    ),
    /** stdio servers can only be launched by the sandbox; when null, stdio configurations report as unavailable. */
    private val stdio: McpStdioLauncher? = null,
    /** Built-in preset catalog; tests inject a temporary catalog, app asset loading is wired up elsewhere. The UI reads the catalog from the same instance. */
    val catalog: McpCatalog = McpCatalog.Empty,
    /** Installation/caching when a stdio preset includes a package; when null, such presets report as unavailable. */
    private val packageProvisioner: McpPackageProvisioner? = null,
    /** Download consent for stdio packages; confirmed "package name + exact version + source" triples are persisted, so they stay valid after reinstalling the app and need no repeated confirmation. */
    val packageConsent: McpPackageConsent = McpPackageConsent(File(dir, "mcp-package-consent.json")),
) {
    private val file = File(dir, "mcp-servers.json")
    private val secretsFile = File(dir, "mcp-server-secrets.bin")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val oauth = McpOAuth(http)
    private val tokenJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fieldSecrets = McpFieldSecrets(::loadSecrets, ::saveSecrets)
    private val resolver = McpPresetResolver()
    private val _health = MutableStateFlow<Map<String, McpHealth>>(emptyMap())
    /** Result of the most recent connection/test; in-memory only, never written to disk. */
    val health: StateFlow<Map<String, McpHealth>> = _health.asStateFlow()

    @Synchronized fun add(name: String, url: String, token: String? = null): Result<McpServerConfig> =
        add(McpServerConfig(name = name.trim(), url = url.trim()), token)

    @Synchronized fun add(config: McpServerConfig, token: String? = null): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value + config
        saveConfigs(next)
        _servers.value = next
        if (!token.isNullOrBlank()) setToken(config.id, token)
        return Result.success(config)
    }

    @Synchronized fun update(config: McpServerConfig): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val next = _servers.value.map { if (it.id == config.id) config else it }
        saveConfigs(next)
        _servers.value = next
        return Result.success(config)
    }

    @Synchronized fun remove(id: String) {
        val next = _servers.value.filterNot { it.id == id }
        saveConfigs(next)
        _servers.value = next
        val secrets = loadSecrets().toMutableMap()
        val hadToken = secrets.remove(id) != null
        val hadOAuth = secrets.remove("oauth:$id") != null
        if (hadToken || hadOAuth) saveSecrets(secrets)
        fieldSecrets.clear(id)
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val next = _servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveConfigs(next)
        _servers.value = next
    }

    @Synchronized fun token(id: String): String? = loadSecrets()[id]

    @Synchronized fun setToken(id: String, token: String?) {
        val secrets = loadSecrets().toMutableMap()
        if (token.isNullOrBlank()) secrets.remove(id) else secrets[id] = token.trim()
        saveSecrets(secrets)
    }

    @Synchronized fun secret(configId: String, fieldId: String): String? = fieldSecrets.get(configId, fieldId)

    @Synchronized fun setSecret(configId: String, fieldId: String, value: String?) = fieldSecrets.set(configId, fieldId, value)

    @Synchronized fun secrets(configId: String): Map<String, String> = fieldSecrets.all(configId)

    /** Build a config from a catalog preset and its non-secret field values. Secrets are supplied later. */
    fun presetConfig(presetId: String, values: Map<String, String>): Result<McpServerConfig> {
        val preset = catalog.presets.find { it.id == presetId }
            ?: return Result.failure(IllegalArgumentException("unknown MCP preset $presetId"))
        val secretIds = preset.fields.filter { it.secret }.map { it.id }.toSet()
        val leaked = secretIds.intersect(values.keys)
        if (leaked.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("secret fields must be supplied separately: ${leaked.sorted().joinToString()}"))
        }
        val resolved = resolver.resolveStructure(preset, values)
        val nonSecret = preset.fields.filter { !it.secret }.map { it.id }.toSet()
        return Result.success(
            McpServerConfig(
                name = preset.name,
                url = resolved.url,
                transport = preset.transport,
                command = resolved.argv.joinToString(" "),
                auth = preset.auth,
                headers = preset.headers,
                presetId = preset.id,
                presetSnapshot = preset,
                fieldValues = values.filterKeys { it in nonSecret },
            ),
        )
    }

    /**
     * Adds or replaces by preset id: retries and re-authorization reuse the existing configuration id, so no duplicate entries appear (secrets still belong to the same config id).
     * Reusing the id also avoids accumulating multiple disabled copies when the same preset is added again and again.
     */
    @Synchronized fun savePreset(config: McpServerConfig): Result<McpServerConfig> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val existing = config.presetId?.let { pid -> _servers.value.firstOrNull { it.presetId == pid } }
        val effective = if (existing != null) config.copy(id = existing.id) else config
        val next = if (existing != null) _servers.value.map { if (it.id == existing.id) effective else it } else _servers.value + effective
        saveConfigs(next)
        _servers.value = next
        if (existing != null) dropStaleSecrets(existing, effective)
        return Result.success(effective)
    }

    /**
     * When a preset in the catalog changes shape (e.g. local package → official remote OAuth), replace it under the same id: field secrets not declared by the new snapshot are deleted,
     * otherwise the resolver would report "unknown field" forever; if the connection target (address/transport/auth) changed, tokens issued to the old target are invalidated as well.
     */
    private fun dropStaleSecrets(old: McpServerConfig, new: McpServerConfig) {
        val declared = new.presetSnapshot?.fields?.map { it.id }?.toSet() ?: return
        (fieldSecrets.all(new.id).keys - declared).forEach { fieldSecrets.set(new.id, it, null) }
        val sameTarget = old.url == new.url && old.command == new.command &&
            old.transport == new.transport && old.auth == new.auth
        if (!sameTarget) {
            setToken(new.id, null)
            clearOAuth(new.id)
        }
    }

    /**
     * Connect a preset for the first time: save its config and secrets, then run the normal
     * connection test. On failure the config is kept but disabled, so the user can edit and retry.
     */
    suspend fun connectPreset(
        presetId: String,
        values: Map<String, String>,
        secrets: Map<String, String>,
    ): Result<McpTestResult> {
        val config = presetConfig(presetId, values).getOrElse { return Result.failure(it) }
        val saved = savePreset(config).getOrElse { return Result.failure(it) }
        // Write only the secret fields this preset declares (and only non-blank ones); unknown fields never enter encrypted storage, so the resolver does not keep reporting "unknown field".
        val declared = saved.presetSnapshot?.fields?.filter { it.secret }?.map { it.id }?.toSet()
        secrets.forEach { (fieldId, value) ->
            if (value.isNotBlank() && (declared == null || fieldId in declared)) setSecret(saved.id, fieldId, value)
        }
        val result = test(saved)
        if (result.isFailure) setEnabled(saved.id, false)
        return result
    }

    /** Resolve a saved preset config from its snapshot plus current field secrets (never the live catalog). */
    private fun resolvedPreset(config: McpServerConfig): com.wangzan101.mika.core.cap.mcp.ResolvedMcpPreset? {
        val snapshot = config.presetSnapshot ?: return null
        return resolver.resolve(snapshot, config.fieldValues + fieldSecrets.all(config.id))
    }

    /** Combines the executable-directory prefix returned by the provisioner with the preset's declared argv (the first element is the executable name) into the final argv. */
    private fun composeArgv(prefix: List<String>, command: List<String>): List<String> =
        if (prefix.isEmpty() || command.isEmpty()) command
        else listOf(prefix.first() + "/" + command.first()) + command.drop(1)

    /** Called by the UI after the user confirms the download; binds package name + exact version + source. */
    fun acceptPackage(spec: McpPackageSpec) = packageConsent.accept(spec)

    /** Whether this package (name + exact version + source) has already been confirmed. */
    fun packageAccepted(spec: McpPackageSpec): Boolean = packageConsent.accepted(spec)

    fun validate(config: McpServerConfig): String? {
        if (config.name.isBlank()) return "name is required"
        config.presetSnapshot?.let { snapshot ->
            val secretIds = snapshot.fields.filter { it.secret }.map { it.id }.toSet()
            val leaked = secretIds.intersect(config.fieldValues.keys)
            if (leaked.isNotEmpty()) return "secret fields must not be stored in plain field values: ${leaked.sorted().joinToString()}"
            val declared = snapshot.fields.map { it.id }.toSet()
            val undeclared = config.fieldValues.keys - declared
            if (undeclared.isNotEmpty()) return "undeclared field values: ${undeclared.sorted().joinToString()}"
        } ?: run {
            if (config.fieldValues.isNotEmpty()) return "field values require a preset snapshot"
        }
        if (config.transport == "stdio") {
            if (config.command.isBlank()) return "command is required"
            if (config.command.length > 4000) return "command is too long"
            if (config.command.any { it == '\n' || it == '\u0000' }) return "command must be one line"
            return null
        }
        val url = config.url.toHttpUrlOrNull() ?: return "invalid URL"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL must not contain user info"
        if (isBlockedHost(url.host)) return "this host is not allowed"
        if (url.scheme == "http") {
            if (!config.allowLocalHttp) return "HTTP is only allowed for local/private servers after enabling it"
            if (!isLocalHost(url.host)) return "HTTP is only allowed for local/private hosts"
        } else if (url.scheme != "https") {
            return "URL must be https (or http for a local server)"
        }
        return null
    }

    suspend fun test(config: McpServerConfig, workspace: String = SETTINGS_WORKSPACE): Result<McpTestResult> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val headers = try {
            authHeaders(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        val endpoint = open(config, headers, workspace).getOrElse { e ->
            _health.update { it + (config.id to healthFailure(e)) }
            return Result.failure(e)
        }
        return try {
            val name = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.serverName
                is LegacySseMcpEndpoint -> endpoint.serverName
                is StdioMcpEndpoint -> endpoint.serverName
                else -> null
            }
            val instructions = when (endpoint) {
                is HttpMcpEndpoint -> endpoint.instructions
                is LegacySseMcpEndpoint -> endpoint.instructions
                is StdioMcpEndpoint -> endpoint.instructions
                else -> null
            }
            val result = McpTestResult(name, instructions, endpoint.tools().map { it.name })
            _health.update { it + (config.id to McpHealth(true, null, result.tools, System.currentTimeMillis())) }
            Result.success(result)
        } finally {
            (endpoint as? Closeable)?.close()
        }
    }

    /** Refreshes the status of all enabled servers (in parallel). */
    suspend fun testAll() = coroutineScope {
        _servers.value.filter { it.enabled }.map { config -> async { test(config) } }.awaitAll()
    }

    /**
     * Used at task start: successful connections belong to [owner] (the task's [ResourceScope]) and are closed together when the scope closes at task teardown;
     * a cancellation landing mid-connection or right after connecting but before hand-off does not leak either (the stdio server process and sandbox lease are released along with it).
     */
    suspend fun connectionsFor(
        owner: ResourceScope,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections = owner.acquire(Dispatchers.IO, { c: McpConnections -> c.close() }) { connections(resultStore, workspace, budgetMs) }

    /**
     * Connects all enabled entries in parallel; a single failure is only recorded, never thrown. The whole phase waits at most [budgetMs]: whatever has not connected by then is skipped for this run,
     * recorded as a failure (with the timeout noted in the health status) so it does not hold up the task start; already connected servers are used as usual. On cancellation, connected ones are closed before rethrowing.
     */
    suspend fun connections(
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore? = null,
        workspace: String = SETTINGS_WORKSPACE,
        budgetMs: Long = CONNECT_BUDGET_MS,
    ): McpConnections {
        val enabled = _servers.value.filter { it.enabled }
        val done = ConcurrentHashMap<String, Result<McpConnection>>()
        try {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    enabled.map { config -> async { done[config.id] = attempt(config, resultStore, workspace) } }.awaitAll()
                }
            }
        } catch (e: Throwable) {
            done.values.forEach { r -> r.getOrNull()?.let { (it.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } } }
            throw e
        }
        val late = "did not connect within ${(budgetMs + 999) / 1000} s; skipped for this task"
        val attempts = enabled.map { config ->
            config to (done[config.id] ?: Result.failure<McpConnection>(McpClientException(late)).also {
                _health.update { h -> h + (config.id to McpHealth(false, late, emptyList(), System.currentTimeMillis())) }
            })
        }
        val connected = attempts.mapNotNull { (c, r) -> r.getOrNull()?.let { c to it } }
        val failures = attempts.mapNotNull { (c, r) -> r.exceptionOrNull()?.let { c.id to (it.message ?: it.javaClass.simpleName) } }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return McpConnections(
            connections = connected.map { it.second },
            // Both task teardown and its ResourceScope close this; close only once
            close = {
                if (closed.compareAndSet(false, true)) connected.forEach { (it.second.endpoint as? Closeable)?.let { c -> runCatching { c.close() } } }
            },
            failures = failures,
        )
    }

    /** Connects one server and records its health status. */
    private suspend fun attempt(
        config: McpServerConfig,
        resultStore: com.wangzan101.mika.core.cap.mcp.McpResultStore?,
        workspace: String,
    ): Result<McpConnection> {
        val result = try {
            open(config, if (config.transport == "stdio") emptyMap() else authHeaders(config), workspace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val connection: Result<McpConnection> = result.map { endpoint ->
            McpConnection(
                endpoint = endpoint,
                resultStore = resultStore,
                maxCostUnits = config.maxCostUnits,
                previewChars = config.previewChars,
                trust = if (config.trust == "trust-annotations") McpTrust.TrustAnnotations else McpTrust.Restricted,
                allowedTools = config.allowedTools?.toSet(),
                displayName = config.name,
            )
        }
        _health.update {
            it + (config.id to connection.fold(
                onSuccess = { c -> McpHealth(true, null, c.endpoint.tools().map { t -> t.name }, System.currentTimeMillis()) },
                onFailure = { e -> McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis()) },
            ))
        }
        return connection
    }

    private suspend fun open(
        config: McpServerConfig,
        headers: Map<String, String>,
        workspace: String,
    ): Result<com.wangzan101.mika.core.cap.mcp.McpEndpoint> = try {
        when (config.transport) {
            "stdio" -> {
                val launcher = stdio ?: throw IllegalStateException("stdio MCP servers are not available on this build")
                val resolved = if (config.presetId != null) resolvedPreset(config) else null
                val spec = config.presetSnapshot?.packageSpec
                val argv = if (spec != null) {
                    val provisioner = packageProvisioner
                        ?: throw SandboxError("mcp_package_unavailable", "Package installation is not available on this device.", recoverable = true)
                    composeArgv(
                        provisioner.prepare(workspace, spec, packageConsent.accepted(spec)),
                        resolved?.argv ?: emptyList(),
                    )
                } else {
                    resolved?.argv ?: parseStdioCommand(config.command)
                }
                val environment = resolved?.environment ?: emptyMap()
                StdioMcpEndpoint.connect(
                    serverId = config.id, command = argv, launcher = launcher, workspace = workspace,
                    environment = environment, timeoutSec = config.timeoutSec,
                    clientName = "Claire", clientVersion = BuildConfig.VERSION_NAME,
                ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            }
            "http-sse" -> LegacySseMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Claire", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
            else -> HttpMcpEndpoint.connect(
                serverId = config.id, url = config.url, http = http,
                headers = headers, timeoutSec = config.timeoutSec,
                clientName = "Claire", clientVersion = BuildConfig.VERSION_NAME,
            ).map { it as com.wangzan101.mika.core.cap.mcp.McpEndpoint }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun authHeaders(config: McpServerConfig): Map<String, String> {
        if (config.transport == "stdio") return emptyMap()
        // Preset with declared fields: resolve Bearer, secret headers and non-secret headers from
        // the saved snapshot + current secrets, so no secret ever lives in the plain config.
        if (config.auth == "fields") {
            return resolvedPreset(config)?.headers ?: config.headers
        }
        return buildMap {
            putAll(config.headers)
            if (config.auth == "oauth") {
                var tokens = oauthTokens(config.id) ?: throw McpOAuthException("oauth_login_required")
                if (!tokens.isFresh()) {
                    tokens = oauth.refresh(tokens)
                    setOAuthTokens(config.id, tokens)
                }
                put("Authorization", "Bearer ${tokens.accessToken}")
            } else if (config.auth == "token") {
                token(config.id)?.let { put("Authorization", "Bearer $it") }
            }
        }
    }

    @Synchronized fun oauthTokens(id: String): McpOAuthTokens? =
        loadSecrets()["oauth:$id"]?.let { runCatching { tokenJson.decodeFromString<McpOAuthTokens>(it) }.getOrNull() }

    @Synchronized fun setOAuthTokens(id: String, tokens: McpOAuthTokens) {
        val secrets = loadSecrets().toMutableMap()
        secrets["oauth:$id"] = tokenJson.encodeToString(tokens)
        saveSecrets(secrets)
    }

    @Synchronized fun clearOAuth(id: String) {
        val secrets = loadSecrets().toMutableMap()
        if (secrets.remove("oauth:$id") != null) saveSecrets(secrets)
    }

    /**
     * OAuth 2.1 sign-in: discover the authorization server → dynamically register the loopback callback → open the browser → wait for the callback and exchange it for tokens.
     * [openBrowser] is called once when sign-in starts (the UI opens the system browser); the whole flow waits at most 5 minutes.
     */
    suspend fun authorize(config: McpServerConfig, openBrowser: (String) -> Unit): Result<McpOAuthTokens> {
        validate(config)?.let { return Result.failure(IllegalArgumentException(it)) }
        val serverUrl = config.url.toHttpUrlOrNull() ?: return Result.failure(IllegalArgumentException("invalid URL"))
        return try {
            Result.success(LoopbackCallback.open().use { callback ->
                val server = oauth.discover(serverUrl)
                val redirect = callback.redirectUri(LoopbackCallback.HOST)
                val registration = oauth.register(server, redirect)
                val attempt = oauth.newAttempt(redirect)
                openBrowser(oauth.authorizeUrl(server, registration, attempt, server.resource))
                var tokens: McpOAuthTokens? = null
                var failure: Exception? = null
                callback.await(
                    timeoutMs = 5 * 60_000L,
                    accept = { it["state"] == attempt.state },
                ) { params ->
                    try {
                        val error = oauth.readCallbackError(params, attempt)
                        if (error != null) {
                            failure = error
                            oauthPage(false)
                        } else {
                            tokens = oauth.exchange(server, registration, oauth.readCode(params, attempt), attempt, server.resource)
                            tokens?.let { setOAuthTokens(config.id, it) }
                            oauthPage(true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        oauthPage(false)
                    }
                } ?: throw McpOAuthException("oauth_timeout")
                failure?.let { throw it }
                tokens ?: throw McpOAuthException("oauth_no_token")
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun healthFailure(e: Throwable): McpHealth =
        McpHealth(false, e.message ?: e.javaClass.simpleName, emptyList(), System.currentTimeMillis())

    private fun oauthPage(ok: Boolean): String = if (ok) {
        "<html><body><h3>Claire: signed in</h3><p>Sign-in complete. You can return to Claire.</p></body></html>"
    } else {
        "<html><body><h3>Claire: sign-in failed</h3><p>Sign-in failed. Return to Claire and try again.</p></body></html>"
    }

    private fun loadServers(): List<McpServerConfig> = runCatching {
        if (!file.isFile) emptyList()
        else json.decodeFromString<List<McpServerConfig>>(file.readText())
    }.getOrDefault(emptyList())

    private fun saveConfigs(configs: List<McpServerConfig>) = atomicWrite(file) { json.encodeToString(configs) }

    private fun loadSecrets(): Map<String, String> = runCatching {
        if (!secretsFile.isFile) emptyMap()
        else {
            val text = String(cipher.decrypt(secretsFile.readBytes()), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(text).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
    }.getOrDefault(emptyMap())

    private fun saveSecrets(secrets: Map<String, String>) {
        val text = JsonObject(secrets.mapValues { JsonPrimitive(it.value) }).toString()
        atomicWriteBytes(secretsFile) { cipher.encrypt(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun atomicWrite(target: File, text: () -> String) {
        atomicWriteBytes(target) { text().toByteArray(Charsets.UTF_8) }
    }

    private fun atomicWriteBytes(target: File, bytes: () -> ByteArray) {
        dir.mkdirs()
        AtomicFiles.write(target, bytes(), durable = false)
    }

    private fun isLocalHost(host: String): Boolean = LocalHosts.isPrivateOrMesh(host)

    companion object {
        /** Sandbox workspace used when the settings page tests a stdio server; inside tasks the conversation ID is used. */
        const val SETTINGS_WORKSPACE = "mcp-settings"
        /**
         * Maximum time the start of a task waits while connecting external servers (counted across all servers together). A single server's own timeout may be several minutes,
         * but that is meant for tool calls; the start is only a handshake, so slow ones are skipped for this run instead of leaving the task stuck in "preparing". stdio servers inside the sandbox have to launch PRoot, so a little extra is allowed.
         */
        const val CONNECT_BUDGET_MS = 15_000L
    }

    private fun isBlockedHost(host: String): Boolean =
        host == "169.254.169.254" || host == "metadata.google.internal" ||
            host.startsWith("169.254.") || host == "0.0.0.0" || host == "::" || host == "[::]" ||
            host.startsWith("fd00:ec2:") || host.startsWith("fe80:")
}
