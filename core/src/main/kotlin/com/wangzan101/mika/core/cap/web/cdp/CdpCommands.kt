package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

// Wrappers for just a few common commands, to avoid the chance of mistakes when hand-writing parameter names and unwrapping results; use CdpSession.send directly for the other commands.

/**
 * The parameters [p] of `Page.frameNavigated` say the main frame [frameId] switched documents (once the document changes, all isolated worlds inside it are gone).
 * Changing the address within the same document is a different event and does not count. It does not count when [frameId] is null.
 */
fun mainFrameNavigated(p: JsonObject, frameId: String?): Boolean {
    val frame = p["frame"] as? JsonObject ?: return false
    if (frame["parentId"] != null) return false
    return frameId != null && frame["id"]?.jsonPrimitive?.contentOrNull == frameId
}

/** A script in the page threw an exception. */
class CdpEvaluationException(val details: JsonObject) :
    RuntimeException(
        details["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            ?: details["text"]?.jsonPrimitive?.contentOrNull
            ?: "evaluation failed",
    )

/** `Runtime.evaluate`, returning the RemoteObject (with `returnByValue` the value is in `value`); a script exception throws [CdpEvaluationException]. */
suspend fun CdpSession.evaluate(
    expression: String,
    sessionId: String? = null,
    awaitPromise: Boolean = true,
    returnByValue: Boolean = true,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject {
    val r = send(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", expression)
            put("awaitPromise", awaitPromise)
            put("returnByValue", returnByValue)
        },
        sessionId,
        timeoutMs,
    )
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/** `DOM.getDocument`, returning the root node. [depth] of -1 fetches the whole tree, [pierce] pierces iframes and shadow roots. */
suspend fun CdpSession.getDocument(
    depth: Int = -1,
    pierce: Boolean = true,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject = send(
    "DOM.getDocument",
    buildJsonObject {
        put("depth", depth)
        put("pierce", pierce)
    },
    sessionId,
    timeoutMs,
).getValue("root").jsonObject

/** `Accessibility.getFullAXTree`, returning the flat node list. */
suspend fun CdpSession.getFullAXTree(
    depth: Int? = null,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): List<JsonObject> = send(
    "Accessibility.getFullAXTree",
    buildJsonObject { if (depth != null) put("depth", depth) },
    sessionId,
    timeoutMs,
)["nodes"]?.jsonArray?.map { it.jsonObject }.orEmpty()

/** `Page.captureScreenshot`, returning the decoded image bytes. [format] is png / jpeg / webp. */
suspend fun CdpSession.captureScreenshot(
    format: String = "png",
    quality: Int? = null,
    captureBeyondViewport: Boolean = false,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): ByteArray {
    val r = send(
        "Page.captureScreenshot",
        buildJsonObject {
            put("format", format)
            if (quality != null) put("quality", quality)
            put("captureBeyondViewport", captureBeyondViewport)
        },
        sessionId,
        timeoutMs,
    )
    return Base64.getDecoder().decode(r.getValue("data").jsonPrimitive.content)
}

/** `Input.dispatchMouseEvent`. [type]: mousePressed / mouseReleased / mouseMoved / mouseWheel; coordinates are in CSS pixels. */
suspend fun CdpSession.dispatchMouseEvent(
    type: String,
    x: Double,
    y: Double,
    button: String = "none",
    clickCount: Int = 0,
    modifiers: Int = 0,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
) {
    send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", type)
            put("x", x * inputScale)
            put("y", y * inputScale)
            put("button", button)
            put("clickCount", clickCount)
            if (modifiers != 0) put("modifiers", modifiers)
        },
        sessionId,
        timeoutMs,
    )
}

/** Left-button single click: press then release. */
suspend fun CdpSession.click(x: Double, y: Double, sessionId: String? = null) {
    dispatchMouseEvent("mousePressed", x, y, "left", 1, sessionId = sessionId)
    dispatchMouseEvent("mouseReleased", x, y, "left", 1, sessionId = sessionId)
}

/** `Network.enable`; `Network.*` events only arrive after this. */
suspend fun CdpSession.enableNetwork(sessionId: String? = null, timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS) {
    send("Network.enable", JsonObject(emptyMap()), sessionId, timeoutMs)
}
