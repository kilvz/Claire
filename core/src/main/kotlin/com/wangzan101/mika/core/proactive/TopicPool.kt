package com.wangzan101.mika.core.proactive

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kinds of topic (a fixed set; anything else the model gives is not accepted). */
@Serializable
enum class TopicKind(val wire: String) {
    /** Subjects the user seems interested in. */
    @SerialName("interest") Interest("interest"),
    /** Accounts, authors and channels the user follows. */
    @SerialName("follow") Follow("follow"),
    /** Things the user is looking at or wants to buy. */
    @SerialName("shopping") Shopping("shopping"),
    /** Services and subscriptions the user uses. */
    @SerialName("service") Service("service"),
    /** Recurring personal matters (deliveries, commuting, bills), by category only, without amounts, tracking numbers or addresses. */
    @SerialName("routine") Routine("routine");

    companion object {
        fun ofWire(s: String?): TopicKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * One accumulated topic. [label] is a phrase the model derived from untrusted notification text (cleaned and validated) and is only displayed as plain text.
 * [evidence]: app package name → the days (epoch days of the local date, ascending) on which that app corroborated it; evidence is counted per "app × day", not per notification.
 * The app is always a package name from the set we gave, never from the model. [opened] and [dismissed] are how many notifications assigned here the user opened or swiped away.
 * No notification original text is stored.
 */
@Serializable
data class ObservedTopic(
    val id: String,
    val kind: TopicKind,
    val label: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val evidence: Map<String, List<Long>> = emptyMap(),
    val opened: Int = 0,
    val dismissed: Int = 0,
    /** Sensitive inference (health, finance, religion, politics, sexual orientation): only exists when the user has turned on "sensitive inference". */
    val sensitive: Boolean = false,
) {
    /** How many distinct "app × day" entries there are. */
    val evidenceCount: Int get() = evidence.values.sumOf { it.size }

    /** On how many distinct days it has appeared (regardless of app). */
    val days: Int get() = evidence.values.flatten().toSet().size

    /** How many apps have corroborated it. */
    val apps: Int get() = evidence.size

    /** Established only once it has appeared on at least [LearnTuning.ESTABLISHED_DAYS] distinct days; one that appeared on a single day is still uncertain. */
    val established: Boolean get() = days >= LearnTuning.ESTABLISHED_DAYS

    /** Strength: the number of evidence entries, with a bonus for opened and a penalty for dismissed, never below 0. */
    val strength: Double
        get() = (evidenceCount + LearnTuning.OPENED_BONUS * opened - LearnTuning.DISMISSED_PENALTY * dismissed).coerceAtLeast(0.0)

    /** Retention: exp(-Δt / S), where Δt is how long since it last appeared and S = [LearnTuning.STABILITY_DAYS] days × (1 + strength). */
    fun retention(nowMs: Long): Double {
        val dtDays = (nowMs - lastSeenMs).coerceAtLeast(0L).toDouble() / LearnTuning.DAY_MS
        return exp(-dtDays / (LearnTuning.STABILITY_DAYS * (1 + strength)))
    }

    fun score(nowMs: Long): Double = strength * retention(nowMs)
}

/**
 * The persisted shape of the topic pool: only topic labels, kinds, counts and times. [blocked] holds fingerprints of the topic labels the user deleted and asked not to record again
 * (see [TopicPool.fingerprint]: fingerprints only, no labels). [merged] maps the fingerprint of a label the user merged away to the id of the topic it was merged into
 * (see [TopicPool.merge]; fingerprints only here too).
 */
@Serializable
data class TopicPoolData(
    val version: Int = 1,
    val topics: List<ObservedTopic> = emptyList(),
    val blocked: List<String> = emptyList(),
    val merged: Map<String, String> = emptyMap(),
)

/** One validated signal: either cite an existing topic ([topicId]) or create a new one ([newKind] + [newLabel]). [events] are indices within this batch. There is no app field. */
data class LearnSignal(
    val events: List<Int>,
    val topicId: String? = null,
    val newKind: TopicKind? = null,
    val newLabel: String? = null,
    /** A new topic the model marked sensitive. */
    val sensitive: Boolean = false,
)

/**
 * The topic pool: pure logic and not thread-safe (it runs on the single serial coroutine in ProactiveRuntime).
 * The model can only "add a topic" or "cite a topic"; renaming, merging and deleting are for the user alone. The one exception is deliberate: when a new topic the model created is marked sensitive
 * and has the same label as an existing one, the existing one is marked sensitive too (this only tightens, never loosens, and can make up for earlier misses).
 * No periodic upkeep: tidying ([maintain]) happens every time learning results are applied or that page is opened.
 *
 * A topic the user deleted on its own is never learned back: a fingerprint of its label is recorded, and a topic the model later creates with the same label (of any kind) is not accepted.
 * Only the label is matched, so a differently worded one gets through. "Clear all", "clear one app's", "delete the sensitive ones" and old ones forgotten on their own are separate matters and are not recorded.
 *
 * Once the user merges one topic into another ([merge]), the merged-away label is recorded as an alias: a topic the model later creates with that label counts as citing the one it was merged into.
 * "Do not record again" and aliases are two decisions about the same label, and the later one wins: merging a label away removes it from "do not record again"; deleting a topic records
 * its own label and every label merged into it under "do not record again" (what the user deleted is the merged one, and those labels should not slip away through an alias and be learned again).
 *
 * A topic id is random ([randomId]), not a sequential number: sequential numbers would be issued again after "a topic was created but not persisted" or "the pool could not be read and started over empty",
 * while topics linked from contacts and topics recorded on events only know ids, and would then point at unrelated topics.
 */
class TopicPool(data: TopicPoolData = TopicPoolData(), private val newId: () -> String = ::randomId) {
    private val topics = LinkedHashMap<String, ObservedTopic>().apply { data.topics.forEach { put(it.id, it) } }
    private val blocked = LinkedHashSet(data.blocked)
    /** Fingerprint of a merged-away label → the id of the topic it was merged into. Aliases whose target topic is gone (forgotten, cleared) do not count; both persisting and label matching only look at aliases that still resolve. */
    private val aliases = LinkedHashMap(data.merged)
    /** Merged-away topic id → the id it was merged into, for this process: requests in flight at the moment of the merge still cite the merged-away one. Not persisted (requests never cross processes). */
    private val moved = HashMap<String, String>()

    fun data(): TopicPoolData =
        TopicPoolData(topics = topics.values.toList(), blocked = blocked.toList(), merged = aliases.filterValues { it in topics })

    /** How many topics the user deleted and asked not to record again. */
    val blockedCount: Int get() = blocked.size

    fun all(): List<ObservedTopic> = topics.values.toList()

    operator fun get(id: String): ObservedTopic? = topics[id]

    /**
     * The existing topics shown to the model, [limit] in total, all picked by strength × retention from high to low: first the ones [packageName] (this batch's app) corroborated, taking at most two-thirds of the slots;
     * the remaining slots go to the strongest of the rest (any app; if its own did not fill the two-thirds, the spare slots go here too).
     * The model can only cite what it can see: when the pool holds far more than [limit] topics, picking by strength alone would usually leave out the topic this app pushed before,
     * so when it pushes the same thing again the model can only invent a similarly worded one. The same app mostly pushes the same subjects, so its own go first;
     * but not all of them: an app with many topics (a chat app, say) should not push out all the strong topics from other apps — the same subject comes from other apps too.
     */
    fun strongest(
        nowMs: Long,
        limit: Int = LearnTuning.EXISTING_MAX,
        includeSensitive: Boolean = false,
        packageName: String? = null,
    ): List<ObservedTopic> {
        val byScore = topics.values.filter { includeSensitive || !it.sensitive }.sortedByDescending { it.score(nowMs) }
        val own = if (packageName == null) emptyList() else byScore.filter { packageName in it.evidence }.take(limit * 2 / 3)
        val ownIds = own.mapTo(HashSet()) { it.id }
        return own + byScore.filter { it.id !in ownIds }.take(limit - own.size)
    }

    /** Statistics after applying one batch of results. [byEvent]: indices in this batch → the topic ids they were assigned to. [blocked]: how many newly created topics were rejected because the user had deleted that label. */
    data class Applied(val byEvent: Map<Int, Set<String>>, val applied: Int, val missing: Int, val blocked: Int = 0)

    /**
     * Applies one batch of learning results to the pool. [packageName] is this batch's app (we do the batching), and evidence is only recorded under it.
     * A cited topic the user deleted while the request was in flight is dropped and not revived; one the user merged into another counts as citing that other one.
     * A new topic with the same kind and the same label as an existing one (ignoring case, full/half width and whitespace) counts as citing it; if none matches by label but the label is one the user merged away (of any kind), it counts as citing the one merged into;
     * if neither applies but the label is one the user deleted (of any kind back then), it is not accepted.
     * The same notification counts only once as opened/dismissed for the same topic.
     *
     * One reply records at most one day of evidence for a topic: when several signals land on the same topic they are combined, and recorded on the day the newest notification they cite was posted.
     * So a batch spanning midnight (caught up after unlocking in the morning with both last night's and this morning's) cannot make a topic "established" on the strength of one judgement. The day is the day the notification was posted, not the day of the judgement.
     */
    fun apply(
        packageName: String,
        events: List<LearnEvent>,
        signals: List<LearnSignal>,
        nowMs: Long,
        zone: ZoneId,
        allowSensitive: Boolean = false,
    ): Applied {
        // for this round, the notifications (indices) citing each topic, and whether any of them marked it sensitive
        val cites = LinkedHashMap<String, LinkedHashSet<Int>>()
        val flagged = HashSet<String>()
        var applied = 0
        var missing = 0
        var refused = 0
        for (s in signals) {
            val cited = s.events.distinct().filter { it in events.indices }
            if (cited.isEmpty()) continue
            // with "sensitive inference" off: new topics marked sensitive are not accepted, and existing sensitive topics get no evidence (this uses the switch as of the moment results are applied)
            if (!allowSensitive && s.sensitive) { missing++; continue }
            val id = when {
                s.topicId != null -> (moved[s.topicId] ?: s.topicId).takeIf { it in topics }
                // first match an existing topic with the same label (still kept, still used), then a label the user merged away; only when creating does it check whether the user deleted this label
                s.newKind != null && s.newLabel != null -> findSame(s.newKind, s.newLabel)?.id
                    ?: aliases[fingerprint(s.newLabel)]?.takeIf { it in topics }
                    ?: if (fingerprint(s.newLabel) in blocked) { refused++; continue } else create(s.newKind, s.newLabel, s.sensitive, nowMs)
                else -> null
            }
            if (id == null || (!allowSensitive && topics.getValue(id).sensitive)) { missing++; continue }
            cites.getOrPut(id) { LinkedHashSet() } += cited
            if (s.sensitive) flagged += id
            applied++
        }
        val byEvent = HashMap<Int, MutableSet<String>>()
        for ((id, cited) in cites) {
            val t = topics.getValue(id)
            val seen = cited.maxOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            topics[id] = t.copy(
                evidence = addDays(t.evidence, packageName, listOf(dayOf(seen, zone))),
                lastSeenMs = maxOf(t.lastSeenMs, seen),
                opened = t.opened + cited.count { events[it].outcome == LearnOutcome.Opened },
                dismissed = t.dismissed + cited.count { events[it].outcome == LearnOutcome.Dismissed },
                sensitive = t.sensitive || id in flagged,
            )
            cited.forEach { byEvent.getOrPut(it) { HashSet() } += id }
        }
        maintain(nowMs)
        // topics dropped during tidying are no longer cited
        return Applied(byEvent.mapValues { (_, ids) -> ids.filterTo(HashSet()) { it in topics } }, applied, missing, refused)
    }

    /**
     * A notification that was learned from but only opened/swiped away later: the outcome is added to the topic it was assigned to. Returns true if anything changed.
     * With "sensitive inference" off ([allowSensitive] false) sensitive topics are left alone: off means nothing more is added to them at all.
     */
    fun addOutcome(ids: Set<String>, outcome: LearnOutcome, allowSensitive: Boolean = false): Boolean {
        var changed = false
        for (id in ids) {
            val t = topics[id] ?: continue
            if (t.sensitive && !allowSensitive) continue
            topics[id] = when (outcome) {
                LearnOutcome.Opened -> t.copy(opened = t.opened + 1)
                LearnOutcome.Dismissed -> t.copy(dismissed = t.dismissed + 1)
                LearnOutcome.None -> continue
            }
            changed = true
        }
        return changed
    }

    /** Tidying: drops topics with retention below [LearnTuning.FORGET_BELOW], and when there are more than [LearnTuning.POOL_CAP] drops the lowest strength × retention. Returns the ids dropped. */
    fun maintain(nowMs: Long): Set<String> {
        val dropped = HashSet<String>()
        topics.values.filter { it.retention(nowMs) < LearnTuning.FORGET_BELOW }.forEach { dropped += it.id }
        dropped.forEach(topics::remove)
        if (topics.size > LearnTuning.POOL_CAP) {
            topics.values.sortedBy { it.score(nowMs) }.take(topics.size - LearnTuning.POOL_CAP).forEach {
                dropped += it.id
                topics.remove(it.id)
            }
        }
        return dropped
    }

    /** The user deletes one: topics with the same label are no longer recorded either (only the label's fingerprint is kept); labels merged into it get the same treatment. */
    fun delete(id: String): Boolean {
        val t = topics.remove(id) ?: return false
        // its own label goes last (when the list is full the earliest are dropped first)
        val names = aliases.filterValues { it == id }.keys + fingerprint(t.label)
        for (f in names) {
            // this label is no longer anyone's alias (likewise if another topic with the same label was merged away before: the later decision wins)
            aliases.remove(f)
            blocked.remove(f)
            blocked += f
        }
        while (blocked.size > LearnTuning.TOPIC_BLOCKED_CAP) blocked.remove(blocked.first())
        return true
    }

    /**
     * The user merges [fromId] into [intoId]: the two are really the same thing (the model created it again with different wording). Afterwards only [intoId] remains, keeping its label and kind:
     * evidence is merged per "app × day" (the same app on the same day on both sides counts once), opened and dismissed counts are added (a notification counted on both sides is counted twice,
     * which cannot be told apart and is left alone), either one being sensitive makes it sensitive (only tightening), and the first and last seen times take the extremes of both.
     * [fromId]'s label is recorded as an alias (see the class comment); labels previously merged into [fromId] are re-pointed at [intoId].
     * When the two are the same, or one is already gone (the page shows an older state), nothing happens and false is returned. It cannot be undone: there is no inverse operation.
     */
    fun merge(fromId: String, intoId: String): Boolean {
        if (fromId == intoId) return false
        val from = topics[fromId] ?: return false
        val into = topics[intoId] ?: return false
        topics.remove(fromId)
        val union = LinkedHashMap<String, List<Long>>()
        for (pkg in into.evidence.keys + from.evidence.keys) {
            union[pkg] = (into.evidence[pkg].orEmpty() + from.evidence[pkg].orEmpty()).toSortedSet().toList().takeLast(LearnTuning.DAYS_PER_APP)
        }
        // when there are too many apps, keep the ones that appeared most recently
        val kept = union.entries.sortedByDescending { it.value.lastOrNull() ?: Long.MIN_VALUE }.take(LearnTuning.APPS_PER_TOPIC).map { it.key }.toSet()
        topics[intoId] = into.copy(
            firstSeenMs = minOf(into.firstSeenMs, from.firstSeenMs),
            lastSeenMs = maxOf(into.lastSeenMs, from.lastSeenMs),
            evidence = union.filterKeys { it in kept },
            opened = into.opened + from.opened,
            dismissed = into.dismissed + from.dismissed,
            sensitive = into.sensitive || from.sensitive,
        )
        // aliases whose target topic is gone are removed here; ones previously merged into from are re-pointed at into
        aliases.values.removeAll { it != fromId && it !in topics }
        aliases.replaceAll { _, id -> if (id == fromId) intoId else id }
        val f = fingerprint(from.label)
        aliases.remove(f)
        aliases[f] = intoId
        blocked.remove(f)
        while (aliases.size > LearnTuning.TOPIC_MERGED_CAP) aliases.remove(aliases.keys.first())
        moved.replaceAll { _, id -> if (id == fromId) intoId else id }
        moved[fromId] = intoId
        return true
    }

    /** Allows deleted topics again (all of them at once). */
    fun allowAgain(): Boolean {
        if (blocked.isEmpty()) return false
        blocked.clear()
        return true
    }

    /** Clear everything: this is a fresh start and does not go into "do not record again" (what is already recorded stays); merged-away labels are no longer remembered either. */
    fun clear() {
        topics.clear()
        aliases.clear()
        moved.clear()
    }

    /** Deletes all sensitive topics (the user turned off "sensitive inference" and chose deletion). Returns the deleted ids. */
    fun deleteSensitive(): Set<String> {
        val ids = topics.values.filter { it.sensitive }.map { it.id }.toSet()
        ids.forEach(topics::remove)
        return ids
    }

    /** Removes all evidence from one app; topics left with no evidence are deleted. Returns the ids of the deleted topics. */
    fun clearApp(packageName: String): Set<String> {
        val dropped = HashSet<String>()
        for (t in topics.values.toList()) {
            if (packageName !in t.evidence) continue
            val left = t.evidence - packageName
            if (left.isEmpty()) {
                topics.remove(t.id)
                dropped += t.id
            } else {
                topics[t.id] = t.copy(evidence = left)
            }
        }
        return dropped
    }

    private fun findSame(kind: TopicKind, label: String): ObservedTopic? {
        val n = normalize(label)
        return topics.values.firstOrNull { it.kind == kind && normalize(it.label) == n }
    }

    private fun create(kind: TopicKind, label: String, sensitive: Boolean, nowMs: Long): String {
        var id = newId()
        while (id in topics) id = newId()
        topics[id] = ObservedTopic(id, kind, label, firstSeenMs = nowMs, lastSeenMs = nowMs, sensitive = sensitive)
        return id
    }

    companion object {
        /** A new topic id: `k` followed by 12 random hex digits. */
        fun randomId(): String = "k" + UUID.randomUUID().toString().replace("-", "").take(12)

        private val SPACES = Regex("[\\s\\p{Z}\\u200B\\uFEFF]+")

        /** A topic label's fingerprint: computed from the label after [normalize], independent of kind. The fingerprint is stored instead of the label: what the user deleted should not be kept verbatim. */
        fun fingerprint(label: String): String = NoticeDedup.idOf("topic:" + normalize(label))

        /**
         * The other topics offered to the user when merging: ones whose label is similar to [of] come first (the more shared adjacent character pairs, the earlier; what is being merged is usually the similarly worded one,
         * and with many topics the user should not have to scroll from the top), with the rest ordered by strength × retention from high to low.
         */
        fun mergeCandidates(of: ObservedTopic, all: List<ObservedTopic>, nowMs: Long): List<ObservedTopic> {
            val pairs = pairsOf(of.label)
            return all.filter { it.id != of.id }
                .sortedWith(compareByDescending<ObservedTopic> { t -> pairsOf(t.label).count { it in pairs } }.thenByDescending { it.score(nowMs) })
        }

        private fun pairsOf(label: String): Set<String> = normalize(label).windowed(2).toSet()

        /** For comparing topic labels: full/half width and compatibility characters unified, case-insensitive and whitespace-insensitive. */
        fun normalize(label: String): String =
            SPACES.replace(Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT), "")

        fun dayOf(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

        /** Adds this app's days (deduplicated, ascending, keeping only the most recent [LearnTuning.DAYS_PER_APP]); when there are too many apps the ones that appeared longest ago are dropped. */
        internal fun addDays(evidence: Map<String, List<Long>>, packageName: String, days: List<Long>): Map<String, List<Long>> {
            val merged = (evidence[packageName].orEmpty() + days).toSortedSet().toList().takeLast(LearnTuning.DAYS_PER_APP)
            val out = LinkedHashMap(evidence)
            out[packageName] = merged
            while (out.size > LearnTuning.APPS_PER_TOPIC) {
                val stalest = out.entries.filter { it.key != packageName }.minByOrNull { it.value.lastOrNull() ?: Long.MIN_VALUE } ?: break
                out.remove(stalest.key)
            }
            return out
        }
    }
}
