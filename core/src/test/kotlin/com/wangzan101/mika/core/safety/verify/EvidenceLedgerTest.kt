package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.eval.Checks
import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceLedgerTest {
    private val goal = "比较长江和黄河哪条更长,各多长"
    private val answer = "长江更长:长江 6300 公里,黄河 5464 公里\n依据: 「长江全长 6300 公里」「黄河全长 5464 公里」"
    private val criteria = Criteria(listOf("回答给出两条河的长度并比较"), "read", fallback = false)
    private val pass = """{"checks": [{"criterion": "回答给出两条河的长度并比较", "status": "met", "quote": "6300 公里"}], "verdict": "pass", "reason": "有"}"""
    private fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private fun filler(i: Int) = "当前网页:https://news.example.com/$i\n" + "无关的新闻正文。".repeat(700)

    /** Two wiki pages are read early and many more pages follow: they are already gone from the old "most recent 3 screens" window. */
    private fun ledger(): EvidenceLedger = EvidenceLedger().apply {
        add(2, ObsKind.Read, "https://wiki.example.com/长江", 1, "网页正文(https://wiki.example.com/长江)\n长江全长 6300 公里,是亚洲第一长河")
        add(4, ObsKind.Read, "https://wiki.example.com/黄河", 2, "网页正文(https://wiki.example.com/黄河)\n黄河全长 5464 公里")
        for (i in 1..20) add(4 + i, ObsKind.Read, "https://news.example.com/$i", 2L + i, filler(i))
    }

    @Test fun earlyPagesRelevantToTheAnswerAreChosen() {
        val l = ledger()
        val chosen = l.forAnswer(goal, answer)
        assertTrue(chosen.any { "6300" in it.text } && chosen.any { "5464" in it.text })
        // The chosen entries follow the order they were read; unrelated pages contribute only the most recent one
        assertEquals(chosen.sortedBy { it.step }, chosen)
        assertEquals(listOf(2, 4, 24), chosen.map { it.step })
        assertEquals(true, Grounding.check(goal, answer, chosen.map { it.text }).found)
        // The verifier model gets excerpts: total characters are capped, and every line carrying a fact from the answer is present
        val shown = Verifier.excerpts(goal, answer, chosen, EvidenceLedger.EXCERPT_CHARS)
        assertTrue(shown.sumOf { it.text.length } <= EvidenceLedger.EXCERPT_CHARS)
        assertTrue(shown.any { "长江全长 6300 公里" in it.text } && shown.any { "黄河全长 5464 公里" in it.text })
    }

    @Test fun verifierPassesOnPagesReadLongAgo() = runTest {
        val m = FakeModel(reply(pass))
        val e = Evidence(freshOf(filler(20)), null, ledger(), listOf("第24步 web_observe() 生效"), protected = false)
        val v = Verifier(m, null).verify(goal, criteria, answer, e)
        assertEquals(Verdict.Pass, v.verdict)
        val body = (m.requests.single().single().parts.single() as Part.Text).text
        // Carries provenance: which step, which kind of source, which URL
        assertTrue(body, "\"step\":2" in body && "\"kind\":\"read\"" in body && "wiki.example.com" in body)
    }

    // The ledger can only vouch for what was read: a number the answer never saw in any page cannot be backed by any amount of ledger selection
    @Test fun ledgerCannotVouchForWhatWasNeverRead() = runTest {
        val wrong = "长江更长:长江 6397 公里,黄河 5464 公里\n依据: 「长江全长 6397 公里」「黄河全长 5464 公里」"
        val l = ledger()
        assertFalse(l.forAnswer(goal, wrong).any { "6397" in it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify(goal, criteria, wrong,
            Evidence(freshOf(filler(20)), null, l, emptyList(), protected = false))
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.NOT_IN_READS, v.reason)
        assertTrue(v.notFound.toString(), v.notFound.any { "6397" in it })
        assertFalse(Checks.grounded(wrong, l.forAnswer(goal, wrong).map { it.text }, 2, goal))
    }

    // Text the assistant itself typed into a field (its echo) cannot vouch for the answer
    @Test fun typedEchoCannotVouch() = runTest {
        val l = EvidenceLedger().apply {
            noteTyped("长江 6300 公里")
            add(3, ObsKind.Read, "com.example.search", 1, "当前 App:com.example.search\n长江 6300 公里\n没有找到相关结果")
        }
        assertFalse(l.forAnswer(goal, answer).any { "6300" in it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify(goal, criteria, answer, Evidence(freshOf(""), null, l, emptyList(), protected = false))
        assertEquals(Verdict.CannotVerify, v.verdict)
    }

    // Redacted before entering the ledger: keys and one-time codes keep no original text, so they cannot vouch either
    @Test fun secretsAndCodesAreRedactedBeforeTheyEnter() {
        val key = "sk-" + "a".repeat(30)
        val l = EvidenceLedger().apply { add(1, ObsKind.Read, null, 1, "短信:您的验证码 482913,5 分钟内有效\n密钥 $key") }
        val t = l.all.single().text
        assertFalse(t, "482913" in t || key in t)
        assertTrue(t, "验证码 ${Redactor.MASK}" in t)
        assertFalse(Grounding.check("我的验证码是多少", "验证码是 482913", l.forAnswer("我的验证码是多少", "验证码是 482913").map { it.text }).found == true)
    }

    @Test fun boundedByTotalSizeOldestFirstAndDeduplicated() {
        val l = EvidenceLedger(capChars = 10_000)
        for (i in 1..5) l.add(i, ObsKind.Read, null, i.toLong(), "第${i}屏\n" + "字".repeat(3_000))
        assertEquals(listOf(3, 4, 5), l.all.map { it.step })
        // The same text is kept only for the latest read, and its provenance is updated with it
        l.add(9, ObsKind.Read, null, 9, "第4屏\n" + "字".repeat(3_000))
        assertEquals(listOf(3, 5, 9), l.all.map { it.step })
        assertEquals(9L, l.all.last().atMs)
    }

    // Eval checking and the verifier fallback draw evidence from the same record with the same selection rule: both reach the same view of the same answer
    @Test fun evalAndVerifierReadTheSameLedger() {
        fun page(step: Int, url: String, text: String) =
            RunEvent.ToolResult(step.toLong(), step, "web_read_page", """{"ok":true,"url":"$url","text":"$text"}""", null, null)
        val events = listOf(RunEvent.TaskStarted(0, goal, "m")) +
            page(2, "https://wiki.example.com/cj", "长江全长 6300 公里") + page(4, "https://wiki.example.com/hh", "黄河全长 5464 公里") +
            (1..8).map { page(4 + it, "https://news.example.com/$it", "无关的新闻正文 第${it}篇") } +
            RunEvent.ModelReply(30, 13, "", "finish", """{"answer":${kotlinx.serialization.json.JsonPrimitive(answer)}}""", null, null, null) +
            RunEvent.Verifier(31, "pass", "{}", 13)
        val (v, f) = RecordedVerifications.pairs(events).single()
        for ((a, ok) in listOf(answer to true, answer.replace("6300", "6397") to false)) {
            val forVerifier = RecordedVerifications.ledgerSources(events, v, f, goal, a)
            val forEval = RunFacts.evidence(events, RECORDED_TOOLS, goal, a)
            assertEquals(a, ok, Grounding.check(goal, a, forVerifier).found == true)
            assertEquals(a, ok, Checks.grounded(a, forEval, 2, goal))
        }
    }

    // ---------- Which thing a value belongs to: the verifier model decides, and the code makes sure it can see the provenance ----------

    private val shopGoal = "华为手环 9 卖多少钱"
    private fun shop(): EvidenceLedger = EvidenceLedger().apply {
        add(2, ObsKind.Read, "https://shop.example.com/a", 1, "网页正文\n小米手环 9\n售价 ¥299", title = "小米手环 9")
        add(6, ObsKind.Read, "https://shop.example.com/b", 2, "网页正文\n华为手环 9\n售价 ¥399", title = "华为手环 9")
    }
    private val shopCriteria = Criteria(listOf("回答给出华为手环 9 的售价"), "read", fallback = false)
    private val shopPass = """{"checks": [{"criterion": "回答给出华为手环 9 的售价", "status": "met", "quote": "¥299"}], "verdict": "pass", "reason": "有"}"""
    private val shopUnmet = """{"checks": [{"criterion": "回答给出华为手环 9 的售价", "status": "unmet", "quote": "¥299"}], "verdict": "fail", "reason": "¥299 是小米手环 9 那一页的"}"""

    /** The ledger entries given to the verifier model (JSON). */
    private fun shownLedger(m: FakeModel) = (m.requests.single().single().parts.first() as Part.Text).text
        .let { kotlinx.serialization.json.Json.parseToJsonElement(it.substring(it.indexOf('{'))) }
        .let { (it as kotlinx.serialization.json.JsonObject)["ledger"] as kotlinx.serialization.json.JsonArray }
        .map { e -> (e as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content } }

    // Counterexample: ¥299 from page A is presented as page B's price. The code only checks the literal text (¥299 really was read); which thing it belongs to is the verifier model's call:
    // it gets each page's provenance (URL, title, step) and the price line, and the prompt states this rule clearly
    @Test fun valueFromAnotherPageIsTheModelsCallWithProvenance() = runTest {
        val wrong = "华为手环 9 售价 ¥299。\n依据:「¥299」"
        assertEquals(true, Grounding.check(shopGoal, wrong, shop().forAnswer(shopGoal, wrong).map { it.text }).found)
        val m = FakeModel(reply(shopUnmet))
        val v = Verifier(m, null).verify(shopGoal, shopCriteria, wrong, Evidence(freshOf("网页标题:华为手环 9\n售价 ¥399"), null, shop(), emptyList(), protected = false))
        assertEquals(Verdict.Fail, v.verdict)
        val shown = shownLedger(m)
        val a = shown.single { it["where"] == "https://shop.example.com/a" }
        assertEquals("小米手环 9", a["title"]); assertEquals("2", a["step"]); assertTrue(a["text"]!!, "小米手环 9\n售价 ¥299" in a["text"]!!)
        // The page read later (the thing the answer is about) is there too
        assertTrue(shown.toString(), shown.any { it["title"] == "华为手环 9" && "¥399" in it["text"]!! })
        assertTrue(Verifier.PROMPT, "A value must belong to the thing the answer says it is about" in Verifier.PROMPT && "where and title" in Verifier.PROMPT && "another line on the same page" in Verifier.PROMPT)
        // The correct answer: both the code and the model let it through
        val right = "华为手环 9 售价 ¥399。\n依据:「¥399」"
        val ok = Verifier(FakeModel(reply(shopPass.replace("299", "399"))), null).verify(shopGoal, shopCriteria, right,
            Evidence(freshOf("网页标题:华为手环 9\n售价 ¥399"), null, shop(), emptyList(), protected = false))
        assertEquals(Verdict.Pass, ok.verdict)
        assertTrue(Checks.grounded(right, shop().forAnswer(shopGoal, right).map { it.text }, 1, shopGoal))
    }

    // ---------- Entries that must be shown ----------

    // The same page read later may contradict the answer: the latest read of that page always goes to the verifier
    @Test fun latestReadOfTheAnswersPlaceIsAlwaysIncluded() {
        val l = EvidenceLedger().apply {
            add(2, ObsKind.Read, "https://shop.example.com/b", 1, "网页标题:华为手环 9\n售价 ¥299")
            for (i in 3..20) add(i, ObsKind.Read, "https://news.example.com/$i", 2, filler(i))
            add(21, ObsKind.Read, "https://shop.example.com/b", 3, "网页标题:华为手环 9\n售价 ¥399(已调价)")
            for (i in 22..24) add(i, ObsKind.Read, "https://news.example.com/$i", 4, filler(i))
        }
        val chosen = l.forAnswer(shopGoal, "华为手环 9 售价 ¥299\n依据:「¥299」")
        assertEquals(listOf(2, 21, 24), chosen.map { it.step })
    }

    // ---------- Field echo is judged by the control ----------

    @Test fun echoIsJudgedByTheFieldNotTheText() {
        val l = EvidenceLedger().apply {
            noteTyped("长江长度")
            // The field has focus: its text, and suggestion lines starting with it, do not count
            add(3, ObsKind.Read, "com.example.search", 1, "长江长度\n长江长度是 6300 公里\n长江长度和黄河比", inputs = listOf("长江长度"), focused = "长江长度")
            // Results page: the text in the field does not count, but result titles identical to the query still do
            add(5, ObsKind.Read, "com.example.search", 2, "长江长度\n长江长度\n全长 6211 公里", inputs = listOf("长江长度"))
        }
        val texts = l.forAnswer("长江有多长", "长江长度 6211 公里").associate { it.step to it.text }
        assertFalse(texts.toString(), texts[3].orEmpty().contains("6300"))
        assertEquals("长江长度\n全长 6211 公里", texts[5])
        // A number that exists only in the field cannot vouch
        assertFalse(Grounding.check("长江有多长", "长江长 6300 公里", l.forAnswer("长江有多长", "长江长 6300 公里").map { it.text }).found == true)
    }

    // ---------- Redaction ----------

    @Test fun codesNearCodeWordsAreMaskedInEitherDirection() {
        for (raw in listOf("验证码 4 8 2 9 1 3,5 分钟内有效", "482913 是您的登录验证码", "短信 code: 4829", "Your OTP is 7712-3390")) {
            val t = Redactor.evidence(raw)
            assertFalse(t, Regex("\\d(?:[ \\-]?\\d){3,}").containsMatchIn(t))
        }
        // Numbers unrelated to these words are untouched
        assertEquals("售价 ¥2026,库存 1288 件", Redactor.evidence("售价 ¥2026,库存 1288 件"))
    }

    // Text the user filled into a field during a takeover does not enter the ledger; text the assistant typed still does
    @Test fun fieldsFilledByTheUserDuringTakeoverAreNotKept() {
        val l = EvidenceLedger().apply {
            noteTyped("北京")
            noteTakeover()
            add(9, ObsKind.Read, "com.example.app", 1, "手机号\n13800138000\n北京\n已登录", inputs = listOf("13800138000", "北京"))
        }
        assertEquals("手机号\n北京\n已登录", l.all.single().text)
    }

    // ---------- What the assistant wrote itself cannot vouch ----------

    @Test fun agentAuthoredOutputCannotVouch() {
        fun reply(step: Int, tool: String, args: String) = RunEvent.ModelReply(step.toLong(), step, "", tool, args, null, null, null)
        fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(step.toLong(), step, tool, json, null, null)
        val events = listOf(
            reply(1, "run_command", """{"command":"echo 6300"}"""), result(1, "run_command", """{"ok":true,"exitCode":0,"output":"6300\n"}"""),
            reply(2, "write_file", """{"path":"out/a.txt","content":"黄河 5464 公里"}"""),
            result(2, "write_file", """{"ok":true,"path":"/workspace/out/a.txt","bytesWritten":20,"size":20}"""),
            reply(3, "read_file", """{"path":"out/a.txt"}"""), result(3, "read_file", """{"ok":true,"path":"/workspace/out/a.txt","content":"黄河 5464 公里"}"""),
        )
        val texts = RunFacts.ledger(events, RECORDED_TOOLS).all.joinToString("\n") { it.text }
        assertFalse(texts, "6300" in texts.replace("退出码 0", "") || "5464" in texts)
        assertTrue(texts, "/workspace/out/a.txt" in texts)
    }

    // ---------- Which thing a value belongs to (cont.): the excerpts given to the verifier keep the lines with the name and the value ----------

    // In a native app, two pages of the same package one after the other: the provenance carries the step, and each page's own name and price are both there
    @Test fun nativeAppPagesAcrossStepsKeepTheirSteps() = runTest {
        val l = EvidenceLedger().apply {
            add(5, ObsKind.Read, "com.example.shop", 1, "当前 App:com.example.shop\n小米手环 9\n售价 ¥299")
            add(7, ObsKind.Read, "com.example.shop", 2, "当前 App:com.example.shop\n华为手环 9\n售价 ¥399")
        }
        val fresh = "当前 App:com.example.shop\n华为手环 9\n售价 ¥399"
        val wrong = "华为手环 9 售价 ¥299。\n依据:「¥299」"
        val m = FakeModel(reply(shopUnmet))
        assertEquals(Verdict.Fail, Verifier(m, null).verify(shopGoal, shopCriteria, wrong, Evidence(freshOf(fresh), null, l, emptyList(), protected = false)).verdict)
        val shown = shownLedger(m)
        assertTrue(shown.toString(), shown.any { it["step"] == "5" && "小米手环 9\n售价 ¥299" in it["text"]!! })
        assertTrue(shown.toString(), shown.any { it["step"] == "7" && "华为手环 9\n售价 ¥399" in it["text"]!! })
    }

    // Two rows on one screen, weather listed by city: when a long page is only excerpted, a matched value is kept together with the names before and after it
    @Test fun excerptKeepsTheRowAroundEachValue() {
        val rows = (1..300).joinToString("\n") { "无关的一行 $it" }
        val page = "网页正文\n$rows\n小米手环 9\n¥299\n华为手环 9\n¥399\n$rows"
        val e = obs(3, ObsKind.Read, "https://shop.example.com/list", page)
        val shown = Verifier.excerpts("小米手环 9 和华为手环 9 各多少钱", "华为手环 9 ¥299\n依据:「¥299」", listOf(e), 2_000).single().text
        assertTrue(shown, shown.length < 2_000 && "小米手环 9\n¥299\n华为手环 9" in shown && " lines omitted]" in shown)
        val cities = "明天\n北京 小雨 12℃/9℃\n上海 阴 23℃/17℃\n广州 多云 30℃/20℃\n" + rows
        val c = Verifier.excerpts("北京、上海、广州明天的最高气温", "北京:最高 23℃\n依据:城市列表", listOf(e.copy(text = cities)), 2_000).single().text
        assertTrue(c, "北京 小雨 12℃/9℃\n上海 阴 23℃/17℃" in c)
        // With one page per city, each page's own title is given to the verifier model
        val l = EvidenceLedger().apply {
            add(2, ObsKind.Read, "https://weather.example.com/bj", 1, "25日(明天) 小雨 12℃/9℃", title = "北京天气预报")
            add(4, ObsKind.Read, "https://weather.example.com/sh", 2, "25日(明天) 阴 23℃/17℃", title = "上海天气预报")
        }
        val chosen = l.forAnswer("北京、上海明天的最高气温", "北京:最高 23℃\n上海:最高 12℃\n依据:各城市预报页")
        assertEquals(listOf("北京天气预报", "上海天气预报"), chosen.map { it.title })
    }

    // Nothing is dropped: when many long entries carry facts from the answer, each one is shortened but all of them stay, each with its matched line
    @Test fun excerptsNeverDropAnEntryWithAClaim() {
        val entries = (1..20).map { i -> obs(i, ObsKind.Read, "https://x.example.com/$i", "字".repeat(3_000) + "\n价格 ${1000 + i} 元\n" + "字".repeat(3_000)) }
        val answer = (1..20).joinToString(",") { "${1000 + it} 元" }
        val shown = Verifier.excerpts("", answer, entries, EvidenceLedger.EXCERPT_CHARS)
        assertEquals(20, shown.size)
        assertTrue(shown.sumOf { it.text.length } <= 20 * EvidenceLedger.MIN_EXCERPT + 20 * 40)
        for (i in 1..20) assertTrue(shown[i - 1].text, "价格 ${1000 + i} 元" in shown[i - 1].text)
    }

    // Unrecognized units are each their own dimension: ¥5 + $3 = 8 does not add up
    @Test fun unknownUnitsAreTheirOwnDimension() {
        val a = "一共 8:¥5 + $3 = 8\n依据:「¥5」「$3」"
        assertFalse(Grounding.check("", a, listOf("门票 ¥5", "饮料 $3")).found == true)
        assertTrue(Grounding.check("", "一共 ¥8:¥5 + ¥3 = ¥8\n依据:「门票 ¥5」「饮料 ¥3」", listOf("门票 ¥5", "饮料 ¥3")).found == true)
    }

    // Code masking leaves numbers with units, years and currencies alone
    @Test fun codeMaskingSparesQuantities() {
        assertEquals("短信:2026 年 10 月账单 ¥1288,共 3000 元", Redactor.evidence("短信:2026 年 10 月账单 ¥1288,共 3000 元"))
        assertFalse(Redactor.evidence("短信验证码 7712").contains("7712"))
    }
}
