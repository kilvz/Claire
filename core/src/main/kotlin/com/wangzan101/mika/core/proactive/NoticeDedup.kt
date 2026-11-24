package com.wangzan101.mika.core.proactive

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Deduplication: for each notification key it records the post time it has seen and the time of the newest message seen, keeping [capacity] keys in least-recently-used order.
 * Plain notifications are judged by (key, post time); for conversation-style notifications only messages newer than the last seen ones count as new.
 * Only times are stored, never notification content; keys are not stored verbatim either: a key carries the tag the posting app chose (which may contain a conversation identifier),
 * so both in memory and in what [encode] writes out only the first [ID_HEX] hex digits of the key's SHA-256 ([idOf]) are kept, and the persisted data cannot be reversed into the original key.
 *
 * Message times are filled in by the posting app and cannot be fully trusted ([trusted]): looking at time alone, a time set in the future would be recorded verbatim as the watermark, so genuinely new messages afterwards would always be "older" than it;
 * when every message is 0 or several fall in the same millisecond, the later ones never count as new either. So untrusted times never enter the watermark, and when there is previous content to compare against, content is compared:
 * whether this round's messages contain any not seen last time. Each message's fingerprint lives only in memory (nothing persisted: the persisted copy is agreed to hold only times),
 * so after a process restart there is nothing to compare against the first time and only times can be used — and on that round, messages whose times are untrusted count as seen.
 */
class NoticeDedup(private val capacity: Int = DEFAULT_CAPACITY) {
    /** [sigs] holds the fingerprints of the messages seen last time (memory only; nothing loaded from storage has them). */
    private data class Seen(val postedAtMs: Long, val lastMessageAtMs: Long, val sigs: Set<Long>? = null)

    /** The messages newly seen this round in a notification that was accepted (oldest first); empty for non-conversation-style notifications. */
    class Admitted(val newMessages: List<NoticeMessage>)

    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > capacity
    }

    /** Whether [record] has new content; if so, record it and return true. */
    fun accept(record: NoticeRecord): Boolean = admit(record) != null

    /** If [record] has new content, record it and report which of its messages were newly seen this round; null when there is no new content. */
    @Synchronized fun admit(record: NoticeRecord): Admitted? {
        val id = idOf(record.key)
        val before = seen[id]
        if (!record.conversation || record.messages.isEmpty()) {
            val fresh = before == null || record.postedAtMs != before.postedAtMs
            // keep the previous message fingerprints: this round simply carried no messages (or all were blank), and the next round that does carry them still needs something to compare against
            if (fresh) seen[id] = Seen(record.postedAtMs, before?.lastMessageAtMs ?: Long.MIN_VALUE, before?.sigs)
            return if (fresh) Admitted(emptyList()) else null
        }
        // the recorded watermark is later than this notification's post time: that cannot be real, so treat it as absent
        val mark = before?.lastMessageAtMs?.takeIf { trusted(it, record.postedAtMs) || it < 1 } ?: Long.MIN_VALUE
        val sigs = record.messages.map(::fingerprint)
        val known = before?.sigs
        val new = when {
            before == null -> record.messages
            // there is previous content to compare against: this round's messages that were not seen last time (an app merely dropping the oldest entries from the list or filling in wrong times will not cause a wrong call)
            known != null -> record.messages.filterIndexed { i, _ -> sigs[i] !in known }
            // first round after a restart: only times are available, and only trusted times count
            else -> record.messages.filter { trusted(it.atMs, record.postedAtMs) && it.atMs > mark }
        }
        val newest = record.messages.filter { trusted(it.atMs, record.postedAtMs) }.maxOfOrNull { it.atMs }
        val fresh = before == null || new.isNotEmpty()
        // even when nothing is new, still record this round's content: the next round needs something to compare against
        seen[id] = Seen(if (fresh) record.postedAtMs else before!!.postedAtMs, maxOf(newest ?: Long.MIN_VALUE, mark), sigs.toSet())
        return if (fresh) Admitted(new) else null
    }

    @Synchronized fun clear() = seen.clear()

    @Synchronized fun size(): Int = seen.size

    /** `{"v":2,"seen":[[key id, post time, newest message time], …]}`, oldest first. */
    @Synchronized fun encode(): String = JsonObject(mapOf(
        "v" to JsonPrimitive(FORMAT),
        "seen" to JsonArray(seen.map { (id, v) ->
            JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(v.postedAtMs), JsonPrimitive(v.lastMessageAtMs)))
        }),
    )).toString()

    companion object {
        const val DEFAULT_CAPACITY = 500
        /** Version of the stored format; data in any other version is treated as empty when read. */
        const val FORMAT = 2
        const val ID_HEX = 16
        private const val HEX = "0123456789abcdef"

        /** Id of a notification key: the first [ID_HEX] lowercase hex digits of the SHA-256 of its UTF-8 bytes. Hex is written out by hand rather than using HexFormat, which only exists on Android 34. */
        fun idOf(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return buildString(ID_HEX) {
                for (i in 0 until ID_HEX / 2) {
                    val b = digest[i].toInt() and 0xff
                    append(HEX[b ushr 4]); append(HEX[b and 0x0f])
                }
            }
        }

        private fun isId(s: String) = s.length == ID_HEX && s.all { it in HEX }

        /** How much later than the notification's post time a message time may be: it is common for the messaging server's clock to run slightly ahead of the phone's. */
        const val CLOCK_SKEW_MS = 2 * 60_000L

        /**
         * Whether the time a message carries ([atMs], filled in by the posting app) can be trusted: it must lie between 1 and the post time recorded by the system ([postedAtMs]),
         * with a tiny bit later ([CLOCK_SKEW_MS]) also allowed. Missing times, 0, and times far later than the post (mistakes, or deliberately set in the future) are not trusted.
         * The notification journal uses the same rule when timing its entries.
         */
        fun trusted(atMs: Long, postedAtMs: Long): Boolean = atMs in 1..postedAtMs + CLOCK_SKEW_MS

        /** Fingerprint of a message (who said it, what was said, at what time): the first 8 bytes of its SHA-256. Used only in memory to compare "was this seen last time". */
        private fun fingerprint(m: NoticeMessage): Long {
            val d = MessageDigest.getInstance("SHA-256").digest((m.sender.orEmpty() + "\u0000" + m.text + "\u0000" + m.atMs).toByteArray(Charsets.UTF_8))
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (d[i].toLong() and 0xff)
            return out
        }

        /** Reads back the result of [encode]; anything unreadable (corrupt, another format version, ids that do not look like ids) is treated as empty. */
        fun decode(text: String?, capacity: Int = DEFAULT_CAPACITY): NoticeDedup {
            val d = NoticeDedup(capacity)
            if (text.isNullOrBlank()) return d
            runCatching {
                val o = Json.parseToJsonElement(text).jsonObject
                require(o.getValue("v").jsonPrimitive.int == FORMAT)
                val entries = o.getValue("seen").jsonArray.map { e ->
                    val a = e.jsonArray
                    val id = a[0].jsonPrimitive.content
                    require(a[0].jsonPrimitive.isString && isId(id))
                    id to Seen(a[1].jsonPrimitive.long, a[2].jsonPrimitive.long)
                }
                entries.forEach { (k, v) -> d.seen[k] = v }
            }.onFailure { d.seen.clear() }
            return d
        }
    }
}
