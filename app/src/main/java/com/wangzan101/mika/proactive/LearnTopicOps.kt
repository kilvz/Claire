package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicPool
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.proactive.LearnRunner.Companion.PURGE_SENSITIVE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_TOPIC
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_CONTACT
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MERGE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MERGE_INTO
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_NOTE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_DAY
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_WEEK
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MONTH

// Learning: deleting, merging and clearing topics and contacts, and the delete records
internal fun LearnRunner.deleteTopic(id: String) {
    val p = loadedPool() ?: return
    if (p.delete(id)) {
        // Record first, then save: if saving fails and the process dies, it is redone on the next load (see [LearnPurges])
        val mark = MARK_TOPIC + id
        purges.topicPurges = purges.topicPurges - mark + mark
        topicPurgesApplied = topicPurgesApplied + mark
        topicsGone(setOf(id))
        save(p)
        dropBroken()
    }
}

/**
 * Merge topic [fromId] into [intoId] (done by the user on the topics page; rules in [TopicPool.merge]): references to [fromId] on events and
 * contacts are repointed to [intoId]. It does not go through the delete path: the merged-away name does not enter "no longer recorded", and
 * links are not lost.
 *
 * Like a delete, it is recorded before saving (see [LearnPurges]) and cleared only once saved; if saving fails and the process dies, it is
 * redone in the recorded order on the next load:
 * - The topics side: without the redo, both topics would still be in the file. If the user then deletes the merged result (that delete is
 *   recorded and will be redone), the redo would find no merge in the file, so the merged-away one comes back with its evidence, and its name
 *   is not in "no longer recorded" either.
 * - The contacts side: if unreadable at that moment, or changed but not saved, the file still links the merged-away id; once the topics side
 *   is saved, the next tidy-up treats those links as pointing at nothing and drops them.
 * Both are recorded in one step ([LearnPurges.addToTopicsAndContacts]): recording only the topics half would lose the contact links on redo.
 *
 * The marker holds only the two ids, so both topics must be in the file at redo time. If one is not there yet (just distilled, not saved),
 * save first; if it still cannot be saved, do not merge — a merge could not be recorded either: if the process dies, the target is not in the
 * file, this merge cannot be redone, and the merged-away one comes back. A merge cannot be split, so when it cannot be saved it is better not to do it.
 *
 * Save contacts before topics: if the process dies mid-way, the contacts link to the topic that still exists.
 */
internal fun LearnRunner.mergeTopic(fromId: String, intoId: String) {
    val p = loadedPool() ?: return
    if (p[fromId] == null || p[intoId] == null || fromId == intoId) return
    if ((fromId !in savedTopics || intoId !in savedTopics) && !save(p)) {
        log("learn merge not done: topic not saved")
        return
    }
    if (!p.merge(fromId, intoId)) return
    val mark = MARK_MERGE + fromId + MERGE_INTO + intoId
    purges.addToTopicsAndContacts(mark)
    topicPurgesApplied = topicPurgesApplied + mark
    buffer.remapTopic(fromId, intoId)
    val c = contactPool
    if (c == null) {
        // Contacts are not loaded yet: they are applied (including this entry) the moment they load; if they cannot be read, it waits until they can
        loadedContacts()
    } else {
        c.remapTopic(fromId, intoId)
        contactPurgesApplied = contactPurgesApplied + mark
        saveContacts(c)
    }
    save(p)
}

/** A recorded merge: the merged-away topic id and the id merged into; null for non-merge markers or malformed ones. */
internal fun LearnRunner.mergeIn(mark: String): Pair<String, String>? {
    if (!mark.startsWith(MARK_MERGE)) return null
    val ids = mark.removePrefix(MARK_MERGE).split(MERGE_INTO)
    return if (ids.size == 2 && ids[0].isNotEmpty() && ids[1].isNotEmpty()) ids[0] to ids[1] else null
}

/**
 * Clear everything: topics and contacts (contacts do not go into "no longer recorded"; this is a fresh start). Whichever store cannot be
 * read, its file is deleted outright: clearing must not end up doing nothing just because it cannot be read (deleting the contacts file that
 * way also loses the "no longer recorded" list: unreadable means it cannot be kept). Ones that can be read are cleared and saved as usual,
 * and the old files saved aside are deleted too.
 */
internal fun LearnRunner.clearAll() {
    // Cancel the batch in flight and clear the unsent ones in the buffer: otherwise a reply coming back would add back what was just cleared, and the remaining notification text would still be sent to the model later
    stopRequest()
    buffer.clear()
    val p = loadedPool()
    val topicsCleared: Boolean
    if (p != null) {
        topicsGone(p.all().map { it.id }.toSet())
        p.clear()
        // Cleared but not saved: the file still holds the old data and it would come back on the next start. So delete the file
        topicsCleared = save(p) || wipe("topics") { store.wipe() }
    } else {
        topicsCleared = wipe("topics") { store.wipe() }
        pool = TopicPool()
        topicsFlow.value = emptyList()
        topicsBlockedFlow.value = 0
    }
    val c = loadedContacts()
    val contactsCleared: Boolean
    if (c != null) {
        // Save even when memory is already empty: a previous delete may not have been saved and the file may still hold the old data — skipping
        // the save here, with the recorded pending deletes cleared below, would bring the old contacts back on the next start
        c.clear()
        contactsCleared = saveContacts(c) || wipe("contacts") { contactStore.wipe() }
    } else {
        contactsCleared = wipe("contacts") { contactStore.wipe() }
        contactPool = ContactPool()
        contactsFlow.value = ContactsView()
    }
    dropBroken()
    // Cleared and persisted: no deletes are left to redo. When it was neither saved nor deleted (the file still holds the old data): record a
    // "wipe all"; memory is already empty, so it is cleared when the next save succeeds; if the process dies before that, it is applied on the
    // next load (see purgeTopics, purgeContacts)
    purges.topicPurges = if (topicsCleared) emptySet() else setOf(MARK_ALL)
    topicPurgesApplied = purges.topicPurges
    purges.contactPurges = if (contactsCleared) emptySet() else setOf(MARK_ALL)
    contactPurgesApplied = purges.contactPurges
    // Clear the raw notifications and the daily notification digest too (whatever cannot be deleted is recorded by each of them, see dropJournal, dropDigest)
    dropJournal()
    dropDigest()
    if (host != null) evaluate()
}

/** "Sensitive-category inference" turned off with deletion chosen: delete topics marked sensitive and drop the contacts' "partner" votes. Whichever store is unreadable at that moment is recorded and redone once readable. */
internal fun LearnRunner.deleteSensitive() = purge(PURGE_SENSITIVE)

/** Clear one App's data: its evidence on topics and its contacts (also not recorded into "no longer recorded"). Whichever store is unreadable at that moment is recorded and redone once readable. */
internal fun LearnRunner.clearApp(packageName: String) = purge(packageName)

/** Delete one contact: the same conversation is not recorded again either. */
internal fun LearnRunner.deleteContact(id: String) {
    loadedContacts()?.let {
        if (it.delete(id)) {
            val mark = MARK_CONTACT + id
            purges.contactPurges = purges.contactPurges + mark
            contactPurgesApplied = contactPurgesApplied + mark
            saveContacts(it)
            dropBroken()
        }
    }
}

/** The user sets the relation; null hands it back to Claire to decide. */
internal fun LearnRunner.setContactRelation(id: String, relation: ContactRelation?) {
    loadedContacts()?.let { if (it.setRelation(id, relation)) saveContacts(it) }
}

/** Allow deleted topics again (all at once). */
internal fun LearnRunner.allowDeletedTopics() {
    loadedPool()?.let { if (it.allowAgain()) save(it) }
}

/** Allow deleted contacts again (all at once). */
internal fun LearnRunner.allowDeletedContacts() {
    loadedContacts()?.let { if (it.allowAgain()) saveContacts(it) }
}

/**
 * [what] is [PURGE_SENSITIVE] or a package name: one delete pass over each of the two stores. Record into [purges] first, then act, and
 * clear only once the store that was edited has been saved: so anything unreadable at the time, or deleted without being saved before the
 * process died, is redone on the next load ([loadedPool], [loadedContacts]). Once saved it is no longer recorded, so it will not be redone
 * later and delete what has been added since.
 */
internal fun LearnRunner.purge(what: String) {
    if (what != PURGE_SENSITIVE) {
        // Cancel this App's batch in flight (topic digest or segmented digest) and drop its unsent entries from the buffer: otherwise a reply
        // coming back would add back the topics and contacts just cleared, and the remaining notification text would still be sent to the model
        if (notesApp == what || buffer.sending().firstOrNull()?.packageName == what) stopRequest()
        buffer.purge { it != what }
        // "Clear this App's data" also clears its entries in the raw notification journal; if the journal cannot be read right now, delete it
        // outright (raw text is not kept waiting for a later redo). While the switches are off the journal is already gone
        if (on() && digest()) {
            if (loadedJournal() == null) {
                // Delete it all; if that fails, dropJournal records it and it is redone once readable
                dropJournal()
            } else {
                journalRemove(setOf(what))
            }
        }
        // Sentences and digests at each level: they are kept while the switches are off, so they are cleared as well. When unreadable right
        // now (keystore temporarily unavailable), do not delete everything — it holds monthly digests kept for two years; record it and redo it
        // once readable (see loadedDigest)
        digestRemove(setOf(what), stopRequests = true)
    }
    purges.addToTopicsAndContacts(what)
    // Ones loaded only now already applied the recorded entries (including this one) at the moment they were loaded
    val hadTopics = pool != null
    val p = loadedPool()
    if (p != null && hadTopics) purgeTopics(p, setOf(what))
    val hadContacts = contactPool != null
    val c = loadedContacts()
    if (c != null && hadContacts) purgeContacts(c, setOf(what))
    dropBroken()
}

internal fun LearnRunner.purgeTopics(p: TopicPool, what: Set<String>) {
    if (what.isEmpty()) return
    val gone = HashSet<String>()
    // Follow the recorded order (see [LearnPurges]): when a delete came after a merge, it deletes the merged result, and the names merged into
    // it must enter "no longer recorded" as well; when a name was deleted first and another topic with the same name was later merged into
    // something else, that name counts as merged away. Re-applying ones already saved changes nothing: the merged-away or deleted id is gone,
    // and an id is never assigned to another topic
    for (w in what) when {
        w == MARK_ALL -> { gone += p.all().map { it.id }; p.clear() }
        w == PURGE_SENSITIVE -> gone += p.deleteSensitive()
        w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> p.merge(from, into) }
        w.startsWith(MARK_TOPIC) -> w.removePrefix(MARK_TOPIC).let { if (p.delete(it)) gone += it }
        // Other markers are not the topic pool's business (and must not be treated as package names to clear)
        w.startsWith(":") -> {}
        else -> gone += p.clearApp(w)
    }
    topicPurgesApplied = topicPurgesApplied + what
    topicsGone(gone)
    save(p)
}

internal fun LearnRunner.purgeContacts(c: ContactPool, what: Set<String>) {
    if (what.isEmpty()) return
    // Follow the recorded order. Recorded merges: links to the merged-away topic are repointed to the one merged into
    for (w in what) when {
        w == MARK_ALL -> c.clear()
        w == PURGE_SENSITIVE -> c.deleteSensitive()
        w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> c.remapTopic(from, into) }
        // Even if it is gone it must be recorded into "no longer recorded" (see [ContactPool.forget])
        w.startsWith(MARK_CONTACT) -> c.forget(w.removePrefix(MARK_CONTACT))
        w.startsWith(":") -> {}
        else -> c.clearApp(w)
    }
    contactPurgesApplied = contactPurgesApplied + what
    saveContacts(c)
}

/**
 * Run one delete pass over the digest store following [marks] (package name: remove this App's; markers: the single sentence, day, week or
 * month to delete). Returns the entries that actually deleted something.
 */
internal fun LearnRunner.applyDigestPurges(d: DigestPool, marks: Set<String>): Set<String> = marks.filterTo(HashSet()) { w ->
    when {
        w == MARK_ALL -> d.clear()
        w.startsWith(MARK_NOTE) -> d.delete(w.removePrefix(MARK_NOTE))
        w.startsWith(MARK_DAY) -> w.removePrefix(MARK_DAY).toLongOrNull()?.let(d::deleteDay) ?: false
        w.startsWith(MARK_WEEK) -> w.removePrefix(MARK_WEEK).toLongOrNull()?.let { d.deleteSpan(SpanKind.Week, it) } ?: false
        w.startsWith(MARK_MONTH) -> w.removePrefix(MARK_MONTH).toLongOrNull()?.let { d.deleteSpan(SpanKind.Month, it) } ?: false
        w.startsWith(":") -> false
        else -> d.removePackages { it != w }
    }
}

/**
 * Delete things from the digest store ([marks] format in [applyDigestPurges]). Whatever is deleted is recorded into [purges] before saving,
 * and cleared only once saved (see [saveDigest]): anything not saved before the process died is redone on the next load ([loadedDigest]).
 * When unreadable at the moment, everything is recorded and redone once readable. When nothing was deleted but some of it was left unsaved
 * last time: save once more. [stopRequests]: when something was deleted, cancel the daily (weekly, monthly) digest in progress.
 */
internal fun LearnRunner.digestRemove(marks: Set<String>, stopRequests: Boolean = false) {
    if (marks.isEmpty()) return
    val pending = purges.digestPurges
    val d = loadedDigest()
    if (d == null) {
        if (!pending.containsAll(marks)) purges.digestPurges = pending + marks
        return
    }
    val done = applyDigestPurges(d, marks)
    if (done.isNotEmpty()) {
        if (stopRequests && (dayInFlight != null || spanInFlight != null)) stopRequest()
        if (!purges.digestPurges.containsAll(done)) purges.digestPurges = purges.digestPurges + done
        digestPurgesApplied = digestPurgesApplied + done
        saveDigest(d)
    } else if (marks.any { it in purges.digestPurges }) {
        saveDigest(d)
    }
}

/** The user deleted something: delete the old files saved aside by both stores (the unreadable copies may still hold what was just deleted). A failed delete is only logged. */
internal fun LearnRunner.dropBroken() {
    wipe("topics copies") { store.dropBroken() }
    wipe("contacts copies") { contactStore.dropBroken() }
}

/** Returns true when deleted; when it cannot be deleted, it only logs and returns false. */
internal inline fun LearnRunner.wipe(what: String, block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn could not delete $what: ${e.javaClass.simpleName}")
        false
    }
