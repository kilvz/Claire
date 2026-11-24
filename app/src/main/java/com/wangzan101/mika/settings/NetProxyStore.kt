package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Settings › Network proxy, of the form `http://[user:password@]host:port`; an empty string means none, i.e. a direct connection.
 * The address may contain credentials, so like provider keys it is encrypted with [KeyCipher] and stored in its own file; it never goes into the plaintext preferences, nor into the logs.
 *
 * Who uses it: Claire's own connections (model calls, listing models, account sign-in token exchange, update downloads, the built-in browser) go through [NetProxy], while loopback and LAN addresses connect directly;
 * commands in the file and compute environments receive it through an environment variable when networking is allowed (see `AndroidSandbox`).
 *
 * Earlier it existed only for containers and was stored in `container-proxy.bin` (with a different key): on the first read it is decrypted with [legacyCipher], saved in the new form, and the old file is deleted.
 */
class NetProxyStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val legacyCipher: KeyCipher? = null,
    /** Called after a new value is stored (so already established connections reconnect with the new settings). */
    private val onChange: () -> Unit = {},
) {
    private val file = File(dir, FILE)
    private val legacy = File(dir, LEGACY_FILE)
    @Volatile private var cached: String? = null

    /** The stored proxy; an empty string when there is none or it cannot be decrypted (device changed, key gone). */
    @Synchronized fun get(): String {
        cached?.let { return it }
        migrate()
        val stored = runCatching { if (file.isFile) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) else "" }.getOrDefault("")
        return stored.also { cached = it }
    }

    /** Stores [proxy] (surrounding whitespace trimmed); an empty value deletes the file. The caller has already checked it with [valid]. */
    fun set(proxy: String) {
        synchronized(this) {
            val v = proxy.trim()
            require(valid(v)) { "not a proxy address" }
            if (v.isEmpty()) file.delete() else write(v)
            legacy.delete()
            cached = v
        }
        onChange()
    }

    /** The copy stored by the older version that only served containers: if it can be decrypted, save it in the new form; if the new one already exists or it cannot be decrypted, leave it alone, and it is deleted along with the next save. */
    private fun migrate() {
        val old = legacyCipher ?: return
        if (file.isFile || !legacy.isFile) return
        val v = runCatching { String(old.decrypt(legacy.readBytes()), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            if (v.isNotBlank()) write(v.trim())
            legacy.delete()
        }
    }

    private fun write(v: String) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(cipher.encrypt(v.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val FILE = "net-proxy.bin"
        private const val LEGACY_FILE = "container-proxy.bin"
        private const val KEY_ALIAS = "mika-net-proxy"
        private const val LEGACY_KEY_ALIAS = "mika-container-proxy"

        /** Empty string (not used), or a proxy address with scheme, host and port (http, https, socks5, socks5h), without a trailing path. */
        fun valid(proxy: String): Boolean = ProxyAddress.valid(proxy)

        /** Human-readable form: without credentials (that line is visible on the settings page at any time). */
        fun display(proxy: String): String = ProxyAddress.parse(proxy)?.display.orEmpty()

        @Volatile private var instance: NetProxyStore? = null

        /** The production instance; on first retrieval [NetProxy.shared] is wired to it. */
        fun get(context: Context): NetProxyStore = instance ?: synchronized(this) {
            instance ?: NetProxyStore(
                File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(KEY_ALIAS), KeystoreKeyCipher(LEGACY_KEY_ALIAS),
                onChange = { NetProxy.shared.changed(); WebProxy.apply(context.applicationContext) },
            ).also { store ->
                instance = store
                NetProxy.sharedSource = store::get
            }
        }
    }
}
