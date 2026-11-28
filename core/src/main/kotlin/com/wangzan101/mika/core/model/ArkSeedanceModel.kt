package com.wangzan101.mika.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Base64

/**
 * Volcengine Ark Seedance adapter (API per the Ark documentation "Create / query / cancel or delete video generation tasks"):
 * POST `/contents/generations/tasks` submits (parameters in the body, strictly validated); GET `.../tasks/{id}` polls,
 * statuses queued / running / succeeded / failed / cancelled / expired; on success `content.video_url` is a download URL valid for 24 hours, handed to the caller for immediate download.
 *
 * Billing safety:
 * - Submission is not retried automatically (OkHttp connection-failure retries are off); if the connection drops after the request is sent, throw [VideoModelError.Unconfirmed] so the caller does not treat it as not submitted.
 * - The request carries `execution_expires_after` (the documentation's minimum, 1 hour): a task abandoned locally is terminated by the provider if it queues too long, rather than running days later.
 * - Polling has an overall limit [timeoutMs]; occasional network / 5xx / rate-limit errors are tolerated [MAX_POLL_ERRORS] times, so one blip does not abandon a task that is already being billed.
 * - When waiting stops after submission due to cancellation, timeout or error, make a best-effort DELETE: the documentation states that only queued tasks can be cancelled (and stop being billed),
 *   running tasks cannot be cancelled and will finish on the provider side; DELETE on a terminal task only deletes the record. A failed DELETE does not affect the original result.
 */
class ArkSeedanceModel(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    override val capabilities: VideoCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val pollMs: Long = 5_000,
    private val timeoutMs: Long = 30 * 60_000,
) : VideoModel {
    override val name: String get() = model
    private val root = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    /** Client used for submission: OkHttp does not silently retry connection failures, so one generation does not create two paid tasks. */
    private val submitHttp = http.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun generate(request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit): VideoOutput {
        check(request)
        val jobId = submit(request)
        val outcome = try {
            onProgress(VideoJobStage.Submitted)
            withTimeoutOrNull(timeoutMs) { poll(jobId, onProgress) }
        } catch (e: CancellationException) {
            abandon(jobId)
            throw e
        } catch (e: Exception) {
            if (!e.isTerminal()) abandon(jobId)
            throw e
        }
        if (outcome == null) {
            abandon(jobId)
            throw VideoModelError.Timeout("video job did not finish within ${timeoutMs / 60_000} minutes")
        }
        return VideoOutput(
            url = outcome.url, jobId = jobId,
            durationMs = (outcome.durationSec ?: request.durationSec).toLong() * 1000L,
        )
    }

    /** Build the submission request with the body shape from the documentation; unit tests check it directly. */
    internal fun submitBody(request: VideoRequest): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
            request.firstFrame?.let { bytes ->
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        val mime = (request.firstFrameMime ?: "image/jpeg").lowercase()
                        put("url", "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
                    }
                    put("role", "first_frame")
                })
            }
        }
        put("ratio", if (request.firstFrame != null && capabilities.firstFrameKeepsAspect) ADAPTIVE else request.aspect)
        put("duration", request.durationSec)
        put("resolution", request.resolution)
        if (capabilities.supportsAudio) put("generate_audio", true)
        put("execution_expires_after", EXPIRES_AFTER_SEC)
    }

    private suspend fun submit(request: VideoRequest): String {
        val req = Request.Builder().url("$root/contents/generations/tasks")
            .withHeaders(headers)
            .headerOrReject("Authorization", "Bearer $apiKey")
            .post(submitBody(request).toString().toRequestBody(jsonType))
            .build()
        val submitted = try {
            HttpSupport.execute(submitHttp, req)
        } catch (e: ModelError.Network) {
            // Being unable to connect (DNS failure, connection refused, no route) means the request was not sent; other disconnects (timeout, read halfway) leave it unknown whether the provider created a task
            if (e.cause.isNotSent()) throw e
            throw VideoModelError.Unconfirmed(e)
        }
        if (submitted.status !in 200..299) throw ModelHttp.openAiError(submitted.status, submitted.retryAfter, submitted.body, model)
        return parseJobId(submitted.body)
    }

    private class Done(val url: String, val durationSec: Int?)

    private suspend fun poll(jobId: String, onProgress: suspend (VideoJobStage) -> Unit): Done {
        var last: VideoJobStage = VideoJobStage.Submitted
        var errors = 0
        while (true) {
            delay(pollMs)
            val r = try {
                HttpSupport.execute(http, task(jobId).get().build())
            } catch (e: ModelError) {
                if (e.isTransient() && ++errors <= MAX_POLL_ERRORS) continue
                throw e
            }
            if (r.status !in 200..299) {
                val error = ModelHttp.openAiError(r.status, r.retryAfter, r.body, model)
                if (error.isTransient() && ++errors <= MAX_POLL_ERRORS) continue
                throw error
            }
            errors = 0
            val task = parseTask(r.body)
            when (val status = (task["status"] as? JsonPrimitive)?.contentOrNull) {
                "succeeded" -> {
                    val url = ((task["content"] as? JsonObject)?.get("video_url") as? JsonPrimitive)?.contentOrNull
                        ?: throw VideoModelError.BadResponse("no content.video_url")
                    return Done(url, (task["duration"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 })
                }
                "failed" -> throw VideoModelError.Failed(parseError(task))
                "cancelled" -> throw VideoModelError.Failed("video task was cancelled")
                "expired" -> throw VideoModelError.Timeout("video task expired")
                else -> {
                    val stage = status?.let(::stageOf)
                    if (stage != null && stage != last) {
                        last = stage
                        onProgress(stage)
                    }
                }
            }
        }
    }

    /** Best-effort cancellation: does not wait long, is not interrupted by cancellation, and does not throw on failure. */
    private suspend fun abandon(jobId: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_TIMEOUT_MS) { runCatching { HttpSupport.execute(http, task(jobId).delete().build()) } }
        }
    }

    private fun task(jobId: String): Request.Builder = Request.Builder().url("$root/contents/generations/tasks/$jobId")
        .withHeaders(headers)
        .headerOrReject("Authorization", "Bearer $apiKey")

    private fun check(request: VideoRequest) {
        if (request.prompt.isBlank()) throw VideoModelError.Unsupported("prompt is empty")
        if (request.durationSec !in capabilities.durations) throw VideoModelError.Unsupported("duration ${request.durationSec}s is not supported")
        if (request.aspect !in capabilities.aspects) throw VideoModelError.Unsupported("aspect ${request.aspect} is not supported")
        if (request.resolution !in capabilities.resolutions) throw VideoModelError.Unsupported("resolution ${request.resolution} is not supported")
        if (request.firstFrame != null && !capabilities.supportsFirstFrame) throw VideoModelError.Unsupported("this model does not support a first frame")
    }

    private fun parseJobId(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad submit response") }
        return (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw VideoModelError.BadResponse("no task id")
    }

    private fun parseTask(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad poll response") }

    private fun parseError(task: JsonObject): String {
        val error = task["error"] as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        return listOfNotNull(code, message).joinToString(": ").take(300).ifBlank { "video task failed" }
    }

    companion object {
        private const val CANCEL_TIMEOUT_MS = 5_000L
        private const val MAX_POLL_ERRORS = 5
        private const val ADAPTIVE = "adaptive"

        /** `execution_expires_after` ranges from [3600, 259200] seconds (default 48 hours); use the minimum. */
        internal const val EXPIRES_AFTER_SEC = 3_600

        /** Ark task status → progress; null for terminal and unknown statuses. */
        internal fun stageOf(status: String): VideoJobStage? = when (status) {
            "queued" -> VideoJobStage.Queued
            "running" -> VideoJobStage.Running
            else -> null
        }

        private fun Throwable?.isNotSent(): Boolean =
            this is UnknownHostException || this is ConnectException || this is NoRouteToHostException

        /** The provider has reached a terminal state: the task will not run again, so there is no need to cancel. */
        private fun Exception.isTerminal(): Boolean = this is VideoModelError.Failed || this is VideoModelError.Timeout

        private fun ModelError.isTransient(): Boolean =
            this is ModelError.Network || this is ModelError.RateLimited || (this is ModelError.Server && status >= 500)
    }
}
