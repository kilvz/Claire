package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.TopicPoolData

enum class LearnStatus {
    Off,
    /** Learning is on, but the proactive capability is not watching (while fully exited). */
    Paused,
    NoModel,
    NeedsConsent,
    /** The "App access" settings cannot be read: we do not know which Apps must not be touched, so it stays suspended; nothing accumulated is touched. */
    Suspended,
    Locked,
    /** A task is running; wait for it to finish. */
    Busy,
    BudgetUsedUp,
    /** The topic pool cannot be read (keystore temporarily unavailable or the like): send nothing, retry on the next event. */
    StoreUnavailable,
    /** There is pending work, but no App is due yet; or the previous batch failed and the pause is running (see [LearnTuning.FAILURE_HOLD_MS]). */
    Waiting,
    Sending,
    Idle,
}

/**
 * Counters for one App (in memory only, no content): how many captured, how many distilled, how many batches sent, how many failed, how
 * many accepted, how many dropped and why; for contact classification, how many accepted and how many dropped and why (counted separately
 * from topics).
 */
data class LearnFeed(
    val captured: Int = 0,
    val distilled: Int = 0,
    val batches: Int = 0,
    val failed: Int = 0,
    val accepted: Int = 0,
    val dropped: Map<LearnDrop, Int> = emptyMap(),
    val contactsAccepted: Int = 0,
    val contactDropped: Map<ContactDrop, Int> = emptyMap(),
) {
    val droppedTotal: Int get() = dropped.values.sum()
    val contactDroppedTotal: Int get() = contactDropped.values.sum()
}

/** Contacts as shown to the page: who they are, and how many are in "no longer recorded" (the count only). */
data class ContactsView(val contacts: List<ObservedContact> = emptyList(), val blocked: Int = 0)

data class LearnState(
    val status: LearnStatus = LearnStatus.Off,
    val buffered: Int = 0,
    val pending: Int = 0,
    val lastDistilledAtMs: Long? = null,
    val feed: Map<String, LearnFeed> = emptyMap(),
    /** How many raw notifications the journal currently holds (0 while "daily notification digest" is off). */
    val journal: Int = 0,
    /** Which Apps the kept raw notifications come from (package names only): Apps with nothing digested yet must also be clearable on their own, see "clear what came from it". */
    val journalApps: Set<String> = emptySet(),
)

/** Tapping a segmented digest sentence on the page to see which notifications it was written from ([LearnRunner.rawFor]). */
sealed interface RawNotices {
    /** The entries still kept (possibly none: past their retention, or cleared). */
    data class Found(val entries: List<NoticeEntry>) : RawNotices
    /** The master switch or "daily notification digest" is off: the raw notifications were deleted at that point. */
    data object Off : RawNotices
    /** Cannot be read right now (storage or "App access" cannot be read). */
    data object Unavailable : RawNotices
}

/**
 * Where the topic pool lives and how it is read and written: [TopicPoolFile] in production, an in-memory one in tests. Read, write and
 * delete errors are all thrown and caught by [LearnRunner].
 */
interface TopicPoolStore {
    fun load(): TopicPoolData
    fun save(data: TopicPoolData)
    /** Delete the old files saved aside when the store could not be read: the user deleted something, and those invisible copies may still hold it. */
    fun dropBroken()
    /** Delete everything (the file, temp files, old files saved aside): so "clear all" works even when the store cannot be read. */
    fun wipe()
}

/**
 * Where contacts live and how they are read and written: [ContactPoolFile] in production. Stored separately from the topic pool: contacts
 * being unreadable does not hold up topic digesting, and contact actions on the page do not have to wait for the topic pool. The reverse does
 * not hold: when the topic pool cannot be read, the batch is not sent (contacts ride on the same request).
 */
interface ContactPoolStore {
    fun load(): ContactPoolData
    fun save(data: ContactPoolData)
    /** Same as [TopicPoolStore.dropBroken]. */
    fun dropBroken()
    /** Same as [TopicPoolStore.wipe]. */
    fun wipe()
}

/**
 * Where the notification journal (raw notifications, see [NoticeJournal]) lives: [NoticeJournalFile] in production. Loading with nothing
 * returns null; read, write and delete errors are all thrown and caught by [LearnRunner].
 */
interface NoticeJournalStore {
    fun load(): NoticeJournalData?
    fun save(data: NoticeJournalData)
    /** Delete everything; also works when it cannot be read. */
    fun wipe()
}

/** Where the sentences accumulated by the daily notification digest live: [DigestFile] in production. Loading with nothing returns empty; read, write and delete errors are all thrown and caught by [LearnRunner]. */
interface DigestStore {
    fun load(): DigestData
    fun save(data: DigestData)
    /** Delete everything; also works when it cannot be read. */
    fun wipe()
}

/**
 * Deletes not yet persisted: the user asked to delete something (turn off "sensitive-category inference" and delete, clear one App's data,
 * delete a single topic or contact, one sentence, one day's/week's/month's digest), but that part could not be read at the time, or the
 * delete was not saved. They are recorded and redone first both when the store becomes readable and on the next start, and cleared only once
 * saved. Without recording, a delete that was not saved plus a process death would leave the file holding the old data, and the deleted
 * things would come back on the next start.
 *
 * [ProactivePrefs] in production (survives process death). It holds package names (clear this App's data) and markers starting with a colon
 * (package names contain no colon): [LearnRunner.PURGE_SENSITIVE] plus the single deletes (only ids and days, no content; topic and sentence
 * ids are random, contact ids are hashes computed from the App and conversation name — the preferences file is not encrypted, so this entry
 * sits in it for a while before being saved). Recording it is best effort: a failed disk write usually means storage is full, in which case
 * the preferences usually cannot be written either.
 *
 * The topic and contact lists are ordered by the order recorded (a set iterates in that order), and the redo follows that order: for the
 * same topic name, "delete" and "merge into another" count in the order they were done, and a merge target must exist before the step that
 * merges into it. Implementations must persist that order along with the entries.
 */
interface LearnPurges {
    /** Not yet persisted on the topic pool, in the order recorded. */
    var topicPurges: Set<String>
    /** Not yet persisted on contacts, in the order recorded. */
    var contactPurges: Set<String>

    /**
     * Record the same marker at the end of both the topic and the contact lists (a merge changes both). The production implementation
     * ([ProactivePrefs]) persists both in one write: if written in two steps and the process died in between, only half would be recorded,
     * and the redo would merge the topics while the contact links lagged behind. The default here writes in two steps (contacts first) and is
     * only for test doubles.
     */
    fun addToTopicsAndContacts(mark: String) {
        contactPurges = contactPurges - mark + mark
        topicPurges = topicPurges - mark + mark
    }
    /** Not yet persisted on the segmented digest sentences and the digests at each level. */
    var digestPurges: Set<String>
    /** Not yet persisted on the raw notification journal: package names whose raw notifications are all to be removed, and markers for "a notification key's entries in one burst" (only the key's hash and time). */
    var journalPurges: Set<String>
}
