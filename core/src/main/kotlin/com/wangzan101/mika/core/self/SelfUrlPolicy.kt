package com.wangzan101.mika.core.self

/**
 * Addresses the user's browser may open on behalf of the model: http/https only, no username or password, length-limited.
 * The core does not reference the web capability (dependency direction: capability → core), so only basic checks are done here;
 * when the app side opens one, the [com.wangzan101.mika.core.self.SelfPort] implementation checks again through the browser's own WebUrlPolicy.
 */
object SelfUrlPolicy {
    const val MAX_URL = 2048

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** null = allowed; otherwise the reason shown to the model. */
    fun check(url: String): String? {
        val u = url.trim()
        if (u.length > MAX_URL) return "the URL is longer than $MAX_URL characters"
        if (u.isEmpty()) return "the URL is empty"
        if (u.any { it.isWhitespace() || it.isISOControl() }) return "the URL must not contain spaces or control characters"
        val scheme = SCHEME.find(u)?.groupValues?.get(1)?.lowercase()
            ?: return "the URL must start with http:// or https://"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        val authority = u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "the URL has no domain"
        if ('@' in authority) return "URLs with a username or password are not allowed"
        return null
    }

    /** Host name (lowercase, leading www. removed); null when it cannot be parsed. */
    fun host(url: String): String? = runCatching {
        val authority = url.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority) return@runCatching null
        val h = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        h.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** A host name or URL given by the user → host name. */
    fun hostOf(input: String): String? {
        val s = input.trim()
        return host(if ("://" in s) s else "https://$s")
    }
}
