package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class McpHandshakeInfo(
    val protocolVersion: String,
    val serverName: String?,
    val instructions: String?,
    val tools: List<McpToolDescriptor>,
    val resources: List<McpResourceDescriptor>,
    val prompts: List<McpPromptDescriptor>,
)

/**
 * initialize → notifications/initialized → tools/list → resources/list, prompts/list.
 * Shared only by transports that use a long-lived JSON-RPC connection (legacy SSE, stdio); resources/prompts are
 * optional capabilities, and if the server claims support but errors out they degrade to empty.
 */
internal suspend fun mcpHandshake(
    requestedProtocol: String,
    clientName: String,
    clientVersion: String,
    request: suspend (String, JsonObject) -> JsonObject,
    notify: suspend (String) -> Unit,
): McpHandshakeInfo {
    val init = request("initialize", buildJsonObject {
        put("protocolVersion", requestedProtocol)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
            put("name", clientName)
            put("version", clientVersion)
        }
    })
    val negotiated = (init["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: requestedProtocol
    val serverInfo = init["serverInfo"] as? JsonObject
    val serverName = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull
    val instructions = (init["instructions"] as? JsonPrimitive)?.contentOrNull
    notify("notifications/initialized")

    val tools = mcPages(request, "tools/list", "tools", MAX_PAGES) { mcpToolDescriptor(it) }
    val caps = init["capabilities"] as? JsonObject
    val resources = if (caps?.get("resources") is JsonObject) {
        optionalPages(request, "resources/list", "resources") { mcpResourceDescriptor(it) }
    } else emptyList()
    val prompts = if (caps?.get("prompts") is JsonObject) {
        optionalPages(request, "prompts/list", "prompts") { mcpPromptDescriptor(it) }
    } else emptyList()
    return McpHandshakeInfo(negotiated, serverName, instructions, tools, resources, prompts)
}

/** tools/list is a required capability, so a paging error fails the connection outright. */
private suspend fun <T> mcPages(
    request: suspend (String, JsonObject) -> JsonObject,
    method: String,
    key: String,
    maxPages: Int,
    parse: (JsonObject) -> T,
): List<T> {
    val out = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    do {
        val result = request(method, buildJsonObject { cursor?.let { put("cursor", it) } })
        (result[key] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { out += parse(it) }
        }
        cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        pages++
    } while (cursor != null && pages < maxPages)
    return out
}

/** resources/prompts are optional: if the server claims support but errors out, return an empty list without affecting the tool connection. */
private suspend fun <T> optionalPages(
    request: suspend (String, JsonObject) -> JsonObject,
    method: String,
    key: String,
    parse: (JsonObject) -> T,
): List<T> = try {
    mcPages(request, method, key, MAX_PAGES, parse)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyList()
}

private const val MAX_PAGES = 20
