package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpException
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTimeoutException
import com.wangzan101.mika.core.cap.web.cdp.mainFrameNavigated
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import com.wangzan101.mika.core.cap.web.ScriptAbort
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptCall
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Creates an isolated world in a tab's main frame to run page scripts (web_script).
 * The outbox lives only in this isolated world, invisible to the page's own scripts; the model code cannot reach any page objects from inside it (see [PageScriptProtocol]),
 * every `mika.*` call made by the script is put into the outbox and handed back by the always-pending take evaluation ([PageScriptProtocol.TAKE]), one at a time, to [ScriptBridge] (Claire's action path), with results returned via `Runtime.evaluate` in that same world.
 * Guard rails: network requests initiated by the script, and main-frame navigations while no mika action is running (the latter after `Page.stopLoading` first), end the script with an error.
 * Navigating the whole page away destroys the isolated world and the script stops there (returns [ScriptRun.Navigated]).
 * The time limit counts only the script's own time: it does not run while a mika action is in progress (including waiting for a lock-screen barrier); when the limit is hit, the running action is allowed to finish before wrapping up.
 * [preflight] checks the tab (takeover in progress, crash) before every mika call; if it returns non-null the script is ended.
 */
class PageScriptRunner(
    private val session: CdpSession,
    private val sessionId: String? = null,
    private val preflight: suspend () -> ScriptRun.Failed? = { null },
) {

    suspend fun run(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun = coroutineScope {
        val world = PageScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
        val outcome = CompletableDeferred<ScriptRun>()
        val calls = Channel<ScriptCall>(Channel.UNLIMITED)
        // While it is held there is an action in progress; wrapping up waits for it to finish, so an action is never cut off midway
        val busy = Mutex()
        val context = AtomicInteger(-1)
        val gone = AtomicBoolean(false)
        val mainFrame = AtomicReference<String?>(null)
        val reader = AtomicInteger(-1)

        // Subscribe before creating the world: the event stream is not replayed
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.sessionId != sessionId) return@collect
                val p = e.params
                when (e.method) {
                    "Runtime.executionContextDestroyed" -> if (p.int("executionContextId") == context.get()) gone.set(true)
                    "Runtime.executionContextsCleared" -> gone.set(true)
                    // Without Runtime enabled there is no context-destroyed event: when the main frame switches document, the world is gone
                    "Page.frameNavigated" -> if (mainFrameNavigated(p, mainFrame.get())) gone.set(true)
                    "Network.requestWillBeSent" -> if (context.get() >= 0 && PageScriptProtocol.requestFromScript(p)) {
                        outcome.complete(ScriptRun.Failed(BLOCKED_NETWORK, "the script sent a network request (${p.requestUrl()}); scripts may only read the page"))
                    }
                    "Page.frameRequestedNavigation" -> {
                        val frame = mainFrame.get() ?: return@collect
                        if (context.get() >= 0 && !outcome.isCompleted && PageScriptProtocol.navigationOutsideAction(p, frame, busy.isLocked)) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(ScriptRun.Failed(BLOCKED_NAVIGATION, "the page tried to navigate to ${p.str("url").orEmpty()} while no mika action was running; it was stopped. Use mika.navigate or mika.tap to move between pages"))
                        }
                    }
                }
            }
        }

        try {
            val contextId = try {
                setUp(world).let { w -> mainFrame.set(w.frameId); reader.set(w.reader); context.set(w.script); w.script }
            } catch (e: CdpException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for scripts: ${e.errorMessage}")
            } catch (e: CdpTimeoutException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
            }

            // One take is always pending: the script's mika calls come back one by one; the wrap-up (__mikaEnd) returns null, and a failed evaluation after the world is gone also ends it here
            launch {
                while (!outcome.isCompleted) {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", PageScriptProtocol.TAKE); put("contextId", contextId)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        break
                    }
                    val payload = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
                    val call = PageScriptProtocol.parseCall(payload) ?: continue
                    if (call.op == "log") call.args.str("text")?.let(bridge::log) else calls.trySend(call)
                }
            }

            launch {
                outcome.complete(try {
                    // No limit here: the limit is governed by the clock below (which does not run while an action is in progress)
                    val r = session.send("Runtime.evaluate", buildJsonObject {
                        put("expression", PageScriptProtocol.run(code)); put("contextId", contextId)
                        put("awaitPromise", true); put("returnByValue", true)
                    }, sessionId, Long.MAX_VALUE)
                    val details = r["exceptionDetails"] as? JsonObject
                    when {
                        gone.get() -> ScriptRun.Navigated
                        details != null -> PageScriptProtocol.error(details).let { (m, line) -> ScriptRun.Failed("script_error", m, line) }
                        else -> ScriptRun.Done((r["result"] as? JsonObject)?.get("value")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content)
                    }
                } catch (e: CdpException) {
                    // The world died with the whole-page navigation, and the script's Promise is gone with it
                    if (gone.get() || CONTEXT_GONE.containsMatchIn(e.errorMessage)) ScriptRun.Navigated else ScriptRun.Failed("script_error", e.errorMessage)
                })
            }

            launch {
                for (c in calls) {
                    val stop = busy.withLock {
                        if (outcome.isCompleted) return@withLock true
                        preflight()?.let { outcome.complete(it); return@withLock true }
                        val result = try {
                            bridge.call(c.op, c.args).let { r -> (r["read"] as? JsonObject)?.let { readPage(reader.get(), it) } ?: r }
                        } catch (a: ScriptAbort) {
                            outcome.complete(ScriptRun.Failed(a.error, a.message.orEmpty()))
                            return@withLock true
                        }
                        if (gone.get()) { outcome.complete(ScriptRun.Navigated); return@withLock true }
                        try {
                            session.send("Runtime.evaluate", buildJsonObject {
                                put("expression", PageScriptProtocol.settle(c.id, result)); put("contextId", contextId)
                            }, sessionId, STEP_TIMEOUT_MS)
                            false
                        } catch (e: CdpException) {
                            outcome.complete(ScriptRun.Navigated)
                            true
                        } catch (e: CdpTimeoutException) {
                            outcome.complete(ScriptRun.Failed("timeout", "the page stopped answering"))
                            true
                        }
                    }
                    if (stop) break
                }
            }

            // The clock only runs while no action is in progress
            launch {
                var left = timeoutMs
                while (left > 0) {
                    delay(TICK_MS)
                    if (!busy.isLocked) left -= TICK_MS
                }
                outcome.complete(ScriptRun.Failed("timeout", "the script did not finish within $timeoutMs ms"))
            }

            val result = outcome.await()
            busy.withLock { }
            result
        } finally {
            withContext(NonCancellable) {
                if (context.get() >= 0 && !gone.get()) {
                    val ended = try {
                        session.send("Runtime.evaluate", buildJsonObject { put("expression", PageScriptProtocol.END); put("contextId", context.get()) }, sessionId, STEP_TIMEOUT_MS)
                        true
                    } catch (e: CdpTimeoutException) {
                        false
                    } catch (e: Exception) {
                        true
                    }
                    // Even the wrap-up gets no response: the script is running a synchronous infinite loop, occupying the page. Only then interrupt it (interrupting when nothing is running would hit the page's next script)
                    if (!ended) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
                }
            }
            coroutineContext.cancelChildren()
        }
    }

    private class Worlds(val frameId: String, val script: Int, val reader: Int)

    /** Reads the DOM in the reader world with validated parameters and brings back plain data only. */
    private suspend fun readPage(ctx: Int, spec: JsonObject): JsonObject {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", PageScriptProtocol.read(spec)); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        val v = (r["result"] as? JsonObject)?.get("value") as? JsonObject
        if (r["exceptionDetails"] != null || v == null) return buildJsonObject { put("ok", false); put("error", "read_failed"); put("message", "the page could not be read") }
        if (v.toString().length > READ_RESULT_MAX_CHARS) return buildJsonObject {
            put("ok", false); put("error", "too_large"); put("message", "the result is too large; ask for fewer items (limit), fewer fields or a smaller maxChars")
        }
        return v
    }

    /**
     * Creates two isolated worlds: the script world (outbox, `mika`, runs the model code, cannot reach page objects) and the reader world (only Claire's fixed reader).
     */
    private suspend fun setUp(world: String): Worlds {
        // Do not enable Runtime: with it on, the page can detect the debugger (and some sites keep reloading because of it); isolated worlds and evaluation do not need it
        // Events the guard rails need: navigation requests and network requests (re-enabling an already enabled domain is harmless)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", world)
            // The parameter name in the protocol really is spelled this way
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(ctx, PageScriptProtocol.PRELUDE)
        val readerCtx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", PageScriptProtocol.READER_WORLD_PREFIX + world.removePrefix(PageScriptProtocol.WORLD_PREFIX))
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(readerCtx, PageScriptProtocol.READER)
        return Worlds(frameId, ctx, readerCtx)
    }

    /** If installation fails, the model code is not run. */
    private suspend fun install(ctx: Int, source: String) {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", source); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, PageScriptProtocol.error(it).first, "Runtime.evaluate") }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.requestUrl() = ((this["request"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.take(200).orEmpty()

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TICK_MS = 100L
        const val READ_RESULT_MAX_CHARS = 200_000
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
