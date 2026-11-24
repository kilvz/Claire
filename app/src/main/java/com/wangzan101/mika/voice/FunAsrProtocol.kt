package com.wangzan101.mika.voice

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Protocol of the self-hosted Fun-ASR real-time service (`serve_realtime_ws.py` in the Fun-ASR repo):
 * the text `START` begins a recognition session (the server replies `{"event":"started"}`), after which 16 kHz mono PCM16 binary frames are sent;
 * the server replies roughly every half second with the full text so far `{"sentences":[{"text",...}],"partial":"...","is_final":false}`,
 * each reply being a full replacement (`sentences` are the finalized sentences, `partial` is the sentence still changing);
 * the text `STOP` wraps up: the server first sends the final result with `is_final` true, then `{"event":"stopped"}`.
 * When a recognition language is specified, `LANGUAGE:<Chinese language name>` is sent first (the server replies `language_set`), then `START`, so the language confirmation precedes "start".
 * When a stretch of speech is long (no pause in between, VAD has not split the sentence yet), the server only feeds the most recent 15 seconds (`--partial-window-sec`) into `partial`,
 * so earlier characters fall out of `partial` and only appear in `sentences` once the stretch is finished. Therefore when the window shifts forward
 * (`partial_start_ms` grows while `sentences` is unchanged), the successive `partial`s are stitched here into the full text so far ([PartialStitch]):
 * the retained text is treated as finalized and appended after the sentences; it is discarded once the server puts this stretch into `sentences` (or gives a final result). Only the server's final results are used.
 * Every push-to-talk uses a new connection, never reused.
 *
 * This message set is not limited to Fun-ASR models: another model (e.g. Qwen3-ASR) fronted by a service speaking this message set can be entered in the same field and works; no distinction is made here.
 */
internal class FunAsrProtocol(private val language: AsrLanguage = AsrLanguage.Auto) : LiveAsrProtocol {
    private var final: String? = null
    private var lockedCount = 0
    /** The characters so far of the current stretch (not yet placed into `sentences`). */
    private val stitch = PartialStitch()

    override fun opening() = listOfNotNull(language.takeIf { it != AsrLanguage.Auto }?.let { "LANGUAGE:${it.funAsrName}" }, "START")
    override fun closing() = listOf("STOP")

    override fun read(message: String): LiveAsrUpdate {
        val root = Json.parseToJsonElement(message).jsonObject
        when (root["event"]?.jsonPrimitive?.contentOrNull) {
            "started" -> return LiveAsrUpdate.Started
            // If no audio was ever sent, the server gives no final result and replies stopped directly: treat it as nothing was heard
            "stopped" -> return LiveAsrUpdate.Ended(final ?: "")
            null -> Unit
            else -> return LiveAsrUpdate.None // confirmations such as hotwords_set, language_set
        }
        val sentences = (root["sentences"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull?.trim() }
        val stable = joinAsr(sentences)
        if (root["is_final"]?.jsonPrimitive?.booleanOrNull == true) {
            final = stable
            return LiveAsrUpdate.Text(stable, "")
        }
        val partial = root["partial"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (sentences.size != lockedCount) {
            // This stretch has been fully placed into sentences: the retained text is carried by it; the next partial is a new stretch and is not aligned with this one
            lockedCount = sentences.size
            stitch.reset()
        }
        stitch.feed(
            partial,
            root["partial_start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            root["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        )
        return LiveAsrUpdate.Text(joinAsr(listOf(stable, stitch.carried)), if (partial.isEmpty()) "" else stitch.tail)
    }

    companion object {
        /** How to deploy the self-hosted service (Fun-ASR-Nano's real-time service, Qwen3-ASR + vLLM) is written in this document; the settings page's "deployment notes" opens it. */
        const val DEPLOY_GUIDE = "https://github.com/kilvz/Claire/blob/main/docs/dev/live-asr.md"

        /** Default port of the service. */
        const val DEFAULT_PORT = 10095

        /** Cards and diagnostics show only the host and port, not the path and parameters. */
        fun hostOf(url: String): String = runCatching { URI(url).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrDefault(url)

        /**
         * Normalize the service address entered by the user into a ws(s) address: no protocol is treated as ws, http(s) is changed to ws(s), and a missing port is filled with [DEFAULT_PORT];
         * returns null if it is not a valid address (no host, wrong protocol, contains credentials).
         */
        fun normalize(input: String): String? {
            val raw = input.trim()
            if (raw.isEmpty() || raw.any(Char::isWhitespace)) return null
            val withScheme = if ("://" in raw) raw else "ws://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
            val host = uri.host ?: return null
            if (uri.userInfo != null) return null
            val port = if (uri.port > 0) uri.port else if (scheme == "ws" && "://" !in raw) DEFAULT_PORT else -1
            val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$hostPart${if (port > 0) ":$port" else ""}$path$query"
        }
    }
}
