package com.wangzan101.mika.core.cap.web.cdp

import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.EVAL_RESULT_MAX_CHARS
import com.wangzan101.mika.core.cap.web.EvalJs
import com.wangzan101.mika.core.cap.web.ReadScriptProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tab's read script (see [ReadScriptProtocol]): runs in the main frame's read world, one at a time.
 * The read world is created once and kept for reuse (its state survives across calls); when the page loads a new document it is gone and is recreated next time (with fresh=true in the result).
 * Safety net: while it runs, any network request issued by the script or any navigation of the main frame (`Page.stopLoading` first) makes this run end with an error.
 * At the deadline the script's subsequent sleeps are first made to fail; only if even that gets no reply (a synchronous infinite loop is hogging the page) is execution interrupted.
 */
class ReadScriptRunner(val session: CdpSession, private val sessionId: String? = null) {
    private val lock = Mutex()
    private val worldName = ReadScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
    @Volatile private var context: Int? = null

    private class World(val frameId: String, val context: Int, val origin: String?, val fresh: Boolean)

    /** Throws [CdpConnectionClosedException] when the connection drops (the caller decides whether the tab crashed). */
    suspend fun run(code: String, args: JsonObject?, timeoutMs: Long): EvalJs = lock.withLock {
        coroutineScope {
            val outcome = CompletableDeferred<EvalJs>()
            val running = AtomicBoolean(false)
            val answered = AtomicBoolean(false)
            var world: World? = null

            // Subscribe before acting: the event stream is not replayed
            launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e ->
                    if (e.sessionId != sessionId) return@collect
                    val w = world ?: return@collect
                    val p = e.params
                    when (e.method) {
                        "Runtime.executionContextDestroyed" -> if ((p["executionContextId"] as? JsonPrimitive)?.intOrNull == w.context) gone(outcome)
                        "Runtime.executionContextsCleared" -> gone(outcome)
                        // Without Runtime enabled there are no context-destroyed events: when the main frame switches documents, the world is gone
                        "Page.frameNavigated" -> if (mainFrameNavigated(p, w.frameId)) gone(outcome)
                        "Network.requestWillBeSent" -> if (running.get()) ReadScriptProtocol.requestViolation(p)?.let {
                            outcome.complete(EvalJs.Failed(BLOCKED_NETWORK, it))
                        }
                        "Page.frameRequestedNavigation" -> if (running.get() && (p["frameId"] as? JsonPrimitive)?.contentOrNull == w.frameId) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(EvalJs.Failed(BLOCKED_NAVIGATION,
                                "the page tried to navigate to ${(p["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(200)} while the read script ran; it was stopped. Read scripts cannot navigate: use web_navigate or web_tap"))
                        }
                    }
                }
            }

            try {
                val w = try {
                    ensureWorld()
                } catch (e: CdpConnectionClosedException) {
                    throw e
                } catch (e: CdpTimeoutException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
                } catch (e: CdpException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for read scripts: ${e.errorMessage}")
                }
                world = w
                running.set(true)
                launch {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", ReadScriptProtocol.run(code, args, EVAL_RESULT_MAX_CHARS + 1)); put("contextId", w.context)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, timeoutMs + STEP_TIMEOUT_MS)
                    } catch (e: CdpTimeoutException) {
                        outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms")); return@launch
                    } catch (e: CdpConnectionClosedException) {
                        outcome.completeExceptionally(e); return@launch
                    } catch (e: CdpException) {
                        if (CONTEXT_GONE.containsMatchIn(e.errorMessage)) gone(outcome) else outcome.complete(EvalJs.Failed("script_error", e.errorMessage))
                        return@launch
                    }
                    answered.set(true)
                    val details = r["exceptionDetails"] as? JsonObject
                    outcome.complete(if (details != null) EvalJs.Failed("script_error", exceptionText(details))
                        else ReadScriptProtocol.result((r["result"] as? JsonObject)?.get("value") as? JsonObject, w.fresh))
                }
                launch {
                    delay(timeoutMs)
                    outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms"))
                }
                outcome.await()
            } finally {
                running.set(false)
                val w = world
                withContext(NonCancellable) {
                    if (w != null && !answered.get() && context == w.context) stop(w.context)
                }
                coroutineContext.cancelChildren()
            }
        }
    }

    private fun gone(outcome: CompletableDeferred<EvalJs>) {
        context = null
        outcome.complete(EvalJs.Failed(NAVIGATED, "the page loaded a new document while the script ran, so the script stopped and its state is gone; read the new page"))
    }

    /** Wraps up a script that did not finish: its subsequent sleeps are first made to fail; only when even that gets no reply is it running a synchronous infinite loop, and only then is it interrupted. */
    private suspend fun stop(ctx: Int) {
        val answeredStop = try {
            session.send("Runtime.evaluate", buildJsonObject { put("expression", ReadScriptProtocol.STOP); put("contextId", ctx) }, sessionId, STEP_TIMEOUT_MS)
            true
        } catch (e: CdpTimeoutException) {
            false
        } catch (e: Exception) {
            true
        }
        if (!answeredStop) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
    }

    /** If the kept read world is still there, use it; otherwise (or the first time) create one and install the prelude script. */
    private suspend fun ensureWorld(): World {
        // Runtime is not enabled: while it is on, pages can detect the debugger (some sites therefore keep reloading); isolated worlds and evaluation do not need it
        // Events the safety net needs: navigation requests and network requests (enabling an already enabled domain again is harmless)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        context?.let { c -> probe(c)?.let { origin -> return World(frameId, c, origin, fresh = false) } }
        context = null
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", worldName)
            // This is how the parameter name is spelled in the protocol
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PRELUDE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, exceptionText(it), "Runtime.evaluate") }
        val origin = probe(ctx) ?: throw CdpException(0, "the read world did not start", "Runtime.evaluate")
        context = ctx
        return World(frameId, ctx, origin, fresh = true)
    }

    /** The page's origin when the read world is installed; null when the world is gone or was not installed. */
    private suspend fun probe(ctx: Int): String? = try {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PROBE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: CdpException) {
        null
    }

    private fun exceptionText(details: JsonObject): String {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        return description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TIMEOUT = "timeout"
        const val NAVIGATED = "navigated"
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
