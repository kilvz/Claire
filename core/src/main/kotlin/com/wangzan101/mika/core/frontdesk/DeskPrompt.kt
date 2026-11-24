package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.Prompts

/**
 * The persona the front desk uses, the same set as background tasks: [assistant] is the assistant's name (Claire by default), [layer] is the assembled persona layer
 * ([PersonaPrompt.layer], null when no persona is selected or everything is default).
 */
data class DeskPersona(val assistant: String = PersonaPrompt.DEFAULT_NICKNAME, val layer: String? = null)

/**
 * The front desk's system prompt: one for talking to the user, one for speaking for the owner on a call.
 * Spoken contexts (voice calls, phone calls) prepend [LlmBrain.SPOKEN_STYLE];
 * the phone version fills in this call's task brief and call rules verbatim; even {{…}} inside is kept as written.
 */
object DeskPrompt {
    /**
     * The front-desk prompt for talking to the user; when [spoken] is true, replies must be written to be read aloud. Layered like the background:
     * role text (the name goes into the first sentence) → the rename note when the name changed (`persona/renamed.md`) → the persona layer.
     * The same persona is byte-for-byte identical; with the default persona the last two layers are absent.
     */
    fun user(spoken: Boolean, persona: DeskPersona = DeskPersona()): String {
        // The name sits outside the persona fence, so clean it again here rather than relying on the caller
        val name = PersonaPrompt.nickname(persona.assistant)
        val body = listOfNotNull(
            Prompts.fill(Prompts.role("front_desk"), mapOf("assistant" to name)),
            if (name == PersonaPrompt.DEFAULT_NICKNAME) null else Prompts.fill(Prompts.read("persona/renamed.md"), mapOf("assistant" to name)),
            persona.layer,
        ).joinToString("\n\n")
        return if (spoken) LlmBrain.SPOKEN_STYLE + "\n\n" + body else body
    }

    /** The front-desk prompt for phone calls: [brief] is this call's task brief and [policy] the call rules, both filled in verbatim. */
    fun phone(brief: String, policy: String): String =
        LlmBrain.SPOKEN_STYLE + "\n\n" +
            Prompts.fill(Prompts.role("front_desk_phone"), mapOf("brief" to brief, "policy" to policy))
}
