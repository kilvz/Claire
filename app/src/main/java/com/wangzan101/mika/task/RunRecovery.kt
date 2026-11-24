package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** The few things a scheduled trigger must be told during recovery; forwarded to [TriggerRuntime] in the product. */
interface TriggerOutcomes {
    fun interrupted(key: String, reason: String)
    fun delivered(key: String)
    fun actionFailed(key: String, reason: String)
}

/**
 * Cleanup for "interrupted runs": re-deliver a reply that already exists, don't resend when the state is unknown, and otherwise report
 * "run interrupted" and write it into the conversation. Two entry points: [recover] after a process restart (called on both App start and
 * task-service start, runs only once per process with later calls doing nothing; the App comes up before any service in the process,
 * so on the first call no run newly started by this process can be misjudged), and [settleInterrupted] when the task service is destroyed but the process is still alive. **Never resumes a run mid-way**: the screen state can't be restored.
 *
 * "Already told" needs no separate field: it is read from the delivery ledger. A run without a reply has a notice recorded under `interrupted:<run>`, and a run in an unknown state or with a failed delivery has the corresponding notice;
 * once a record exists it is skipped. A run whose reply was delivered moves straight to Ended. So both entry points and repeated calls only report once.
 */
class RunRecovery(
    private val store: RunStore,
    private val outbox: Outbox,
    private val channels: (String) -> Channel?,
    private val triggers: TriggerOutcomes,
    private val conversations: ConversationStore,
    private val scope: CoroutineScope,
    private val records: RunRecords? = null,
    private val label: (String) -> String = { it },
    private val appLabel: (String) -> String = { it },
    private val languageTag: () -> String? = { null },
    private val scheduledLocalChannel: Channel? = null,
) {
    private val done = AtomicBoolean(false)
    private val lock = Mutex()

    /** The marking is done synchronously here (runs started afterwards are not touched); the cleanup then runs slowly in [scope]. Returns the runs marked as interrupted this time, empty when it isn't the first call. */
    fun recover(live: Set<RunId> = emptySet()): List<StoredRun> {
        if (!done.compareAndSet(false, true)) return emptyList()
        val interrupted = store.interruptUnfinished(live)
        settleInterrupted()
        return interrupted
    }

    /**
     * Settle every interrupted run that hasn't been reported yet, once. Called on the spot when the task service is destroyed (the process is still alive, so the user need not wait until the next start to find out);
     * [recover] at startup sweeps as well, catching runs that were killed before they could finish.
     */
    fun settleInterrupted() {
        scope.launch {
            lock.withLock {
                store.all().filter { it.phase == RunPhase.Interrupted && !told(it) }
                    .forEach { run -> runCatching { settle(run) } }
            }
        }
    }

    private fun resultId(run: StoredRun) = run.deliveryId ?: "result:${run.id.value}"
        .takeIf { run.channelPeer != null && outbox.get(it)?.delivery is Delivery.Result }

    private fun noticeIds(run: StoredRun) =
        if (resultId(run) != null) listOf("uncertain:${resultId(run)}", "failed:${resultId(run)}")
        else listOf("interrupted:${run.id.value}")

    private fun told(run: StoredRun) = noticeIds(run).any { outbox.get(it) != null }

    private suspend fun settle(run: StoredRun) {
        run.triggerWindowKey?.let { triggers.interrupted(it, "run interrupted before the result was confirmed") }
        val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.threadId)
        val scheduledChannel = scheduledLocalChannel.takeIf {
            peer.channelId == LocalChannel.ID && run.triggerWindowKey != null
        }
        val channel = scheduledChannel ?: channels(peer.channelId) ?: return
        val deliveryId = resultId(run)
        val prior = deliveryId?.let(outbox::get)
        val reply = run.reply ?: (prior?.delivery as? Delivery.Result)?.text
        if (reply != null && deliveryId != null) {
            val result = prior?.delivery as? Delivery.Result
                ?: Delivery.Result(deliveryId, run.threadId, reply, restored = true, title = tr(R.string.recovered_result))
            val state = ResultDelivery.send(outbox, channel, peer, result)
            when (state) {
                DeliveryState.Delivered -> finish(run, reply)
                else -> Unit // ResultDelivery has recorded the failure/uncertainty notice for the entire batch.
            }
        } else {
            val said = run.userText ?: run.goal
            val turnId = "interrupted:${run.id.value}"
            if (conversations.turns(run.threadId).none { it.runId == turnId }) {
                val reason = tr(R.string.recover_interrupted_turn)
                val summary = interruptedSummary(run, said)
                conversations.append(run.threadId, Turn(said, listOfNotNull(reason, summary).joinToString("\n"), "failed", runId = turnId))
            }
            outbox.deliver(channel, peer, Delivery.Notice(turnId, run.threadId,
                tr(R.string.recover_interrupted, said.take(40))))
        }
    }

    /** Read only the events persisted last time to fill in the summary; if the record has been cleaned up or can't be read, the generic interruption reason is still kept. */
    private suspend fun interruptedSummary(run: StoredRun, said: String): String? {
        val id = run.recordId ?: return null
        val events = try {
            records?.events(id).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!StopSummary.worthSaying(events, null)) return null
        val text = StopSummary.plain(said, events, null, label, appLabel, language = languageTag())
        return text.take(RECOVERY_SUMMARY_CHARS) + if (text.length > RECOVERY_SUMMARY_CHARS) "…" else ""
    }

    private fun finish(run: StoredRun, reply: String) {
        store.save(run.copy(phase = RunPhase.Ended, roundInProgress = false))
        run.triggerWindowKey?.let { key ->
            if (run.runSucceeded == false) triggers.actionFailed(key, reply) else triggers.delivered(key)
        }
    }

    private companion object {
        const val RECOVERY_SUMMARY_CHARS = 2_400
    }
}
