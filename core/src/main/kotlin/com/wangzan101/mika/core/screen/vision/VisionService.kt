package com.wangzan101.mika.core.screen.vision

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * Asks a vision model to look at images and answer a question. Only constructed when a model that can see images is configured;
 * the caller is responsible for never passing in frames of protected pages or payment pages.
 *
 * Generic multi-level waterfall fallback (Waterfall Chain) is now supported:
 * when the primary vision model hits 429, 503, a timeout or exhausted quota, it switches smoothly to the fallback vision model, keeping the task from being interrupted or going blind.
 */
class VisionService(
    private val model: ChatModel,
    val ops: ImageOps,
    private val fallbacks: List<ChatModel> = emptyList(),
    private val onModelSwitch: ((from: ChatModel, to: ChatModel, error: ModelError) -> Unit)? = null,
) {
    /** Builds the complete vision waterfall chain */
    constructor(
        models: List<ChatModel>,
        ops: ImageOps,
        onModelSwitch: ((from: ChatModel, to: ChatModel, error: ModelError) -> Unit)? = null,
    ) : this(models.first(), ops, models.drop(1), onModelSwitch)

    /** Vision model chain: the primary model + all fallback models */
    val chain: List<ChatModel> = listOf(model) + fallbacks

    /** Because the actually responding model is not distinguished, grounding can only be promised as verified when the whole chain is verified. */
    val groundingVerified: Boolean get() = chain.all { it.capabilities.groundingVerified }

    data class Answer(val text: String, val groundingVerified: Boolean)

    /**
     * A single call without tools.
     * When the primary vision model throws a [ModelError], if the chain has fallback models it switches automatically and keeps executing.
     */
    suspend fun ask(system: String, text: String, images: List<ByteArray>): String =
        askWithGrounding(system, text, images).text

    /** The grounding-verified flag is returned with this answer, so concurrency or later re-checks cannot change the property of the model the coordinates belong to. */
    suspend fun askWithGrounding(system: String, text: String, images: List<ByteArray>): Answer {
        val parts = listOf(Part.Text(text)) + images.map { ModelImage.part(it, ops) }
        val messages = listOf(Message(Role.User, parts, Author.Mika))
        var lastError: ModelError? = null

        for (i in chain.indices) {
            val candidate = chain[i]
            try {
                val reply = candidate.complete(system, messages, emptyList())
                val answer = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                if (answer.isBlank()) throw ModelError.BadResponse("视觉模型没有给出回答")
                return Answer(answer, candidate.capabilities.groundingVerified)
            } catch (e: ModelError) {
                lastError = e
                val next = chain.getOrNull(i + 1)
                if (next != null) {
                    onModelSwitch?.invoke(candidate, next, e)
                    continue
                }
            }
        }
        throw lastError ?: ModelError.BadResponse("视觉模型没有给出回答")
    }
}
