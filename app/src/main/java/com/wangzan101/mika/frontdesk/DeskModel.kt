package com.wangzan101.mika.frontdesk

import android.content.Context
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.voice.model.ThinkModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment

/**
 * The thinking model used by the front desk (chat and calls): taken from the light/main model selected under "call mode";
 * if the light model is selected but not configured, falls back to the main model. Returns null when neither is
 * configured, so entry points can guide the user to settings instead of failing silently.
 */
fun deskModel(
    context: Context,
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): ChatModel? {
    val think = VoiceModelEnvironment.get(context).catalog.state.value.selections.think
    val store = ProviderStore.get(context)
    return when (think) {
        ThinkModel.Light -> store.modelFor(ModelRole.Light, session, mainOverride) ?: store.modelFor(ModelRole.Main, session, mainOverride)
        ThinkModel.Main -> store.modelFor(ModelRole.Main, session, mainOverride)
    }
}
