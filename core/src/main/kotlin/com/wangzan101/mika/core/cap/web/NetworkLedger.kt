package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.net.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * An XHR/fetch request issued by the page, together with its response. [id] is the protocol requestId (for sub-sessions the key is `sessionId:requestId`);
 * [size] is the decoded byte count (null when unavailable); [body] is present only after the body has been fetched, and [bodyNote] explains why there is no body.
 */
data class CapturedResponse(
    val id: String,
    val url: String,
    val method: String,
    val type: String,
    val status: Int?,
    val mime: String?,
    val size: Long?,
    val fromCache: Boolean,
    val state: State,
    val error: String?,
    val startedMs: Long,
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    val bodyNote: String? = null,
) {
    enum class State(val wire: String) { PENDING("pending"), DONE("done"), FAILED("failed") }
}

/**
 * Network ledger for a single tab (pure logic, does not touch the protocol connection): it is fed `Network.*` events, answers "is anything still in flight" and "how long has it been quiet",
 * and keeps the most recent [maxCaptured] XHR/fetch responses. Bodies are fetched on demand (the caller retrieves them with `Network.getResponseBody` and passes them to [putBody]);
 * only JSON/text is kept, at most [maxBodyBytes] per entry; once the total of all bodies exceeds [maxTotalBodyBytes], the earliest-fetched bodies are dropped first.
 *
 * In-flight counting ignores long-lived connections: event streams, media, beacons, prefetches, and requests not answered within [staleMs] of being sent (long polling).
 * Not thread-safe; the caller has to serialize access.
 */
class NetworkLedger(
    val quietMs: Long = 500,
    val staleMs: Long = 10_000,
    val maxCaptured: Int = 200,
    val maxBodyBytes: Int = 256 * 1024,
    val maxTotalBodyBytes: Int = 2 * 1024 * 1024,
) {
    data class BodyFetch(val requestId: String, val sessionId: String?)

    private class Flight(val startedMs: Long, var longLived: Boolean, val actionSeq: Int)

    private class Entry(
        val id: String,
        val requestId: String,
        val sessionId: String?,
        var url: String,
        var method: String,
        val type: String,
        val startedMs: Long,
        val actionSeq: Int,
    ) {
        var status: Int? = null
        var mime: String? = null
        var decoded = 0L
        var encoded: Long? = null
        var fromCache = false
        var state = CapturedResponse.State.PENDING
        var error: String? = null
        var body: String? = null
        var bodyBytes = 0
        var bodyTruncated = false
    }

    private val flights = HashMap<String, Flight>()
    private val captured = LinkedHashMap<String, Entry>()
    // Order in which bodies were fetched, used to drop the earliest first
    private val bodyOrder = ArrayDeque<String>()
    private var bodyTotal = 0
    private var lastBusyMs = Long.MIN_VALUE / 2
    // Only count requests sent since the current action
    private var lastActionBusyMs = Long.MIN_VALUE / 2
    private var actionSeq = 0

    fun onEvent(method: String, params: JsonObject, now: Long, sessionId: String? = null) {
        val requestId = params.str("requestId") ?: return
        val key = if (sessionId == null) requestId else "$sessionId:$requestId"
        when (method) {
            "Network.requestWillBeSent" -> {
                val request = params["request"] as? JsonObject ?: return
                val url = request.str("url") ?: return
                val type = params.str("type") ?: "Other"
                val m = request.str("method") ?: "GET"
                val f = flights[key]
                if (f == null) {
                    pruneStale(now)
                    flights[key] = Flight(now, type in LONG_LIVED_TYPES, actionSeq)
                }
                val flight = flights.getValue(key)
                if (!flight.longLived) {
                    lastBusyMs = now
                    if (flight.actionSeq == actionSeq) lastActionBusyMs = now
                }
                val e = captured[key]
                if (e != null) {
                    // Redirect: the same requestId is sent again carrying the previous hop's response
                    e.url = url
                    e.method = m
                } else if (type in CAPTURED_TYPES) {
                    captured[key] = Entry(key, requestId, sessionId, url, m, type, now, actionSeq)
                    while (captured.size > maxCaptured) drop(captured.keys.first())
                }
            }
            "Network.requestServedFromCache" -> captured[key]?.fromCache = true
            "Network.responseReceived" -> {
                val r = params["response"] as? JsonObject ?: return
                val mime = r.str("mimeType")
                if (mime != null && mime.lowercase().startsWith(EVENT_STREAM)) flights[key]?.longLived = true
                captured[key]?.apply {
                    status = (r["status"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                    this.mime = mime
                    if (r.bool("fromDiskCache") || r.bool("fromPrefetchCache") || r.bool("fromServiceWorker")) fromCache = true
                }
            }
            "Network.dataReceived" -> captured[key]?.let { it.decoded += params.long("dataLength") ?: 0 }
            "Network.loadingFinished" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.DONE
                    encoded = params.long("encodedDataLength")
                }
            }
            "Network.loadingFailed" -> {
                end(key, now)
                captured[key]?.apply {
                    state = CapturedResponse.State.FAILED
                    error = params.str("errorText")?.takeIf { it.isNotBlank() }
                        ?: if (params.bool("canceled")) "canceled" else params.str("blockedReason") ?: "failed"
                }
            }
        }
    }

    /** Number of requests still in flight that also hold back "network idle". */
    fun inFlight(now: Long): Int = flights.values.count { counts(it, now) }

    /** No request in flight, and at least [quietMs] has passed since the last request started or finished. */
    fun isIdle(now: Long): Boolean = inFlight(now) == 0 && now - lastBusyMs >= quietMs

    /** Called when an action starts: requests sent afterwards count as "since the last action". */
    /**
     * Requests sent during this action (since [markAction]) are still in flight, or the last one finished less than [quietMs] ago: the wait after the action is extended accordingly.
     * Requests already in flight before the action, long-lived connections and long polling do not count, and neither does the page changing by itself all the time.
     */
    fun actionBusy(now: Long): Boolean =
        flights.values.any { it.actionSeq == actionSeq && counts(it, now) } || now - lastActionBusyMs < quietMs

    fun markAction() {
        lastActionBusyMs = Long.MIN_VALUE / 2
        actionSeq++
    }

    fun list(urlContains: String? = null, method: String? = null, sinceLastAction: Boolean = false): List<CapturedResponse> =
        captured.values.filter { e ->
            (urlContains == null || e.url.contains(urlContains, ignoreCase = true)) &&
                (method == null || e.method.equals(method, ignoreCase = true)) &&
                (!sinceLastAction || e.actionSeq == actionSeq)
        }.map { view(it) }

    fun get(id: String): CapturedResponse? = captured[id]?.let { view(it) }

    /** Supplies the protocol parameters when the body should be fetched; null when it was already fetched or must not be fetched (not text, too large, not finished) — see [CapturedResponse.bodyNote] for the reason. */
    fun bodyToFetch(id: String): BodyFetch? {
        val e = captured[id] ?: return null
        if (e.body != null || noBodyReason(e) != null) return null
        return BodyFetch(e.requestId, e.sessionId)
    }

    fun putBody(id: String, body: String, base64Encoded: Boolean) {
        val e = captured[id] ?: return
        forgetBody(e)
        val text = if (base64Encoded) runCatching { String(Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrNull() ?: return else body
        var kept = text
        var bytes = kept.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBodyBytes) {
            kept = cutToBytes(kept, maxBodyBytes)
            bytes = kept.toByteArray(Charsets.UTF_8).size
            e.bodyTruncated = true
        }
        e.body = kept
        e.bodyBytes = bytes
        bodyOrder.addLast(id)
        bodyTotal += bytes
        while (bodyTotal > maxTotalBodyBytes && bodyOrder.size > 1) {
            captured[bodyOrder.removeFirst()]?.let(::forgetBody)
        }
    }

    fun totalBodyBytes(): Int = bodyTotal

    private fun counts(f: Flight, now: Long) = !f.longLived && now - f.startedMs < staleMs

    private fun end(key: String, now: Long) {
        val f = flights.remove(key) ?: return
        if (counts(f, now)) {
            lastBusyMs = now
            if (f.actionSeq == actionSeq) lastActionBusyMs = now
        }
    }

    private fun pruneStale(now: Long) {
        if (flights.size < MAX_FLIGHTS) return
        flights.entries.removeAll { now - it.value.startedMs >= staleMs }
    }

    private fun drop(key: String) {
        captured.remove(key)?.let(::forgetBody)
    }

    private fun forgetBody(e: Entry) {
        if (e.body == null) return
        bodyTotal -= e.bodyBytes
        bodyOrder.remove(e.id)
        e.body = null
        e.bodyBytes = 0
        e.bodyTruncated = false
    }

    private fun size(e: Entry): Long? = e.decoded.takeIf { it > 0 } ?: e.encoded?.takeIf { it > 0 }

    private fun noBodyReason(e: Entry): String? = when {
        e.state == CapturedResponse.State.FAILED -> "failed"
        e.state != CapturedResponse.State.DONE -> "not_finished"
        !isText(e.mime) -> "not_text"
        (size(e) ?: 0) > maxBodyBytes -> "too_large"
        else -> null
    }

    private fun view(e: Entry) = CapturedResponse(
        id = e.id, url = e.url, method = e.method, type = e.type, status = e.status, mime = e.mime, size = size(e),
        fromCache = e.fromCache, state = e.state, error = e.error, startedMs = e.startedMs,
        body = e.body, bodyTruncated = e.bodyTruncated, bodyNote = if (e.body == null) noBodyReason(e) else null,
    )

    companion object {
        /** Only requests issued by page scripts are captured. */
        private val CAPTURED_TYPES = setOf("XHR", "Fetch")
        /** Requests that never end by nature, or that the page does not wait for: they do not hold back "network idle". */
        private val LONG_LIVED_TYPES = setOf("EventSource", "WebSocket", "Media", "Ping", "Prefetch", "CSPViolationReport")
        private const val EVENT_STREAM = "text/event-stream"
        private const val MAX_FLIGHTS = 512

        fun isText(mime: String?): Boolean {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return false
            return m.startsWith("text/") || m.endsWith("json") || m.endsWith("+json") || m.endsWith("xml") || m == "application/javascript"
        }

        private fun cutToBytes(s: String, max: Int): String {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + n > max) break
                bytes += n
                i += Character.charCount(cp)
            }
            return s.substring(0, i)
        }

        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.toLong()
    }
}
