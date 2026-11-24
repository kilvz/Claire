package com.wangzan101.mika.core.device.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The SPAKE2 variant used for adb wireless pairing (edwards25519 + SHA-512). It must match the implementation on the device byte for byte:
 * - private key = (64-byte random number mod L) × 8, multiplied by the cofactor;
 * - password scalar = SHA-512(password) mod L, then add L, 2L, 4L according to the three low bits, making it a multiple of 8 (matching the historical fix on the device side);
 * - send P* = private key·B + password scalar·(M for Alice, N for Bob);
 * - shared key = SHA-512(each part prefixed with an 8-byte little-endian length: Alice's name, Bob's name, Alice's message, Bob's message, private key·(Q* − password scalar·the other side's constant), SHA-512(password)).
 * M and N are derived from public seed strings, see [SEED_M]/[SEED_N]; the test re-derives them and checks.
 */
class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    enum class Role { ALICE, BOB }

    private var privateKey: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null
    private var done = false

    fun generateMessage(password: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        generateMessage(password, ByteArray(64).also { random.nextBytes(it) })

    /** [seed] is a 64-byte random number; only tests pass a fixed value. */
    internal fun generateMessage(password: ByteArray, seed: ByteArray): ByteArray {
        check(myMsg == null) { "message already generated" }
        require(seed.size == 64)
        val priv = EdPoint.fromLe(seed).mod(EdPoint.L).shiftLeft(3)
        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        var s = EdPoint.fromLe(hash).mod(EdPoint.L)
        if (s.testBit(0)) s += EdPoint.L
        if (s.testBit(1)) s += EdPoint.L.shiftLeft(1)
        if (s.testBit(2)) s += EdPoint.L.shiftLeft(2)
        val mask = (if (role == Role.ALICE) M else N).times(s)
        val msg = (EdPoint.BASE.times(priv) + mask).encode()
        privateKey = priv
        passwordScalar = s
        passwordHash = hash
        myMsg = msg
        return msg.copyOf()
    }

    /** Returns the 64-byte shared key; throws [IllegalArgumentException] when the peer's point is not on the curve. */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        val msg = checkNotNull(myMsg) { "generateMessage first" }
        check(!done) { "already processed" }
        require(theirMsg.size == 32) { "peer message must be 32 bytes" }
        val qStar = requireNotNull(EdPoint.decode(theirMsg)) { "peer point not on curve" }
        val peerMask = (if (role == Role.ALICE) N else M).times(passwordScalar!!)
        val shared = (qStar - peerMask).times(privateKey!!).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            val len = ByteArray(8)
            var l = b.size.toLong()
            for (i in 0 until 8) { len[i] = (l and 0xff).toByte(); l = l ushr 8 }
            sha.update(len)
            sha.update(b)
        }
        if (role == Role.ALICE) {
            put(myName); put(theirName); put(msg); put(theirMsg)
        } else {
            put(theirName); put(myName); put(theirMsg); put(msg)
        }
        put(shared)
        put(passwordHash!!)
        done = true
        return sha.digest()
    }

    companion object {
        const val SEED_M = "edwards25519 point generation seed (M)"
        const val SEED_N = "edwards25519 point generation seed (N)"
        internal val M = requireNotNull(EdPoint.decode(EdPoint.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        internal val N = requireNotNull(EdPoint.decode(EdPoint.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))

        /** Derives the constant point from the seed string: SHA-256(seed); if it does not decode to a point, SHA-256 the digest again until it lies on the curve. */
        internal fun derivePoint(seed: String): EdPoint {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
            while (true) {
                EdPoint.decode(v)?.let { return it }
                v = MessageDigest.getInstance("SHA-256").digest(v)
            }
        }
    }
}
