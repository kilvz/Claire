package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Different models write answers and quote sources in different formats: both sides are normalized before comparing, so a correct answer is not judged "not found" because of format. */
class GroundingFormatsTest {
    private val screen = listOf("当前 App:com.example.notes\nJetpack Compose 入门\n阅读 5.3万\n价格 ¥128")

    @Test fun citationStylesAreNotPartOfTheAnswer() {
        val styles = listOf(
            "最新一篇是「Jetpack Compose 入门」（@e16）\n依据: 屏上标题「Jetpack Compose 入门」(@e16)",
            "最新一篇是「Jetpack Compose 入门」[@e16, @e17]\n依据: 「Jetpack Compose 入门」",
            "最新一篇是“Jetpack Compose 入门”[1]\n依据：“Jetpack Compose 入门”[^1]",
            "最新一篇是 Jetpack Compose 入门 @e16。\n依据: Jetpack Compose 入门【2】",
            // Full-width letters and digits, full-width colon
            "最新一篇是「Ｊｅｔｐａｃｋ Ｃｏｍｐｏｓｅ 入门」\n依据：「Ｊｅｔｐａｃｋ Ｃｏｍｐｏｓｅ 入门」",
        )
        for (a in styles) assertEquals(a, true, Grounding.found("最新一篇是什么", a, screen))
    }

    @Test fun numbersWithUnitsCompareByValue() {
        assertEquals(true, Grounding.found("阅读量多少", "阅读 53000\n依据: 阅读 5.3万", screen))
        assertEquals(true, Grounding.found("阅读量多少", "5.3万次阅读\n依据: 「阅读 5.3万」", screen))
        assertEquals(true, Grounding.found("多少钱", "价格 128 元\n依据：价格 ¥128", screen))
        // A wrong number still does not match
        assertEquals(false, Grounding.found("阅读量多少", "阅读 6.3万\n依据: 阅读 6.3万", screen))
        assertEquals(false, Grounding.found("多少钱", "128.5 元(@e3)\n依据: 价格 128.5", screen))
    }

    /** The answer contains empty quotes (JSON "" or an empty pair of Chinese quotation marks): this used to throw NoSuchElementException while extracting quotes, and the whole task ended as "interrupted by an exception". */
    @Test fun emptyQuotesAreSkipped() {
        // One extra empty field does not change the verdict (whether key names themselves count as quotes is another matter; here only the presence of empty quotes is compared)
        val withEmpty = "```json\n{\"title\": \"Jetpack Compose 入门\", \"recent\": \"\", \"price\": \"¥128\"}\n```"
        val without = "```json\n{\"title\": \"Jetpack Compose 入门\", \"price\": \"¥128\"}\n```"
        assertEquals(Grounding.found("标题和价格", without, screen), Grounding.found("标题和价格", withEmpty, screen))
        assertEquals(true, Grounding.found("标题", "标题是「Jetpack Compose 入门」,标签「」", screen))
        assertEquals(false, Grounding.found("价格", "{\"price\": \"¥256\", \"recent\": \"\"}", screen))
    }

    @Test fun refsInQuotesStillMatch() {
        assertTrue(Grounding.quoteInReads("Jetpack Compose 入门 (@e16)", screen))
        assertFalse(Grounding.quoteInReads("Kotlin 协程 (@e16)", screen))
    }

    /** The web page title is in the tool result, not in the page body: it is evidence too, the verifier sees it, and the fallback accepts it. */
    @Test fun toolFactsAreEvidence() = runTest {
        val criteria = Criteria(listOf("回答给出页面的大标题"), "read", fallback = false)
        val pass = """{"checks": [{"criterion": "回答给出页面的大标题", "status": "met", "quote": "Example Domain"}], "verdict": "pass", "reason": "标题对得上"}"""
        val m = FakeModel(ModelReply(listOf(Part.Text(pass)), StopReason.EndTurn, null))
        val e = Evidence(freshOf("当前网页:https://example.com/\n这个域名可用于文档示例"), null,
            ledgerOf(facts = listOf("web_navigate:网址 https://example.com/,网页标题「Example Domain」,HTTP 状态 200")), listOf("第1步 web_navigate(https://example.com) 生效"),
            protected = false, appOpened = false)
        val v = Verifier(m, null).verify("打开 example.com 告诉我大标题", criteria, "页面标题是「Example Domain」\n依据: 网页标题「Example Domain」", e)
        assertEquals(Verdict.Pass, v.verdict)
        val body = (m.requests.single().single().parts.single() as Part.Text).text
        assertTrue(body, "\"kind\":\"fact\"" in body && "Example Domain" in body)
    }

    // Text truncated on screen (ending in …) is only part of a value: an answer built on it is not fully verified
    @Test fun truncatedTextIsPartialEvidence() = runTest {
        val shown = listOf("当前 App:com.example.jobs\nAndroid 工程师 · Kotlin + Jetpack…\n薪资 25-40K")
        assertTrue(Grounding.builtOnTruncated("岗位要求「…Kotlin + Jetpack ...」\n依据: 列表第一条", shown))
        assertTrue(Grounding.builtOnTruncated("要求 Kotlin + Jetpack...\n依据: 列表第一条", shown))
        assertTrue(Grounding.builtOnTruncated("要求「Kotlin + Jetpack」\n依据: 列表第一条", shown))
        // An untruncated value, or a full source text elsewhere: as usual
        assertFalse(Grounding.builtOnTruncated("薪资「25-40K」\n依据: 列表第一条", shown))
        assertFalse(Grounding.builtOnTruncated("要求「Kotlin + Jetpack」\n依据: 详情页", shown + "岗位要求 Kotlin + Jetpack Compose"))

        val criteria = Criteria(listOf("回答给出岗位要求"), "read", fallback = false)
        val pass = """{"checks": [{"criterion": "回答给出岗位要求", "status": "met", "quote": "Kotlin + Jetpack…"}], "verdict": "pass", "reason": "有"}"""
        val v = Verifier(FakeModel(ModelReply(listOf(Part.Text(pass)), StopReason.EndTurn, null)), null).verify("岗位要求是什么", criteria,
            "要求「Kotlin + Jetpack…」\n依据: 列表第一条「Kotlin + Jetpack…」",
            Evidence(freshOf(shown.single()), null, EvidenceLedger(), emptyList(), protected = false))
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.TRUNCATED, v.reason)
        assertTrue("truncated" in Verifier.PROMPT)
    }
}
