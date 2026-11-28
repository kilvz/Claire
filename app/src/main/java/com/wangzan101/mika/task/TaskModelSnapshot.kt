package com.wangzan101.mika.task

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.effectiveRoles
import com.wangzan101.mika.settings.lightFallbackChain
import com.wangzan101.mika.settings.mainFallbackChain
import com.wangzan101.mika.settings.mainProviderId
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.settings.untestedMainProvider
import com.wangzan101.mika.settings.usableMainChoice
import com.wangzan101.mika.settings.visionFallbackChain

/** Immutable model routing for one task; changes to settings or conversation overrides after the task starts do not change this instance. */
data class TaskModelSnapshot(
    val main: ChatModel?,
    val light: ChatModel?,
    val vision: ChatModel?,
    val mainFallbacks: List<ChatModel> = emptyList(),
    val mainProviderIds: List<String?> = emptyList(),
    val lightFallbacks: List<ChatModel> = emptyList(),
    val visionFallbacks: List<ChatModel> = emptyList(),
    val retestProvider: Provider? = null,
    val routeLightSeparately: Boolean = true,
    val conversationChoice: ModelChoice? = null,
) {
    companion object {
        /** Resolve the full routing once under the same ProviderStore lock, so a settings write cannot produce a snapshot that is half new and half old. */
        fun capture(store: ProviderStore, session: ModelSession, mainOverride: ModelChoice? = null): TaskModelSnapshot =
            synchronized(store) {
                val choices = store.effectiveRoles(mainOverride)
                val (mainFallbacks, fallbackProviders) = store.mainFallbackChain(session, mainOverride)
                TaskModelSnapshot(
                    main = store.modelFor(ModelRole.Main, session, mainOverride),
                    light = store.modelFor(ModelRole.Light, session, mainOverride),
                    vision = store.modelFor(ModelRole.Vision, session, mainOverride),
                    mainFallbacks = mainFallbacks.toList(),
                    mainProviderIds = (listOf(store.mainProviderId(mainOverride)) + fallbackProviders).toList(),
                    lightFallbacks = store.lightFallbackChain(session, mainOverride).toList(),
                    visionFallbacks = store.visionFallbackChain(session, mainOverride).toList(),
                    retestProvider = store.untestedMainProvider(mainOverride),
                    routeLightSeparately = !choices.sameModel(ModelRole.Main, ModelRole.Light),
                    conversationChoice = store.usableMainChoice(mainOverride),
                )
            }
    }
}
