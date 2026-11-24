package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One released version: version number (without the v), title, release notes (Markdown), release date, release page, APK direct link. */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** Release notes. */
    val notes: String,
    /** Release date yyyy-MM-dd; empty when the source does not provide it. */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** Byte size of the APK asset; empty when the API does not provide it. */
    val apkSize: Long? = null,
    /** Digest of the APK asset, of the form `sha256:<hex>`; older assets have none, and then only package name, version number and signature are checked. */
    val apkDigest: String? = null,
    /** The asset list has been read; an empty apkUrl means the list has no compatible package. */
    val apkListKnown: Boolean = false,
) {
    /** The address "Download from GitHub" opens: download the APK when there is a direct link, otherwise open the release page. */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * Reads the version information from the GitHub releases API (releases/latest): tag_name, name, body, published_at, html_url,
 * plus the browser_download_url of the APK in assets that suits this device (see [pickApk]).
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Besides the all-architecture `Claire-v<version>.apk`, a release may contain packages carrying native libraries for only one
     * architecture, whose names end with `-<suffix>.apk`.
     * The key is the Android ABI name and the value is the asset name suffix.
     */
    private val ABI_APK_SUFFIX = mapOf("arm64-v8a" to "arm64")

    /** One asset in a release. When the update check falls back to the release page, asset information is read from the web page with fields matching the API's. */
    internal data class ReleaseAsset(val name: String, val url: String, val size: Long? = null, val digest: String? = null)

    /** [deviceAbis] is the ABIs this device supports, ordered by preference (Build.SUPPORTED_ABIS); when empty only the all-architecture package is taken. */
    fun fromGitHub(text: String, deviceAbis: List<String> = emptyList()): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val assets = (o["assets"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toAsset() }
        val apk = pickAsset(assets.orEmpty(), deviceAbis)
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${MikaLinks.RELEASES}/tag/$tag",
            apkUrl = apk?.url,
            apkSize = apk?.size,
            apkDigest = apk?.digest,
            apkListKnown = assets != null,
        )
    }

    /**
     * A version found through the release-page fallback: only the tag, with empty release notes (notes exist only in the API).
     * [assets] being null means the assets page could not be read, in which case the all-architecture package address is guessed from
     * Claire's fixed naming.
     * When the assets page has been read, only packages in the list that are compatible with the device are offered.
     */
    internal fun fromPage(tag: String, assets: List<ReleaseAsset>?, deviceAbis: List<String>, releasesUrl: String): ReleaseInfo {
        val base = releasesUrl.trimEnd('/')
        val apk = assets?.let { pickAsset(it, deviceAbis) }
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = version,
            notes = "",
            date = null,
            pageUrl = "$base/tag/$tag",
            apkUrl = apk?.url ?: if (assets == null) "$base/download/$tag/Claire-$tag.apk" else null,
            apkSize = apk?.size,
            apkDigest = apk?.digest,
            apkListKnown = assets != null,
        )
    }

    /**
     * When this device supports an architecture and the release has a dedicated package for it, take the dedicated package (smaller);
     * otherwise take the all-architecture package.
     * Do not take a dedicated package for another architecture only (it cannot be installed); open the release page instead.
     */
    internal fun pickAsset(assets: List<ReleaseAsset>, deviceAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        fun named(suffix: String) = apks.firstOrNull { it.name.endsWith("-$suffix.apk", ignoreCase = true) }
        val forDevice = deviceAbis.firstNotNullOfOrNull { abi -> ABI_APK_SUFFIX[abi]?.let(::named) }
        val universal = apks.firstOrNull { a -> ABI_APK_SUFFIX.values.none { a.name.endsWith("-$it.apk", ignoreCase = true) } }
        return forDevice ?: universal
    }

    /** Compares how well the selected APK suits this device, using the same ABI preference order as asset selection. */
    internal fun apkPreference(url: String?, deviceAbis: List<String>): Int {
        if (url == null) return 0
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val suffix = ABI_APK_SUFFIX.values.firstOrNull { name.endsWith("-$it.apk", ignoreCase = true) }
            ?: return 1
        val index = deviceAbis.indexOfFirst { ABI_APK_SUFFIX[it] == suffix }
        return if (index < 0) 0 else deviceAbis.size - index + 1
    }

    private fun JsonObject.toAsset(): ReleaseAsset? {
        val name = str("name") ?: return null
        val url = str("browser_download_url") ?: return null
        return ReleaseAsset(name, url, strOrNumber("size")?.toLongOrNull(), str("digest"))
    }

    /** For persistence: records the most recently found version so the badge is still there the next time the app opens. */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
        if (r.apkListKnown) put("apkListKnown", true)
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(), apkDigest = o.str("apkDigest"),
            apkListKnown = (o["apkListKnown"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
