package com.wangzan101.mika.proactive

import android.content.SharedPreferences
import com.wangzan101.mika.core.proactive.TriageConsent

/**
 * A few small pieces of proactive state: when the feature was turned on, the last lock time, the dedup table (only notification-key hashes and
 * times, no notification content and no raw keys), the provider id and model name the user consented to; the sub-switches and each App's source
 * tier; the deletes in learning that have not been persisted ([LearnPurges]: package names, the "sensitive category" marker, and the single
 * deletes — random ids for topics and sentences, the day of a given day/week/month, contact identity ids, which are hashes computed from the App
 * and conversation name, the same kind of thing as the notification-key hashes in the dedup table: not the name itself, but not random); which
 * reminder chat is today's (chat id and day). The inbox and the decision results are never persisted; notification text is only written into
 * that chat when the user opens a reminder (see [NoticeTalks]), never here.
 */
class ProactivePrefs(private val prefs: SharedPreferences) : LearnPurges, AllowRevokes {
    var enabledAtMs: Long
        get() = prefs.getLong(ENABLED_AT, 0L)
        set(v) { prefs.edit().putLong(ENABLED_AT, v).apply() }

    var lastLockedAtMs: Long
        get() = prefs.getLong(LAST_LOCKED_AT, 0L)
        set(v) { prefs.edit().putLong(LAST_LOCKED_AT, v).apply() }

    var dedup: String?
        get() = prefs.getString(DEDUP, null)
        set(v) { prefs.edit().putString(DEDUP, v).apply() }

    fun clearDedup() { prefs.edit().remove(DEDUP).apply() }

    /**
     * Which provider's which model the user consented to send notification text to (used by both the decision pass and learning); null before
     * consent, or after the master switch is turned off. Without it, nothing is sent.
     */
    var consent: TriageConsent?
        get() {
            val provider = prefs.getString(CONSENT_PROVIDER, null) ?: return null
            val model = prefs.getString(CONSENT_MODEL, null) ?: return null
            return TriageConsent(provider, model)
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(CONSENT_PROVIDER).remove(CONSENT_MODEL)
            else e.putString(CONSENT_PROVIDER, v.providerId).putString(CONSENT_MODEL, v.model)
            e.commit()
        }

    /** "Sensitive-category inference" (health, finances, religion, politics, sexual orientation); off by default. */
    var learnSensitive: Boolean
        get() = prefs.getBoolean(LEARN_SENSITIVE, false)
        set(v) { prefs.edit().putBoolean(LEARN_SENSITIVE, v).commit() }

    /**
     * "Daily notification digest": off by default. While on, the raw notifications of learned Apps are stored encrypted on this device (at most
     * two days, see [NoticeJournalFile]); turning it off deletes the stored raw text too (sentences already digested and the digests at each
     * level are kept). It has its own disclosure and does not turn on automatically with the master switch's consent.
     */
    var learnDigest: Boolean
        get() = prefs.getBoolean(LEARN_DIGEST, false)
        set(v) { prefs.edit().putBoolean(LEARN_DIGEST, v).commit() }

    /** Aggressiveness (see [com.wangzan101.mika.core.proactive.LearnLevel]); its name is stored, defaulting to the most conservative level. */
    var learnLevel: String?
        get() = prefs.getString(LEARN_LEVEL, null)
        set(v) { prefs.edit().putString(LEARN_LEVEL, v).commit() }

    /** Apps whose source tier is set to "high" (package names only); "low" ones are in [learnOptOut], and read ones in neither are "medium". */
    var learnHigh: Set<String>
        get() = prefs.getStringSet(LEARN_HIGH, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_HIGH, HashSet(v)).commit() }

    /** Apps whose source tier is "low" (package names only): their notifications, decisions and reminders are read, but nothing is learned from them. Empty by default. */
    var learnOptOut: Set<String>
        get() = prefs.getStringSet(LEARN_OPT_OUT, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_OPT_OUT, HashSet(v)).commit() }

    /** Today's reminder chat (see [NoticeTalks]): chat id, and the day it was opened on (epoch day of the local date). Null when none was opened. */
    var hintTalk: Pair<String, Long>?
        get() {
            val id = prefs.getString(HINT_TALK_ID, null) ?: return null
            return id to prefs.getLong(HINT_TALK_DAY, Long.MIN_VALUE)
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(HINT_TALK_ID).remove(HINT_TALK_DAY) else e.putString(HINT_TALK_ID, v.first).putLong(HINT_TALK_DAY, v.second)
            e.apply()
        }

    /** Deletes and merges not yet applied on the topic pool (see [LearnPurges]), in the order recorded. */
    override var topicPurges: Set<String>
        get() = ordered(LEARN_PURGE_TOPICS)
        set(v) { prefs.edit().putOrdered(LEARN_PURGE_TOPICS, v).commit() }

    /** Not yet applied on contacts, in the order recorded. */
    override var contactPurges: Set<String>
        get() = ordered(LEARN_PURGE_CONTACTS)
        set(v) { prefs.edit().putOrdered(LEARN_PURGE_CONTACTS, v).commit() }

    /** Persist both in one write: if the process dies in between, only half is never recorded. */
    override fun addToTopicsAndContacts(mark: String) {
        prefs.edit()
            .putOrdered(LEARN_PURGE_TOPICS, topicPurges - mark + mark)
            .putOrdered(LEARN_PURGE_CONTACTS, contactPurges - mark + mark)
            .commit()
    }

    /**
     * Read them out in the order recorded. A string set in preferences does not preserve order, so the order is stored separately as one entry
     * per line ([key] plus [ORDER]) and written together with the set. When the two do not match (an older version that only knew the set
     * changed it later), only the set is trusted and the order is lost.
     */
    private fun ordered(key: String): Set<String> {
        val set = prefs.getStringSet(key, null)?.toSet().orEmpty()
        val order = prefs.getString(key + ORDER, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        return if (order.size == set.size && order.toSet() == set) LinkedHashSet(order) else set
    }

    private fun SharedPreferences.Editor.putOrdered(key: String, v: Set<String>): SharedPreferences.Editor =
        putStringSet(key, HashSet(v)).putString(key + ORDER, v.joinToString("\n"))

    /** Deletes not yet persisted on the sentences and digests at each level (package names, and single-delete markers: sentence id, the day of a given day/week/month). */
    override var digestPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_DIGEST, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_DIGEST, HashSet(v)).commit() }

    /** Per-App deletes not yet persisted on the raw notification journal (package names). */
    override var journalPurges: Set<String>
        get() = prefs.getStringSet(LEARN_PURGE_JOURNAL, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(LEARN_PURGE_JOURNAL, HashSet(v)).commit() }

    /** Apps the user chose "do not read" for while the list file could not be written (package names only); redone on startup ([AllowEdits.replay]). */
    override var allowRevoked: Set<String>
        get() = prefs.getStringSet(ALLOW_REVOKED, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(ALLOW_REVOKED, HashSet(v)).commit() }

    companion object {
        const val FILE = "proactive"
        private const val ENABLED_AT = "enabled_at_ms"
        private const val LAST_LOCKED_AT = "last_locked_at_ms"
        private const val DEDUP = "dedup_v2"
        // The key names only mention the decision pass, but what is stored is the consent shared by the decision pass and learning
        private const val CONSENT_PROVIDER = "triage_consent_provider"
        private const val CONSENT_MODEL = "triage_consent_model"
        private const val LEARN_OPT_OUT = "learn_opt_out"
        private const val LEARN_SENSITIVE = "learn_sensitive"
        private const val LEARN_DIGEST = "learn_digest"
        private const val LEARN_LEVEL = "learn_level"
        private const val LEARN_HIGH = "learn_high"
        private const val LEARN_PURGE_TOPICS = "learn_purge_topics"
        /** The order of the recorded entries (see [ordered]): the key name is the set's key plus this. */
        private const val ORDER = "_order"
        private const val HINT_TALK_ID = "hint_talk_id"
        private const val HINT_TALK_DAY = "hint_talk_day"
        private const val LEARN_PURGE_CONTACTS = "learn_purge_contacts"
        private const val LEARN_PURGE_DIGEST = "learn_purge_digest"
        private const val LEARN_PURGE_JOURNAL = "learn_purge_journal"
        private const val ALLOW_REVOKED = "allow_revoked"
    }
}
