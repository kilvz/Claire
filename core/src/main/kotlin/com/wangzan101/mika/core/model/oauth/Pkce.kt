package com.wangzan101.mika.core.model.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random values for PKCE (RFC 7636) and callback verification. Newly generated on every sign-in, never persisted.
 * The verifier is 32 random bytes written as base64url (43 characters, within the 43–128 the spec requires, and all characters
 * are within the allowed range).
 */
object Pkce {
    private val random = SecureRandom()

    /** A new verifier (code_verifier). */
    fun verifier(bytes: Int = 32): String = randomString(bytes)

    /** S256: SHA-256 of the verifier, base64url without padding. */
    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The random string carried back verbatim in the callback and used to recognize this sign-in (state). */
    fun state(): String = randomString(24)

    private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
