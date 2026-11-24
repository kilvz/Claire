package com.wangzan101.mika.cap.web

import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.PageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/** Checks before clicking by ref: clickable ([x],[y] in top-level viewport CSS pixels), covered by another element, outside the viewport, or the element is gone. */
sealed interface HitResult {
    data class Hit(val x: Int, val y: Int) : HitResult
    data class Obscured(val byName: String) : HitResult
    data object Offscreen : HitResult
    data object Gone : HitResult
}

/** For barrier judgement: whether the page has a password field, and the full URLs of cross-origin frames. */
data class PageFacts(val passwordField: Boolean, val frameUrls: List<String>)

/** The current value of an input field; for password fields only the length. */
sealed interface FieldValue {
    data class Text(val value: String) : FieldValue
    data class Password(val length: Int) : FieldValue
    data object NotAField : FieldValue
}

/**
 * App side of the page script assets/web/page.js: injection, calls, polling for async results. Scripts written by the model do not go through here
 * (read scripts and the page script each run in their own isolated world); this only calls our own fixed code.
 * When the system supports it, inject at the start of every document (before the page's own scripts, so the original entry points cannot be replaced by the page first); otherwise inject after loading finishes.
 * The page carries only one bridge function with a random name, and the interface can be obtained only with the secret known solely here; the page gets no entry point to the App,
 * and all results are fetched back through evaluateJavascript from here. When the main document changes and the script is not there, the next call re-injects automatically.
 */
/** When [useDocumentStart] is false, inject only after loading finishes (that is the case when the system does not support document-start injection; tests use it too). */
class PageScript(private val tab: WebTab, private val useDocumentStart: Boolean = true) {
    /** Name of the bridge function on the page; when injecting after load, every document gets a new one (together with the secret), while document-start injection keeps the pair from registration. */
    @Volatile internal var bridgeName = newBridgeName()
        private set
    @Volatile private var secret = UUID.randomUUID().toString()
    private val source: String by lazy {
        tab.view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }
    private val bootstrap: String get() = "(\n$source\n)(${quote(bridgeName)},${quote(secret)})"
    private var documentStartRegistered = false

    /** Whether document-start injection is used; when false, the page script may have modified global objects before this script. */
    var documentStart: Boolean = false
        private set

    /** Registers document-start injection (only once), and injects into the current document if it is not there yet. */
    suspend fun install() {
        withContext(Dispatchers.Main) {
            if (!documentStartRegistered) {
                documentStartRegistered = true
                if (useDocumentStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(tab.view, bootstrap, setOf("*"))
                    documentStart = true
                }
            }
        }
        if (!present()) inject()
    }

    private suspend fun present(): Boolean =
        raw("(function(){var g=window[${quote(bridgeName)}];return typeof g==='function'&&!!g(${quote(secret)});})()") == "true"

    // Inject after load: each new document gets a new name and secret, so a name leaked in the previous document is useless in the new one
    private suspend fun inject() {
        if (!documentStart) {
            bridgeName = newBridgeName()
            secret = UUID.randomUUID().toString()
        }
        raw(bootstrap)
    }

    /**
     * JSON of the elements visible in the viewport (the input format of PageTree); an empty tree when the page cannot be read.
     * The ref table is stored under [key] (the snapshot id) and the page keeps the most recent few; when [key] is null, read only without registering.
     */
    suspend fun tree(key: String? = DEFAULT_KEY): String =
        call("tree(${key?.let(::quote) ?: "null"})")?.takeIf { it is JsonObject }?.toString() ?: EMPTY_TREE

    /**
     * Expression evaluated in the page's main world through DevTools: returns the element array from the ref table [key] (null means the most recent screen read), or null when the script is missing.
     * For DevTools only (it returns element objects); it does not go through [call].
     */
    internal fun refNodesExpression(key: String?): String =
        "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
            "return m?m.refNodes(${key?.let(::quote) ?: "null"}):null;})()"

    suspend fun facts(): PageFacts {
        val r = call("facts()") as? JsonObject ?: return PageFacts(false, emptyList())
        val frames = (r["frames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return PageFacts(r["password"]?.jsonPrimitive?.booleanOrNull == true, frames)
    }

    suspend fun mutationCount(): Long = (call("mutationCount()") as? JsonPrimitive)?.long ?: 0L

    /** Scrolls the element into the center of the viewport first, then returns the center of its visible part (top-level viewport CSS pixels); null if the element is gone. */
    suspend fun centerOf(ref: String, key: String = DEFAULT_KEY): Pair<Int, Int>? = pair(call("centerOf(${quote(ref)},${quote(key)})"))

    suspend fun hitTest(ref: String, key: String = DEFAULT_KEY): HitResult {
        val r = call("hitTest(${quote(ref)},${quote(key)})") as? JsonObject ?: return HitResult.Gone
        return when (r["k"]?.jsonPrimitive?.content) {
            "hit" -> HitResult.Hit(r["x"]!!.jsonPrimitive.int, r["y"]!!.jsonPrimitive.int)
            "obscured" -> HitResult.Obscured(r["by"]?.jsonPrimitive?.content.orEmpty())
            "offscreen" -> HitResult.Offscreen
            else -> HitResult.Gone
        }
    }

    /** When [ref] is null, reads the input field that currently has focus. */
    /** Content of a blob: URL in the page; when it cannot be read or exceeds [maxBytes], the reason is returned. */
    sealed interface Blob {
        class Ok(val mime: String?, val bytes: ByteArray) : Blob
        class Failed(val error: String) : Blob
    }

    /**
     * Has the page script read the blob: content as base64, then fetches it in chunks and decodes it. The length reported by the page and every chunk are checked against [BlobChunks]' limits;
     * waiting for the result timing out, or any error, makes the page drop this content (the page side also expires it on its own).
     */
    suspend fun readBlob(url: String, maxBytes: Long): Blob {
        val token = nextToken()
        try {
            if (call("startBlob(${quote(token)},${quote(url)},$maxBytes)") == null) return Blob.Failed(BlobChunks.UNREADABLE)
            val meta = poll(token, BLOB_TIMEOUT_MS) as? JsonObject ?: return Blob.Failed(BlobChunks.UNREADABLE)
            if ((meta["ok"] as? JsonPrimitive)?.booleanOrNull != true) return Blob.Failed((meta["error"] as? JsonPrimitive)?.content ?: BlobChunks.UNREADABLE)
            val length = (meta["length"] as? JsonPrimitive)?.intOrNull ?: return Blob.Failed(BlobChunks.UNREADABLE)
            val chunks = BlobChunks(length, maxBytes)
            chunks.refusal()?.let { return Blob.Failed(it) }
            while (!chunks.complete) {
                val part = (call("blobChunk(${quote(token)},${chunks.received})") as? JsonPrimitive)?.content ?: return Blob.Failed(BlobChunks.UNREADABLE)
                if (part.isEmpty()) break
                chunks.add(part)?.let { return Blob.Failed(it) }
            }
            return when (val o = chunks.finish()) {
                is BlobChunks.Outcome.Ok -> Blob.Ok((meta["mime"] as? JsonPrimitive)?.content?.ifBlank { null }, o.bytes)
                is BlobChunks.Outcome.Failed -> Blob.Failed(o.error)
            }
        } finally {
            call("blobDone(${quote(token)})")
        }
    }

    /**
     * The download name the page gives on a link pointing to [url] (`<a download="…">`): recorded by the page script at click time, or taken from a link to it in the document.
     * Null if none. The name is provided by the web page and is sanitized by the download side per file-name rules. Very long data: URLs are not compared.
     */
    suspend fun downloadName(url: String): String? {
        if (url.length > MAX_NAMED_URL_CHARS) return null
        return (call("downloadName(${quote(url)})") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** HTTP status of the main document response; null if unavailable. */
    suspend fun navStatus(): Int? = (call("navStatus()") as? JsonPrimitive)?.intOrNull

    /** Whether this element currently has focus (or focus lies in this editable area of it). */
    suspend fun isActive(ref: String, key: String = DEFAULT_KEY): Boolean =
        (call("isActive(${quote(ref)},${quote(key)})") as? JsonPrimitive)?.booleanOrNull == true

    suspend fun fieldValue(ref: String?, key: String = DEFAULT_KEY): FieldValue {
        val r = call("fieldValue(${ref?.let(::quote) ?: "null"},${quote(key)})") as? JsonObject ?: return FieldValue.NotAField
        return when (r["k"]?.jsonPrimitive?.content) {
            "password" -> FieldValue.Password(r["len"]!!.jsonPrimitive.int)
            "text" -> FieldValue.Text(r["v"]?.jsonPrimitive?.content.orEmpty())
            else -> FieldValue.NotAField
        }
    }

    /**
     * Readable text of the whole page (the PageText JSON). When [scroll] is true, scroll down up to 5 screens before reading the first page to trigger lazy loading, then restore;
     * pass false when verifying results, so the page's scroll position is left untouched.
     */
    suspend fun readPage(maxChars: Int, offset: Int, scroll: Boolean = true): String {
        val token = nextToken()
        val max = maxChars.coerceIn(1, MAX_READ_CHARS)
        if (call("startRead(${quote(token)},$max,${offset.coerceAtLeast(0)},$scroll)") == null) return error("read_failed", "page script unavailable")
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) {
            call("discard(${quote(token)})")
            return error("read_failed", "page did not finish reading")
        }
        return r.toString()
    }

    /** Whole-page read for web_read_page (segments, foldable list blocks, quality signs; format in PageDigest), no scrolling; null if unreadable. */
    suspend fun digest(): JsonObject? {
        val token = nextToken()
        if (call("startDigest(${quote(token)},${PageDigest.scriptOptions})") == null) return null
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) call("discard(${quote(token)})")
        return (r as? JsonObject)?.takeIf { it["error"] == null }
    }

    /** The body's (length, digest), used to judge whether the body is stable; null if unreadable. */
    suspend fun contentSample(): Pair<Int, Int>? = runCatching { pair(call("contentSample()")) }.getOrNull()

    /**
     * Scrolls down a few screens to trigger lazy loading and returns only after scrolling finishes; [endLazy] must then be called to restore the scroll position. Returns a token; null when the page script is missing.
     */
    suspend fun startLazy(): String? {
        val token = nextToken()
        if (call("startLazy(${quote(token)})") == null) return null
        poll(token, LAZY_TIMEOUT_MS)
        return token
    }

    suspend fun endLazy(token: String) {
        call("endLazy(${quote(token)})")
    }

    /** Scroll position (CSS pixels); when [ref] is null it is the page itself. Null if the element is gone. */
    suspend fun scrollState(ref: String?, key: String = DEFAULT_KEY): Pair<Int, Int>? =
        pair(call("scrollState(${ref?.let(::quote) ?: "null"},${quote(key)})"))

    private suspend fun poll(token: String, timeoutMs: Long): JsonElement? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = call("take(${quote(token)})")
            if (r != null && r !is JsonNull) return r
            if (tab.crashed) return null
            delay(POLL_MS)
        }
        return null
    }

    /** Calls one method of the page script; when the script is missing (the main document changed), injects and retries once. Null if no result can be obtained. */
    private suspend fun call(expr: String): JsonElement? {
        repeat(2) { attempt ->
            val js = "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
                "if(!m)return {missing:1};return {v:m.$expr};})()"
            val r = raw(js) ?: return null
            if (r == "null") return null
            val o = runCatching { Json.parseToJsonElement(r) }.getOrNull() as? JsonObject ?: return null
            if (o["missing"] == null) return o["v"] ?: JsonNull
            if (attempt == 0) inject()
        }
        return null
    }

    /** Raw callback string of evaluateJavascript; null on timeout or when the tab is no longer valid. */
    private suspend fun raw(js: String, timeoutMs: Long = CALL_TIMEOUT_MS): String? {
        if (tab.crashed) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(js) { c.resume(it) } }
            }
        }
    }

    private fun pair(e: JsonElement?): Pair<Int, Int>? {
        val a = e as? JsonArray ?: return null
        if (a.size != 2) return null
        return a[0].jsonPrimitive.int to a[1].jsonPrimitive.int
    }

    private fun nextToken() = UUID.randomUUID().toString()

    private fun error(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun quote(s: String) = JsonPrimitive(s).toString()

    companion object {
        private fun newBridgeName() = "_" + UUID.randomUUID().toString().replace("-", "").take(16)
        private const val ASSET = "web/page.js"
        private const val MAX_NAMED_URL_CHARS = 100_000
        /** The ref table used by the page script when no snapshot id is specified. */
        const val DEFAULT_KEY = "default"
        private const val MAX_READ_CHARS = 30_000
        private const val READ_TIMEOUT_MS = 15_000L
        /** Scrolls a few screens, waiting a moment per screen, normally finishing within two seconds; when the page is stuck, do not let it hold up the page read. */
        private const val LAZY_TIMEOUT_MS = 5_000L
        private const val BLOB_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L
        private const val EMPTY_TREE = """{"els":[],"vw":0,"vh":0,"dpr":1,"trunc":false,"mut":0}"""
    }
}
