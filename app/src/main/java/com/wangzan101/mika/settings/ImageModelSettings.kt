package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.model.ImageAspect
import com.wangzan101.mika.core.model.ImageCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Wire shape of the image service. Names are stored, so entries may only be appended — never renamed or deleted — otherwise old settings could not be read back. */
enum class ImageProtocol(val wire: String) {
    /** OpenAI Images: `/images/generations` and `/images/edits`. */
    OpenAiImages("openai-images"),

    /** Gemini image models: generateContent + responseModalities. */
    GeminiImage("gemini-image");

    companion object {
        fun of(wire: String?): ImageProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

/** The provider, model and protocol the user chose for image generation; keys are still stored per provider by [ProviderStore] and not copied here. */
data class ImageChoice(val providerId: String, val model: String, val protocol: ImageProtocol)

/**
 * Image-related settings, stored in the [FeatureFlags] preferences as one key holding one JSON blob; anything unreadable falls back to the defaults.
 * [enabled] is the master switch, off by default; when no model is selected the tools are not offered either. Saving to the album has its own switch, off by default;
 * [askBeforeSave] is on by default, so every save goes through the existing approval card; [askBeforeGenerate] is off by default, to avoid interrupting for every image.
 */
data class ImageSettingsState(
    val enabled: Boolean = false,
    val choice: ImageChoice? = null,
    val maxPerTask: Int = DEFAULT_MAX_PER_TASK,
    val saveEnabled: Boolean = false,
    val askBeforeSave: Boolean = true,
    val askBeforeGenerate: Boolean = false,
    /** Turned off by the user in settings when the provider/model cannot edit images; once off, image_edit is not sent to the model. */
    val supportsEdit: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_PER_TASK = 8
        const val MAX_PER_TASK_LIMIT = 50
    }
}

class ImageModelSettings(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ImageSettingsState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }

    fun setChoice(choice: ImageChoice?) = update { copy(choice = choice) }

    fun setMaxPerTask(n: Int) = update {
        copy(maxPerTask = n.coerceIn(1, ImageSettingsState.MAX_PER_TASK_LIMIT))
    }

    fun setSaveEnabled(on: Boolean) = update { copy(saveEnabled = on) }

    fun setAskBeforeSave(on: Boolean) = update { copy(askBeforeSave = on) }

    fun setAskBeforeGenerate(on: Boolean) = update { copy(askBeforeGenerate = on) }

    fun setSupportsEdit(on: Boolean) = update { copy(supportsEdit = on) }

    /** What the currently selected provider/model can do. Capabilities are initialized from the protocol, and the user can additionally turn editing off. */
    fun capabilities(): ImageCapabilities? {
        val choice = _state.value.choice ?: return null
        val base = when (choice.protocol) {
            ImageProtocol.OpenAiImages -> ImageCapabilities(
                supportsEdit = true, maxReferences = 4, maxCount = 4,
                aspects = setOf(ImageAspect.Square, ImageAspect.Portrait, ImageAspect.Landscape),
            )
            ImageProtocol.GeminiImage -> ImageCapabilities(
                supportsEdit = true, maxReferences = 4, maxCount = 4,
                aspects = ImageAspect.entries.toSet(),
            )
        }
        return if (_state.value.supportsEdit) base else base.copy(supportsEdit = false, maxReferences = 0)
    }

    private fun update(block: ImageSettingsState.() -> ImageSettingsState) {
        val next = _state.value.block()
        prefs.edit().putString(KEY, encode(next).toString()).apply()
        _state.value = next
    }

    private fun read(): ImageSettingsState {
        val raw = prefs.getString(KEY, null) ?: return ImageSettingsState()
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: ImageSettingsState()
    }

    private fun encode(state: ImageSettingsState): JsonObject = buildJsonObject {
        put("enabled", state.enabled)
        put("maxPerTask", state.maxPerTask)
        put("saveEnabled", state.saveEnabled)
        put("askBeforeSave", state.askBeforeSave)
        put("askBeforeGenerate", state.askBeforeGenerate)
        put("supportsEdit", state.supportsEdit)
        state.choice?.let { choice ->
            put("providerId", choice.providerId)
            put("model", choice.model)
            put("protocol", choice.protocol.wire)
        }
    }

    private fun decode(obj: JsonObject): ImageSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = ImageProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else ImageChoice(providerId, model, protocol)
        return ImageSettingsState(
            enabled = bool("enabled", false),
            choice = choice,
            maxPerTask = ((obj["maxPerTask"] as? JsonPrimitive)?.intOrNull ?: ImageSettingsState.DEFAULT_MAX_PER_TASK)
                .coerceIn(1, ImageSettingsState.MAX_PER_TASK_LIMIT),
            saveEnabled = bool("saveEnabled", false),
            askBeforeSave = bool("askBeforeSave", true),
            askBeforeGenerate = bool("askBeforeGenerate", false),
            supportsEdit = bool("supportsEdit", true),
        )
    }

    companion object {
        private const val KEY = "image_settings"

        fun get(context: Context) = ImageModelSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
