package com.wangzan101.mika.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Looks up the latest version from a single source. [source] is the mirror prefix (an empty string means connecting to GitHub directly); the complete request URL is `source + official URL`.
 *
 * The GitHub API is queried first (the most complete information, including release notes, size and digest); when a mirror does not support the API it falls back to the releases page:
 * take the latest tag from the redirect of `/releases/latest` or from the page itself, then read the APK file names and SHA-256 from `/releases/expanded_assets/<tag>`.
 * If neither path works, this source is treated as having found nothing and [UpdateChecker] moves on to the next source.
 *
 * Requests use OkHttp's asynchronous API, and reading the response body remains cancellable as well.
 */
internal object UpdateProbe {
    private const val MAX_METADATA_BYTES = 2L * 1024 * 1024

    /** Latest-version links inside the releases page. The `releases/tag/' + ...` concatenations produced by scripts will not match. */
    private val TAG = Regex("""releases/tag/([A-Za-z0-9._+-]+)""")

    /** APK file name inside an asset row; only the file name is taken, the download URL is always assembled from the official repository. */
    private val APK = Regex("""releases/download/[^"'\s<>]+?/([^"'/\s<>]+\.apk)""", RegexOption.IGNORE_CASE)

    private val DIGEST = Regex("""sha256:[0-9a-fA-F]{64}""")
    private val ROW = Regex("<li\\b", RegexOption.IGNORE_CASE)

    suspend fun fetch(
        client: OkHttpClient,
        source: String,
        apiUrl: String,
        releasesUrl: String,
        deviceAbis: List<String>,
        userAgent: String,
    ): ReleaseInfo? {
        var apiFailure: Throwable? = null
        val api = try {
            get(client, source + apiUrl, userAgent, json = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MetadataTooLarge) {
            return null
        } catch (e: Exception) {
            apiFailure = e
            null
        }
        if (api != null) {
            try {
                Releases.fromGitHub(api, deviceAbis)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.let { return it }
        }
        // When connecting directly, the API already gave a definitive answer (for example a rate-limit 403): GitHub is reachable and this attempt simply found nothing, so do not hit the web page again.
        // A mirror's 403 usually only means "the API is not proxied", so the releases page still has to be tried.
        if (source.isEmpty() && apiFailure is HttpFailure) return null

        val base = releasesUrl.trimEnd('/')
        val tag = try {
            latestTag(client, source + base + "/latest", userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val assetsPage = try {
            get(client, source + base + "/expanded_assets/$tag", userAgent, json = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MetadataTooLarge) {
            return null
        } catch (_: Exception) {
            null
        }
        val assets = assetsPage?.takeIf(String::isNotBlank)?.let { parseAssets(it, tag, base) }
        return Releases.fromPage(tag, assets, deviceAbis, base)
    }

    private val testClient by lazy { UpdateChecker.defaultClient() }

    /** Used by "test mirror": bounds the whole test with a timeout and reuses the client. */
    suspend fun test(
        prefix: String,
        client: OkHttpClient = testClient,
        apiUrl: String = MikaLinks.LATEST_RELEASE_API,
        releasesUrl: String = MikaLinks.RELEASES,
        timeoutMs: Long = 12_000L,
    ): ReleaseInfo? = withTimeoutOrNull(timeoutMs) {
        try {
            fetch(client, prefix, apiUrl, releasesUrl, emptyList(), "Claire")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Latest tag: some mirrors pass the `/releases/latest` redirect through as-is, so the tag is already carried in `Location`;
     * others follow it all the way and return the 200 releases page, from which the tag is then extracted. Both are handled.
     */
    private suspend fun latestTag(client: OkHttpClient, url: String, userAgent: String): String? {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return execute(noRedirect, request) { resp ->
            if (resp.code in 300..399) resp.header("Location")?.let(::tagIn)
            else if (!resp.isSuccessful) null
            else tagIn(resp.readMetadata())
        }
    }

    private fun tagIn(text: String): String? = TAG.find(text)?.groupValues?.get(1)

    private suspend fun get(client: OkHttpClient, url: String, userAgent: String, json: Boolean): String {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        if (json) {
            builder.header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
        }
        return execute(client, builder.build()) { resp ->
            if (!resp.isSuccessful) throw HttpFailure(resp.code)
            resp.readMetadata()
        }
    }

    /** A known length allows rejecting early, and the actual read limit also covers chunked and decompressed responses. */
    private fun Response.readMetadata(): String {
        val metadata = body ?: throw IOException("empty body")
        if (metadata.contentLength() > MAX_METADATA_BYTES || metadata.source().request(MAX_METADATA_BYTES + 1)) {
            throw MetadataTooLarge()
        }
        return metadata.string()
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    /**
     * Reads the APKs from GitHub's asset-list web page: one asset per row, with the download path and `sha256:...` inside the row.
     * If the page structure changes, it falls back to searching the whole text for file names (no digest then, but the signature is still verified before installing).
     */
    private fun parseAssets(html: String, tag: String, base: String): List<Releases.ReleaseAsset> {
        val byName = LinkedHashMap<String, String?>()
        for (row in ROW.split(html).drop(1)) {
            val name = APK.find(row)?.groupValues?.get(1) ?: continue
            val digests = DIGEST.findAll(row).map { it.value }.distinct().toList()
            if (name !in byName) byName[name] = digests.singleOrNull()
            if (byName.size >= 32) break
        }
        if (byName.isEmpty()) {
            APK.findAll(html).forEach { match ->
                val name = match.groupValues[1]
                if (name !in byName) byName[name] = null
            }
        }
        return byName.map { (name, digest) ->
            Releases.ReleaseAsset(name, "$base/download/$tag/$name", null, digest)
        }
    }

    private class HttpFailure(val code: Int) : IOException("HTTP $code")
    private class MetadataTooLarge : IOException("update metadata exceeds $MAX_METADATA_BYTES bytes")
}
