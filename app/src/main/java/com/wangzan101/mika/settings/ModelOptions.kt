package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One selectable model from the listing. [capabilities] are the merged capabilities: whatever the API reports is used as is, and whatever it omits keeps the preset's initial value;
 * [toolCallsKnown] and [visionKnown] record which of those the API stated explicitly (the UI marks the rest as "unconfirmed").
 * [chat] tells whether the API says it is a chat model (see [ListedModel.chat]); null when unstated.
 * [typed] true means the model is absent from the listed models and was typed in by the user (the UI marks it "typed in").
 * [visionByUser] is the user's statement about whether it can see images (see [Provider.visionOverrides]); null when unstated, and anything stated is already folded into [capabilities].
 */
data class ModelOption(
    val id: String,
    val capabilities: Capabilities,
    val toolCallsKnown: Boolean,
    val visionKnown: Boolean,
    val contextKnown: Boolean,
    val displayName: String = "",
    val chat: Boolean? = null,
    val typed: Boolean = false,
    val visionByUser: Boolean? = null,
) {
    /** Capability flags for reference only; the model picker does not filter by them. */
    val canBeMain: Boolean get() = capabilities.toolCalls
    val canSee: Boolean get() = capabilities.vision
}

object ModelOptions {
    /**
     * Merges the listed models with the preset capabilities. Models that can serve as the main model come first; the rest keep the order the provider gave.
     * Models that declare only API protocols Claire does not speak (see [ListedModel.usable]) would always fail on chat calls, so they are not listed by default;
     * when [includeUnsupported] is true they are listed too (provider details and voice models need the full catalog).
     */
    fun merge(listed: List<ListedModel>, base: Capabilities, includeUnsupported: Boolean = false): List<ModelOption> =
        listed.filter { includeUnsupported || it.usable }.map { m ->
            ModelOption(
                id = m.id,
                capabilities = base.copy(
                    toolCalls = m.toolCalls ?: base.toolCalls,
                    vision = m.vision ?: base.vision,
                    contextTokens = m.contextTokens ?: base.contextTokens,
                    reasoning = m.reasoning ?: base.reasoning,
                    // whether image grounding is accurate only counts once measured in practice; newly connected ones are always unverified
                    groundingVerified = false,
                ),
                toolCallsKnown = m.toolCalls != null,
                visionKnown = m.vision != null,
                contextKnown = m.contextTokens != null,
                displayName = m.displayName,
                chat = m.chat,
            )
        }.sortedBy { if (it.canBeMain) 0 else 1 }

    /**
     * Order for using and trial-calling as the main model: only models that can serve as the main model and do not look like some other purpose (see [looksNonChat]);
     * own-family ones first (the model id or display name contains a word from [familyTokens], see [ProviderPreset.familyTokens]),
     * then those the API explicitly says support tool calls; within the same tier for own-family models, the newer version in the model name comes first (see [ModelVersion]:
     * for the same version the stable release comes first, and unparseable ones rank after parseable ones); the rest follow the listing order.
     * Provider listings often put old models first, and old models may already be retired; other vendors' models each have their own version numbers, so they are not compared together.
     */
    fun candidates(options: List<ModelOption>, familyTokens: List<String> = emptyList()): List<String> =
        options.filter { it.canBeMain && !looksNonChat(it) }
            .map { Triple(it, inFamily(it, familyTokens), ModelVersion.of(it.id)) }
            .sortedWith(
                compareBy<Triple<ModelOption, Boolean, ModelVersion>>({ if (it.second) 0 else 1 }, { if (it.first.toolCallsKnown) 0 else 1 })
                    .thenComparator { a, b -> if (a.second) ModelVersion.NEWEST_FIRST.compare(a.third, b.third) else 0 },
            )
            .map { it.first.id }

    /** The default main model: the first of [candidates]; when there is none, the first model that can serve as the main model. */
    fun defaultMain(options: List<ModelOption>, familyTokens: List<String> = emptyList()): String? =
        candidates(options, familyTokens).firstOrNull() ?: options.firstOrNull { it.canBeMain }?.id

    /** The family tokens of the preset whose address matches; empty when the address is not recognized. */
    fun familyOf(baseUrl: String): List<String> = ProviderPresets.forBaseUrl(baseUrl.trim())?.familyTokens.orEmpty()

    private fun inFamily(o: ModelOption, tokens: List<String>): Boolean =
        tokens.any { t -> o.id.contains(t, ignoreCase = true) || o.displayName.contains(t, ignoreCase = true) }

    /**
     * Looks like a non-chat model (embedding, rerank, speech, image generation, moderation…): when the API says so, follow the API; otherwise look at the segments of the model name split on punctuation and slashes,
     * and one segment starting with a word from [NON_CHAT_WORDS] is enough.
     */
    fun looksNonChat(o: ModelOption): Boolean =
        o.chat?.not() ?: o.id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** Short form of the context length: 64K, 1M. */
    fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1000 -> if (tokens % 1024 == 0) "${tokens / 1024}K" else "${(tokens + 500) / 1000}K"
        else -> "$tokens"
    }

    /**
     * Whether a model counts as able to see images when doing work: whatever the user said about this model ([userSaid]) wins; if nothing was said, using it for vision ([usedForVision]:
     * selecting it as the vision model, or choosing "use the main model" for vision) means the user said it can see; only when neither applies is [stored] used (what the listing states, otherwise the preset's initial value).
     */
    fun seesImages(stored: Boolean, userSaid: Boolean?, usedForVision: Boolean): Boolean = userSaid ?: (usedForVision || stored)

    /** Initial capabilities for a provider: those of the matching preset when the address matches, otherwise those of "custom". */
    fun baseOf(p: Provider): Capabilities = (ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.custom).capabilities

    /**
     * All models selectable for a provider: the remembered listing (merged with the initial capabilities; models that already have a config use the config's capabilities),
     * plus models that have a config but are missing from the listing (e.g. typed in by hand), and models that were typed in but have no config yet ([Provider.typedModels]).
     * When [includeTts] is true, models whose listing declares only API protocols Claire does not speak are listed too (the provider detail page needs the full catalog).
     * Whether a model can see images, when the user stated it ([Provider.visionOverrides]), follows the user.
     */
    fun ofProvider(p: Provider, configs: List<ProviderConfig>, includeTts: Boolean = false): List<ModelOption> =
        listedOf(p, configs, includeTts).mapNotNull { o ->
            val said = p.visionOverrides[o.id] ?: return@mapNotNull o
            o.copy(capabilities = o.capabilities.copy(vision = said), visionByUser = said)
        }

    private fun listedOf(p: Provider, configs: List<ProviderConfig>, includeTts: Boolean): List<ModelOption> {
        val saved = configs.filter { it.providerId == p.id }
        val byModel = saved.associateBy { it.model }
        val base = baseOf(p)
        val listed = merge(p.models.orEmpty(), base, includeUnsupported = includeTts).map { o -> byModel[o.id]?.let { o.copy(capabilities = it.capabilities) } ?: o }
        val rest = saved.filter { c -> listed.none { it.id == c.model } }.map {
            // For a provider that cannot list models, all models are typed in; for one that has a listing, a model later dropped from it does not count as typed in
            val typed = it.model in p.typedModels || p.models?.isEmpty() == true
            ModelOption(it.model, it.capabilities, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = typed)
        }
        val options = listed + rest
        val knownIds = options.mapTo(mutableSetOf()) { it.id }
        // Typed in but not yet used for any purpose (no config): list them anyway, with capabilities from this provider's initial values
        val typed = p.typedModels.filter { it !in knownIds }.sorted().map {
            ModelOption(it, base, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = true)
        }
        return options + typed
    }
}

/**
 * Version read from the model name; used only to order models from the same vendor, not to judge which tier (flash, pro, lite…) is better.
 * Model names are split into segments on `-` `_` `:` `/` etc. (lowercased, decimal points preserved):
 * - [numbers] is the first segment that looks like a version number: 1–2 digits, optionally with a decimal part (`2.5`, `3.1`), optionally preceded by letters (`qwen3`, `v3.2`, `k2`, `x4`),
 *   optionally followed by a single letter (`4o`, `1v`); those ending in b / k / m are parameter counts or context lengths (`72b`, `8k`) and do not count.
 *   Immediately following pure-digit segments of 1–2 digits are joined as the minor version (`claude-3-5` → 3.5, `seed-1-6` → 1.6).
 * - [date] is a date or snapshot number assembled from the remaining all-digit segments: when a 4-digit year is present it is read as year/month/day (`-2025-08-07`, `-09-2025`),
 *   otherwise the digits as written (`-0827`, `-20241022`, `-001`; 6 digits are treated as a date with a two-digit year); null when there are none.
 * - [stage]: 0 stable release; 1 a mutable alias such as `latest`; 2 preview or experimental (`preview`, `exp`, `beta`, `alpha`, `rc`…).
 * Names with neither a version number nor a date cannot be parsed ([parsed] is false).
 */
internal data class ModelVersion(val numbers: List<Int>, val stage: Int, val date: Long?) {
    val parsed: Boolean get() = numbers.isNotEmpty() || date != null

    companion object {
        /** Parseable ones first; higher versions first; for the same version, stable release, then alias, then preview; ones without a date (which mostly point at the newest snapshot) first, then newer dates first. */
        val NEWEST_FIRST: Comparator<ModelVersion> = Comparator { a, b ->
            when {
                a.parsed != b.parsed -> if (a.parsed) -1 else 1
                !a.parsed -> 0
                else -> compareNumbers(b.numbers, a.numbers).takeIf { it != 0 }
                    ?: a.stage.compareTo(b.stage).takeIf { it != 0 }
                    ?: compareValues(b.date ?: Long.MAX_VALUE, a.date ?: Long.MAX_VALUE)
            }
        }

        fun of(id: String): ModelVersion {
            val parts = id.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            var numbers = emptyList<Int>()
            var at = -1
            for ((i, p) in parts.withIndex()) {
                val m = VERSION.matchEntire(p) ?: continue
                if (m.groupValues[3] in SIZE_SUFFIXES) continue
                numbers = m.groupValues[2].split('.').map { it.toInt() }
                at = i
                break
            }
            var end = at + 1
            if (at >= 0 && numbers.size == 1) {
                // Minor version separated by hyphens: claude-3-5, seed-1-6
                while (end < parts.size && numbers.size < 3 && MINOR.matches(parts[end])) numbers = numbers + parts[end++].toInt()
            }
            val rest = parts.filterIndexed { i, _ -> i < at || i >= end }
            val stage = when {
                rest.any { p -> PRE_RELEASE.any { p.startsWith(it) } } -> 2
                "latest" in rest -> 1
                else -> 0
            }
            return ModelVersion(numbers, stage, dateOf(rest.filter { p -> p.all { it.isDigit() } }))
        }

        private fun dateOf(digits: List<String>): Long? {
            if (digits.isEmpty()) return null
            val year = digits.firstOrNull { it.length == 4 && it.toInt() in 1990..2099 }
            if (year != null) {
                val md = (digits - year).take(2).map { it.take(2).toLong() }
                return year.toLong() * 10_000 + (md.getOrNull(0) ?: 0) * 100 + (md.getOrNull(1) ?: 0)
            }
            val joined = digits.joinToString("").take(14)
            return if (digits.size == 1 && joined.length == 6) 20_000_000 + joined.toLong() else joined.toLong()
        }

        private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }

        private val VERSION = Regex("([a-z]*)((?:0|[1-9]\\d?)(?:\\.\\d{1,3})*)([a-z]?)")
        private val MINOR = Regex("[1-9]?\\d")
        private val SIZE_SUFFIXES = setOf("b", "k", "m")
        private val PRE_RELEASE = listOf("preview", "exp", "beta", "alpha", "rc", "dev", "test")
    }
}

/** Provider grouping rule and display name: the same API kind, address and name count as the same provider. */
object ProviderConnection {
    fun keyOf(kind: ProviderConfig.Kind, baseUrl: String, name: String) = "${kind.name}|${normalizeUrl(baseUrl)}|${name.trim()}"

    /** Entries without a name use the host of the address. */
    fun displayName(c: ProviderConfig): String = c.name.ifBlank { nameForUrl(c.baseUrl) }

    fun nameForUrl(baseUrl: String): String =
        ProviderPresets.forBaseUrl(baseUrl)?.label ?: baseUrl.trim().toHttpUrlOrNull()?.host ?: baseUrl.trim()

    fun normalizeUrl(url: String) = url.trim().trimEnd('/')
}
