package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickFallbackTest {
    private val w = 1080
    private val h = 2400

    @Test fun picksSmallestClickableTargetContainingPoint() {
        val card = ClickFallback.Candidate("card", 0, 200, 1080, 600, label = "Repository card")
        val star = ClickFallback.Candidate("star", 900, 240, 1040, 360, label = "Star")
        assertEquals("star", ClickFallback.pickTarget(960, 300, w, h, listOf(card, star)))
    }

    @Test fun rejectsLargeContainerScrollableAndWebView() {
        val fullScreen = ClickFallback.Candidate("root", 0, 0, 1080, 2200, label = "Container")
        val tallPane = ClickFallback.Candidate("tall", 0, 0, 500, 1300, label = "HalfHeight")
        val list = ClickFallback.Candidate("list", 0, 200, 1080, 500, scrollable = true)
        val web = ClickFallback.Candidate("web", 0, 200, 1080, 500, webView = true)
        assertNull(ClickFallback.pickTarget(200, 300, w, h, listOf(fullScreen, tallPane, list, web)))
    }

    @Test fun outwardButtonsAreNotRetriedOnSecondChannel() {
        assertTrue(ClickFallback.mayBeOutward("发送", null))
        assertTrue(ClickFallback.mayBeOutward("立即支付", null))
        assertTrue(ClickFallback.mayBeOutward("删除", null))
        assertTrue(ClickFallback.mayBeOutward("确认", null))
        assertTrue(ClickFallback.mayBeOutward("Submit", null))
        assertTrue(ClickFallback.mayBeOutward(null, "com.app:id/btn_send"))
        assertTrue(ClickFallback.mayBeOutward(null, "com.app:id/confirmButton"))

        assertFalse(ClickFallback.mayBeOutward("搜索", "com.github.android:id/search"))
        assertFalse(ClickFallback.mayBeOutward("Repositories", null))
        assertFalse(ClickFallback.mayBeOutward("设置", "com.app:id/settings_tab"))

        // A long name on a clickable control is still recognised: the irreversible pre-click check ignores total length, and a container without a name uses its children's text joined together
        assertTrue(ClickFallback.mayBeOutward("Send message to Alice Johnson", null))
        assertTrue(ClickFallback.mayBeOutward("立即支付 ¥1,299.00 (含运费¥12.00)", null))
        // Long body text that is not clickable is not blocked; toggle words are matched by containment, so long ones are not checked
        assertFalse(ClickFallback.mayBeOutward("Post-processing runs on every frame before it is shown", null, clickable = false))
        assertFalse(ClickFallback.mayBeOutward("关注我们的公众号,每周推送最新的开源项目和工具教程", null))

        val sendBtn = ClickFallback.Candidate("send", 800, 2100, 1040, 2220, label = "发送")
        assertNull(ClickFallback.pickTarget(900, 2160, w, h, listOf(sendBtn)))
    }

    /** Clicking a toggle-style button a second time turns the state that was just switched on back off (a custom-drawn icon shows no visible change in the frame either), so it can only be blocked by name. */
    @Test fun statefulTogglesAreNotRetriedOnSecondChannel() {
        assertTrue(ClickFallback.mayBeOutward("收藏", null))
        assertTrue(ClickFallback.mayBeOutward("已关注", null))
        assertTrue(ClickFallback.mayBeOutward("点赞(12)", null))
        assertTrue(ClickFallback.mayBeOutward("静音", null))
        assertTrue(ClickFallback.mayBeOutward("接听", null))
        assertTrue(ClickFallback.mayBeOutward("Follow", null))
        assertTrue(ClickFallback.mayBeOutward(null, "com.app:id/btn_follow"))
        assertTrue(ClickFallback.mayBeOutward(null, "com.app:id/likeButton"))

        assertFalse(ClickFallback.mayBeOutward("刷新", "com.app:id/refresh"))
        assertFalse(ClickFallback.mayBeOutward("Repositories", null))
    }
}
