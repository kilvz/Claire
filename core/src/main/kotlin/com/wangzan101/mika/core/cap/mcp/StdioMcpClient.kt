package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A long-lived stdio process; started by the host (the phone's Linux sandbox), not exec'd directly by the App. */
interface McpStdioProcess : Closeable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
}

/** Starts a stdio server; [workspace] is the conversation workspace inside the sandbox, [environment] is the resolved explicit environment (secrets reach the process this way, not via argv). */
fun interface McpStdioLauncher {
    suspend fun launch(workspace: String, argv: List<String>, environment: Map<String, String>): McpStdioProcess
}

/**
 * MCP's stdio transport (newline-delimited JSON-RPC): write requests to stdin, read responses from stdout, and
 * read stderr without parsing it.
 *
 * The process must be started inside the Linux sandbox rather than exec'd directly by the App process: a stdio
 * server is a third-party program installed by the user and should not get read access to the App's private
 * directories (tokens, conversations). The [McpStdioLauncher] here is implemented by the App's sandbox.
 *
 * Like the other transports: the catalog is frozen at connect time, `tools/call` is never retried automatically,
 * and closing kills the whole process tree.
 */
class StdioMcpEndpoint private constructor(
    override val serverId: String,
    private val process: McpStdioProcess,
    private val pending: ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>,
    private val ids: AtomicLong,
    private val scope: CoroutineScope,
    private val tools: List<McpToolDescriptor>,
    private val resources: List<McpResourceDescriptor>,
    private val prompts: List<McpPromptDescriptor>,
    private val timeoutSec: Int,
    val serverName: String?,
    override val instructions: String?,
) : McpEndpoint, Closeable {

    override fun tools(): List<McpToolDescriptor> = tools
    override fun resources(): List<McpResourceDescriptor> = resources
    override fun prompts(): List<McpPromptDescriptor> = prompts

    override suspend fun call(name: String, arguments: JsonObject): McpResult {
        if (tools.none { it.name == name }) return McpResult("unknown MCP tool: $name", isError = true)
        val result = try {
            rpc("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        } catch (e: McpClientException) {
            return McpResult(e.message.orEmpty(), isError = true)
        } catch (e: IOException) {
            return McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        return runCatching { mcpToolResult(result) }
            .getOrElse { McpResult("MCP server returned an unreadable result: ${it.message ?: it.javaClass.simpleName}", isError = true) }
    }

    override suspend fun readResource(uri: String): McpResult = try {
        mcpResourceResult(rpc("resources/read", buildJsonObject { put("uri", uri) }))
    } catch (e: McpClientException) {
        McpResult(e.message.orEmpty(), isError = true)
    } catch (e: IOException) {
        McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    override suspend fun getPrompt(name: String, arguments: JsonObject): McpResult = try {
        mcpPromptResult(rpc("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }))
    } catch (e: McpClientException) {
        McpResult(e.message.orEmpty(), isError = true)
    } catch (e: IOException) {
        McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            write(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString())
            val message = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
                ?: throw McpClientException("MCP server did not answer $method in time")
            message["error"]?.let { throw McpClientException("MCP server rejected $method: ${mcpErrorMessage(it)}") }
            return message["result"] as? JsonObject ?: throw McpClientException("MCP server returned no result for $method")
        } finally {
            pending.remove(id)
        }
    }

    private fun write(text: String) {
        try {
            synchronized(process.stdin) {
                process.stdin.write((text + "\n").toByteArray(Charsets.UTF_8))
                process.stdin.flush()
            }
        } catch (e: IOException) {
            throw McpClientException("MCP stdio process is not accepting input: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { process.close() }
        val error = IOException("MCP stdio connection closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val MAX_LINE_CHARS = 4 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Start the process and complete the initialize / tools/list handshake. The command is executed directly
         * as argv (not through a shell), started inside the sandbox by [launcher]; [workspace] is the conversation
         * ID inside the sandbox.
         */
        suspend fun connect(
            serverId: String,
            command: List<String>,
            launcher: McpStdioLauncher,
            workspace: String,
            environment: Map<String, String> = emptyMap(),
            timeoutSec: Int = 60,
            clientName: String = "Claire",
            clientVersion: String = "1",
            protocolVersion: String = PROTOCOL_VERSION,
        ): Result<StdioMcpEndpoint> {
            require(command.isNotEmpty()) { "stdio command is empty" }
            require(timeoutSec in 1..3600) { "timeoutSec must be 1..3600" }
            return try {
                Result.success(connectUnsafe(serverId, command, launcher, workspace, environment, timeoutSec, clientName, clientVersion, protocolVersion))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private suspend fun connectUnsafe(
            serverId: String,
            command: List<String>,
            launcher: McpStdioLauncher,
            workspace: String,
            environment: Map<String, String>,
            timeoutSec: Int,
            clientName: String,
            clientVersion: String,
            protocolVersion: String,
        ): StdioMcpEndpoint {
            val process = launcher.launch(workspace, command, environment)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
            scope.launch { readMessages(process.stdout, pending) }
            scope.launch { drainQuietly(process.stderr) }
            try {
                val ids = AtomicLong(0)
                suspend fun write(text: String) {
                    try {
                        synchronized(process.stdin) {
                            process.stdin.write((text + "\n").toByteArray(Charsets.UTF_8))
                            process.stdin.flush()
                        }
                    } catch (e: IOException) {
                        throw McpClientException("MCP stdio process is not accepting input: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                suspend fun request(method: String, params: JsonObject): JsonObject {
                    val id = ids.incrementAndGet()
                    val deferred = CompletableDeferred<JsonObject>()
                    pending[id] = deferred
                    try {
                        write(buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("method", method)
                            put("params", params)
                        }.toString())
                        val message = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
                            ?: throw McpClientException("MCP server did not answer $method in time")
                        message["error"]?.let { throw McpClientException("MCP server rejected $method: ${mcpErrorMessage(it)}") }
                        return message["result"] as? JsonObject
                            ?: throw McpClientException("MCP server returned no result for $method")
                    } finally {
                        pending.remove(id)
                    }
                }
                suspend fun notify(method: String) {
                    write(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", method)
                        putJsonObject("params") {}
                    }.toString())
                }

                val handshake = mcpHandshake(
                    requestedProtocol = protocolVersion,
                    clientName = clientName,
                    clientVersion = clientVersion,
                    request = { method, params -> request(method, params) },
                    notify = { method -> notify(method) },
                )
                return StdioMcpEndpoint(
                    serverId = serverId,
                    process = process,
                    pending = pending,
                    ids = ids,
                    scope = scope,
                    tools = handshake.tools,
                    resources = handshake.resources,
                    prompts = handshake.prompts,
                    timeoutSec = timeoutSec,
                    serverName = handshake.serverName,
                    instructions = handshake.instructions,
                )
            } catch (e: Throwable) {
                scope.cancel()
                runCatching { process.close() }
                throw e
            }
        }

        private suspend fun readMessages(input: InputStream, pending: ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>) {
            try {
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = readLine(reader) ?: break
                    if (line.isBlank()) continue
                    val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                    val id = (message["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: continue
                    pending.remove(id)?.complete(message)
                }
                throw McpClientException("MCP stdio server closed its output")
            } catch (e: Throwable) {
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
            }
        }

        private suspend fun drainQuietly(input: InputStream) {
            runCatching {
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (readLine(reader) != null) Unit
            }
        }

        /** Read one line, up to [MAX_LINE_CHARS]; error out beyond that so a malicious server cannot exhaust memory with one enormous line. */
        private fun readLine(reader: Reader): String? {
            val out = StringBuilder()
            var any = false
            while (true) {
                val c = reader.read()
                if (c < 0) return if (any) out.toString() else null
                any = true
                if (c == '\n'.code) return out.toString()
                if (c != '\r'.code) {
                    if (out.length >= MAX_LINE_CHARS) throw IOException("MCP stdio line too long")
                    out.append(c.toChar())
                }
            }
        }
    }
}

/**
 * Split a command string into argv: supports single/double quotes and backslash escapes, performs no variable
 * expansion, and does not go through a shell.
 * Throws [McpClientException] when a quote is left unclosed.
 */
fun parseStdioCommand(line: String): List<String> {
    val out = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false
    fun flush() {
        if (started || token.isNotEmpty()) {
            out += token.toString()
            token.clear()
            started = false
        }
    }
    for (c in line) {
        when {
            escaped -> {
                token.append(c)
                started = true
                escaped = false
            }
            c == '\\' && quote != '\'' -> {
                escaped = true
                started = true
            }
            quote != null -> {
                if (c == quote) quote = null else token.append(c)
                started = true
            }
            c == '\'' || c == '"' -> {
                quote = c
                started = true
            }
            c.isWhitespace() -> flush()
            else -> {
                token.append(c)
                started = true
            }
        }
    }
    if (escaped) token.append('\\')
    if (quote != null) throw McpClientException("unclosed quote in stdio command")
    flush()
    return out
}
