package com.wangzan101.mika.core.proactive

import java.time.ZoneId
import kotlin.math.exp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Whether a contact is a person or a group (group members are not recorded separately). */
@Serializable
enum class ContactType(val wire: String) {
    @SerialName("person") Person("person"),
    @SerialName("group") Group("group");

    companion object {
        fun ofWire(s: String?): ContactType? = entries.firstOrNull { it.wire == s }
    }
}

/** The relationship to the user (a fixed set); groups use the same set (family group, work group, classmate group). */
@Serializable
enum class ContactRelation(val wire: String) {
    @SerialName("family") Family("family"),
    /** Partner: counts as a sensitive-type inference; the model can only supply it while "sensitive inference" is enabled. */
    @SerialName("partner") Partner("partner"),
    @SerialName("friend") Friend("friend"),
    @SerialName("colleague") Colleague("colleague"),
    @SerialName("classmate") Classmate("classmate"),
    @SerialName("other") Other("other"),
    @SerialName("unknown") Unknown("unknown");

    companion object {
        fun ofWire(s: String?): ContactRelation? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * A contact accumulated over time (a person or a group). [id] is computed by [ContactIdentity], and [name] is the most recent usable display name from the notification titles;
 * neither comes from the model; the model only casts votes for type and relationship. Votes are recorded per day: [typeVotes] and [relationVotes] hold the days on which each value was voted for (epoch days of the local date, ascending),
 * and the same value on the same day counts as one vote (ten messages in one day still count as one). [days] are the days on which it appeared. Neither message text nor a per-day message count is stored.
 * [topics] are the linked topic ids: we link them mechanically according to "which topic this conversation's notification was classified into", not something the model provides.
 * [userRelation] is set by the user and always takes precedence over the vote counts.
 */
@Serializable
data class ObservedContact(
    val id: String,
    val packageName: String,
    val name: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val typeVotes: Map<ContactType, List<Long>> = emptyMap(),
    val relationVotes: Map<ContactRelation, List<Long>> = emptyMap(),
    val days: List<Long> = emptyList(),
    val topics: List<String> = emptyList(),
    val userRelation: ContactRelation? = null,
) {
    /** Sensitive: someone has voted "partner", or the user set it to partner. */
    val sensitive: Boolean get() = userRelation == ContactRelation.Partner || ContactRelation.Partner in relationVotes

    /** Type: the one with the most votes (on a tie, the one voted for most recently); a person when there are no votes yet. */
    val type: ContactType
        get() = typeVotes.entries.maxWithOrNull(compareBy({ it.value.size }, { it.value.lastOrNull() ?: Long.MIN_VALUE }))?.key
            ?: ContactType.Person

    /**
     * The relationship shown: the one the user set, if any; otherwise the most-voted one, shown only when it was voted for on at least [LearnTuning.RELATION_MIN_DAYS] different days
     * and holds more than half of all relationship votes ("unknown" votes count toward the total); otherwise [ContactRelation.Unknown].
     */
    val relation: ContactRelation
        get() {
            userRelation?.let { return it }
            val total = relationVotes.values.sumOf { it.size }
            val top = relationVotes.entries.maxByOrNull { it.value.size } ?: return ContactRelation.Unknown
            return if (top.value.size >= LearnTuning.RELATION_MIN_DAYS && top.value.size * 2 > total) top.key else ContactRelation.Unknown
        }

    /** Whether the displayed relationship is one the user set (if not, it is a guess). */
    val userSet: Boolean get() = userRelation != null

    /** Counts as established only after appearing on at least [LearnTuning.CONTACT_ESTABLISHED_DAYS] different days. */
    val established: Boolean get() = days.size >= LearnTuning.CONTACT_ESTABLISHED_DAYS

    /** Strength: the number of distinct days it appeared on. */
    val strength: Double get() = days.size.toDouble()

    /** Retention: as with topics, exp(-Δt / S), where S = [LearnTuning.STABILITY_DAYS] days × (1 + strength). */
    fun retention(nowMs: Long): Double {
        val dtDays = (nowMs - lastSeenMs).coerceAtLeast(0L).toDouble() / LearnTuning.DAY_MS
        return exp(-dtDays / (LearnTuning.STABILITY_DAYS * (1 + strength)))
    }

    fun score(nowMs: Long): Double = strength * retention(nowMs)
}

/** How contacts are persisted: others' display names, type and relationship votes, days, linked topics; [blocked] holds the identity ids of deleted contacts to "no longer record" (ids only). */
@Serializable
data class ContactPoolData(
    val version: Int = 1,
    val contacts: List<ObservedContact> = emptyList(),
    val blocked: List<String> = emptyList(),
)

/** One validated contact classification: it references several notifications from this batch ([events] are indices) and states who sent them (type) and what their relationship to the user is. No name. */
data class ContactSignal(val events: List<Int>, val type: ContactType, val relation: ContactRelation)

/** Why a contact classification was dropped (used for counting, carries no content). */
enum class ContactDrop {
    /** Beyond [LearnTuning.CONTACTS_MAX]. */
    OverLimit,
    /** Not an object, or "type" is an unrecognized value or missing. */
    Malformed,
    /** A referenced notification id is not in this batch: the whole entry is dropped (counted once per entry). */
    UnknownId,
    /** There is not a single valid notification id. */
    NoIds,
    /** "Summarize contacts and relationships" is off (at request time or at application time). */
    Off,
    /** No identity can be computed for the referenced notifications, or the title yielded no usable display name when first seen. */
    NoIdentity,
    /** The user deleted this contact ("do not record again"). */
    Blocked,
}

/**
 * The contact pool: pure logic, not thread-safe (used on the single serial coroutine of ProactiveRuntime).
 * Who counts as one contact is decided by [ContactIdentity], and the model only casts votes for type and relationship; a relationship is always a guess unless the user set it.
 * The type (person or group) is derived from how the notification is written when that is unambiguous ([LearnEvent.group], see [NoticeShapes]); the model's vote is used only when it is not.
 * There is no periodic maintenance: it is tidied once ([maintain]) when summarization results are applied or a page is opened.
 */
class ContactPool(data: ContactPoolData = ContactPoolData()) {
    private val contacts = LinkedHashMap<String, ObservedContact>().apply { data.contacts.forEach { put(it.id, it) } }
    private val blocked = LinkedHashSet(data.blocked)

    fun data(): ContactPoolData = ContactPoolData(contacts = contacts.values.toList(), blocked = blocked.toList())

    fun all(): List<ObservedContact> = contacts.values.toList()

    operator fun get(id: String): ObservedContact? = contacts[id]

    val blockedCount: Int get() = blocked.size

    data class Applied(val accepted: Int, val dropped: Map<ContactDrop, Int>, val changed: Boolean)

    /**
     * Applies a batch of contact classifications to the pool. [events] is the batch in its current form (titles still present); identities are computed per [strategy].
     * When one classification's referenced notifications land on several identities, each identity gets one vote. While "sensitive inference" is off ([allowSensitive] is false), "partner" counts as unknown.
     * [topicsByEvent] maps each notification in this batch to the topics it was classified into: for a notification belonging to a contact, its topics are linked to that contact (mechanically, not supplied by the model).
     *
     * One reply records at most one day per contact and casts at most one vote per type and relationship: several classifications landing on the same identity are merged and recorded on the day
     * the newest of their referenced notifications was posted. So a batch spanning midnight cannot, from one judgement alone, reach the two days required for a displayed relationship. The day is the day the notification was posted, not the day of the judgement.
     */
    fun apply(
        events: List<LearnEvent>,
        signals: List<ContactSignal>,
        topicsByEvent: Map<Int, Set<String>>,
        nowMs: Long,
        zone: ZoneId,
        allowSensitive: Boolean = false,
        strategy: ContactIdStrategy = LearnTuning.CONTACT_ID,
    ): Applied {
        // when the title is the speaker rather than the conversation name (group chats without a conversation name), the conversation cannot be identified: not recorded
        val ids = events.map { if (it.titleIsSpeaker) null else ContactIdentity.of(it.packageName, it.key, it.title, strategy) }
        // The "do not record again" list may still hold ids recorded under the previous algorithm (with the notification key): if this notification's id under that previous algorithm is exactly it, record the current id too.
        // Otherwise, after a change of algorithm, contacts the user deleted would quietly come back
        // The list growing as a result also counts as a change: it must be saved. Otherwise, once the process restarts and the notification key changes again, the previous id no longer matches and the deleted contacts come back
        var carried = false
        if (strategy != ContactIdStrategy.KeyAndTitle) events.forEachIndexed { i, e ->
            val now = ids[i]?.id ?: return@forEachIndexed
            if (now in blocked) return@forEachIndexed
            // The current id already has a contact (either the one the user kept, carried over, or one recorded anew after the notification key changed): do not carry it over. Carrying it over would put it both on the list
            // and among the contacts: it would stay on the page yet never move again, with no explanation. If the user does not want it, they can delete it once more; this time it is recorded by conversation name, which holds up even when the key changes
            if (now in contacts) return@forEachIndexed
            val before = ContactIdentity.of(e.packageName, e.key, e.title, ContactIdStrategy.KeyAndTitle)?.id
            // Replace it with the current id rather than adding another one: the old one will never be needed again (the current id holds up even when the notification key changes), and keeping both would mean
            // that one deletion takes two slots on the list; when the list is full, each conversion evicts some other deletion that has not been converted yet
            if (before != null && before in blocked) { blocked.remove(before); block(now); carried = true }
        }
        // the display name comes from the newest notification in this batch that can provide one for this conversation; if there is none, the old name is kept; one that yields none on first sight is not recorded
        fun nameOf(id: String): String? = events.indices.filter { ids[it]?.id == id }.sortedBy { events[it].postedAtMs }
            .mapNotNull { ids[it]?.name }.lastOrNull()
        val dropped = LinkedHashMap<ContactDrop, Int>()
        fun drop(d: ContactDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
        // for each identity in this round, the referenced notifications (indices) and the types and relationships voted for
        class Cite {
            val cited = LinkedHashSet<Int>()
            val types = LinkedHashSet<ContactType>()
            val relations = LinkedHashSet<ContactRelation>()
        }
        val cites = LinkedHashMap<String, Cite>()
        var accepted = 0
        for (s in signals) {
            val cited = s.events.distinct().filter { it in events.indices }
            val relation = if (!allowSensitive && s.relation == ContactRelation.Partner) ContactRelation.Unknown else s.relation
            var used = false
            for ((id, idx) in cited.groupBy { ids[it]?.id }) {
                if (id == null) { drop(ContactDrop.NoIdentity); continue }
                if (id in blocked) { drop(ContactDrop.Blocked); continue }
                if (id !in contacts && nameOf(id) == null) { drop(ContactDrop.NoIdentity); continue }
                val c = cites.getOrPut(id) { Cite() }
                c.cited += idx
                // group or person: when the notification's wording is unambiguous (all the referenced ones agree), go by it and ignore the model; only use the model's value when it is not
                c.types += knownType(idx.map { events[it].group }) ?: s.type
                c.relations += relation
                used = true
            }
            if (used) accepted++
        }
        var changed = false
        for ((id, cite) in cites) {
            val name = nameOf(id)
            val first = cite.cited.minOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            val seen = cite.cited.maxOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            val day = listOf(TopicPool.dayOf(seen, zone))
            val c = contacts[id] ?: ObservedContact(id, events[cite.cited.first()].packageName, name!!, firstSeenMs = first, lastSeenMs = seen)
            contacts[id] = c.copy(
                name = name ?: c.name,
                firstSeenMs = minOf(c.firstSeenMs, first),
                lastSeenMs = maxOf(c.lastSeenMs, seen),
                days = addDays(c.days, day),
                typeVotes = cite.types.fold(c.typeVotes) { votes, type -> vote(votes, type, day) },
                relationVotes = cite.relations.fold(c.relationVotes) { votes, relation -> vote(votes, relation, day) },
            )
            changed = true
        }
        // link topics to contacts: for notifications in this batch that belong to a contact (one now present in the pool), the topics they were classified into
        for ((i, topicIds) in topicsByEvent) {
            val id = ids.getOrNull(i)?.id ?: continue
            val c = contacts[id] ?: continue
            val next = (c.topics - topicIds + topicIds).takeLast(LearnTuning.CONTACT_TOPICS)
            if (next != c.topics) { contacts[id] = c.copy(topics = next); changed = true }
        }
        return Applied(accepted, dropped, changed || carried)
    }

    /**
     * Tidies up: removes links to topics that no longer exist (not checked when [liveTopics] is null: the topic pool could not be read); drops contacts whose retention is below [LearnTuning.FORGET_BELOW] (those with a user-set relationship are not dropped);
     * when there are more than [LearnTuning.CONTACT_CAP], drops the ones with the lowest strength × retention (user-set relationships are likewise not dropped). Returns true when something changed.
     */
    fun maintain(nowMs: Long, liveTopics: Set<String>?): Boolean {
        var changed = liveTopics != null && forgetTopicsWhere { it !in liveTopics }
        val auto = contacts.values.filter { it.userRelation == null }
        auto.filter { it.retention(nowMs) < LearnTuning.FORGET_BELOW }.forEach { contacts.remove(it.id); changed = true }
        if (contacts.size > LearnTuning.CONTACT_CAP) {
            contacts.values.filter { it.userRelation == null }.sortedBy { it.score(nowMs) }
                .take(contacts.size - LearnTuning.CONTACT_CAP).forEach { contacts.remove(it.id); changed = true }
        }
        return changed
    }

    /** Topic [from] was merged into [into] by the user: links to it now point at the other one (kept in the original position; when both are linked, only one remains). Returns true when something changed. */
    fun remapTopic(from: String, into: String): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (from !in c.topics) continue
            contacts[c.id] = c.copy(topics = c.topics.map { if (it == from) into else it }.distinct())
            changed = true
        }
        return changed
    }

    /** A topic was deleted: contacts are no longer linked to it. Returns true when something changed. */
    fun forgetTopics(ids: Set<String>): Boolean = ids.isNotEmpty() && forgetTopicsWhere { it in ids }

    private fun forgetTopicsWhere(gone: (String) -> Boolean): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (c.topics.none(gone)) continue
            contacts[c.id] = c.copy(topics = c.topics.filterNot(gone))
            changed = true
        }
        return changed
    }

    /** The user sets a relationship; null hands it back to guessing from votes. */
    fun setRelation(id: String, relation: ContactRelation?): Boolean {
        val c = contacts[id] ?: return false
        if (c.userRelation == relation) return false
        contacts[id] = c.copy(userRelation = relation)
        return true
    }

    /**
     * The user deletes one: the same conversation is no longer recorded afterwards either (only the id is kept). When the deleted entry was stored under the previous algorithm (making up for a deletion that was not saved before),
     * the id it would have under the current algorithm is recorded as well — the identically named entries are about to merge into one ([adoptIdentities]), and it must not come back by way of the algorithm change.
     */
    fun delete(id: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        val c = contacts.remove(id) ?: return false
        block(id)
        if (fromAnOlderStrategy(id)) ContactIdentity.ofStored(c.packageName, c.name, strategy)?.let(::block)
        return true
    }

    /**
     * Replays a recorded deletion ([id] is the contact deleted at the time): if it still exists it is deleted as usual; if not, [id] is still recorded on the "do not record again" list.
     * It can legitimately be missing: entries were converted to the current identities ([adoptIdentities]) but not saved, the user then deleted the converted entry and that was not saved either,
     * and the process died — the next read still gets the file from before the conversion, which lacks this id. If it were merely skipped, the next identity conversion would bring the deleted contact back.
     * Returns true when something changed.
     */
    fun forget(id: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        if (delete(id, strategy)) return true
        if (id in blocked) return false
        block(id)
        return true
    }

    private fun block(id: String) {
        blocked.remove(id)
        blocked += id
        while (blocked.size > LearnTuning.CONTACT_BLOCKED_CAP) blocked.remove(blocked.first())
    }

    /**
     * Converts contacts stored under the previous algorithm (with the notification key) to the current identities ([strategy], conversation name only): identically named ones in the same app merge into one.
     * Previously, each change of notification key left another copy of the same group stored; here they are merged back. Ones whose current identity cannot be computed (empty name) are left as they are, to fade away.
     * When merging: the days that appeared and the type and relationship votes are unioned; the name and the user-set relationship come from the entry seen most recently, falling back to the other entry when it has none;
     * all linked topics are kept (when that exceeds the limit, the most recent ones are kept). If the current identity is on the "do not record again" list, it is not kept.
     * When everything is already under the current identity, nothing happens. Returns true when something changed. Must be called after replaying the recorded deletions (those deletions record the previous ids).
     */
    fun adoptIdentities(strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        if (strategy != ContactIdStrategy.Title || contacts.keys.none(::fromAnOlderStrategy)) return false
        val out = LinkedHashMap<String, ObservedContact>()
        for (c in contacts.values) {
            val id = if (fromAnOlderStrategy(c.id)) ContactIdentity.ofStored(c.packageName, c.name, strategy) ?: c.id else c.id
            if (id in blocked) continue
            val moved = if (id == c.id) c else c.copy(id = id)
            out[id] = out[id]?.let { merge(it, moved) } ?: moved
        }
        contacts.clear()
        contacts.putAll(out)
        return true
    }

    /** Allows deleted contacts again (all of them at once). */
    fun allowAgain(): Boolean {
        if (blocked.isEmpty()) return false
        blocked.clear()
        return true
    }

    /** Clears everything: this is a fresh start, nothing goes on the "do not record again" list. */
    fun clear(): Boolean {
        if (contacts.isEmpty()) return false
        contacts.clear()
        return true
    }

    /** Clears one app's contacts: likewise nothing goes on the "do not record again" list. */
    fun clearApp(packageName: String): Boolean = contacts.values.removeAll { it.packageName == packageName }

    /**
     * "Sensitive inference" was turned off and deletion was chosen: removes the "partner" votes (a user-set value is left alone, since that is not an inference). Returns true when something changed.
     */
    fun deleteSensitive(): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (ContactRelation.Partner !in c.relationVotes) continue
            contacts[c.id] = c.copy(relationVotes = c.relationVotes - ContactRelation.Partner)
            changed = true
        }
        return changed
    }

    companion object {
        /** The type when several notifications all say group (or all say one-to-one); null when some are unclear or they disagree with each other. */
        private fun knownType(groups: List<Boolean?>): ContactType? = when {
            groups.isEmpty() || groups.any { it == null } -> null
            groups.all { it == true } -> ContactType.Group
            groups.none { it == true } -> ContactType.Person
            else -> null
        }

        // the id produced by the previous algorithm (key plus title, the only one used in released versions): its letter plus a hex hash. Compared character by character, without a regex
        private fun fromAnOlderStrategy(id: String): Boolean {
            val prefix = ContactIdStrategy.KeyAndTitle.prefix
            return id.length == prefix.length + NoticeDedup.ID_HEX && id.startsWith(prefix) &&
                id.drop(prefix.length).all { it in '0'..'9' || it in 'a'..'f' }
        }

        /** The two entries are actually the same contact: merge them into one (rules in [adoptIdentities]). The id and app are taken from [a]. */
        private fun merge(a: ObservedContact, b: ObservedContact): ObservedContact {
            val (older, newer) = if (b.lastSeenMs >= a.lastSeenMs) a to b else b to a
            fun <K> votes(x: Map<K, List<Long>>, y: Map<K, List<Long>>): Map<K, List<Long>> =
                (x.keys + y.keys).associateWith { addDays(x[it].orEmpty(), y[it].orEmpty()) }
            return a.copy(
                name = newer.name,
                firstSeenMs = minOf(a.firstSeenMs, b.firstSeenMs),
                lastSeenMs = maxOf(a.lastSeenMs, b.lastSeenMs),
                typeVotes = votes(a.typeVotes, b.typeVotes),
                relationVotes = votes(a.relationVotes, b.relationVotes),
                days = addDays(a.days, b.days),
                // when over the limit keep the most recent: topics linked by both sides take their position from the newer entry (the same rule as when refreshing links normally)
                topics = (older.topics - newer.topics.toSet() + newer.topics).distinct().takeLast(LearnTuning.CONTACT_TOPICS),
                userRelation = newer.userRelation ?: older.userRelation,
            )
        }

        private fun addDays(old: List<Long>, days: List<Long>): List<Long> =
            (old + days).toSortedSet().toList().takeLast(LearnTuning.CONTACT_DAYS)

        /** One value on one day counts as one vote: the votes are simply the days voted for. */
        private fun <T> vote(votes: Map<T, List<Long>>, value: T, days: List<Long>): Map<T, List<Long>> {
            val out = LinkedHashMap(votes)
            out[value] = addDays(votes[value].orEmpty(), days)
            return out
        }
    }
}
