package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.user.UserScreen
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.core.screen.user.UserScreenPrompt
import com.wangzan101.mika.core.screen.user.UserScreenSource
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Screens the user sends during a task: reads the description and screenshots, and replaces the utterance that carries a screen with the form shown to the model (see [UserScreenPrompt]).
 * Screens in this utterance (the opening utterance, an interjection) are given in full: the words, the screenshots (when [seesImages]), and the details; those from earlier turns collapse to a single line.
 * When there is no [source] (this entry point has no user screen), that line is left as-is.
 */
internal class UserScreenTurns(
    private val source: UserScreenSource?,
    private val zone: ZoneId,
    private val seesImages: Boolean,
    /** Note in the details that a vision model can be asked (view_user_screen exists, a vision model is available, and the main model cannot see images). */
    private val canAskVision: Boolean,
    private val image: (ByteArray) -> Part.Image,
    /** A screen given in full for this utterance (recorded in the evidence ledger). */
    private val seen: (UserScreen) -> Unit = {},
) {
    /**
     * The converted utterance: [said] is the user's utterance (the literal words given to the model), [images] are embedded in it, and [details] are the details that follow it (source → body);
     * [words] are the characters the user typed themselves (memory quotes, intent detection, language hints, and the answer's language check look only at these).
     */
    class Rendered(val said: String, val images: List<Part>, val details: List<Pair<String, String>>, val words: String)

    private val cache = HashMap<String, UserScreen?>()

    /** Replaces the utterance with the form shown to the model when it carries a screen; null when it does not. */
    suspend fun render(raw: String): Rendered? {
        val p = UserScreenMessage.parse(raw)
        if (!p.hasScreen) return null
        val screens = p.screens.map { it to load(it) }
        screens.forEach { (_, s) -> s?.let(seen) }
        val said = screens.mapIndexed { i, (id, s) -> UserScreenPrompt.said(id, s, if (i == screens.lastIndex) p.words else "", zone) }
            .joinToString("\n")
        val images = if (!seesImages) emptyList() else screens.mapNotNull { (id, s) ->
            if (s == null || s.blocked) null else read { source?.image(id) }?.let(image)
        }
        val details = screens.mapNotNull { (_, s) ->
            s?.let { "user's screen · ${it.app}" to UserScreenPrompt.detail(it, p.words.isEmpty(), canAskVision && !it.blocked, zone) }
        }
        return Rendered(said, images, details, p.words)
    }

    /** What the user said in earlier turns (the utterance and interjections): the screen line is collapsed into a one-line description. */
    suspend fun collapse(turns: List<Turn>): List<Turn> {
        if (turns.none { t -> UserScreenMessage.has(t.userText) || t.steers.any(UserScreenMessage::has) }) return turns
        turns.flatMap { listOf(it.userText) + it.steers }.flatMap { UserScreenMessage.parse(it).screens }.distinct().forEach { load(it) }
        fun one(text: String) = UserScreenPrompt.collapse(text, { cache[it] }, zone)
        return turns.map { t -> t.copy(userText = one(t.userText), steers = t.steers.map(::one)) }
    }

    /** When the answer carries a screen (the capsule was double-tapped while Claire was asking you): convert it into a text passage; screenshots must be viewed with the review tool. */
    suspend fun asText(raw: String): String {
        val r = render(raw) ?: return raw
        return (listOf(r.said) + r.details.map { it.second }).joinToString("\n\n")
    }

    private suspend fun load(id: String): UserScreen? = cache.getOrPut(id) { read { source?.screen(id) } }

    private inline fun <T> read(block: () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
