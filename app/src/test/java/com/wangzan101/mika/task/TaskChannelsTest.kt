package com.wangzan101.mika.task

import android.app.NotificationManager
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.telegram.TelegramChannel
import com.wangzan101.mika.wechat.WechatChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TaskChannelsTest {
    // Fix 1 M4: the "needs you" channel (shared by takeover, questions and confirmations) is created at high priority
    @Test fun attentionChannelCreatedHigh() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_ATTENTION])
    }

    // Results should pop up once, while progress stays quiet
    @Test fun resultHighProgressQuiet() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_RESULT])
        assertEquals(NotificationManager.IMPORTANCE_LOW, TaskService.CHANNEL_IMPORTANCE["task_progress"])
    }

    private class FakeChannel(override val id: String) : Channel {
        override val confirms = false
        override fun start(sink: ChannelInputSink): AutoCloseable = AutoCloseable {}
        override suspend fun send(peer: ChannelPeer, delivery: Delivery) = SendOutcome.Delivered
    }

    /** Both messaging channels must resolve: looking up only WeChat crashed every Telegram task on its first acknowledgement. */
    @Test fun messagingChannelIdsResolveFromBothRuntimes() {
        val wechat = FakeChannel(WechatChannel.ID)
        val telegram = FakeChannel(TelegramChannel.ID)
        assertSame(wechat, channelForId(WechatChannel.ID, { wechat }, { telegram }))
        assertSame(telegram, channelForId(TelegramChannel.ID, { wechat }, { telegram }))
        assertNull(channelForId("unknown", { wechat }, { telegram }))
    }

    // The old result channel was created at a lower importance and cannot be raised: switch to a new id, and the old id must be deleted
    @Test fun resultChannelUsesNewIdAndOldIsDeleted() {
        assertEquals("task_result_v2", TaskService.CHANNEL_RESULT)
        assertEquals("task_result", TaskService.OLD_CHANNEL_RESULT)
        assertNotEquals(TaskService.OLD_CHANNEL_RESULT, TaskService.CHANNEL_RESULT)
        assertFalse(TaskService.CHANNEL_IMPORTANCE.containsKey(TaskService.OLD_CHANNEL_RESULT))
    }
}
