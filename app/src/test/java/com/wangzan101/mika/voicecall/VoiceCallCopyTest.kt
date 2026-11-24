package com.wangzan101.mika.voicecall

import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/** Copy mapping for errors/endings/status on the call screen: each of the 8+1 error kinds and each of the 5 end reasons gets its own line; the Ended(Background) produced when RESUMED times out also has separate wording. */
class VoiceCallCopyTest {

    @get:Rule val texts = ChineseTexts()

    @Test
    fun `每类错误各占一条文案`() {
        assertEquals("实时语音凭据没过，去配置里检查", voiceCallErrorText(VoiceCallError.Auth))
        assertEquals("连接超时，再试一次", voiceCallErrorText(VoiceCallError.ConnectTimeout))
        assertEquals("连接断开了", voiceCallErrorText(VoiceCallError.Disconnected))
        assertEquals("请求太密，稍后再试", voiceCallErrorText(VoiceCallError.RateLimited))
        assertEquals("实时语音服务出错了", voiceCallErrorText(VoiceCallError.Provider))
        assertEquals("麦克风收不到声音", voiceCallErrorText(VoiceCallError.MicSilent))
        assertEquals("声音通道正被别的应用占用", voiceCallErrorText(VoiceCallError.AudioFocus))
        assertEquals("播放出错了", voiceCallErrorText(VoiceCallError.Playback))
        assertEquals("正在打电话，结束后再试", voiceCallErrorText(VoiceCallError.Busy))
        assertEquals(VoiceCallError.entries.size, VoiceCallError.entries.map(::voiceCallErrorText).distinct().size)
    }

    @Test
    fun `每种结束原因各占一条文案`() {
        assertEquals("通话已结束", voiceCallEndText(EndReason.Hangup))
        assertEquals("已从通知挂断", voiceCallEndText(EndReason.Notification))
        assertEquals("有电话进来，通话结束", voiceCallEndText(EndReason.IncomingCall))
        assertEquals("聊满 10 分钟，通话结束", voiceCallEndText(EndReason.TimeLimit))
        assertEquals("请回到前台再继续", voiceCallEndText(EndReason.Background))
        assertEquals(EndReason.entries.size, EndReason.entries.map(::voiceCallEndText).distinct().size)
    }

    @Test
    fun `进行中的状态词`() {
        assertEquals("正在连接…", voiceCallStatusText(VoiceCallState.Connecting))
        assertEquals("正在检查麦克风…", voiceCallStatusText(VoiceCallState.MicCheck))
        assertEquals("已连接", voiceCallStatusText(VoiceCallState.Active(1_000L)))
        // The status wording for terminal states is the end/error copy
        assertEquals("通话已结束", voiceCallStatusText(VoiceCallState.Ended(EndReason.Hangup)))
        assertEquals("连接超时，再试一次", voiceCallStatusText(VoiceCallState.Failed(VoiceCallError.ConnectTimeout, 1)))
    }

    @Test
    fun `打字请求的结果回到本地——路由的模型指令文案换成本地说法`() {
        assertEquals("正在处理", typedResultText(VoiceCallRoute.RESULT_BUSY))
        assertEquals("没办成", typedResultText(VoiceCallRoute.RESULT_START_FAILURE))
        assertEquals("需要你在任务页处理，去任务页看看", typedResultText(VoiceCallRoute.RESULT_NEEDS_USER))
        assertEquals("已取消", typedResultText(VoiceCallRoute.RESULT_CANCELLED))
        assertEquals("已完成", typedResultText(VoiceCallRoute.RESULT_DONE_FALLBACK))
        assertEquals("没办成", typedResultText(VoiceCallRoute.RESULT_FAILED_FALLBACK))
        // The task's own answer/reason is passed through verbatim
        assertEquals("明天晴天，最高 25 度", typedResultText("明天晴天，最高 25 度"))
        assertNotEquals(VoiceCallRoute.RESULT_BUSY, typedResultText(VoiceCallRoute.RESULT_BUSY))
    }
}
