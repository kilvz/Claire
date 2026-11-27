package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import kotlinx.serialization.json.add
import java.io.IOException

// Provider storage: role selections, fallback chains, model groups and per-model settings
/**
 * Assign a role: whatever the user picked wins, capability flags are not consulted. When a concrete model is chosen as the main model, its tool-calls flag is set to true (the runtime relies on it to send tool definitions);
 * the vision flag does not modify what is stored but is derived in [configFor] from the current selection, and follows back when the selection changes. Configs that nobody uses afterwards are deleted.
 * Returns false when the config does not exist, the model name is empty, or the role does not accept this kind of selection (see [RoleChoices.with]).
 */
fun ProviderStore.choose(role: ModelRole, choice: RoleChoice): Boolean {
    return synchronized(this) {
        if (choice is RoleChoice.Model && configById(choice.configId)?.canUseForChat() != true) return false
        if (choice is RoleChoice.Group && !groupUsable(choice.groupId, role)) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else if (role == ModelRole.Main) c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                else c
            }
        }
        _roles.value = next
        dropMainFromFallbacks()
        prune()
        writeConfig()
        return true
    }
}

/**
 * Ensure a config exists for [model] under provider [providerId] (created from [caps] if absent; the tool-calls flag is set to true because it must be usable as the main model),
 * and return the config id; it is not assigned to any role. Returns null if the provider does not exist.
 */
fun ProviderStore.ensureConfigId(providerId: String, model: String, caps: Capabilities): String? {
    return synchronized(this) {
        val p = provider(providerId) ?: return null
        val name = model.trim()
        if (name.isEmpty()) return null
        val existing = _configs.value.firstOrNull { it.providerId == p.id && it.model == name }
        if (existing != null) {
            if (!existing.capabilities.toolCalls) {
                _configs.value = _configs.value.map { c ->
                    if (c.id == existing.id) c.copy(capabilities = c.capabilities.copy(toolCalls = true)) else c
                }
                writeConfig()
            }
            return existing.id
        }
        val id = newId()
        save(ProviderConfig(id, p.kind, p.baseUrl, name, caps.copy(toolCalls = true), p.name, p.id), null)
        return id
    }
}

/** Remove the role's own config from its fallback chain if it slipped in. */
internal fun ProviderStore.dropRoleFromFallbacks(role: ModelRole) {
    val roleId = _roles.value.resolve(role) ?: return
    val current = _roles.value.fallbacks(role)
    if (roleId !in current) return
    _roles.value = _roles.value.withFallbacks(role, current.filter { it != roleId })
}

internal fun ProviderStore.dropMainFromFallbacks() {
    dropRoleFromFallbacks(ModelRole.Main)
}

/** Whether [model] under [providerId] is the current main model (used to block it when a fallback is entered manually, MK-1 D1). */
fun ProviderStore.isMainModel(providerId: String, model: String): Boolean =
    configFor(ModelRole.Main)?.let { it.providerId == providerId && it.model == model } == true

/**
 * Change slot [slot] of the given role's fallback chain: a null [configId] removes that slot (later entries shift forward);
 * a config already in the chain is moved to the target slot (it does not occupy two slots); a new config replaces the entry at the target slot when there is one, and is appended at the end when the chain is not full
 * (gaps in the middle are filled in too, no empty slot is left). The chain holds at most [MAX_FALLBACKS] entries.
 */
fun ProviderStore.setFallback(role: ModelRole, slot: Int, configId: String?): Boolean {
    return synchronized(this) {
        if (slot < 0 || slot >= MAX_FALLBACKS) return false
        if (configId != null && configById(configId)?.canUseForChat() != true) return false
        val current = _roles.value.fallbacks(role)
        val next = when {
            configId == null -> if (slot in current.indices) current.toMutableList().apply { removeAt(slot) } else return true
            configId in current -> {
                val rest = current.filter { it != configId }
                rest.toMutableList().apply { add(minOf(slot, rest.size), configId) }
            }
            slot in current.indices -> current.toMutableList().apply { this[slot] = configId }
            current.size < MAX_FALLBACKS -> current + configId
            else -> return false
        }
        if (next == current) return true
        _roles.value = _roles.value.withFallbacks(role, next)
        dropRoleFromFallbacks(role)
        prune()
        writeConfig()
        return true
    }
}

/** Keeps the legacy method signature fully compatible */
fun ProviderStore.setMainFallback(slot: Int, configId: String?): Boolean = synchronized(this) { setFallback(ModelRole.Main, slot, configId) }

/**
 * Move position [from] of a fallback chain to position [to], keeping the rest in their original order; out of range is
 * null, and an unchanged position returns the same chain. Pure function used by [ProviderStore.moveFallback], the voice
 * fallback chains (`VoiceFallbacks.moved`) and the up/down buttons in the UI.
 */
internal fun <T> movedFallbacks(chain: List<T>, from: Int, to: Int): List<T>? {
    if (from !in chain.indices || to !in chain.indices) return null
    if (from == to) return chain
    return chain.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Move position [from] to position [to] in the fallback chain for [role] (up/down means adjacent positions), changing only
 * that one role's chain. Out of range returns false and changes nothing; like [setFallback], it changes the chain under the
 * lock and writes the same configuration file.
 */
fun ProviderStore.moveFallback(role: ModelRole, from: Int, to: Int): Boolean {
    return synchronized(this) {
        val current = _roles.value.fallbacks(role)
        val next = movedFallbacks(current, from, to) ?: return false
        if (next == current) return true
        _roles.value = _roles.value.withFallbacks(role, next)
        writeConfig()
        return true
    }
}

/** This group; null when it does not exist. */
fun ProviderStore.group(id: String): ModelGroup? = _groups.value.firstOrNull { it.id == id }

/** The group bound to this role; null when no group is bound or the group no longer exists. */
fun ProviderStore.groupFor(role: ModelRole): ModelGroup? = _roles.value.resolveGroup(role)?.let(::group)

/**
 * Whether the group can be selected for [role]: the main model requires every member to support tools, vision requires at least one member with vision, and light imposes no requirement.
 * An empty group, or one whose members point to configs that no longer exist, counts as unusable.
 */
fun ProviderStore.groupUsable(groupId: String, role: ModelRole): Boolean =
    group(groupId)?.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities } == true

/** Whether a group can be used for a role; the UI uses this to badge the group list. */
fun ProviderStore.groupUsable(group: ModelGroup, role: ModelRole): Boolean =
    group.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities }

/**
 * Add a group, or replace the group with the same id. Every config referenced by a member must still exist; a main-model group needs all members to support tools, and a vision group needs at least one member with vision.
 * Returns false when these conditions are not met, leaving the existing group unchanged.
 */
fun ProviderStore.saveGroup(group: ModelGroup): Boolean {
    return synchronized(this) {
        if (group.members.any { m -> _configs.value.none { it.id == m.configId } }) return false
        // Newly added members need a usable model config; members already in the group from older settings are kept, leaving it to the user to resolve them in the provider details
        val before = group(group.id)?.members?.mapTo(hashSetOf()) { it.configId }.orEmpty()
        if (group.members.any { m -> m.configId !in before && configById(m.configId)?.canUseForChat() != true }) return false
        // Only explicitly bound roles are considered; Vision/Light in "same as main" mode does not count as using this group and must not block saving
        val usedRoles = ModelRole.entries.filter { role -> (_roles.value[role] as? RoleChoice.Group)?.groupId == group.id }
        if (usedRoles.any { role -> !group.usableFor(role) { id -> _configs.value.firstOrNull { it.id == id }?.capabilities } }) return false
        val i = _groups.value.indexOfFirst { it.id == group.id }
        _groups.value = if (i >= 0) _groups.value.toMutableList().also { it[i] = group } else _groups.value + group
        repairGroupRoles()
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        writeConfig()
        return true
    }
}

/** Delete a group; roles using it for main/vision/light fall back to their defaults. Member configs nobody uses anymore are deleted along with it. */
fun ProviderStore.deleteGroup(groupId: String) {
    synchronized(this) {
        _groups.value = _groups.value.filterNot { it.id == groupId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        writeConfig()
    }
}

/** Used when creating groups/adding members: reuse the config for this provider's model if it exists, otherwise create one from [caps] (assigning no role). */
fun ProviderStore.ensureConfig(providerId: String, model: String, caps: Capabilities): String? {
    return synchronized(this) {
        val p = provider(providerId) ?: return null
        if (model.isBlank()) return null
        _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.let { return it.id }
        val id = newId()
        save(ProviderConfig(id, p.kind, p.baseUrl, model, caps, p.name, p.id), null)
        return id
    }
}

internal fun ProviderStore.usableGroupIds(): Set<String> = _groups.value.filter { g ->
    g.enabledMembers.any { m -> _configs.value.any { it.id == m.configId } }
}.map { it.id }.toSet()

/** After a group loses members or its capabilities change, roles explicitly bound to it that no longer satisfy the role requirements fall back to their defaults. */
internal fun ProviderStore.repairGroupRoles() {
    var roles = _roles.value
    for (role in ModelRole.entries) {
        val choice = roles[role]
        if (choice !is RoleChoice.Group || groupUsable(choice.groupId, role)) continue
        roles = when (role) {
            ModelRole.Main -> roles.copy(main = null, mainGroup = null)
            ModelRole.Vision -> roles.copy(vision = RoleChoice.Off)
            ModelRole.Light -> roles.copy(light = RoleChoice.SameAsMain)
        }
    }
    _roles.value = roles
}

/** Changes this role only; rejected for unsupported protocols, roles that follow the main model, or levels the list explicitly reports as unsupported. */
fun ProviderStore.setReasoningEffort(role: ModelRole, effort: String?): Boolean {
    return synchronized(this) {
        if (_roles.value[role] !is RoleChoice.Model && _roles.value[role] !is RoleChoice.Group) return false
        val cfgs = groupFor(role)?.enabledMembers?.mapNotNull { m -> _configs.value.firstOrNull { it.id == m.configId } }
            ?: listOfNotNull(configFor(role))
        if (cfgs.isEmpty()) return false
        if (effort != null && cfgs.none { cfg ->
                val options = ReasoningOptions.of(cfg, listedOf(cfg))
                options != null && effort in options.levels
            }) return false
        _roles.value = _roles.value.copy(reasoningEfforts =
            if (effort == null) _roles.value.reasoningEfforts - role else _roles.value.reasoningEfforts + (role to effort))
        writeConfig()
        return true
    }
}

fun ProviderStore.setModelContext(configId: String, settings: ModelContextSettings): Boolean {
    return synchronized(this) {
        val cfg = _configs.value.firstOrNull { it.id == configId } ?: return false
        val p = provider(cfg.providerId) ?: return false
        if (!settings.validFor(cfg.capabilities.contextTokens)) return false
        val saved = if (settings == ModelContextSettings()) p.contextSettings - cfg.model
            else p.contextSettings + (cfg.model to settings)
        return saveProviderSettings(p.copy(contextSettings = saved))
    }
}

fun ProviderStore.setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
    return synchronized(this) {
        val p = provider(providerId) ?: return false
        if (model.isBlank() || !p.supportsServiceTier(model)) return false
        val tiers = if (tier == null) p.serviceTiers - model else p.serviceTiers + (model to tier)
        return saveProviderSettings(p.copy(serviceTiers = tiers))
    }
}

/** Update this provider's daily token budget; on a failed write both the in-memory and the on-disk values are kept. */
fun ProviderStore.setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
    return synchronized(this) {
        if (limit != null && !TokenBudgetLimits.valid(limit)) return false
        val p = provider(providerId) ?: return false
        return saveProviderSettings(p.copy(dailyTokenBudget = limit))
    }
}

internal fun ProviderStore.saveProviderSettings(p: Provider): Boolean {
    val updated = _providers.value.map { if (it.id == p.id) p else it }
    try {
        writeAtomically(configFile, configJson(updated))
    } catch (_: Exception) {
        return false
    }
    _providers.value = updated
    return true
}

/** Record whether the user says [model] can see images (see [Provider.visionOverrides]); returns false if the provider does not exist. */
fun ProviderStore.setModelVision(providerId: String, model: String, sees: Boolean): Boolean {
    return synchronized(this) {
        val p = provider(providerId) ?: return false
        _providers.value = _providers.value.map { if (it.id == p.id) p.copy(visionOverrides = p.visionOverrides + (model to sees)) else it }
        writeConfig()
        return true
    }
}

internal fun ProviderStore.saveRoleSelection(
    providers: List<Provider>, configs: List<ProviderConfig>, roles: RoleChoices, groups: List<ModelGroup> = _groups.value,
): Boolean {
    try {
        writeAtomically(configFile, configJson(providers, configs, roles, groups))
    } catch (_: IOException) {
        return false
    }
    _providers.value = providers
    _configs.value = configs
    _groups.value = groups
    _roles.value = roles
    return true
}

/** The vision model's "vision grounding verified" flag (toggled in developer options); when vision uses the main model, the main model's config is changed instead. Does nothing when there is no vision model. */
fun ProviderStore.setVisionGroundingVerified(verified: Boolean) {
    synchronized(this) {
        // When vision is a concrete model, change that entry; when it is a group (including "use main model" resolving to the main group), change every enabled member of the group
        val ids = _roles.value.resolveConfig(ModelRole.Vision)?.let { setOf(it) }
            ?: groupFor(ModelRole.Vision)?.enabledMembers?.map { it.configId }?.toSet()
            ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id in ids) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }
}
