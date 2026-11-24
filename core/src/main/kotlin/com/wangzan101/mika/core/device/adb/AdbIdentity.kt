package com.wangzan101.mika.core.device.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Claire's own adb identity: one RSA-2048 key plus a self-signed certificate. During pairing [adbPublicKey] is given to the device,
 * and every subsequent TLS connection presents [certificate] (the device only compares the public key inside the certificate).
 */
class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /**
     * One line in the device keystore: base64 (the 524-byte public key structure) + space + name.
     * The name must contain no whitespace: system settings split this line on whitespace, so a name with spaces cannot be removed from "Paired devices".
     */
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(AdbPublicKey.encode(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        /** Public exponent of new keys: F4 (65537). When the exponent cannot be obtained while reading a key back, it is filled in with this value and the fill is verified. */
        val PUBLIC_EXPONENT: BigInteger = RSAKeyGenParameterSpec.F4

        fun generate(name: String): AdbIdentity {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, PUBLIC_EXPONENT))
            return AdbIdentity(g.generateKeyPair(), name)
        }

        /**
         * Reads the identity back from stored PKCS#8. It does not require the platform to provide a private key with CRT parameters: any RSA private key yields the modulus,
         * and the public exponent is recovered via [publicExponent]. Throws [AdbKeyUnreadableException] when it cannot be read.
         */
        fun fromPkcs8(pkcs8: ByteArray, name: String): AdbIdentity {
            val kf = KeyFactory.getInstance("RSA")
            val priv = try {
                kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw AdbKeyUnreadableException("stored key is not valid PKCS#8", e)
            }
            val rsa = priv as? RSAPrivateKey ?: throw AdbKeyUnreadableException("stored key is not RSA (${priv.javaClass.simpleName})")
            val pub = kf.generatePublic(RSAPublicKeySpec(rsa.modulus, publicExponent(rsa, pkcs8))) as RSAPublicKey
            return AdbIdentity(KeyPair(pub, rsa), name)
        }

        /**
         * The public exponent for the private key: if the platform provides the CRT form, use it directly; otherwise read it from the RSAPrivateKey structure in the PKCS#8;
         * only when it is absent there (written as 0) is [PUBLIC_EXPONENT] filled in, and before filling it in a signature is made and verified once; on mismatch it throws rather than connecting with a wrong public key.
         */
        internal fun publicExponent(key: RSAPrivateKey, pkcs8: ByteArray): BigInteger {
            (key as? RSAPrivateCrtKey)?.publicExponent?.takeIf { it.signum() > 0 }?.let { return it }
            Pkcs8Rsa.publicExponent(pkcs8)?.takeIf { it.signum() > 0 }?.let { return it }
            if (!signsWith(key, PUBLIC_EXPONENT)) throw AdbKeyUnreadableException("cannot recover the public exponent of the stored key")
            return PUBLIC_EXPONENT
        }

        private fun signsWith(key: RSAPrivateKey, e: BigInteger): Boolean = runCatching {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, e))
            val data = "mika-adb-key-check".toByteArray()
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data); sign() }
            Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(data); verify(sig) }
        }.getOrDefault(false)
    }
}

/** A stored key exists but cannot be read (cannot be decrypted or parsed). It must not be silently replaced: the device only accepts the one from pairing. */
class AdbKeyUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The key's current state (for diagnostics). */
enum class AdbKeyState {
    /** The key has not been used in this process yet. */
    NotLoaded,
    /** The stored key was read back. */
    Loaded,
    /** There was none before (or the unreadable one was replaced during pairing), so a new one was created and stored. */
    Created,
    /** One is stored yet unreadable: pairing again is required. */
    Unreadable,
}

/**
 * There is only one adb identity per process: pairing and connecting always get the same key, and a failed read never leaves them each using their own.
 * Only pairing ([forPairing]) replaces a stored key that cannot be read with a new one (pairing hands the new key to the device anyway);
 * connecting ([forConnect]) throws [AdbKeyUnreadableException] when the key cannot be read, and the UI asks the user to pair again based on that.
 */
class AdbIdentitySource(
    private val store: AdbKeyStore,
    private val name: () -> String,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private var cached: AdbIdentity? = null

    @Volatile var state: AdbKeyState = AdbKeyState.NotLoaded
        private set

    /** Reason of the most recent failed read (for diagnostics); null when it can be read. */
    @Volatile var problem: String? = null
        private set

    /** For connecting: the stored key; if there is none, create one and store it; if one is stored but unreadable, throw instead of overwriting. */
    @Synchronized
    fun forConnect(): AdbIdentity = cached ?: load()

    /** For pairing: the same key as [forConnect]; only when the stored one is unreadable is a new one created and stored here (the following pairing hands it to the device). */
    @Synchronized
    fun forPairing(): AdbIdentity = cached ?: try {
        load()
    } catch (e: AdbKeyUnreadableException) {
        log("replacing the unreadable adb key for a new pairing", e)
        store.clear()
        create()
    }

    /** Unpairing: delete the key together with the cache; the next pairing uses a new key. */
    @Synchronized
    fun forget() {
        cached = null
        store.clear()
        state = AdbKeyState.NotLoaded
        problem = null
    }

    private fun load(): AdbIdentity {
        val bytes = try {
            store.read()
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        } ?: return create()
        val id = try {
            AdbIdentity.fromPkcs8(bytes, name())
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        }
        state = AdbKeyState.Loaded
        problem = null
        return id.also { cached = it }
    }

    private fun create(): AdbIdentity {
        val id = AdbIdentity.generate(name())
        store.write(id.keyPair.private.encoded)
        state = AdbKeyState.Created
        problem = null
        return id.also { cached = it }
    }

    private fun unreadable(e: AdbKeyUnreadableException): AdbKeyUnreadableException {
        state = AdbKeyState.Unreadable
        problem = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: it.javaClass.simpleName }.take(200)
        log("stored adb key is unreadable; pair again to replace it", e)
        return e
    }
}

/**
 * Storage for the key (PKCS#8). In the App it is stored encrypted, wrapped by a layer of Android Keystore.
 * [read] returns null when nothing was stored; when something is stored but cannot be read it throws [AdbKeyUnreadableException] (the two must be kept apart; the latter must not be treated as absent).
 */
interface AdbKeyStore {
    fun read(): ByteArray?
    fun write(pkcs8: ByteArray)
    fun clear()
}

/**
 * A key stored encrypted in a file: file content = 1-byte IV length + IV + AES-GCM ciphertext.
 * The IV is generated by the Cipher itself (Android Keystore keys do not allow the caller to specify an IV).
 */
class WrappedKeyFile(private val file: File, private val wrappingKey: () -> SecretKey) : AdbKeyStore {
    override fun read(): ByteArray? {
        if (!file.isFile) return null
        return try {
            val b = file.readBytes()
            require(b.isNotEmpty()) { "key file is empty" }
            val ivLen = b[0].toInt() and 0xff
            require(b.size > 1 + ivLen) { "key file is truncated" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        } catch (e: Exception) {
            throw AdbKeyUnreadableException("cannot unwrap ${file.name}", e)
        }
    }

    override fun write(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(pkcs8)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "cannot write ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Reads the RSA public exponent from PKCS#8: PrivateKeyInfo ::= SEQUENCE { version, algorithm, privateKey OCTET STRING },
 * privateKey holds RSAPrivateKey ::= SEQUENCE { version, modulus, publicExponent, ... }. Returns null when the structure is wrong.
 */
internal object Pkcs8Rsa {
    fun publicExponent(pkcs8: ByteArray): BigInteger? = runCatching {
        val info = Der(pkcs8).read(0x30)
        val inner = Der(info).apply { read(0x02); read(0x30) }.read(0x04)
        val rsa = Der(Der(inner).read(0x30))
        rsa.read(0x02)
        rsa.read(0x02)
        BigInteger(rsa.read(0x02))
    }.getOrNull()

    /** Just enough DER to read the levels above: takes the content of the given tag in order. */
    private class Der(private val b: ByteArray) {
        private var pos = 0

        fun read(tag: Int): ByteArray {
            require((b[pos++].toInt() and 0xff) == tag) { "unexpected tag" }
            var len = b[pos++].toInt() and 0xff
            if (len >= 0x80) {
                val n = len and 0x7f
                require(n in 1..3) { "unsupported length" }
                len = 0
                repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xff) }
            }
            require(pos + len <= b.size) { "truncated" }
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}

/**
 * adb's RSA public key binary format (all little-endian, 524 bytes in total):
 * modulus word count (64), n0inv = −1/n mod 2^32, modulus n, rr = (2^2048)^2 mod n, public exponent.
 */
object AdbPublicKey {
    private const val WORDS = 64
    const val SIZE = 4 + 4 + WORDS * 4 * 2 + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(EdPoint.toLe(n, 256))
        buf.put(EdPoint.toLe(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}

/** The smallest self-signed X.509 v3 certificate (no extensions): CN=Claire, 1970-01-01 to 2099-12-31, SHA256withRSA. */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(keyPair: KeyPair): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "Claire".toByteArray()))))
        val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            algId, name, validity, name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
