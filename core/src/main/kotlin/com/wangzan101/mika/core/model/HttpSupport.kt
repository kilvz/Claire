package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.run.trace.TraceCall
import com.wangzan101.mika.core.model.usage.ModelHttpAttempts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String, private val onPacket: ((String) -> Unit)? = null) {
    /** Some chat endpoints send a buffered SSE body even when streaming was not requested. */
    fun replay(events: List<String>, accept: (String) -> Unit) {
        for (event in events) {
            onPacket?.invoke(event)
            accept(event)
        }
    }
}

/** HTTP execution shared by the adapters (enqueue + coroutine cancellation) and Retry-After parsing. */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse =
        observe(req) { attempt -> executeRaw(http, req, attempt) }.also(::requireProxyAccepted)

    /** HTTP 407 can only mean a proxy along the way wants credentials (or rejected the ones given), not a reply from the provider. */
    private fun requireProxyAccepted(raw: RawResponse) {
        if (raw.status == 407) throw ModelError.Proxy(ProxyFailure.AuthFailed)
    }

    /** Connection failure: if a proxy was used and the error is in the proxy hop, report it as a proxy problem; anything else still counts as a network failure. */
    fun networkError(http: OkHttpClient, req: Request, e: IOException): ModelError =
        NetProxy.failure(http, req.url, e)?.let { ModelError.Proxy(it, e) } ?: ModelError.Network(e)

    private suspend inline fun observe(req: Request, send: (TraceCall.Attempt?) -> RawResponse): RawResponse {
        val wire = req.tag(InferenceRequest::class.java) ?: return send(null)
        val call = currentCoroutineContext()[TraceCall] ?: return send(null)
        val attempt = call.sending(wire) ?: return send(null)
        val response = send(attempt)
        if (response.status !in 200..299) {
            call.finish("error", null)
        } else if (response.body.isNotEmpty()) {
            attempt.packet(response.body)
            return response.copy(onPacket = attempt::packet)
        }
        return response
    }

    private suspend fun countAttempt(req: Request) {
        if (req.tag(InferenceRequest::class.java) != null) {
            currentCoroutineContext()[ModelHttpAttempts]?.start()
        }
    }

    private suspend fun executeRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?): RawResponse {
        countAttempt(req)
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        attempt?.httpStatus(it.code)
                        try {
                            val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(raw)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                        }
                    }
                }
            })
        }
    }

    /**
     * SSE is delivered event by event; cancelling the coroutine also cancels the HTTP request. [onEvent] returning true means the protocol level has reached
     * its terminal state (e.g. OpenAI's [DONE], Anthropic's message_stop), the read loop ends there and the connection is closed —
     * some servers do not actively close the stream after sending the terminal state (through a tunnel they finish generating but hang silently until the read timeout),
     * so waiting for EOF forever is not an option. Callers still judge by their own protocol whether the stream ended properly; an incomplete stream counts as [ModelError.StreamCut].
     * A successful response with no events and no event-stream format (e.g. the URL points at a web page) counts as [ModelError.BadResponse].
     */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Boolean): RawResponse =
        observe(req) { attempt -> streamRaw(http, req, attempt, onEvent) }.also(::requireProxyAccepted)

    private suspend fun streamRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?, onEvent: (String) -> Boolean): RawResponse {
        countAttempt(req)
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        attempt?.httpStatus(it.code)
                        try {
                            if (it.code !in 200..299) {
                                val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                                if (cont.isActive) cont.resume(error)
                                return
                            }
                            if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                                if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                                return
                            }
                            val source = it.body?.source() ?: throw ModelError.BadResponse("The streaming response is empty")
                            val data = mutableListOf<String>()
                            var events = 0
                            /** Dispatch one event; returning true means the protocol level has reached its terminal state (e.g. [DONE]), the read loop ends there and no longer waits for the connection to close. */
                            fun dispatch(): Boolean {
                                if (data.isEmpty()) return false
                                events++
                                val event = data.joinToString("\n")
                                attempt?.packet(event)
                                val terminal = onEvent(event)
                                data.clear()
                                return terminal
                            }
                            while (cont.isActive) {
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.isEmpty() -> if (dispatch()) break
                                    line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                                }
                            }
                            if (cont.isActive) {
                                dispatch()
                                val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                                if (events == 0 && !eventStream) throw ModelError.BadResponse("The response is not streaming content (Content-Type: ${it.header("Content-Type").orEmpty()})")
                                cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                        }
                    }
                }
            })
        }
    }

    private val RETRY_AFTER_BODY = Regex("""(?:try again in|retry after|wait|retry in)\s+(\d+(?:\.\d+)?)\s*(?:s|sec|second)""", RegexOption.IGNORE_CASE)

    fun retryAfterMs(header: String?, body: String? = null): Long? {
        header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        if (body != null) {
            RETRY_AFTER_BODY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        }
        return null
    }
}
