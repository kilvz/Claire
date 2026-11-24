package com.wangzan101.mika.core.device.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPrivateKeySpec

class AdbIdentitySourceTest {
    private class MemKeyStore(var bytes: ByteArray? = null) : AdbKeyStore {
        var writes = 0
        var unreadable = false
        override fun read(): ByteArray? {
            if (unreadable) throw AdbKeyUnreadableException("cannot unwrap")
            return bytes?.copyOf()
        }
        override fun write(pkcs8: ByteArray) { bytes = pkcs8.copyOf(); writes++ }
        override fun clear() { bytes = null; unreadable = false }
    }

    private val crt = TestTls.client.keyPair.private as RSAPrivateCrtKey

    /** The same key, but the platform hands back an RSA private key without CRT parameters (the public exponent is encoded as 0). */
    private fun nonCrt(key: RSAPrivateCrtKey = crt): RSAPrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(key.modulus, key.privateExponent)) as RSAPrivateKey

    @Test
    fun generatedKeyRoundTripsToTheIdenticalPublicKey() {
        // What was stored before the upgrade is exactly the private-key encoding produced by generate(): it must read back as the same public key so already-paired devices need no re-pairing
        repeat(3) {
            val id = AdbIdentity.generate("mika@x")
            val back = AdbIdentity.fromPkcs8(id.keyPair.private.encoded, "mika@x")
            assertArrayEquals(id.publicKey.encoded, back.publicKey.encoded)
            assertEquals(id.adbPublicKey, back.adbPublicKey)
            assertArrayEquals(id.certificate.publicKey.encoded, back.certificate.publicKey.encoded)
        }
    }

    @Test
    fun nonCrtPrivateKeyStillParsesToTheSamePublicKey() {
        val key = nonCrt()
        assertFalse(key is RSAPrivateCrtKey)
        val id = AdbIdentity.fromPkcs8(key.encoded, "mika@x")
        assertEquals(TestTls.client.publicKey, id.publicKey)
        assertEquals(BigInteger.valueOf(65537), id.publicKey.publicExponent)
        id.certificate.verify(TestTls.client.publicKey)
    }

    @Test
    fun publicExponentComesFromTheEncodingWhenTheKeyObjectLacksIt() {
        assertEquals(BigInteger.valueOf(65537), Pkcs8Rsa.publicExponent(crt.encoded))
        // An encoding without CRT has an exponent of 0: treat it as unknown
        assertEquals(BigInteger.ZERO, Pkcs8Rsa.publicExponent(nonCrt().encoded))
        assertNull(Pkcs8Rsa.publicExponent(byteArrayOf(0x30, 0x05, 1)))
        // The platform's private key has no CRT, yet the encoding does carry the exponent: use the encoded one
        assertEquals(BigInteger.valueOf(65537), AdbIdentity.publicExponent(nonCrt(), crt.encoded))
    }

    @Test
    fun exponentThatIsNotF4AndNotStoredIsRejectedNotGuessed() {
        val g = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, BigInteger.valueOf(65539))) }
        val odd = g.generateKeyPair().private as RSAPrivateCrtKey
        val stripped = nonCrt(odd)
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentity.fromPkcs8(stripped.encoded, "mika@x") }
        // An encoding that carries the exponent is read as usual
        assertEquals(BigInteger.valueOf(65539), AdbIdentity.fromPkcs8(odd.encoded, "mika@x").publicKey.publicExponent)
    }

    @Test
    fun garbageIsUnreadable() {
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentity.fromPkcs8(byteArrayOf(1, 2, 3), "mika@x") }
    }

    @Test
    fun noKeyStoredCreatesAndStoresOne() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        assertEquals(AdbKeyState.NotLoaded, src.state)
        val id = src.forConnect()
        assertEquals(AdbKeyState.Created, src.state)
        assertEquals(1, store.writes)
        assertArrayEquals(id.keyPair.private.encoded, store.bytes)
        val again = AdbIdentitySource(store, { "mika@x" })
        assertEquals(id.publicKey, again.forConnect().publicKey)
        assertEquals(AdbKeyState.Loaded, again.state)
        assertEquals(1, store.writes)
    }

    @Test
    fun unreadableStoredKeyIsReportedAndNeverOverwrittenByConnect() {
        val garbage = byteArrayOf(0x30, 0x03, 2, 1, 0)
        val store = MemKeyStore(garbage)
        val logged = mutableListOf<String>()
        val src = AdbIdentitySource(store, { "mika@x" }) { msg, _ -> logged += msg }
        assertThrows(AdbKeyUnreadableException::class.java) { src.forConnect() }
        assertThrows(AdbKeyUnreadableException::class.java) { src.forConnect() }
        assertEquals(AdbKeyState.Unreadable, src.state)
        assertNotNull(src.problem)
        assertTrue(logged.isNotEmpty())
        assertArrayEquals(garbage, store.bytes)
        assertEquals(0, store.writes)

        // Same when it cannot be unwrapped (the wrapping key is gone)
        val locked = MemKeyStore(TestTls.client.keyPair.private.encoded).apply { unreadable = true }
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentitySource(locked, { "mika@x" }).forConnect() }
        assertEquals(0, locked.writes)
    }

    @Test
    fun pairingReplacesAnUnreadableKeyAndConnectThenUsesTheNewOne() {
        val store = MemKeyStore(byteArrayOf(9, 9, 9))
        val src = AdbIdentitySource(store, { "mika@x" })
        val paired = src.forPairing()
        assertEquals(AdbKeyState.Created, src.state)
        assertNull(src.problem)
        assertArrayEquals(paired.keyPair.private.encoded, store.bytes)
        assertSame(paired, src.forConnect())
    }

    @Test
    fun pairingReusesAReadableKey() {
        val stored = TestTls.client.keyPair.private.encoded
        val store = MemKeyStore(stored)
        val src = AdbIdentitySource(store, { "mika@x" })
        assertEquals(TestTls.client.publicKey, src.forPairing().publicKey)
        assertEquals(0, store.writes)
    }

    @Test
    fun pairAndConnectInOneProcessShareOneIdentityEvenIfTheStoreFailsLater() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        val forPair = src.forPairing()
        // The stored data breaks later: this process keeps using the identity from pairing
        store.unreadable = true
        assertSame(forPair, src.forConnect())
        assertSame(forPair, src.forPairing())
    }

    @Test
    fun forgetClearsTheCacheAndTheStore() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        val first = src.forConnect()
        src.forget()
        assertNull(store.bytes)
        assertEquals(AdbKeyState.NotLoaded, src.state)
        val second = src.forPairing()
        assertNotEquals(first.publicKey, second.publicKey)
        assertArrayEquals(second.keyPair.private.encoded, store.bytes)
    }
}
