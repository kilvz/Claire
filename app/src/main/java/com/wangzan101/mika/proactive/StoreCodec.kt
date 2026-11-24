package com.wangzan101.mika.proactive

import com.wangzan101.mika.settings.KeyCipher
import java.io.IOException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * This store was written by a newer version: the current code does not understand its format. The reading layer keeps it as-is — it is not read in, not modified, and not saved over; switching back to the newer version can still read it.
 * (Deletions the user asks for still happen: "clear all" and the like delete such unreadable stores in full, without distinguishing why they were unreadable.)
 */
class NewerStoreVersion(val found: Int, val known: Int) : IOException("store written by a newer version: $found > $known")

/**
 * The shared read path for the four encrypted proactive stores (topics, contacts, notification text, summarization at each level): it tells "the file is genuinely corrupt" apart from "it cannot be read right now".
 *
 * Only cases with evidence count as corrupt: failed authentication (the ciphertext was modified, or the key changed and it can never be decrypted again), or too short to be a ciphertext.
 * Any other error counts as "cannot be read right now": it is thrown as usual and the file is left untouched:
 * - the keystore being busy, an IO error while fetching the key, the device not being unlocked ... none of these mean the ciphertext is corrupt;
 * - decrypting fine but failing to parse does not count as corrupt either: authentication passed, so this is the copy Claire itself wrote; failing to parse most likely means it was written by another version
 *   (enum values a newer version added, a changed format), and switching back to a version that can read it will still read it, whereas deleting it loses it for good.
 * The other way round (treating only a few exception types as "unreadable" and everything else as a corrupt file to delete or re-save), a single IO error while fetching the key would delete monthly summaries kept for two years.
 *
 * Version number: every store carries `version` at its outermost level ([write] always writes it and does not rely on the data class's default value — default values are omitted during serialization).
 * When reading, look at it first, then decode into the data class: if it is newer than what this code knows, throw [NewerStoreVersion]. Without looking first, a file from a newer version would mostly still decode
 * (unknown fields are ignored), and the next save would rewrite it in the old format, losing whatever the newer version added. A missing version number counts as 1.
 */
internal object StoreCodec {
    val json = Json { ignoreUnknownKeys = true }

    private const val VERSION = "version"

    /** The plaintext used for storage: the contents of [data] with [version] at the outermost level. */
    fun <T> write(serializer: SerializationStrategy<T>, data: T, version: Int): ByteArray {
        val body = json.encodeToJsonElement(serializer, data) as JsonObject
        return JsonObject(mapOf(VERSION to JsonPrimitive(version)) + (body - VERSION)).toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Reads it back; null if the file is genuinely corrupt (the caller deletes or re-saves it). Cases that cannot be read right now are thrown as usual;
     * a version newer than [known] throws [NewerStoreVersion].
     */
    fun <T> read(bytes: ByteArray, cipher: KeyCipher, serializer: DeserializationStrategy<T>, known: Int): T? {
        val plain = try {
            cipher.decrypt(bytes)
        } catch (e: AEADBadTagException) {
            return null
        } catch (e: IllegalArgumentException) {
            // too short to hold the IV and the authentication tag
            return null
        }
        // unparseable input is thrown as usual (SerializationException); the file is left untouched
        val root = json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject ?: throw SerializationException("store is not an object")
        val version = when (val v = root[VERSION]) {
            null -> 1
            else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw SerializationException("store version is not a number")
        }
        if (version > known) throw NewerStoreVersion(version, known)
        return json.decodeFromJsonElement(serializer, root)
    }
}
