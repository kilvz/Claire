package com.wangzan101.mika.core.cap.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Unified parsing of server tool descriptions; both HTTP/SSE transports share the same risk-annotation reader. */
internal fun mcpToolDescriptor(o: JsonObject): McpToolDescriptor {
    val annotations = o["annotations"] as? JsonObject
    return McpToolDescriptor(
        name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
        annotations = McpAnnotations(
            readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            destructiveHint = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull,
            openWorldHint = (annotations?.get("openWorldHint") as? JsonPrimitive)?.booleanOrNull,
        ),
        estimatedCostUnits = (o["estimatedCostUnits"] as? JsonPrimitive)?.longOrNull ?: 0,
    )
}

/** The text of a JSON-RPC error; takes only the server message, never the body. */
internal fun mcpErrorMessage(error: kotlinx.serialization.json.JsonElement?, fallback: String = "JSON-RPC error"): String =
    (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: fallback

internal fun mcpResourceDescriptor(o: JsonObject): McpResourceDescriptor = McpResourceDescriptor(
    uri = (o["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    mimeType = (o["mimeType"] as? JsonPrimitive)?.contentOrNull,
)

internal fun mcpPromptDescriptor(o: JsonObject): McpPromptDescriptor = McpPromptDescriptor(
    name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    arguments = (o["arguments"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
        (element as? JsonObject)?.let { arg ->
            McpPromptArgument(
                name = (arg["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                description = (arg["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                required = (arg["required"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }.orEmpty(),
)

/** contents[] of resources/read; blobs get a placeholder only, so binary data is not stuffed into the context. */
internal fun mcpResourceResult(result: JsonObject): McpResult {
    val contents = result["contents"] as? kotlinx.serialization.json.JsonArray
    val text = contents?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val text = (item["text"] as? JsonPrimitive)?.contentOrNull
        if (text != null) return@mapNotNull text
        val mime = (item["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
        val blob = (item["blob"] as? JsonPrimitive)?.contentOrNull
        if (blob != null) "[blob: $mime, ${blob.length} base64 chars]"
        else (item["uri"] as? JsonPrimitive)?.contentOrNull
    }?.joinToString("\n").orEmpty()
    return McpResult(text.ifEmpty { result.toString() })
}

/** description + messages[] of prompts/get; roles and text are joined into one readable paragraph. */
internal fun mcpPromptResult(result: JsonObject): McpResult {
    val description = (result["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val messages = result["messages"] as? kotlinx.serialization.json.JsonArray
    val body = messages?.mapNotNull { element ->
        val message = element as? JsonObject ?: return@mapNotNull null
        val role = (message["role"] as? JsonPrimitive)?.contentOrNull ?: "message"
        val content = message["content"] as? JsonObject ?: return@mapNotNull null
        when ((content["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> "$role: " + (content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            "resource" -> {
                val resource = content["resource"] as? JsonObject
                "$role: " + ((resource?.get("text") as? JsonPrimitive)?.contentOrNull
                    ?: (resource?.get("uri") as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            "image" -> "$role: [image: " + ((content["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "") + "]"
            else -> null
        }
    }?.joinToString("\n").orEmpty()
    return McpResult(listOfNotNull(description.takeIf { it.isNotBlank() }, body.takeIf { it.isNotBlank() })
        .joinToString("\n"))
}

/** content[], structuredContent, and cost of tools/call; shared by all three transports. */
internal fun mcpToolResult(result: JsonObject): McpResult {
    val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
    val content = result["content"] as? kotlinx.serialization.json.JsonArray
    val text = content?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull
            "resource" -> {
                val resource = item["resource"] as? JsonObject
                (resource?.get("text") as? JsonPrimitive)?.contentOrNull
                    ?: (resource?.get("uri") as? JsonPrimitive)?.contentOrNull
            }
            "image" -> (item["mimeType"] as? JsonPrimitive)?.contentOrNull?.let { "[image: $it]" } ?: "[image]"
            else -> null
        }
    }?.joinToString("\n").orEmpty()
    val structured = result["structuredContent"]
    val full = if (text.isNotEmpty()) text else structured?.toString().orEmpty()
    val cost = (result["_meta"] as? JsonObject)?.get("costUnits")?.jsonPrimitive?.longOrNull ?: 0L
    return McpResult(full, isError = isError, costUnits = cost)
}
