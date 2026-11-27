package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Provider storage: connecting, remembering models, verification state and removing providers
/**
 * Adds or replaces the model config with the same id (a newly added one must still be handed to [choose], otherwise nobody uses it and it is deleted on the next load).
 * The config is attached to the provider named by [ProviderConfig.providerId] (API kind, address and name always come from the provider);
 * when that is unset or does not exist, the matching provider is found by API kind, address and name, and a new one is created if none is found.
 * [apiKey] is the key of that provider: null means leave it unchanged, an empty string means clear it.
 */
fun ProviderStore.save(cfg: ProviderConfig, apiKey: String?) {
    synchronized(this) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        if (attached.id in _roles.value.referenced && !attached.canUseForChat()) {
            require(list.getOrNull(i)?.let { it.providerId == p.id && it.model == attached.model } == true)
        }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }
}

/**
 * "Test and save" connected a provider: stores it (name, API kind, address, key) and the listed models.
 * If a provider with the same API kind, address and name already exists, that one is updated (new key, new list) instead of being added again.
 * When [apiKey] is null the stored key is left unchanged. [verified] holds the model names that replied during this trial call, recorded into [Provider.verifiedModels];
 * [typed] holds those among them that the user typed in by hand, recorded into [Provider.typedModels].
 * [signIn] records where the key came from (see [Provider.signIn]): null when the user entered the key themselves, otherwise the vendor marker for an authorized key.
 * The two origins are separate providers (not merged even with the same address and name): authorization never displaces a user-entered key, and signing out deletes only the authorized one.
 * [untested] true means the user saved it by skipping the test (see [Provider.savedUntested]): no models listed and no trial call.
 * If this provider already exists and has listed models or been verified, this parameter does not mark it as untested again.
 */
fun ProviderStore.connect(
    name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
    sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
    verified: Set<String> = emptySet(),
    signIn: String? = null,
    typed: Set<String> = emptySet(),
    untested: Boolean = false,
    nickname: String? = null,
): Provider {
    return synchronized(this) {
        val found = findOrAddProvider(name, kind, baseUrl, signIn)
        // Skipping the test leaves no list to store: keep what this provider listed before, and do not overwrite the existing list with an empty one
        val listed = if (untested && models.isEmpty()) found.models ?: models else models
        val p = found.copy(
            models = listed, sessionHeader = sessionHeader,
            verifiedModels = found.verifiedModels + verified, typedModels = found.typedModels + typed,
            // "Untested" only when this run did not test, the provider has no verified models and no list was ever listed; every other case counts as tested
            savedUntested = untested && verified.isEmpty() && found.verifiedModels.isEmpty() && listed.isEmpty(),
            // 这次没填备注就留着原来的;备注不参与找同一家(见 [Provider.nickname])
            nickname = Provider.cleanNickname(nickname) ?: found.nickname,
        )
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        repairUnavailableReasoningEfforts()
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
        return p
    }
}

/** 改这家的备注(见 [Provider.nickname]);空的就是去掉备注。和其它改动一样在锁里改、写同一个配置文件。 */
fun ProviderStore.setProviderNickname(providerId: String, nickname: String?) {
    synchronized(this) {
        val p = provider(providerId) ?: return
        val clean = Provider.cleanNickname(nickname)
        if (p.nickname == clean) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(nickname = clean) else it
        }
        writeConfig()
    }
}

/** 记下服务商最近一次列出的模型。列出来了,就说明「没测过」不再成立(跳过测试存下的那家在这里清掉标记)。 */
fun ProviderStore.rememberModels(providerId: String, models: List<ListedModel>) {
    synchronized(this) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(models = models, savedUntested = false) else it
        }
        repairUnavailableReasoningEfforts()
        writeConfig()
    }
}

/**
 * Records that [model] got a reply from its trial call at this provider (see [Provider.verifiedModels]).
 * Passing the test means "untested" no longer holds, so the flag is cleared here for a provider saved by skipping the test — it must also be cleared when this model was
 * already in [Provider.verifiedModels], so an early return is not allowed.
 */
fun ProviderStore.markVerified(providerId: String, model: String) {
    synchronized(this) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels && !p.savedUntested) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model, savedUntested = false) else it
        }
        writeConfig()
    }
}

/** Records a [model] the user typed in by hand at this provider (see [Provider.typedModels]). */
fun ProviderStore.markTyped(providerId: String, model: String) {
    synchronized(this) {
        val p = provider(providerId) ?: return
        if (model in p.typedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(typedModels = it.typedModels + model) else it }
        writeConfig()
    }
}

/**
 * Deletes a provider: its model configs and key are deleted along with it. Roles using it fall back to the defaults (the main model becomes unset, vision is not used, the light model uses the main model);
 * the UI has already stated before deleting which roles must be re-chosen. When a ChatGPT one is deleted, the registered client_id is forgotten too (the host identifier is kept):
 * the next sign-in registers again, so a different account can be used.
 */
fun ProviderStore.removeProvider(providerId: String) {
    synchronized(this) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        val gone = _configs.value.filter { it.providerId == providerId }.map { it.id }.toSet()
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        // Groups lose these members; groups left with no members are deleted, and roles using them fall back to the defaults
        _groups.value = _groups.value
            .map { g -> g.copy(members = g.members.filterNot { it.configId in gone }) }
            .filter { it.members.isNotEmpty() }
        repairGroupRoles()
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet(), usableGroupIds())
        prune()
        if (providerId in keys) setKey(providerId, "")
        _providerKeyRevisions.value -= providerId
        writeConfig()
    }
}

internal fun ProviderStore.findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String, signIn: String? = null): Provider {
    val key = ProviderConnection.keyOf(kind, baseUrl, name)
    _providers.value.firstOrNull { it.key == key && it.signIn == signIn }?.let { return it }
    val p = Provider(newId(), name.trim(), kind, baseUrl.trim(), signIn = signIn)
    _providers.value = _providers.value + p
    return p
}
