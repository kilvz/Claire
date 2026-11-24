package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test

class SteerJoinTest {
    private val notes = listOf(
        SteerNote("m1", "改成查明天", SteerState.Received),
        SteerNote("m2", "顺便看看后天", SteerState.Received),
    )

    // A batch of two steers handed to the model together: the interrupted text is attached only to the first one, not to the second
    @Test fun interruptedReplyGoesToTheFirstSteerOfTheBatch() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf(SteerState.Joined, SteerState.Joined), after.map { it.state })
        assertEquals(listOf("今天晴", null), after.map { it.interrupted })
    }

    // When nothing was interrupted, only the destination is changed
    @Test fun steerWithoutInterruptionOnlyChangesState() {
        val after = SteerJoin().joined(notes, "m2")
        assertEquals(listOf(SteerState.Received, SteerState.Joined), after.map { it.state })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    // Cleared once used: the next batch of steers does not carry the previous text again
    @Test fun interruptedReplyIsUsedOnce() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val first = join.joined(notes, "m1")
        val second = join.joined(first + SteerNote("m3", "算了", SteerState.Received), "m3")
        assertEquals(listOf("今天晴", null, null), second.map { it.interrupted })
    }

    // A reply that finished before the steer is likewise attached to the first steer of this batch, recorded separately from the interrupted one
    @Test fun finishedReplyGoesToTheFirstSteerAndIsUsedOnce() {
        val join = SteerJoin()
        join.finished("光合作用是……的过程。")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf("光合作用是……的过程。", null), after.map { it.finished })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    @Test fun remoteStatusExcludesLocalMessagesAndDistinguishesReceiptConsumptionAndDiscard() {
        val local = SteerNote("local", "phone-only text", SteerState.Joined)
        val remote = SteerNote("remote", "only today", SteerState.Received, remote = true)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "received")), remoteSteers(listOf(local, remote)))
        val joined = SteerJoin().joined(listOf(local, remote), "remote")
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")), remoteSteers(joined))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(remote.copy(state = SteerState.Cancelled))))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "joined")),
            remoteSteers(joined, finished = true))
    }

    // Remote steers cannot actually be withdrawn; should one be in the withdrawn state, it is still only reported as not applied, with no extra outward-facing state; withdrawals made on the phone are not reported to the desktop
    @Test fun withdrawnMapsToNotAppliedAndLocalWithdrawalsStayHidden() {
        val remote = SteerNote("remote", "only today", SteerState.Withdrawn, remote = true)
        val local = SteerNote("local", "phone-only text", SteerState.Withdrawn)
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")), remoteSteers(listOf(local, remote)))
        assertEquals(listOf(com.wangzan101.mika.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
    }
}
