package com.wangzan101.mika.download

import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.UpdateMirror
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Download URL allowlist shared by app updates and voice packs. A URL must be the official address itself, or a built-in/user-added mirror prefix plus the official address.
 * Mirrors are still trusted by the user; size and digest are still verified after download (updates also verify a signature), so widening addresses cannot install other files.
 *
 * - App updates ([suffix] is `.apk`): the official address must be an HTTPS release asset of the Claire repository.
 * - Voice packs (empty [suffix]): the official address is the release or upstream address from the manifest, and the caller passes this file's exact address.
 *   The release address is likewise a Claire repository asset; upstream addresses accept only pinned HuggingFace commits (`/resolve/<commit>/`)
 * and GitHub raw files (license texts) listed separately in the manifest at pinned commits. Mirrors only mirror GitHub release assets.
 */
object DownloadUrls {
    private val release = "${MikaLinks.RELEASES}/download/".toHttpUrl()
    private val commit = "[0-9a-f]{40}"
    /** Upstream hosts and their pinned-commit path forms: `/<owner>/<repo>/resolve/<commit>/…` and `/<owner>/<repo>/<commit>/…`. */
    private val upstream = mapOf(
        "huggingface.co" to Regex("/[^/]+/[^/]+/resolve/$commit/.+"),
        "raw.githubusercontent.com" to Regex("/[^/]+/[^/]+/$commit/.+"),
    )

    /** [url] is an HTTPS release asset of the Claire repository ending in [suffix] (case-insensitive; empty means any). */
    fun requireRelease(url: String, suffix: String?) {
        val parsed = url.toHttpUrl()
        requireHttps(parsed)
        require(parsed.host == release.host)
        require(parsed.encodedPath.startsWith(release.encodedPath))
        requireSuffix(parsed, suffix)
    }

    /**
     * [url] must be [official] itself, or some prefix from [mirrors] plus [official].
     * [suffix] `.apk` applies the update rules; empty applies the voice-pack rules, where [official] may be a release or an upstream address.
     * Throws [IllegalArgumentException] when invalid.
     */
    fun requireTrusted(url: String, official: String, mirrors: List<UpdateMirror>, suffix: String?) {
        if (url == official) {
            if (suffix != null) requireRelease(url, suffix) else requireOfficialPackUrl(url)
            return
        }
        require(mirrors.any { url == it.prefix + official }) { "not an official or configured mirror URL" }
        // The caller has already checked the official update address; voice packs have two kinds of official address, and mirrors may only wrap the release one.
        if (suffix == null) requireRelease(official, null)
        val parsed = url.toHttpUrl()
        require(parsed.isHttps)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        requireSuffix(parsed, suffix)
    }

    private fun requireOfficialPackUrl(url: String) {
        val parsed = url.toHttpUrl()
        if (parsed.host == release.host) return requireRelease(url, null)
        requireHttps(parsed)
        val path = requireNotNull(upstream[parsed.host]) { "not a trusted voice-pack host" }
        require(parsed.query == null && parsed.fragment == null)
        require(path.matches(parsed.encodedPath))
    }

    private fun requireHttps(parsed: HttpUrl) {
        require(parsed.isHttps && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
    }

    private fun requireSuffix(parsed: HttpUrl, suffix: String?) {
        if (suffix != null) require(parsed.encodedPath.endsWith(suffix, ignoreCase = true))
    }
}
