package com.wangzan101.mika.voice

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * WebSocket protocol for Alibaba Cloud Bailian real-time speech recognition (fun-asr-realtime, paraformer-realtime-v2 etc. all use the same run-task command set):
 * after connecting, send `run-task`, then wait for `task-started` before sending 16 kHz mono PCM16 binary frames;
 * the `sentence` in `result-generated` is a full replacement of the same sentence identified by `sentence_id`, and the sentence is finalized when `sentence_end` is true;
 * when the audio is done send `finish-task` and wait for `task-finished` to count as finished (without waiting, the last sentence is lost); `task-failed` is an error and the connection is closed accordingly.
 */
internal class BailianProtocol(private val model: String, private val language: AsrLanguage = AsrLanguage.Auto) : LiveAsrProtocol {
    private val taskId = UUID.randomUUID().toString().replace("-", "")
    private val sentences = sortedMapOf<Int, String>()
    private val ended = mutableSetOf<Int>()

    // The official example sends one packet every 100 ms and one packet per 100 ms; audio accumulated before connecting catches up at double speed
    override val packetGapMs = 50L

    override fun opening() = listOf(buildJsonObject {
        putJsonObject("header") {
            put("action", "run-task"); put("task_id", taskId); put("streaming", "duplex")
        }
        putJsonObject("payload") {
            put("task_group", "audio"); put("task", "asr"); put("function", "recognition"); put("model", model)
            putJsonObject("parameters") {
                put("format", "pcm"); put("sample_rate", 16000)
                // When a language is specified, provide only one (fun-asr-realtime only honors the first)
                if (language != AsrLanguage.Auto) put("language_hints", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(language.code)) })
                // The Qwen transcription message edition does not return intermediate results by default; enable it to get text as you speak
                if (model.startsWith("qwen-audio")) put("intermediate_result_enabled", true)
                // Push-to-talk has no upper bound, and pausing to think mid-way means relatively long silence: turn on the heartbeat so the server does not disconnect for prolonged silence (heartbeat results are skipped in read)
                else put("heartbeat", true)
            }
            putJsonObject("input") {}
        }
    }.toString())

    override fun closing() = listOf(buildJsonObject {
        putJsonObject("header") {
            put("action", "finish-task"); put("task_id", taskId); put("streaming", "duplex")
        }
        putJsonObject("payload") { putJsonObject("input") {} }
    }.toString())

    override fun read(message: String): LiveAsrUpdate {
        val root = Json.parseToJsonElement(message).jsonObject
        val header = root["header"] as? JsonObject ?: return LiveAsrUpdate.None
        return when (header["event"]?.jsonPrimitive?.contentOrNull) {
            "task-started" -> LiveAsrUpdate.Started
            "task-finished" -> LiveAsrUpdate.Ended(joinAsr(sentences.values.toList()))
            "task-failed" -> {
                val code = header["error_code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                // Keep only the error code: the server's own wording may contain what was said
                throw LiveAsrError(code, auth = AUTH_CODES.any { code.contains(it, ignoreCase = true) })
            }
            "result-generated" -> sentence(root)
            else -> LiveAsrUpdate.None
        }
    }

    private fun sentence(root: JsonObject): LiveAsrUpdate {
        val s = root["payload"]?.jsonObject?.get("output")?.jsonObject?.get("sentence") as? JsonObject ?: return LiveAsrUpdate.None
        if (s["heartbeat"]?.jsonPrimitive?.booleanOrNull == true) return LiveAsrUpdate.None
        val text = s["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return LiveAsrUpdate.None
        // Sentences without an index are appended to the last sentence
        val id = s["sentence_id"]?.jsonPrimitive?.intOrNull ?: (sentences.keys.lastOrNull() ?: 1)
        if (id in ended && s["sentence_end"]?.jsonPrimitive?.booleanOrNull != true) return LiveAsrUpdate.None
        sentences[id] = text
        if (s["sentence_end"]?.jsonPrimitive?.booleanOrNull == true) ended += id
        val open = sentences.keys.filter { it !in ended }
        return LiveAsrUpdate.Text(
            joinAsr(sentences.filterKeys { it in ended }.values.toList()),
            joinAsr(open.map { sentences.getValue(it) }),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "fun-asr-realtime"

        /** The few listed on the settings page; other models using the same protocol can also be entered manually. */
        val MODELS = listOf("fun-asr-realtime", "paraformer-realtime-v2", "qwen-audio-3.1-asr-flash-message")

        private val AUTH_CODES = listOf("ApiKey", "Access", "Arrearage", "Unauthorized", "Forbidden", "Permission")

        /**
         * Normalize the workspace ID into the segment used in the hostname: strip surrounding whitespace, lowercase it, and prepend the `llm-` prefix when absent (workspace IDs in the console start with it,
         * so copying only the latter half still works); an empty string means no workspace is used. Only letters, digits and hyphens are allowed, otherwise null is returned.
         */
        fun workspace(input: String): String? {
            val raw = input.trim().lowercase()
            if (raw.isEmpty()) return ""
            if (!raw.matches(Regex("[a-z0-9-]{1,63}")) || raw.startsWith("-") || raw.endsWith("-")) return null
            val id = if (raw.startsWith("llm-")) raw else "llm-$raw"
            return id.takeIf { it.length <= 63 && it != "llm-" }
        }

        /** The model name is spliced into JSON, so only common characters are accepted. */
        fun validModel(model: String): Boolean = model.trim().matches(Regex("[A-Za-z0-9._-]{1,80}"))
    }
}

/**
 * Bailian regions: API keys for Beijing and Singapore are not interchangeable and must be in the same region as the endpoint. The names are stored, so entries can only be appended.
 * [endpoint] is the general-purpose endpoint (used when no workspace ID is given); [maas] is the region segment in a workspace-specific endpoint.
 */
enum class BailianRegion(val endpoint: String, val maas: String) {
    Beijing("wss://dashscope.aliyuncs.com/api-ws/v1/inference", "cn-beijing"),
    Singapore("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", "ap-southeast-1");

    /**
     * Where to connect this time: with a workspace ID, use its dedicated endpoint `wss://<workspace>.<region>.maas.aliyuncs.com/api-ws/v1/inference` (as the docs recommend),
     * otherwise use the general-purpose endpoint (still usable). [workspace] is first normalized via [BailianProtocol.workspace].
     */
    fun endpoint(workspace: String): String =
        if (workspace.isEmpty()) endpoint else "wss://$workspace.$maas.maas.aliyuncs.com/api-ws/v1/inference"
}
