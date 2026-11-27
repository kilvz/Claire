package com.wangzan101.mika.cap.phone.shell.adb

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.wangzan101.mika.core.device.adb.AdbIdentity
import com.wangzan101.mika.core.device.adb.AdbIdentitySource
import com.wangzan101.mika.core.device.adb.AdbKeyStore
import com.wangzan101.mika.core.device.adb.AdbTls
import com.wangzan101.mika.core.device.adb.KeyingMaterialExporter
import com.wangzan101.mika.core.device.adb.WrappedKeyFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/** adb identity and TLS on Android: the key is wrapped with an AES key from the Keystore and kept in the app's private directory; keying material export goes through platform TLS. */
object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "mika_adb_key_wrap"
    private const val TAG = "MikaAdbKey"

    // The device-side export has no context; under TLS 1.3 an empty context and no context produce the same result (RFC 8446 §7.5)
    // The platform export API requires Android 12; the built-in shell itself is only enabled from 12
    val exporter = KeyingMaterialExporter { socket, label, length ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw SSLException("keying material export needs Android 12")
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length) ?: throw SSLException("keying material export failed")
    }

    @Volatile private var source: AdbIdentitySource? = null

    /** The single identity source for this process: both pairing and connecting take it from here, so they get the same key. */
    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "claire@${Build.MODEL}" }) { msg, e -> Log.w(TAG, msg, e) }.also { source = it }
    }

    /** Identity used for connecting; throws [com.wangzan101.mika.core.device.adb.AdbKeyUnreadableException] when the stored key cannot be read. */
    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    /** Identity used for pairing: falls back to a new key when the stored key cannot be read (see [AdbIdentitySource.forPairing]). */
    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    /** Deletes the key and this process's cache (used by "unpair"); the authorization record on the device has to be revoked by the user in system settings. */
    fun forget(context: Context) = identities(context).forget()

    /** The key file plus the Keystore key that wraps it: clearing removes both. */
    private fun keyStore(context: Context): AdbKeyStore {
        val file = WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")) { wrappingKey() }
        return object : AdbKeyStore by file {
            override fun clear() {
                file.clear()
                runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
