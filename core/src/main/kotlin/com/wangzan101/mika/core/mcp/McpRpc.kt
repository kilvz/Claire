package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteAccess
import com.wangzan101.mika.core.remote.HttpResponse
import com.wangzan101.mika.core.remote.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * MCP JSON-RPC handling (Streamable HTTP transport, replying with a single JSON body, no SSE stream). Both protocol generations are accepted:
 * in the modern generation each request carries its version and client capabilities (the `io.modelcontextprotocol/...` keys in `_meta`, repeated in HTTP headers that must match),
 * with no handshake and no session; the legacy generation does an `initialize` handshake first. The server sends no requests, no notifications and no session id, so the legacy generation is stateless too.
 * Requests reaching here have already passed [RemoteAccess] (token, origin, rate limit, path).
 */
class McpRpc(
    private val tools: McpTaskTools,
    private val serverVersion: String,
    /** Every request that passed the gate and could be read: which client the other side says it is (empty when it does not say). */
    private val onRequest: (remote: java.net.InetAddress, clientName: String?) -> Unit = { _, _ -> },
) {
    fun handle(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return transportError(405, "Only POST is supported on this endpoint", mapOf("Allow" to "POST"))
        val type = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json") return transportError(415, "Content-Type must be application/json")
        req.header("accept")?.lowercase()?.let { a ->
            if ("application/json" !in a && "*/*" !in a && "application/*" !in a && "text/event-stream" !in a) {
                return transportError(406, "Accept must include application/json")
            }
        }
        val msg = runCatching { json.parseToJsonElement(req.body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return error(400, null, PARSE_ERROR, "Parse error")
        if (msg is JsonArray) return error(400, null, INVALID_REQUEST, "Batches are not supported")
        if (msg !is JsonObject || (msg["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") return error(400, null, INVALID_REQUEST, "Not a JSON-RPC 2.0 message")
        val method = (msg["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = msg["id"]
        // Responses sent by the client (we send no requests, so there should be none) and notifications: accept them and reply with no content
        if (method == null) return if ("result" in msg || "error" in msg) HttpResponse.empty(202) else error(400, null, INVALID_REQUEST, "Missing method")
        if (id == null) {
            onRequest(req.remote, null)
            return HttpResponse.empty(202)
        }
        if (id !is JsonPrimitive || id is JsonNull || (!id.isString && id.content.toLongOrNull() == null && id.content.toDoubleOrNull() == null)) {
            return error(400, null, INVALID_REQUEST, "id must be a string or a number")
        }
        val params = when (val p = msg["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return error(400, id, INVALID_PARAMS, "params must be an object")
        }
        val meta = params["_meta"] as? JsonObject
        val bodyVersion = (meta?.get(META_VERSION) as? JsonPrimitive)?.contentOrNull
        return if (bodyVersion != null && method != "initialize") modern(req, id, method, params, meta, bodyVersion)
        else legacy(req, id, method, params)
    }

    /** Modern generation: version, method and tool name are also written in the headers and rejected when they do not match the body; for an unrecognized version, the supported ones are listed. */
    private fun modern(req: HttpRequest, id: JsonElement, method: String, params: JsonObject, meta: JsonObject, version: String): HttpResponse {
        val headerVersion = req.header("mcp-protocol-version")
        if (headerVersion == null) return error(400, id, HEADER_MISMATCH, "Missing MCP-Protocol-Version header")
        if (headerVersion != version) return error(400, id, HEADER_MISMATCH,
            "Header mismatch: MCP-Protocol-Version '$headerVersion' does not match body '$version'")
        if (version !in MODERN_VERSIONS) return unsupported(id, version)
        val headerMethod = req.header("mcp-method") ?: return error(400, id, HEADER_MISMATCH, "Missing Mcp-Method header")
        if (headerMethod != method) return error(400, id, HEADER_MISMATCH, "Header mismatch: Mcp-Method '$headerMethod' does not match body '$method'")
        if (method == "tools/call") {
            val bodyName = (params["name"] as? JsonPrimitive)?.contentOrNull
            val headerName = req.header("mcp-name")?.let(::decodeHeader) ?: return error(400, id, HEADER_MISMATCH, "Missing Mcp-Name header")
            if (headerName != bodyName) return error(400, id, HEADER_MISMATCH, "Header mismatch: Mcp-Name does not match body")
        }
        if (meta[META_CAPABILITIES] !is JsonObject) return error(400, id, INVALID_PARAMS, "Missing $META_CAPABILITIES in _meta")
        onRequest(req.remote, clientName(meta[META_CLIENT]))
        val result = when (method) {
            "server/discover" -> buildJsonObject {
                putJsonArray("supportedVersions") { (MODERN_VERSIONS + LEGACY_VERSIONS).forEach { add(it) } }
                put("capabilities", capabilities())
                put("instructions", INSTRUCTIONS)
            }
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> buildJsonObject { put("tools", tools.definitions()) }
            "tools/call" -> when (val r = callTool(params)) {
                is Call.Done -> r.result
                is Call.Bad -> return error(400, id, INVALID_PARAMS, r.message)
            }
            else -> return error(404, id, METHOD_NOT_FOUND, "Method not found: $method")
        }
        val complete = buildJsonObject {
            put("resultType", "complete")
            result.forEach { (k, v) -> put(k, v) }
            if (method in CACHEABLE) {
                put("ttlMs", LIST_TTL_MS)
                put("cacheScope", "private")
            }
            putJsonObject("_meta") { put(META_SERVER, serverInfo()) }
        }
        return ok(id, complete)
    }

    /** Legacy generation: handshake first (choosing a version both sides accept), then calls as usual; no session id is sent. */
    private fun legacy(req: HttpRequest, id: JsonElement, method: String, params: JsonObject): HttpResponse {
        val headerVersion = req.header("mcp-protocol-version")
        if (headerVersion != null && method != "initialize" && headerVersion !in LEGACY_VERSIONS) {
            // The header names a modern-generation version, but the body does not carry the _meta that generation requires
            return if (headerVersion in MODERN_VERSIONS) error(400, id, INVALID_PARAMS, "Missing _meta.$META_VERSION")
            else unsupported(id, headerVersion)
        }
        val result = when (method) {
            "initialize" -> {
                onRequest(req.remote, clientName(params["clientInfo"]))
                val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                buildJsonObject {
                    put("protocolVersion", if (asked in LEGACY_VERSIONS) asked!! else LEGACY_VERSIONS.first())
                    put("capabilities", capabilities())
                    put("serverInfo", serverInfo())
                    put("instructions", INSTRUCTIONS)
                }
            }
            "ping" -> { onRequest(req.remote, null); JsonObject(emptyMap()) }
            "tools/list" -> { onRequest(req.remote, null); buildJsonObject { put("tools", tools.definitions()) } }
            "tools/call" -> {
                onRequest(req.remote, null)
                when (val r = callTool(params)) {
                    is Call.Done -> r.result
                    is Call.Bad -> return error(200, id, INVALID_PARAMS, r.message)
                }
            }
            else -> return error(200, id, METHOD_NOT_FOUND, "Method not found: $method")
        }
        return ok(id, result)
    }

    private sealed interface Call {
        data class Done(val result: JsonObject) : Call
        data class Bad(val message: String) : Call
    }

    /** Unrecognized tool name or arguments that are not an object: protocol error. Wrong argument contents or task not found: tool execution error (isError), left to the model on the other side to fix. */
    private fun callTool(params: JsonObject): Call {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Call.Bad("params.name must be a string")
        if (!tools.has(name)) return Call.Bad("Unknown tool: $name")
        val args = when (val a = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> return Call.Bad("params.arguments must be an object")
        }
        val out = try {
            runBlocking { tools.call(name, args) }
        } catch (e: McpTaskTools.ToolError) {
            return Call.Done(toolResult(e.message ?: "error", null, isError = true))
        } catch (e: Exception) {
            return Call.Done(toolResult("Claire could not do that: ${e.message ?: e::class.simpleName}", null, isError = true))
        }
        return Call.Done(toolResult(out.toString(), out, isError = false))
    }

    private fun toolResult(text: String, structured: JsonObject?, isError: Boolean) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        structured?.let { put("structuredContent", it) }
        put("isError", isError)
    }

    private fun capabilities() = buildJsonObject { putJsonObject("tools") { put("listChanged", false) } }
    private fun serverInfo() = buildJsonObject { put("name", "mika"); put("title", "Claire"); put("version", serverVersion) }

    private fun clientName(info: JsonElement?): String? =
        ((info as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.take(64)

    private fun unsupported(id: JsonElement?, requested: String) = HttpResponse.json(400, buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        putJsonObject("error") {
            put("code", UNSUPPORTED_VERSION)
            put("message", "Unsupported protocol version")
            putJsonObject("data") {
                putJsonArray("supported") { (MODERN_VERSIONS + LEGACY_VERSIONS).forEach { add(it) } }
                put("requested", requested)
            }
        }
    }.toString())

    private fun ok(id: JsonElement, result: JsonObject) =
        HttpResponse.json(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())

    companion object {
        const val PATH = "/mcp"
        const val MODERN = "2026-07-28"
        val MODERN_VERSIONS = listOf(MODERN)
        /** Newest first; during the handshake, if the version the other side asks for is not supported, the first one is given. */
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")

        /**
         * The modern generation requires the complete results of these methods to carry cache hints (`ttlMs`, `cacheScope`); without them the client rejects the whole result.
         * The tool list depends on whether device capabilities are attached on this phone and is not the same for everyone, so it may only be reused for the same token (private), and stays fresh for 5 minutes.
         */
        val CACHEABLE = setOf("server/discover", "tools/list")
        const val LIST_TTL_MS = 300_000L

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val HEADER_MISMATCH = -32020
        const val UNSUPPORTED_VERSION = -32022

        const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT = "io.modelcontextprotocol/clientInfo"
        const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val META_SERVER = "io.modelcontextprotocol/serverInfo"

        const val INSTRUCTIONS = "Claire is an AI agent running on the user's Android phone. Hand it whole tasks with mika_run_task " +
            "(it operates the phone's apps and browser itself), then follow them with mika_wait_task. Answer its questions with mika_answer. " +
            "Payments, confirmations and steps handed to the user can only be done by the user on the phone."

        private val json = Json { ignoreUnknownKeys = true }

        private fun error(status: Int, id: JsonElement?, code: Int, message: String) = HttpResponse.json(status, buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            putJsonObject("error") { put("code", code); put("message", message) }
        }.toString())

        /** Requests stopped at the gate: the HTTP status code says it clearly, and the body is a JSON-RPC error without an id. */
        fun transportError(status: Int, message: String, headers: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse.json(status, buildJsonObject {
                put("jsonrpc", "2.0")
                putJsonObject("error") { put("code", INVALID_REQUEST); put("message", message) }
            }.toString(), headers)

        /** Values that do not fit in a header are written as `=?base64?...?=` and decoded before comparison. */
        fun decodeHeader(v: String): String? =
            if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11) {
                runCatching { String(Base64.getDecoder().decode(v.substring(9, v.length - 2)), Charsets.UTF_8) }.getOrNull()
            } else v
    }
}
