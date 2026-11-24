package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun el(
    ref: String, name: String, bounds: List<Int>, res: String? = null, clickable: Boolean = true,
    editable: Boolean = false, focused: Boolean = false, scrollable: Boolean = false, hint: Boolean = false,
) = UiElement(ref, if (editable) "edit" else "button", name, 0, 0, bounds, res, clickable, editable, scrollable, hint = hint, focused = focused)

private fun snap(vararg e: UiElement, id: String = "s1") = ScreenSnapshot(id, "com.chat", e.toList(), 1080, 2400)
private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class IrreversibleDetectorTest {
    private val d = IrreversibleDetector

    @Test fun chineseSendButton() {
        val s = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), el("@e2", "发送(1)", listOf(0, 0, 100, 100)), el("@e3", "确认支付", listOf(0, 200, 100, 300)))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        assertNull(d.matchName("发送给朋友的那张照片在哪里"))
        for ((w, a) in listOf("删除" to IrrevAction.delete, "退出群聊" to IrrevAction.delete, "提交订单" to IrrevAction.order,
            "立即购买" to IrrevAction.order, "转账" to IrrevAction.pay, "确认捐赠" to IrrevAction.pay, "立即捐款" to IrrevAction.pay,
            "发 布" to IrrevAction.send, "报名" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
    }

    @Test fun englishAndCoordinateTaps() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "Place order", listOf(100, 800, 900, 900)),
            el("@e3", "Buy Now", listOf(100, 600, 900, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":500,"y":850}"""), s)?.action)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":[400,600],"y":650}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":300}"""), s))
        for ((w, a) in listOf("Send" to IrrevAction.send, "post" to IrrevAction.send, "Pay now" to IrrevAction.pay,
            "Donate now" to IrrevAction.pay, "Delete" to IrrevAction.delete, "Clear all" to IrrevAction.delete, "Checkout" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
        val r = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.chat:id/btnSend"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
    }

    @Test fun iconOnlyButtonFallsBackToTargetLabel() {
        val icon = snap(el("@e1", "", listOf(800, 900, 990, 990), res = "com.chat:id/iv_icon"))
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), icon))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"x":900,"y":950,"target":"发送"}"""), icon))
        val box = snap(el("@e1", "", listOf(800, 900, 990, 990)), el("@e2", "删除", listOf(820, 920, 980, 980), clickable = false))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), box)?.action)
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":1,"y":1,"target":"删除"}"""), null)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e9","snapshotId":"old","target":"Send"}"""), icon)?.action)
        assertTrue(d.unresolved("phone_tap", args("""{"ref":"@e1","snapshotId":"old"}"""), icon))
        assertTrue(d.unresolved("phone_tap", args("""{"x":1,"y":1}"""), null))
        assertFalse(d.unresolved("phone_tap", args("""{"x":900,"y":950}"""), icon))
    }

    // M6: when the tapped spot has no named element (custom-drawn UI, unnamed icons), judge by the text on the same row
    @Test fun unnamedSpotJudgedByRowText() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "删除", listOf(800, 400, 990, 460), clickable = false),
            el("@e3", "", listOf(50, 400, 150, 460)),
            el("@e4", "张三", listOf(0, 600, 999, 660), clickable = false),
            el("@e5", "删除记录说明", listOf(0, 0, 999, 300), clickable = false),
        )
        assertEquals(Irreversible(IrrevAction.delete, "删除"), d.classify("phone_tap", args("""{"x":100,"y":430}"""), s))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":400,"y":430}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":630}"""), s))
        // An element that is too tall (a container, a large block of text) does not count as "the same row"
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":200}"""), s))
        // When a named element is hit, only that element is judged
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":640}"""), s))
    }

    // Buttons that contact someone or submit an application on the user's behalf count as submit; existing conversations, history, support pages, and places that only open a message composer do not
    @Test fun contactAndApplyButtonsAreSubmissions() {
        for (w in listOf("立即沟通", "沟通", "打招呼", "立即打招呼", "一键投递", "投递简历", "投简历", "立即申请", "申请职位", "应聘",
            "联系TA", "发起聊天", "立即沟通 >", "Say hi", "say  hello!", "Easy Apply", "Apply now", "Contact recruiter")) {
            assertEquals(w, IrrevAction.submit, d.matchName(w))
        }
        for (w in listOf("继续沟通", "已沟通", "沟通记录", "已投递", "投递记录", "联系客服", "联系我们", "消息", "私信", "申请", "申请权限",
            "Apply", "Contact", "Contact us", "Message", "Messages", "沟通技巧有哪些")) {
            assertNull(w, d.matchName(w))
        }
        // The button is in the job details (right of the list): tapping it by ref requires confirmation
        val s = snap(el("@e1", "AI Agent 工程师", listOf(300, 100, 999, 160), clickable = false), el("@e2", "立即沟通", listOf(800, 180, 990, 230)),
            el("@e3", "感兴趣", listOf(600, 180, 780, 230)))
        assertEquals(Irreversible(IrrevAction.submit, "立即沟通"), d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s))
    }

    @Test fun submitOnlyExactMatch() {
        assertEquals(IrrevAction.submit, d.matchName("提交"))
        assertEquals(IrrevAction.submit, d.matchName("确认提交"))
        assertEquals(IrrevAction.submit, d.matchName(" Submit "))
        assertNull(d.matchName("提交搜索"))
        assertNull(d.matchName("Submitted"))
        val s = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.x:id/search_submit"))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
    }

    @Test fun searchEnterIsNotSend() {
        val s = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), res = "com.shop:id/search_input", editable = true))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true}"""), s))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), s))
        val focusedSearch = snap(el("@e1", "Search", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true), el("@e2", "备注", listOf(0, 200, 999, 300), editable = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), focusedSearch))
    }

    @Test fun chatEnterIsSend() {
        val s = snap(el("@e1", "说点什么...", listOf(0, 900, 800, 999), editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"你好","pressEnter":true}"""), s)?.action)
        assertNull(d.classify("phone_type_text", args("""{"text":"你好"}"""), s))
        val r = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.chat:id/chat_input", editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), r)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"hi","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_DEL"}"""), r))
    }

    @Test fun readOnlyToolsNeverMatch() {
        val s = snap(el("@e1", "发送", listOf(0, 0, 999, 999)))
        for (t in listOf("phone_observe", "phone_list_elements", "phone_screenshot", "phone_list_apps", "phone_diagnose", "wait", "knowledge_search", "knowledge_read", "skill_search", "skill_read",
            "phone_back", "phone_clear_text", "phone_launch_app", "ask_user", "request_confirmation", "finish", "end_not_done")) {
            assertNull(t, d.classify(t, args("""{"ref":"@e1","snapshotId":"s1","x":500,"y":500,"target":"发送"}"""), s))
        }
    }

    // C1: a coordinate tap is judged against a fresh read of the screen, not against what the model last saw
    @Test fun coordinateTapUsesFreshRead() {
        val old = snap(el("@e1", "+", listOf(800, 900, 990, 990)))
        val now = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), id = "fresh")
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), old))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"x":900,"y":950}"""), old, now)?.action)
        // ref: the original snapshot or the element at the same position now; a hit on either counts
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), old, now)?.action)
    }

    // C2: judged by key code (numeric form, space); an input field defaults to send and only search fields are exempt; a focused button is judged by the word list
    @Test fun activatingKeysAndFocus() {
        val plain = snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true, focused = true))
        for (k in listOf("066", "KEYCODE_NUMPAD_ENTER", "23")) assertEquals(k, IrrevAction.send, d.classify("phone_press_key", args("""{"key":"$k"}"""), plain)?.action)
        assertNull("空格在输入框里只是打字", d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), plain))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), plain)?.action)
        val button = snap(el("@e1", "发送", listOf(0, 0, 999, 100), focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), button)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_DPAD_CENTER"}"""), button)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "返回", listOf(0, 0, 999, 100), focused = true))))
        // Focus cannot be seen, or there is no input field on screen: count as a hit
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "标题", listOf(0, 0, 999, 100), clickable = false))))
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), null))
        assertNotNull(d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true))))
    }

    // I2/M1: strong words, generic confirmations, English phrases, exclusion words, counted entries
    @Test fun wordMatchingExamples() {
        val hits = mapOf(
            "删除聊天记录" to IrrevAction.delete, "撤回" to IrrevAction.delete, "转发给朋友" to IrrevAction.send,
            "发红包" to IrrevAction.pay, "申请退款" to IrrevAction.order, "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order,
            "去结算(3)" to IrrevAction.order, "使用支付宝支付" to IrrevAction.pay, "Send message" to IrrevAction.send,
            "Post comment" to IrrevAction.send, "Delete conversation" to IrrevAction.delete, "DELETE" to IrrevAction.delete,
            "评论" to IrrevAction.send, "回复:" to IrrevAction.send, "立即预约" to IrrevAction.order, "确认报名" to IrrevAction.order,
            "张三 删除" to IrrevAction.delete,
        )
        for ((w, a) in hits) assertEquals(w, a, d.matchName(w))
        for (w in listOf("评论(23)", "回复 3", "支付宝", "支付方式", "支付设置", "预约挂号指南", "Postpone", "Sender", "Payment history",
            "提交搜索", "确定", "OK", "这是一段很长的聊天消息里面提到了删除两个字")) {
            assertNull(w, d.matchName(w))
        }
        // Generic confirmation: only counts when the same screen has a strong word or an amount
        val dialog = snap(el("@e1", "确定删除这条消息?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确定", listOf(500, 500, 999, 600)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog)?.action)
        val sheet = snap(el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确认", listOf(500, 500, 999, 600)))
        // Amount only, no payment word: treat as order (on approval the amount must match the "amount paid" row)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plain = snap(el("@e1", "已保存", listOf(0, 300, 999, 400), clickable = false), el("@e2", "完成", listOf(500, 500, 999, 600)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plain))
        // "post" inside a resource ID does not count
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), snap(el("@e1", "", listOf(0, 0, 99, 99), res = "com.x:id/post_list"))))
    }

    // Traditional Chinese UI and $ amounts: judged by the same word list
    @Test fun traditionalChineseAndDollarAmounts() {
        for ((w, a) in listOf("刪除" to IrrevAction.delete, "確認付款" to IrrevAction.pay, "結帳" to IrrevAction.order, "轉帳" to IrrevAction.pay,
            "立即購買" to IrrevAction.order, "傳送" to IrrevAction.send, "送出" to IrrevAction.submit, "儲值" to IrrevAction.pay)) {
            assertEquals(w, a, d.matchName(w))
        }
        for ((w, s) in listOf("註銷帳户" to "注销账户", "回覆" to "回复", "發佈" to "发布", "聯繫對方" to "联系对方")) {
            assertNotNull(s, d.matchName(s))
            assertEquals(w, d.matchName(s), d.matchName(w))
        }
        assertNull(d.matchName("已付款"))
        assertNull(d.matchName("購買記錄"))
        for (amount in listOf("合計 NT$ 390", "總計 $12.99", "US$12.99")) {
            val sheet = snap(el("@e1", amount, listOf(0, 300, 999, 400), clickable = false), el("@e2", "確定", listOf(500, 500, 999, 600)))
            assertEquals(amount, IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        }
        val pay = snap(el("@e1", "付款方式", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "NT$390", listOf(0, 400, 999, 460), clickable = false), el("@e3", "確認", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), pay)?.action)
        assertNotNull(d.paymentPage(pay))
    }

    // I3: a long enough horizontal swipe starting on a list row = swipe to delete
    @Test fun longHorizontalSwipeOnRowIsDelete() {
        val s = snap(el("@e1", "张三 昨天", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true))
        assertEquals(Irreversible(IrrevAction.delete, "swipe on 张三 昨天"),
            d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
        assertNull("太短", d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":600,"endY":350}"""), null, s))
        assertNull("竖滑", d.classify("phone_swipe", args("""{"startX":500,"startY":800,"endX":500,"endY":200}"""), null, s))
        assertNull("不在行上", d.classify("phone_swipe", args("""{"startX":900,"startY":700,"endX":300,"endY":700}"""), null, s))
        assertEquals(IrrevAction.delete, d.classify("phone_scroll", args("""{"direction":"left","amount":5,"x":500,"y":350}"""), null, s)?.action)
        assertNull("默认幅度不够长", d.classify("phone_scroll", args("""{"direction":"left","x":500,"y":350}"""), null, s))
        assertNull(d.classify("phone_scroll", args("""{"direction":"down","amount":10}"""), null, s))
    }

    // Round-2 case 3: an amount dialog stacked over a product page counts as pay; the presence of "order" wording must not let it through
    @Test fun amountOverOrderPageIsOrderPayWordIsPay() {
        // Choosing a spec on a product page: Buy Now + price + confirm, no payment word → order (approvable, and bound to the amount), not pay
        val s = snap(
            el("@e1", "立即购买", listOf(0, 900, 999, 999)),
            el("@e2", "¥25.00", listOf(0, 500, 999, 560), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        // Payment sheet: ¥25.00 + confirm + pay → pay
        val sheet = snap(
            el("@e1", "支付", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥25.00", listOf(0, 400, 999, 460), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet)?.action)
        // A payment word on the same screen: pay takes precedence
        val both = snap(el("@e1", "删除", listOf(0, 0, 99, 99)), el("@e2", "付款", listOf(0, 200, 99, 299)), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), both)?.action)
        // A price inside a delete confirmation is still delete
        val del = snap(el("@e1", "确定删除这件商品?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "¥25.00", listOf(0, 0, 99, 99), clickable = false), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), del)?.action)
    }

    // Round-2 case 4: labels such as order statuses and records are not actions
    @Test fun statusLabelsAreNotActions() {
        for (w in listOf("待付款", "待支付", "付款成功", "已支付", "退款/售后", "购买记录", "微信红包", "支付明细", "删除记录列表")) {
            assertNull(w, d.matchName(w))
        }
        for ((w, a) in listOf("发红包" to IrrevAction.pay, "塞钱进红包" to IrrevAction.pay, "红包" to IrrevAction.pay,
            "申请退款" to IrrevAction.order, "确认付款" to IrrevAction.pay)) {
            assertEquals(w, a, d.matchName(w))
        }
        // Status labels do not provide context for a generic confirm either
        val tabs = snap(el("@e1", "待付款", listOf(0, 0, 99, 99)), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), tabs))
    }

    // Round-2 case 5: Enter in a search field is not send
    @Test fun searchFieldsRecognized() {
        // A search field showing only suggestions is recognized by its resource ID
        val hinted = snap(UiElement("@e1", "edit", "iPhone 16 Pro", 0, 0, listOf(0, 50, 800, 120), "search_edit_frame", true, true, false, hint = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
        // The results page still holds the previous query, with a Search button on the same row
        val results = snap(el("@e1", "耳机", listOf(0, 50, 800, 120), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertNull(d.classify("phone_type_text", args("""{"text":"蓝牙耳机","pressEnter":true}"""), results))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), results))
        // The Search button is on a different row: still send
        val chat = snap(el("@e1", "耳机", listOf(0, 900, 800, 999), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"66"}"""), chat)?.action)
    }

    // Optional: a start point inside a short scrollable area (a carousel) is not swipe to delete
    @Test fun swipeInsideCarouselIsNotDelete() {
        val s = snap(el("@e1", "推荐 新品", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 250, 999, 450), clickable = false, scrollable = true))
        assertNull(d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
    }

    // 0b-1: text typed into a field is not a hint, so "search" inside it does not make it a search field
    @Test fun searchWordsInTypedTextAreNotSearch() {
        val chat = snap(el("@e1", "帮我搜索一下", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), chat)?.action)
        val en = snap(el("@e1", "research this", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":" please","pressEnter":true}"""), en)?.action)
        // "search" in the hint still counts
        val hinted = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
    }

    // 0b-3: prices inside a scrollable area are not context; a confirm with only an amount and no payment word counts as order
    @Test fun amountsInsideScrollablesAreNotContext() {
        // Filter panel: a scrollable price list plus confirm at the bottom
        val filter = snap(
            el("@e1", "", listOf(0, 200, 999, 800), clickable = false, scrollable = true),
            el("@e2", "¥0-50", listOf(0, 250, 999, 300)),
            el("@e3", "¥50-100", listOf(0, 320, 999, 370)),
            el("@e4", "确定", listOf(500, 850, 999, 950)),
        )
        assertNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), filter))
        // The prices in the list are inside the scrollable area; only amounts outside it count
        val sizes = snap(
            el("@e1", "", listOf(0, 0, 999, 700), clickable = false, scrollable = true),
            el("@e2", "¥99.00", listOf(0, 100, 999, 160), clickable = false),
            el("@e3", "立即购买", listOf(0, 900, 999, 999)),
            el("@e4", "确定", listOf(500, 750, 999, 850)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action)
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action != IrrevAction.pay)
    }

    // Fix 1, C1: the whole payment sheet is inside a scrollable area and the confirm being tapped is inside it too; the amount still counts as context; total-like labels always count
    @Test fun sheetInsideScrollableStillHasContext() {
        val sheet = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plainPrice = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plainPrice))
        // The total is inside the scrollable area and the button outside it: the total still counts
        val total = snap(
            el("@e0", "", listOf(0, 0, 999, 800), clickable = false, scrollable = true),
            el("@e1", "合计 ¥88", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), total)?.action)
    }

    // Fix 1, I2: with an amount present, payment-method wording, English Pay, and pay words inside segments of any length all count as pay; new strong pay words
    @Test fun paySheetWordingWithAmountIsPay() {
        // Bottom payment sheet: the amount, the payment method and the confirm button are all in the lower half of the screen
        fun sheet(word: String) = snap(
            el("@e1", "¥25.00", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", word, listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确认", listOf(500, 850, 999, 950)),
        )
        for (w in listOf("支付方式", "付款方式", "支付宝", "零钱", "账户余额", "招商银行卡(1234)", "收银台", "免密支付已开启", "使用指纹", "请输入支付密码",
            "Pay with card", "Payment method", "Checkout total", "本次将从你的账户中支付这笔款项给商家完成订单")) {
            assertEquals(w, IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet(w))?.action)
        }
        for (w in listOf("收银台", "买单", "充值", "提现", "转出", "打赏", "缴费")) assertEquals(w, IrrevAction.pay, d.matchName(w))
        for (w in listOf("待付款", "充值记录", "提现明细")) assertNull(w, d.matchName(w))
        // Payment-method wording does not count when there is no amount
        val noAmount = snap(el("@e1", "零钱", listOf(0, 400, 999, 460), clickable = false), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), noAmount))
    }

    // Fix 1, M9: "search" in a resource ID is matched on word boundaries
    @Test fun searchResourceIdOnWordBoundary() {
        val research = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.x:id/research_input", editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), research)?.action)
        val camel = snap(el("@e1", "", listOf(0, 0, 800, 99), res = "com.x:id/etSearch", editable = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), camel))
    }

    // Fix 2-1: an amount split across segments (amount paid | ¥ | 39.90) still counts as context; total-like labels count even without a number
    @Test fun splitAmountRowIsContext() {
        fun row(scrolled: Boolean) = snap(
            *listOfNotNull(
                if (scrolled) el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true) else null,
                el("@e1", "实付", listOf(0, 700, 200, 760), clickable = false),
                el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
                el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
                el("@e4", "确认", listOf(500, 850, 999, 950)),
            ).toTypedArray(),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(false)))
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(true)))
        val noLabel = snap(
            el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
            el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
            el("@e4", "确认", listOf(500, 850, 999, 950)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), noLabel))
    }

    // Fix 2-2: wording that merely describes a payment method is not a payment sheet; the new pay words only count on the button itself
    @Test fun paymentOptionAdsAreNotPaySheets() {
        val picker = snap(
            el("@e1", "支持花呗付款", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", "¥99.00", listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), picker)?.action)
        val cod = snap(
            el("@e1", "货到付款", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), cod)?.action != IrrevAction.pay)
        // "Balance" in the upper half of the page is not a payment sheet
        val page = snap(
            el("@e1", "账户余额", listOf(0, 100, 999, 160), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), page)?.action != IrrevAction.pay)
        for (w in listOf("充值中心", "话费充值", "生活缴费", "优惠买单", "收款码", "提现入口")) assertNull(w, d.matchName(w))
        for (w in listOf("立即充值", "确认缴费", "去买单", "充值")) assertEquals(w, IrrevAction.pay, d.matchName(w))
    }

    // Editing text in an input field is not irreversible: clearing a search field, tapping into a field that has text, and the clear-text tool are never blocked; a later submit is still judged normally
    @Test fun editingTextInAFieldIsNeverIrreversible() {
        val field = el("@e1", "删除聊天记录怎么恢复", listOf(100, 100, 800, 160), editable = true)
        val s = snap(
            field,
            el("@e2", "清空", listOf(810, 100, 880, 160)),
            el("@e3", "", listOf(740, 110, 790, 150), res = "com.app:id/iv_clear"),
            el("@e4", "搜索", listOf(890, 100, 990, 160)),
            el("@e5", "清空", listOf(100, 900, 300, 960)),
            el("@e6", "", listOf(700, 110, 735, 150)),
        )
        for (ref in listOf("@e1", "@e2", "@e3", "@e6")) {
            assertNull(ref, d.classify("phone_tap", args("""{"ref":"$ref","snapshotId":"s1","target":"清空搜索框"}"""), s))
        }
        // The coordinate tap lands on the clear key
        assertNull(d.classify("phone_tap", args("""{"x":845,"y":130}"""), s))
        // Clearing text, typing without Enter: not judged
        assertFalse(d.needsCheck("phone_clear_text", args("""{"ref":"@e1","snapshotId":"s1"}""")))
        assertNull(d.classify("phone_type_text", args("""{"ref":"@e1","snapshotId":"s1","text":"删除"}"""), s))
        // "Clear" far from the input field (clear cart, clear history) is still delete
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e5","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun namedSendInsideAnEditorStillCounts() {
        // The web editor area is editable as a whole and the send key is drawn inside it: named elements are still judged
        val s = snap(el("@e1", "", listOf(0, 800, 999, 990), editable = true), el("@e2", "发送", listOf(850, 900, 990, 980)),
            el("@e3", "", listOf(700, 900, 840, 980), res = "com.app:id/btn_send"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun browserInteractKeysAndDrags() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        for (k in listOf("Enter", "Control+Enter", "Shift+Return")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.send, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)?.action)
        }
        // Space on a button acts like a click; inside an input field it is just typing
        assertTrue(d.needsCheck(tool, args("""{"action":"key","key":"Space"}""")))
        assertNull(d.classify(tool, args("""{"action":"key","key":"Space"}"""), s))
        val onButton = snap(el("@e2", "删除", listOf(800, 900, 990, 990), focused = true))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"key","key":"Space"}"""), onButton)?.action)
        // Activation keys (Enter, Space and their modified forms) are judged as clicking the focused element: blocked when focus is on delete, while Space in an input field is typing
        for (k in listOf("Shift+Space", "Control+Space", "Control+Enter")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertTrue(k, d.needsCheck(tool, a))
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, onButton)?.action)
        }
        assertNull(d.classify(tool, args("""{"action":"key","key":"Shift+Space"}"""), s))
        // Moving focus and closing are not judged, even with focus on delete; arrow keys only matter on selection controls
        for (k in listOf("Tab", "Shift+Tab", "Escape", "ArrowDown", "End")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), onButton))
        }
        // Every key inside an input field is editing
        for (k in listOf("Tab", "Escape", "Control+A", "ArrowDown", "d", "Backspace", "Delete", "Control+D")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s))
        }
        assertFalse(d.needsCheck(tool, args("""{"action":"key","key":"bogus"}""")))
    }

    @Test fun browserInteractSelectionKeysJudgedByTheControl() {
        val tool = "web_interact"
        fun ui(ref: String, role: String, name: String, b: List<Int>, focused: Boolean = false) =
            UiElement(ref, role, name, 0, 0, b, null, true, false, false, focused = focused)
        val list = snap(
            ui("@e1", "listbox", "操作", listOf(0, 100, 999, 400), focused = true),
            ui("@e2", "option", "归档", listOf(0, 100, 999, 200)),
            ui("@e3", "option", "删除", listOf(0, 200, 999, 300)),
        )
        val radios = snap(
            ui("@e1", "radio", "普通配送", listOf(0, 100, 500, 200), focused = true),
            ui("@e2", "radio", "立即支付", listOf(500, 100, 999, 200)),
        )
        val city = snap(ui("@e1", "combobox", "城市", listOf(0, 100, 999, 200), focused = true), ui("@e2", "button", "删除", listOf(0, 500, 999, 600)))
        val button = snap(ui("@e1", "button", "删除", listOf(0, 100, 999, 200), focused = true))
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown", "d", "Shift+D")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, list)?.action)
            assertEquals(k, IrrevAction.pay, d.classify(tool, a, radios)?.action)
            // Neither the control nor any option it can select is harmful
            assertNull(k, d.classify(tool, a, city))
        }
        // A button that is not a selection control is not activated by arrow keys (letters on a button are command keys, see browserInteractCommandKeys)
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), button))
        // On a selection control: moving focus and closing are not judged, and a letter with Ctrl is not type-ahead selection
        for (k in listOf("Tab", "Escape", "Control+D")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), list))
    }

    @Test fun browserInteractCommandKeys() {
        val tool = "web_interact"
        fun ui(ref: String, name: String, b: List<Int>, focused: Boolean = false, keys: List<String> = emptyList()) =
            UiElement(ref, "button", name, 0, 0, b, null, true, false, false, focused = focused, keyShortcuts = keys)
        fun hit(k: String, s: ScreenSnapshot) = d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)
        // Focus on an element: Delete, Backspace, a single letter or digit, and Ctrl/Alt/Meta+letter are all judged as clicking it
        val onTrash = snap(ui("@e1", "删除", listOf(0, 0, 999, 100), focused = true))
        val onCard = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true))
        for (k in listOf("Delete", "Backspace", "d", "7", "Shift+D", "Control+D", "Alt+1", "Meta+Shift+K")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.delete, hit(k, onTrash)?.action)
            assertNull(k, hit(k, onCard))
        }
        // No focused element (the page itself): Delete and Backspace act on the page's selection and are always blocked
        val page = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100)))
        assertEquals(Irreversible(IrrevAction.delete, "Delete:焦点不明"), hit("Delete", page))
        assertEquals(IrrevAction.delete, hit("Backspace", page)?.action)
        // Other command keys: allowed unless the page declares them
        for (k in listOf("d", "Control+K", "Alt+s")) assertNull(k, hit(k, page))
        // The page declares shortcuts: judged by that control (aria-keyshortcuts, and accesskey converted to Alt+key)
        val declared = snap(
            ui("@e1", "删除邮件", listOf(0, 0, 999, 100), keys = listOf("Control+Shift+K", "#")),
            ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")),
            ui("@e3", "刷新", listOf(0, 400, 999, 500), keys = listOf("r")),
        )
        assertEquals(IrrevAction.delete, hit("Shift+Control+K", declared)?.action)
        assertNull(hit("Control+K", declared))
        assertEquals(IrrevAction.send, hit("Alt+S", declared)?.action)
        assertNull(hit("r", declared))
        // Focus is on a harmless element, but the key pressed is one the page declared for send
        val focusedElsewhere = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true), ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")))
        assertEquals(IrrevAction.send, hit("Alt+s", focusedElsewhere)?.action)
    }

    @Test fun browserInteractDragsAndSelects() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        // Dragged onto the delete area, dragging the "slide to pay" control; dragging a card onto empty space does not count
        assertEquals(Irreversible(IrrevAction.delete, "drag:删除"),
            d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toRef":"@e2"}"""), s))
        assertEquals(IrrevAction.pay, d.classify(tool, args("""{"action":"drag","ref":"@e3","snapshotId":"s1","toX":990,"toY":550}"""), s)?.action)
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"drag","x":500,"y":350,"toX":900,"toY":950}"""), s)?.action)
        assertNull(d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toX":500,"toY":700}"""), s))
        // Hover is not judged
        assertFalse(d.needsCheck(tool, args("""{"action":"hover","ref":"@e2","snapshotId":"s1"}""")))
        // A dropdown selection is judged by the dropdown itself and by the selected option
        val form = snap(el("@e1", "城市", listOf(0, 100, 999, 200)), el("@e2", "提交方式", listOf(0, 300, 999, 400)), el("@e3", "删除", listOf(0, 500, 999, 600)))
        assertTrue(d.needsCheck(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}""")))
        assertNull(d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}"""), form))
        assertEquals(Irreversible(IrrevAction.delete, "option:删除账户"),
            d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"删除账户"}"""), form))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"select","ref":"@e3","snapshotId":"s1","option":"x"}"""), form)?.action)
    }
}
