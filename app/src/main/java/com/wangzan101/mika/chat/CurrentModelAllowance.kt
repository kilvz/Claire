package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.effectiveRoles

/**
 * What the usage panel's "Current model" section shows: the main model's display name and its associated providers (allowance is queried per provider).
 */
data class CurrentModelAllowance(
    val modelLabel: String,
    val providers: List<Provider>,
)

/**
 * When the main model is a concrete model, show its model id (consistent with the model row in settings, see [com.wangzan101.mika.settings.ui.models.ModelsCopy.modelLine];
 * in configs created by `use()`, `ProviderConfig.name` is the provider name and cannot be used as the model name), and query the allowance only for that one provider;
 * when it is a model group, show the group name, and show allowances deduplicated by the providers of enabled members. When no main model is selected, or the referenced config or provider
 * no longer exists (key deleted, config pruned), return null and the panel does not show this section.
 */
fun currentModelAllowance(
    roles: RoleChoices, configs: List<ProviderConfig>, groups: List<ModelGroup>, providers: List<Provider>,
): CurrentModelAllowance? {
    val configId = roles.resolveConfig(ModelRole.Main)
    if (configId != null) {
        val config = configs.firstOrNull { it.id == configId } ?: return null
        val provider = providers.firstOrNull { it.id == config.providerId } ?: return null
        return CurrentModelAllowance(config.model, listOf(provider))
    }
    val groupId = roles.resolveGroup(ModelRole.Main) ?: return null
    val group = groups.firstOrNull { it.id == groupId } ?: return null
    val used = group.enabledMembers
        .mapNotNull { member -> configs.firstOrNull { it.id == member.configId } }
        .mapNotNull { config -> providers.firstOrNull { it.id == config.providerId } }
        .distinctBy { it.id }
    if (group.name.isBlank() && used.isEmpty()) return null
    return CurrentModelAllowance(group.name, used)
}

/**
 * This conversation's current main model: [mainOverride] is the conversation's own stored choice (the main-model entry above the input field), resolved through
 * the same entry point as task start (`ProviderStore.effectiveRoles`) — the model shown in the panel must match the one this conversation's next task
 * actually uses; when the choice is invalid (config deleted, group unavailable) it falls back to the global main model.
 */
fun currentModelAllowance(store: ProviderStore, mainOverride: ModelChoice? = null): CurrentModelAllowance? =
    currentModelAllowance(store.effectiveRoles(mainOverride), store.configs.value, store.groups.value, store.providers.value)
