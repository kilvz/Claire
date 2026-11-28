package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.GroupLimits
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.usage.TokenBudgetLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import com.wangzan101.mika.settings.ProviderStore.Companion.LEGACY_DEFAULT_CONTEXT_TOKENS
import com.wangzan101.mika.settings.ProviderStore.Companion.USE_MODEL
import com.wangzan101.mika.settings.ProviderStore.Companion.USE_GROUP
import com.wangzan101.mika.settings.ProviderStore.Companion.USE_MAIN
import com.wangzan101.mika.settings.ProviderStore.Companion.USE_NONE
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// Provider store: reads and writes the config file, migrates and repairs old data
/** Delete model configs that no role, group or conversation uses. Keys live on the provider and are unaffected. */
internal fun ProviderStore.prune() {
    val used = _roles.value.referenced + _groups.value.flatMap { g -> g.members.map { it.configId } } + externalConfigReferences()
    _configs.value = _configs.value.filter { it.id in used }
}

internal fun ProviderStore.load() {
    if (configFile.isFile) runCatching {
        val root = json.parseToJsonElement(configFile.readText()).jsonObject
        _providers.value = root["services"]?.jsonArray?.mapNotNull { runCatching { providerOf(it.jsonObject) }.getOrNull() }.orEmpty()
        _configs.value = root["providers"]?.jsonArray?.mapNotNull { runCatching { configOf(it.jsonObject) }.getOrNull() }.orEmpty()
        _groups.value = root["groups"]?.jsonArray?.mapNotNull { runCatching { groupOf(it.jsonObject) }.getOrNull() }.orEmpty()
        val uses = root["uses"]?.jsonObject
        _roles.value = if (uses != null) choicesOf(uses) else migrateRoles(root["roles"]?.jsonObject)
    }
    if (keyFile.isFile) runCatching {
        keys = json.parseToJsonElement(keyFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        keys.forEach { (pid, _) ->
            providerKey(pid)?.let { rawKey -> KeyPoolManager.shared.register(pid, rawKey) }
        }
    }
    migrate()
    repairStaleContextDefaults()
    // Configs referenced by group members no longer exist (old files, deleted providers): drop them from the group; delete groups left with no members
    val validConfigIds = _configs.value.map { it.id }.toSet()
    _groups.value = _groups.value
        .map { g -> g.copy(members = g.members.filter { it.configId in validConfigIds }) }
        .filter { it.members.isNotEmpty() }
    repairGroupRoles()
    // Choices pointing at non-existent configs/groups fall back to the defaults and unused configs are deleted; write back only when this differs from the file (including roles just migrated)
    _roles.value = _roles.value.keepOnly(validConfigIds, usableGroupIds())
    dropMainFromFallbacks()
    repairUnavailableReasoningEfforts()
    prune()
    if (configFile.isFile) {
        val text = configJson()
        if (runCatching { configFile.readText() }.getOrNull() != text) writeAtomically(configFile, text)
    }
}

/**
 * The old version's `roles` only recorded explicitly chosen config ids; anything unset was assigned by default rules: the main model took the first config, vision was served by the main model when that could see images,
 * and light followed the main model. This spells out the assignment actually in effect back then as [RoleChoices], so that no implicit rules remain afterwards.
 */
internal fun ProviderStore.migrateRoles(old: JsonObject?): RoleChoices {
    val ids = _configs.value.map { it.id }.toSet()
    fun explicit(role: ModelRole) = old?.get(role.name)?.jsonPrimitive?.content?.takeIf { it in ids }
    val main = explicit(ModelRole.Main) ?: _configs.value.firstOrNull()?.id
    val mainSees = _configs.value.firstOrNull { it.id == main }?.capabilities?.vision == true
    return RoleChoices(
        main = main,
        vision = explicit(ModelRole.Vision)?.let(RoleChoice::Model) ?: if (mainSees) RoleChoice.SameAsMain else RoleChoice.Off,
        light = explicit(ModelRole.Light)?.let(RoleChoice::Model) ?: RoleChoice.SameAsMain,
    )
}

/**
 * Attaches unattributed model configs (files written by older versions) to providers: group by [ProviderConnection.keyOf], one provider per group,
 * taking the key stored under any config id in the group (the ciphertext is moved over as is). First write the key file containing both new and old entries, then write the configs, and finally remove keys that belong to no provider;
 * if it is interrupted midway, the unattributed configs are still there on the next load and this is redone. Once everything is attributed, only leftover keys are cleaned up.
 */
internal fun ProviderStore.migrate() {
    val known = _providers.value.map { it.id }.toSet()
    val orphans = _configs.value.filter { it.providerId !in known }
    if (orphans.isNotEmpty()) {
        val attachedTo = mutableMapOf<String, Provider>()
        orphans.groupBy { ProviderConnection.keyOf(it.kind, it.baseUrl, ProviderConnection.displayName(it)) }.values.forEach { group ->
            val first = group.first()
            val p = findOrAddProvider(ProviderConnection.displayName(first), first.kind, first.baseUrl)
            group.forEach { attachedTo[it.id] = p }
            if (p.id !in keys) group.firstNotNullOfOrNull { keys[it.id] }?.let { keys = keys + (p.id to it) }
        }
        writeAtomically(keyFile, keysJson())
        _configs.value = _configs.value.map { c ->
            attachedTo[c.id]?.let { p -> c.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name) } ?: c
        }
        writeConfig()
    }
    val ids = _providers.value.map { it.id }.toSet()
    if (keys.keys.any { it !in ids }) {
        keys = keys.filterKeys { it in ids }
        writeAtomically(keyFile, keysJson())
    }
}

/**
 * After the model catalog or the levels the client knows change, old choices can no longer be sent to the server unchanged: when the current options can be determined, invalid entries fall back to the default;
 * old configs that have no level metadata and whose protocol never offered this setting are left alone, to avoid wrongly deleting values the server can still handle.
 */
internal fun ProviderStore.repairUnavailableReasoningEfforts() {
    val repaired = _roles.value.reasoningEfforts.mapNotNull { (role, effort) ->
        // Keep it as long as one member of the group supports this level: it is stripped by memberReasoningEffort when routing to a member that does not
        val cfgs = groupFor(role)?.enabledMembers?.mapNotNull { m -> _configs.value.firstOrNull { it.id == m.configId } }
            ?: listOfNotNull(configFor(role))
        if (cfgs.isEmpty()) return@mapNotNull null
        val supported = cfgs.any { cfg ->
            val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
            val options = ReasoningOptions.of(cfg, listed)
            options != null && effort in options.levels
        }
        if (supported) role to effort else null
    }.toMap()
    _roles.value = _roles.value.copy(reasoningEfforts = repaired)
}

/**
 * After a preset's default context is updated, bring configs stored with the old default up to date: only when the stored value is exactly the old default, the preset's current default already differs,
 * and the user has not written a window for this model separately; any value the user changed is never touched. Runs once after [load] reads the data in,
 * and the changes are persisted by the unified write-back that follows loading. With the old 64k window, compaction rewrites the request prefix frequently and the provider's prefix cache is invalidated wholesale (see [ProviderPresets]).
 */
internal fun ProviderStore.repairStaleContextDefaults() {
    val fixed = _configs.value.mapNotNull { c ->
        val now = ProviderPresets.forBaseUrl(c.baseUrl)?.capabilities?.contextTokens
            ?.takeIf { it != LEGACY_DEFAULT_CONTEXT_TOKENS }
        if (now != null && c.capabilities.contextTokens == LEGACY_DEFAULT_CONTEXT_TOKENS &&
            provider(c.providerId)?.contextSettings?.get(c.model)?.windowTokens == null
        ) c.id to now else null
    }.toMap()
    if (fixed.isEmpty()) return
    _configs.value = _configs.value.map { c ->
        fixed[c.id]?.let { window -> c.copy(capabilities = c.capabilities.copy(contextTokens = window)) } ?: c
    }
}

internal fun ProviderStore.providerOf(o: JsonObject) = Provider(
    id = o.str("id"),
    name = o.str("name"),
    kind = ProviderConfig.Kind.valueOf(o.str("kind")),
    baseUrl = o.str("baseUrl"),
    // Files from older versions lack this field: fill it in from the preset matching the address (recorded when writing the file back after loading)
    sessionHeader = o["sessionHeader"]?.jsonPrimitive?.content ?: ProviderPresets.forBaseUrl(o.str("baseUrl"))?.sessionHeader,
    signIn = o["signIn"]?.jsonPrimitive?.content,
    dailyTokenBudget = o.positiveLong("dailyTokenBudget")?.takeIf(TokenBudgetLimits::valid),
    serviceTiers = (o["serviceTiers"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
        val value = (raw as? JsonPrimitive)?.contentOrNull
        OpenAiServiceTier.entries.firstOrNull { it.requestValue == value }?.let { model to it }
    }.toMap(),
    verifiedModels = o["verifiedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
    contextSettings = (o["contextSettings"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        val settings = ModelContextSettings(entry.positiveInt("windowTokens"), entry.positiveInt("triggerTokens"),
            entry.positiveInt("readTimeoutSeconds")?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS })
        if (settings == ModelContextSettings()) null else model to settings
    }.toMap(),
    visionOverrides = (o["visionOverrides"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
        (raw as? JsonPrimitive)?.booleanOrNull?.let { model to it }
    }.toMap(),
    typedModels = o["typedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it.isNotBlank() }?.toSet().orEmpty(),
    ttsModels = (o["ttsModels"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }.toSet(),
    // Not present in configs stored by older versions: no nickname was set
    nickname = Provider.cleanNickname((o["nickname"] as? JsonPrimitive)?.takeIf { it.isString }?.content),
    // Not present in configs stored by older versions: back then every provider went through "test and save", so count it as tested
    savedUntested = o["untested"]?.jsonPrimitive?.booleanOrNull ?: false,
    models = o["models"]?.jsonArray?.map { e ->
        val m = e.jsonObject
        ListedModel(
            id = m.str("id"),
            displayName = m["displayName"]?.jsonPrimitive?.content.orEmpty(),
            toolCalls = m["toolCalls"]?.jsonPrimitive?.booleanOrNull,
            vision = m["vision"]?.jsonPrimitive?.booleanOrNull,
            contextTokens = m["contextTokens"]?.jsonPrimitive?.intOrNull,
            reasoning = m["reasoning"]?.jsonPrimitive?.booleanOrNull,
            reasoningEfforts = m["reasoningEfforts"]?.jsonArray?.map { it.jsonPrimitive.content },
            thinkingTypes = m["thinkingTypes"]?.jsonArray?.map { it.jsonPrimitive.content },
            chat = m["chat"]?.jsonPrimitive?.booleanOrNull,
            endpoint = (m["api"] as? JsonObject)?.let { a ->
                ModelProtocol.of(a["protocol"]?.jsonPrimitive?.content)?.let { protocol ->
                    ModelEndpoint(protocol, a.str("baseUrl"), a["reasoningField"]?.jsonPrimitive?.content)
                }
            },
            unsupportedApis = m["unsupportedApis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            protocols = m["protocols"]?.jsonArray?.mapNotNull { ModelProtocol.of(it.jsonPrimitive.content) }?.takeIf { it.isNotEmpty() },
        )
    },
)

internal fun ProviderStore.configOf(o: JsonObject) = ProviderConfig(
    id = o.str("id"),
    kind = ProviderConfig.Kind.valueOf(o.str("kind")),
    baseUrl = o.str("baseUrl"),
    model = o.str("model"),
    capabilities = Capabilities(
        toolCalls = o["toolCalls"]!!.jsonPrimitive.boolean,
        vision = o["vision"]!!.jsonPrimitive.boolean,
        contextTokens = o["contextTokens"]!!.jsonPrimitive.int,
        reasoning = o["reasoning"]!!.jsonPrimitive.boolean,
        groundingVerified = o["groundingVerified"]?.jsonPrimitive?.boolean ?: false,
        promptCaching = PromptCaching.of(o["promptCaching"]?.jsonPrimitive?.content),
    ),
    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
    providerId = o["providerId"]?.jsonPrimitive?.content.orEmpty(),
)

internal fun ProviderStore.groupOf(o: JsonObject) = ModelGroup(
    id = o.str("id"),
    name = o.str("name"),
    strategy = GroupStrategy.of(o["strategy"]?.jsonPrimitive?.content),
    sticky = o["sticky"]?.jsonPrimitive?.booleanOrNull ?: true,
    raceReadOnly = o["raceReadOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
    members = o["members"]?.jsonArray?.mapNotNull { e ->
        val m = e as? JsonObject ?: return@mapNotNull null
        val config = m["config"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GroupMember(
            configId = config,
            priority = (m["priority"] as? JsonPrimitive)?.intOrNull ?: 0,
            weight = ((m["weight"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 100),
            enabled = m["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            reasoningEffort = m["reasoningEffort"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            costRank = (m["costRank"] as? JsonPrimitive)?.intOrNull,
        )
    }.orEmpty(),
    limits = (o["limits"] as? JsonObject)?.let { l ->
        GroupLimits(
            requestsPerMinute = (l["requestsPerMinute"] as? JsonPrimitive)?.intOrNull,
            maxConcurrent = (l["maxConcurrent"] as? JsonPrimitive)?.intOrNull,
            dailyTokens = (l["dailyTokens"] as? JsonPrimitive)?.longOrNull,
            dailyCostMicros = (l["dailyCostMicros"] as? JsonPrimitive)?.longOrNull,
        )
    } ?: GroupLimits(),
)

/** One role in `uses`: `{"use":"model","config":id}`, `{"use":"group","group":id}`, `{"use":"main"}` or `{"use":"none"}`; unrecognized ones fall back to the default. */
internal fun ProviderStore.choicesOf(o: JsonObject): RoleChoices {
    fun choice(role: ModelRole): RoleChoice? {
        val c = runCatching { o[role.name]?.jsonObject }.getOrNull() ?: return null
        return when (c["use"]?.jsonPrimitive?.content) {
            USE_MODEL -> c["config"]?.jsonPrimitive?.content?.let(RoleChoice::Model)
            USE_GROUP -> c["group"]?.jsonPrimitive?.content?.let(RoleChoice::Group)
            USE_MAIN -> RoleChoice.SameAsMain
            USE_NONE -> RoleChoice.Off
            else -> null
        }
    }
    var r = RoleChoices()
    ModelRole.entries.forEach { role -> choice(role)?.let { c -> r.with(role, c)?.let { r = it } } }
    r = r.copy(mainFallbacks = runCatching {
        o["MainFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
            ?.distinct()?.take(MAX_FALLBACKS)
    }.getOrNull().orEmpty())
    val vf = runCatching {
        o["VisionFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
            ?.distinct()?.take(MAX_FALLBACKS)
    }.getOrNull().orEmpty()
    val lf = runCatching {
        o["LightFallbacks"]?.jsonArray?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
            ?.distinct()?.take(MAX_FALLBACKS)
    }.getOrNull().orEmpty()
    r = r.copy(visionFallbacks = vf, lightFallbacks = lf)
    return r.copy(reasoningEfforts = ModelRole.entries.mapNotNull { role ->
        val effort = runCatching { o[role.name]?.jsonObject?.get("reasoningEffort")?.jsonPrimitive?.content }.getOrNull()
        if ((r[role] is RoleChoice.Model || r[role] is RoleChoice.Group) && !effort.isNullOrBlank()) role to effort else null
    }.toMap())
}

internal fun ProviderStore.writeConfig() = writeAtomically(configFile, configJson())

internal fun ProviderStore.configJson(
    providers: List<Provider> = _providers.value,
    configs: List<ProviderConfig> = _configs.value,
    roles: RoleChoices = _roles.value,
    groups: List<ModelGroup> = _groups.value,
): String {
    val root = buildJsonObject {
        putJsonArray("services") {
            providers.forEach { p ->
                addJsonObject {
                    put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("baseUrl", p.baseUrl)
                    p.sessionHeader?.let { put("sessionHeader", it) }
                    p.signIn?.let { put("signIn", it) }
                    p.nickname?.let { put("nickname", it) }
                    p.dailyTokenBudget?.let { put("dailyTokenBudget", it) }
                    if (p.serviceTiers.isNotEmpty()) putJsonObject("serviceTiers") {
                        for ((model, tier) in p.serviceTiers) put(model, tier.requestValue)
                    }
                    if (p.savedUntested) put("untested", true)
                    if (p.verifiedModels.isNotEmpty()) putJsonArray("verifiedModels") { p.verifiedModels.forEach { add(it) } }
                    if (p.contextSettings.isNotEmpty()) putJsonObject("contextSettings") {
                        for ((model, settings) in p.contextSettings) putJsonObject(model) {
                            settings.windowTokens?.let { put("windowTokens", it) }
                            settings.triggerTokens?.let { put("triggerTokens", it) }
                            settings.readTimeoutSeconds?.let { put("readTimeoutSeconds", it) }
                        }
                    }
                    if (p.visionOverrides.isNotEmpty()) putJsonObject("visionOverrides") {
                        for ((model, sees) in p.visionOverrides) put(model, sees)
                    }
                    if (p.typedModels.isNotEmpty()) putJsonArray("typedModels") { p.typedModels.forEach { add(it) } }
                    if (p.ttsModels.isNotEmpty()) putJsonArray("ttsModels") { p.ttsModels.forEach { add(it) } }
                    p.models?.let { models ->
                        putJsonArray("models") {
                            models.forEach { m ->
                                addJsonObject {
                                    put("id", m.id)
                                    if (m.displayName.isNotEmpty()) put("displayName", m.displayName)
                                    m.toolCalls?.let { put("toolCalls", it) }
                                    m.vision?.let { put("vision", it) }
                                    m.contextTokens?.let { put("contextTokens", it) }
                                    m.reasoning?.let { put("reasoning", it) }
                                    m.reasoningEfforts?.let { levels -> putJsonArray("reasoningEfforts") { levels.forEach { add(it) } } }
                                    m.thinkingTypes?.let { types -> putJsonArray("thinkingTypes") { types.forEach { add(it) } } }
                                    m.chat?.let { put("chat", it) }
                                    m.endpoint?.let { e ->
                                        putJsonObject("api") {
                                            put("protocol", e.protocol.wire); put("baseUrl", e.baseUrl)
                                            e.reasoningField?.let { put("reasoningField", it) }
                                        }
                                    }
                                    if (m.unsupportedApis.isNotEmpty()) putJsonArray("unsupportedApis") { m.unsupportedApis.forEach { add(it) } }
                                    m.protocols?.takeIf { it.isNotEmpty() }?.let { ps -> putJsonArray("protocols") { ps.forEach { add(it.wire) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
        putJsonArray("providers") {
            configs.forEach { c ->
                addJsonObject {
                    put("id", c.id); put("providerId", c.providerId); put("name", c.name); put("kind", c.kind.name)
                    put("baseUrl", c.baseUrl); put("model", c.model)
                    put("toolCalls", c.capabilities.toolCalls); put("vision", c.capabilities.vision)
                    put("contextTokens", c.capabilities.contextTokens); put("reasoning", c.capabilities.reasoning)
                    put("groundingVerified", c.capabilities.groundingVerified)
                    put("promptCaching", c.capabilities.promptCaching.wire)
                }
            }
        }
        if (groups.isNotEmpty()) putJsonArray("groups") {
            groups.forEach { g ->
                addJsonObject {
                    put("id", g.id); put("name", g.name); put("strategy", g.strategy.wire); put("sticky", g.sticky)
                    if (g.raceReadOnly) put("raceReadOnly", true)
                    putJsonArray("members") {
                        g.members.forEach { m ->
                            addJsonObject {
                                put("config", m.configId); put("priority", m.priority); put("weight", m.weight)
                                if (!m.enabled) put("enabled", false)
                                m.reasoningEffort?.let { put("reasoningEffort", it) }
                                m.costRank?.let { put("costRank", it) }
                            }
                        }
                    }
                    val l = g.limits
                    if (l != GroupLimits()) putJsonObject("limits") {
                        l.requestsPerMinute?.let { put("requestsPerMinute", it) }
                        l.maxConcurrent?.let { put("maxConcurrent", it) }
                        l.dailyTokens?.let { put("dailyTokens", it) }
                        l.dailyCostMicros?.let { put("dailyCostMicros", it) }
                    }
                }
            }
        }
        putJsonObject("uses") {
            ModelRole.entries.forEach { role ->
                when (val c = roles[role]) {
                    is RoleChoice.Model -> putJsonObject(role.name) {
                        put("use", USE_MODEL); put("config", c.configId)
                        roles.reasoningEfforts[role]?.let { put("reasoningEffort", it) }
                    }
                    is RoleChoice.Group -> putJsonObject(role.name) {
                        put("use", USE_GROUP); put("group", c.groupId)
                        roles.reasoningEfforts[role]?.let { put("reasoningEffort", it) }
                    }
                    RoleChoice.SameAsMain -> putJsonObject(role.name) { put("use", USE_MAIN) }
                    RoleChoice.Off -> putJsonObject(role.name) { put("use", USE_NONE) }
                    null -> Unit
                }
            }
            if (roles.mainFallbacks.isNotEmpty()) putJsonArray("MainFallbacks") {
                roles.mainFallbacks.forEach { add(it) }
            }
            if (roles.visionFallbacks.isNotEmpty()) putJsonArray("VisionFallbacks") {
                roles.visionFallbacks.forEach { add(it) }
            }
            if (roles.lightFallbacks.isNotEmpty()) putJsonArray("LightFallbacks") {
                roles.lightFallbacks.forEach { add(it) }
            }
        }
    }
    return root.toString()
}

internal fun ProviderStore.keysJson() = buildJsonObject { keys.forEach { (id, v) -> put(id, v) } }.toString()

internal fun ProviderStore.writeAtomically(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

internal fun ProviderStore.encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)

internal fun ProviderStore.decode(s: String) = Base64.getDecoder().decode(s)
