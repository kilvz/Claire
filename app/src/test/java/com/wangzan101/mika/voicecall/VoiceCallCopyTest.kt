package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Copy mapping for errors/endings/status on the call screen: each of the 8+1 error kinds and each of the 5 end reasons gets its own line; the Ended(Background) produced when RESUMED times out also has separate wording. */
class VoiceCallCopyTest {

    @get:Rule val texts = ChineseTexts()

    @Test
    fun `each error kind has its own message`() {
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
    fun `each end reason has its own message`() {
        assertEquals("通话已结束", voiceCallEndText(EndReason.Hangup))
        assertEquals("已从通知挂断", voiceCallEndText(EndReason.Notification))
        assertEquals("有电话进来，通话结束", voiceCallEndText(EndReason.IncomingCall))
        assertEquals("5 分钟没有动静，通话已结束", voiceCallEndText(EndReason.Idle, idleMinutes = 5))
        assertEquals("5 分钟没有动静，通话已结束", voiceCallStatusText(VoiceCallState.Ended(EndReason.Idle, idleMinutes = 5)))
        assertEquals("请回到 Mika 再继续", voiceCallEndText(EndReason.Background))
        assertEquals(EndReason.entries.size, EndReason.entries.map { voiceCallEndText(it, 5) }.distinct().size)
    }

    @Test
    fun `status words while in progress`() {
        assertEquals("正在连接…", voiceCallStatusText(VoiceCallState.Connecting))
        assertEquals("正在检查麦克风…", voiceCallStatusText(VoiceCallState.MicCheck))
        assertEquals("已连接", voiceCallStatusText(VoiceCallState.Active(1_000L)))
        // The status wording for terminal states is the end/error copy
        assertEquals("通话已结束", voiceCallStatusText(VoiceCallState.Ended(EndReason.Hangup)))
        assertEquals("连接超时，再试一次", voiceCallStatusText(VoiceCallState.Failed(VoiceCallError.ConnectTimeout, 1)))
    }

    @Test
    fun `a front-desk report is spoken as one sentence, with the body truncated to the limit`() {
        assertEquals("订日料那件，办好了：七点两位", deskReportSpeech(DeskReport(DeskEvent.Succeeded("t1", "七点两位"), "订日料")))
        assertEquals("订日料那件，在问你：几点？", deskReportSpeech(DeskReport(DeskEvent.NeedsAnswer("t1", "几点？", null), "订日料")))
        val long = deskReportSpeech(DeskReport(DeskEvent.Succeeded("t1", "字".repeat(DESK_RESULT_MAX_CHARS)), "订日料"))
        assertTrue(long.length <= "订日料那件，".length + DESK_RESULT_MAX_CHARS)
    }

    @Test
    fun `typed dispatch results come back locally`() {
        fun result(status: String) = buildJsonObject { put("status", status) }
        assertEquals("正在处理", typedDeskResult(result("started")))
        assertEquals("正在处理", typedDeskResult(result("queued")))
        assertEquals("正在处理", typedDeskResult(result("already_dispatched")))
        assertEquals("没办成", typedDeskResult(result("failed")))
        assertEquals("没办成", typedDeskResult(result("rejected")))
    }
}
