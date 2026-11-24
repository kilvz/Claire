package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** One-line reply; when [close] is true, disconnect after replying. */
class BridgeReply(val json: String, val close: Boolean = false)

/**
 * The protocol between the `mika` command in the sandbox and Claire: one JSON request per line
 * `{"token","method","params"}` and one JSON reply per line. Phone methods are only forwarded to the current task's
 * action entry; the command process cannot touch the virtual display directly.
 */
object BridgeProtocol {
    const val MAX_REQUEST = 64 * 1024
    const val MAX_PROGRESS = 300

    sealed interface Line {
        class Text(val text: String) : Line
        data object TooLarge : Line
        data object End : Line
    }

    /** Reads one line (without the newline); stops reading beyond [max] bytes. A trailing partial line with no newline counts as the end. */
    fun readLine(input: InputStream, max: Int = MAX_REQUEST): Line {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return Line.End
            if (b == '\n'.code) return Line.Text(buf.toString(Charsets.UTF_8.name()))
            if (buf.size() >= max) return Line.TooLarge
            buf.write(b)
        }
    }

    /**
     * [progress] returning false means this one was dropped by throttling; [share] receives the sandbox path and an
     * optional save name and throws [SandboxError] on failure; [info] gives Claire's version, whether networked, and so
     * on. A wrong token, or a request that is not a JSON object or is too large: reply with an error and disconnect.
     */
    suspend fun handle(
        line: String, token: String,
        progress: (String) -> Boolean,
        share: suspend (String, String?) -> String,
        info: () -> JsonObject,
        phone: (suspend (String, JsonObject) -> JsonObject)? = null,
        /** Unexpected failures are left for Claire to log herself; only the error code goes back to the sandbox. */
        onError: (Throwable) -> Unit = {},
    ): BridgeReply {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_REQUEST) return error("too_large", close = true)
        val req = try { Json.parseToJsonElement(line) as? JsonObject } catch (e: SerializationException) { null }
            ?: return error("bad_request", close = true)
        val given = (req["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (given == null || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return error("bad_token", close = true)
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        fun str(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when ((req["method"] as? JsonPrimitive)?.content) {
            "progress" -> {
                val text = str("text")?.let(::cleanProgress)?.takeIf { it.isNotEmpty() } ?: return error("bad_params")
                ok { put("shown", progress(text)) }
            }
            "share" -> {
                val path = str("path") ?: return error("bad_params")
                try {
                    val saved = share(path, str("name"))
                    ok { put("savedAs", saved) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SandboxError) {
                    error(e.code, e.message)
                } catch (e: Exception) {
                    onError(e)
                    error("share_failed")
                }
            }
            "info" -> BridgeReply(JsonObject(mapOf("ok" to JsonPrimitive(true)) + info()).toString())
            "phone" -> {
                val tool = str("tool")?.takeIf { it.length in 1..80 } ?: return error("bad_params")
                val args = params["args"] as? JsonObject ?: return error("bad_params")
                val handler = phone ?: return error("phone_unavailable")
                try {
                    BridgeReply(handler(tool, args).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                    error("phone_failed")
                }
            }
            else -> error("unknown_method")
        }
    }

    /**
     * A line of text on the task card: strips control characters, format characters (zero-width characters,
     * direction-override characters and the like, which can make the displayed text disagree with the actual text) and
     * line/paragraph separators, then truncates to [MAX_PROGRESS] code points (without splitting surrogate pairs).
     */
    internal fun cleanProgress(s: String): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < s.length && count < MAX_PROGRESS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim()
    }

    private fun ok(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = BridgeReply(buildJsonObject { put("ok", true); b() }.toString())

    private fun error(code: String, message: String? = null, close: Boolean = false) = BridgeReply(buildJsonObject {
        put("ok", false); put("error", code); message?.let { put("message", it) }
    }.toString(), close)
}

/** Lets at most one progress through per [intervalMs]; extras are dropped. */
class ProgressGate(private val intervalMs: Long = 1_000, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE

    @Synchronized fun offer(): Boolean {
        val t = now()
        if (last != Long.MIN_VALUE && t - last < intervalMs) return false
        last = t
        return true
    }
}
