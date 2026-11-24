package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job

// Learning: read/write for the topic pool and the contact pool, budget and status publishing
/** Read once if the topic pool is not loaded yet; null when it cannot be read (keystore temporarily unavailable, IO), retried next time — never overwrite the file with an empty pool. */
internal fun LearnRunner.loadedPool(): TopicPool? {
    pool?.let { return it }
    return try {
        TopicPool(store.load()).also {
            pool = it
            savedTopics = it.all().mapTo(HashSet()) { t -> t.id }
            topicsFlow.value = it.all()
            topicsBlockedFlow.value = it.blockedCount
            purgeTopics(it, purges.topicPurges)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn pool unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** Read once if contacts are not loaded yet; null when they cannot be read, retried next time — never overwrite the file with an empty one. */
internal fun LearnRunner.loadedContacts(): ContactPool? {
    contactPool?.let { return it }
    return try {
        ContactPool(contactStore.load()).also {
            contactPool = it
            contactsFlow.value = ContactsView(it.all(), it.blockedCount)
            purgeContacts(it, purges.contactPurges)
            // Ones stored earlier by notification key: merge entries with the same name (must run after the recorded deletes are redone above, since those record the old ids)
            if (it.adoptIdentities()) saveContacts(it)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn contacts unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** Returns true when saved. */
internal fun LearnRunner.saveContacts(c: ContactPool): Boolean {
    contactsFlow.value = ContactsView(c.all(), c.blockedCount)
    return try {
        contactStore.save(c.data())
        // The pending deletes have now been persisted
        if (contactPurgesApplied.isNotEmpty()) {
            purges.contactPurges = purges.contactPurges - contactPurgesApplied
            contactPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn contacts save failed: ${e.javaClass.simpleName}")
        false
    }
}

/** Returns true when saved. */
internal fun LearnRunner.save(p: TopicPool): Boolean {
    topicsFlow.value = p.all()
    topicsBlockedFlow.value = p.blockedCount
    return try {
        store.save(p.data())
        savedTopics = p.all().mapTo(HashSet()) { it.id }
        // The pending deletes have now been persisted
        if (topicPurgesApplied.isNotEmpty()) {
            purges.topicPurges = purges.topicPurges - topicPurgesApplied
            topicPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // If it cannot be saved, the in-memory copy is kept anyway and saved on the next change
        log("learn pool save failed: ${e.javaClass.simpleName}")
        false
    }
}

internal fun LearnRunner.budgetLeft(now: Long): Boolean = budgetOutDay != dayOf(now) &&
    // Learning counts against the whole budget (the decision pass uses only part of it, see [ProactiveBudget.triageExhausted])
    ProactiveBudget.exhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit()) == null

internal fun LearnRunner.dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

internal fun LearnRunner.stopRequest() {
    val job = inFlight ?: return
    inFlight = null
    notesApp = null
    dayInFlight = null
    spanInFlight = null
    job.cancel()
    buffer.abort()
    log("learn request cancelled")
    slot.release(this)
    slot.wakeOthers(this)
}

internal fun LearnRunner.stopAndPublish(s: LearnStatus) {
    stopRequest()
    publish(s)
}

internal fun LearnRunner.bump(pkg: String, f: (LearnFeed) -> LearnFeed) {
    feed[pkg] = f(feed[pkg] ?: LearnFeed())
    publish(status)
}

internal fun LearnRunner.publish(s: LearnStatus) {
    status = s
    val summary = buffer.state.value
    mutable.value = LearnState(s, summary.total, summary.pending, lastDistilledAtMs, HashMap(feed), journal?.size ?: 0, journal?.packages().orEmpty())
}
