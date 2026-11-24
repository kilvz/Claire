package com.wangzan101.mika.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * The piece shared by the various external JSON APIs (web search, web fetch): sending the request, mapping common HTTP statuses to failures reported to the model, and reading JSON.
 * Provider-specific statuses are recognized first by [call]'s special: it may be a failure or a result (for example the 422 that Jina returns on zero hits).
 * [service] only feeds the wording shown to the model ("search service", "fetch service").
 */
internal object JsonHttp {
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private val JSON_TYPE = "application/json".toMediaType()
    private const val USER_AGENT = "Claire (+https://github.com/kilvz/Claire)"

    /** Why a call failed; the caller converts it into its own failure type. */
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null)

    fun json(body: JsonObject): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    /**
     * Returns [T]: special gets first look at the response (if it returns a result, that result is used), then the HTTP status is mapped to a failure, otherwise the JSON is read and handed to parse.
     * An unreachable network goes through [failure] as well.
     */
    suspend fun <T> call(
        client: OkHttpClient,
        request: Request,
        service: String,
        timeoutSec: Int? = null,
        special: (Response) -> T? = { null },
        failure: (Failure) -> T,
        parse: (JsonObject) -> T,
    ): T {
        val r = request.newBuilder().header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(r).awaitBody(timeoutSec) { response ->
                special(response)?.let { return@awaitBody it }
                failureOf(response, service)?.let { return@awaitBody failure(it) }
                val body = response.body?.charStream()?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        // Read one extra character to detect the limit, avoiding a wait for an oversized body to download completely.
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - text.length + 1))
                        if (count == -1) break
                        if (text.length + count > MAX_RESPONSE_CHARS) {
                            return@awaitBody failure(Failure("invalid_response", "The $service service response is too large."))
                        }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty()
                val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: IllegalArgumentException) { null }
                    ?: return@awaitBody failure(Failure("invalid_response", "The $service service did not return a JSON object."))
                parse(root)
            }
        } catch (e: IOException) {
            failure(Failure("offline", "The $service service could not be reached; check the network and proxy."))
        }
    }

    private fun failureOf(response: Response, service: String): Failure? = when (response.code) {
        in 200..299 -> null
        401, 403 -> Failure("authentication_failed", "The $service service rejected the configured API key.")
        402 -> Failure("quota_exhausted", "The $service service reports that its quota or credits are exhausted.")
        429 -> Failure(
            "rate_limited", "The $service service is rate limiting requests; retry later or use the browser.",
            response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0),
        )
        else -> Failure("service_unavailable", "The $service service returned HTTP ${response.code}.")
    }

    /** Reads a string field; absent, non-string and all-whitespace values all become the empty string. */
    fun text(o: JsonObject, name: String): String = (o[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}