package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.lightFallbackChain
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.settings.visionFallbackChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * What a task takes from the App: where the models come from, the eval hooks, and whether the displayed task currently has a screen image. One per process, provided by the App;
 * tests swap out the model sources and hooks inside it.
 */
class TaskEnvironment {
    /** Virtual display ID of the displayed task; -1 when there is no task, or no display has been created yet (it is created when a shell command, an events tool or the first app launch needs it). */
    val agentDisplayId = MutableStateFlow(-1)
    /** The displayed task has already opened an app on the virtual display (merely creating the display doesn't count: it is empty when only shell commands run). */
    val agentAppShown = MutableStateFlow(false)
    /** The displayed task is currently using the built-in browser (picture-in-picture should show the web surface). */
    val webScreenOpen = MutableStateFlow(false)
    /** There is something for picture-in-picture to show: an app was opened on the virtual display, or the built-in browser is in use. A task that only created an empty display doesn't count. */
    val previewOpen: Flow<Boolean> = combine(agentAppShown, webScreenOpen) { app, web -> app || web }

    // The ModelSession handed to the three factories is the conversation this call belongs to (ModelSession.of(conversation id)): models created within the same conversation all get the same one,
    // so providers that require a session ID route and reuse caches by it

    /** The main model comes from the model configuration in settings; tests can swap in a scripted model. */
    @Volatile var modelFactory: (Context, ModelSession) -> ChatModel? = defaultModelFactory
    /** The light model (listing success criteria, etc.); when null the main model doubles for it. Swapped out together with the main model in tests, so the real model from settings isn't hit. */
    @Volatile var lightModelFactory: (Context, ModelSession) -> ChatModel? = defaultLightModelFactory
    /** The vision model (reads screenshots when verifying); null when settings have no model that can see images. */
    @Volatile var visionModelFactory: (Context, ModelSession) -> ChatModel? = defaultVisionModelFactory
    /** Vision model fallback chain */
    @Volatile var visionFallbackFactory: (Context, ModelSession) -> List<ChatModel> = defaultVisionFallbackFactory
    /** Light model fallback chain */
    @Volatile var lightFallbackFactory: (Context, ModelSession) -> List<ChatModel> = defaultLightFallbackFactory
    /** A separate decision interface that doesn't take up the main/vision/light roles. Tests can inject it; replacing the main model never implicitly calls the real decision service. */
    @Volatile var decisionModelFactory: (Context) -> DecisionModel? = defaultDecisionModelFactory

    fun decisionModel(context: Context): DecisionModel? =
        if (modelFactory !== defaultModelFactory && decisionModelFactory === defaultDecisionModelFactory) null
        else decisionModelFactory(context)

    /**
     * 任务开始唯一的模型快照。默认来源按对话覆盖解析；替换主模型的测试/评测完全不触真实 ProviderStore，
     * 只使用显式注入的其余工厂，避免默认工厂偷偷回调设置里的服务商。
     */
    fun modelSnapshot(context: Context, session: ModelSession, mainOverride: ModelChoice? = null): TaskModelSnapshot {
        if (modelFactory !== defaultModelFactory) {
            return TaskModelSnapshot(
                main = modelFactory(context, session),
                light = if (lightModelFactory !== defaultLightModelFactory) lightModelFactory(context, session) else null,
                vision = if (visionModelFactory !== defaultVisionModelFactory) visionModelFactory(context, session) else null,
                lightFallbacks = if (lightFallbackFactory !== defaultLightFallbackFactory) lightFallbackFactory(context, session).toList() else emptyList(),
                visionFallbacks = if (visionFallbackFactory !== defaultVisionFallbackFactory) visionFallbackFactory(context, session).toList() else emptyList(),
                routeLightSeparately = true,
                conversationChoice = mainOverride,
            )
        }
        val captured = TaskModelSnapshot.capture(ProviderStore.get(context), session, mainOverride)
        return captured.copy(
            light = if (lightModelFactory !== defaultLightModelFactory) lightModelFactory(context, session) else captured.light,
            vision = if (visionModelFactory !== defaultVisionModelFactory) visionModelFactory(context, session) else captured.vision,
            lightFallbacks = if (lightFallbackFactory !== defaultLightFallbackFactory) lightFallbackFactory(context, session).toList() else captured.lightFallbacks,
            visionFallbacks = if (visionFallbackFactory !== defaultVisionFallbackFactory) visionFallbackFactory(context, session).toList() else captured.visionFallbacks,
        )
    }

    /** 评测跑器用的钩子;产品里全是默认值。 */
    val evalHooks = EvalHooks()

    /** An Application that provides the process-wide instance. */
    interface Owner {
        val tasks: TaskEnvironment
    }

    companion object {
        val defaultModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Main, s) }
        val defaultLightModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Light, s) }
        val defaultVisionModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Vision, s) }
        val defaultVisionFallbackFactory: (Context, ModelSession) -> List<ChatModel> = { c, s -> ProviderStore.get(c).visionFallbackChain(s) }
        val defaultLightFallbackFactory: (Context, ModelSession) -> List<ChatModel> = { c, s -> ProviderStore.get(c).lightFallbackChain(s) }
        val defaultDecisionModelFactory: (Context) -> DecisionModel? = { c -> DecisionSettings.get(c).createModel() }

        fun of(context: Context): TaskEnvironment = (context.applicationContext as Owner).tasks
    }
}
