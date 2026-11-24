package com.wangzan101.mika.cap.web.cdp

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.NetworkLedger
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * Network record for one tab: Network is only enabled the first time it is used (the automatic wait after an action, web_capture),
 * subscribing to this tab session's `Network.*` events and feeding them to [NetworkLedger]. It stops when the session drops or the tab closes, and is rebuilt on the new session next time it is used.
 * Only one session's events come in, so there is no need to filter by tab; requests from child sessions (non-empty sessionId) are recorded per session.
 */
class NetworkTracker private constructor(
    private val session: CdpSession,
    private val clock: () -> Long,
) {
    private val ledger = NetworkLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bodyLock = Mutex()
    @Volatile var enabled = false
        private set

    // Subscribe before sending Network.enable: the event stream is not replayed
    private suspend fun start(): Boolean {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.method.startsWith(NETWORK)) synchronized(ledger) { ledger.onEvent(e.method, e.params, clock(), e.sessionId) }
            }
        }
        scope.launch { session.closed.first { it }; close() }
        enabled = try {
            // The body buffer the renderer keeps for getResponseBody is capped too: the per-item cap matches what the ledger accepts (larger ones are not fetched anyway);
            // the total is twice the ledger's, since images, scripts, and other small resources also use this buffer, leaving some room so interface responses are not squeezed out
            session.send(
                "Network.enable",
                buildJsonObject {
                    put("maxResourceBufferSize", ledger.maxBodyBytes)
                    put("maxTotalBufferSize", ledger.maxTotalBodyBytes * 2)
                },
                timeoutMs = CALL_TIMEOUT_MS,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network.enable failed: ${e.message}")
            false
        }
        if (!enabled) scope.cancel()
        return enabled
    }

    val alive: Boolean get() = enabled && !session.closed.value

    fun markAction() = synchronized(ledger) { ledger.markAction() }

    fun idle(): Boolean = synchronized(ledger) { ledger.isIdle(clock()) }

    /** Requests sent by the current action are still in flight (or just finished); a dropped session counts as not busy. */
    fun actionBusy(): Boolean = alive && synchronized(ledger) { ledger.actionBusy(clock()) }

    /** Wait until the network is idle (no requests in flight and quiet for [NetworkLedger.quietMs]), at most [capMs]; returns false if it is still not idle at the deadline. */
    suspend fun awaitIdle(capMs: Long): Boolean {
        val deadline = clock() + capMs
        while (true) {
            if (!alive) return true
            if (idle()) return true
            if (clock() >= deadline) return false
            delay(POLL_MS)
        }
    }

    fun list(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> =
        synchronized(ledger) { ledger.list(urlContains, method, sinceLastAction) }

    /** Fetch one response; when a body should exist but has not been fetched yet, get it with `Network.getResponseBody` (without a body when that fails). */
    suspend fun get(id: String): CapturedResponse? = bodyLock.withLock {
        val fetch = synchronized(ledger) { ledger.bodyToFetch(id) }
        if (fetch != null && alive) {
            try {
                val r = session.send(
                    "Network.getResponseBody",
                    buildJsonObject { put("requestId", fetch.requestId) },
                    fetch.sessionId,
                    CALL_TIMEOUT_MS,
                )
                val body = (r["body"] as? JsonPrimitive)?.content
                val b64 = (r["base64Encoded"] as? JsonPrimitive)?.boolean == true
                if (body != null) synchronized(ledger) { ledger.putBody(id, body, b64) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page already dropped this response (common after navigation), or the call timed out: return as usual, without a body
                Log.i(TAG, "getResponseBody $id failed: ${e.message}")
            }
        }
        synchronized(ledger) { ledger.get(id) }
    }

    fun close() {
        if (!scope.isActive) return
        val s = session
        if (enabled && !s.closed.value) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { s.send("Network.disable", timeoutMs = CALL_TIMEOUT_MS) } }
        }
        enabled = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "MikaNetTrack"
        private const val NETWORK = "Network."
        private const val POLL_MS = 100L
        private const val CALL_TIMEOUT_MS = 3_000L

        private val trackers = WeakHashMap<WebTab, NetworkTracker>()
        private val creating = Mutex()

        /**
         * The network record for this tab; Network is enabled on the first call. Null when there is no DevTools session, or when enabling fails (the caller falls back to the old path).
         * Rebuilt on the current session when the session changed (reconnect) or when the tab was closed.
         */
        suspend fun of(tab: WebTab, clock: () -> Long): NetworkTracker? {
            if (tab.destroyed || tab.crashed) {
                release(tab)
                return null
            }
            val session = tab.devtools.session(tab) ?: return null
            return creating.withLock {
                synchronized(trackers) { trackers[tab] }?.takeIf { it.session === session && it.alive }?.let { return@withLock it }
                synchronized(trackers) { trackers.remove(tab) }?.close()
                val t = NetworkTracker(session, clock)
                if (!t.start()) return@withLock null
                synchronized(trackers) { trackers[tab] = t }
                t
            }
        }

        /** Tab closed: stop its record. */
        fun release(tab: WebTab) {
            synchronized(trackers) { trackers.remove(tab) }?.close()
        }
    }
}
