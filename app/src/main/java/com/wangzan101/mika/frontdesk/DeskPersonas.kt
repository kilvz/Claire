package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.frontdesk.DeskPersona
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.settings.FeatureFlags

/**
 * The persona the front desk uses right now, resolved the same way as backend tasks (see RunPrepStages): the active one
 * chosen in settings, the same for all conversations; if the persona feature is off or none is selected, the default
 * nickname and no persona layer. Resolved once per chat turn and once per call connection.
 */
fun deskPersona(app: MikaApp): DeskPersona {
    val persona = if (FeatureFlags.get(app).persona()) app.personas.active() else null
    val assistant = PersonaPrompt.nickname(persona?.nickname)
    return DeskPersona(assistant, persona?.let { PersonaPrompt.layer(it.name, it.prompt, it.style, assistant) })
}
