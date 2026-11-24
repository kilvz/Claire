package com.wangzan101.mika.core.safety.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The three kinds of correct answers the fallback wrongly rejected (the original wording from real-device records and the page text read), plus a counterexample for each: evidence only vouches for what it can, and wrong answers are always blocked.
 * - Run clock: the goal or the evidence says "tomorrow" or "Tuesday", and the day is worked out from the clock;
 * - Simple arithmetic: the computation the answer spells out (a op b = c), with both a and b matched in the evidence; and rounding when an approximation is stated (about, ≈);
 * - Truncation: the answer itself omits part of a quote with …, while that line on screen is complete, so it does not count as truncation.
 */
class GroundingRecordedRunsTest {
    /** The day of the verification in the record: 2026-10-24, a Saturday. */
    private val today = LocalDate.of(2026, 10, 24)

    private val beijingPage = listOf(
        "18:00更新 | 数据来源 中央气象台",
        "周末两天（10月24日至25日），我国华东沿海多地仍有降雨，其中今天雨势较强；同时，西北地区东部和华北等地降水增多。",
        "25日（明天） 小雨 12℃/9℃ <3级",
        "26日（后天） 阴转晴 16℃/6℃ <3级",
    ).joinToString("\n")
    private val beijingGoal = "用网页查一下明天北京的天气,告诉我最高和最低气温"
    private val beijingAnswer = "明天(10月25日,周日)北京天气已查到。\n\n- 天气:小雨\n- 最高气温:12℃\n- 最低气温:9℃\n\n" +
        "依据:中国天气网北京7天预报页面,「25日(明天)」一栏显示「小雨 12℃/9℃ <3级」,更新时间 18:00,数据来源中央气象台。"

    // Observed in web-weather-beijing: the page only said "25th (tomorrow)" and the answer's conversion to "October 25" was treated as a date not found
    @Test fun dateResolvedFromRunClockIsKnown() {
        val without = Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage))
        assertEquals(false, without.found)
        assertEquals(listOf("10月25日"), without.unmatched.map { it.text })
        assertEquals(true, Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage), today).found)
        // web-weather-three-cities: the same when the year is written in, as "October 25, 2026"
        val cities = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n\n" +
            "依据:中国天气网 7 天预报页面,北京显示\"25日(明天) 小雨 12℃/9℃\"。"
        assertEquals(true, Grounding.check("用网页查北京明天的最高气温", cities, listOf(beijingPage), today).found)
        // The page says "Tuesday": Tuesday is October 27
        val week = "27日（周二） 晴 16℃/2℃ <3级"
        assertEquals(true, Grounding.check("北京周二天气", "周二(10月27日)北京晴,最高气温:16℃\n依据:「晴 16℃/2℃」", listOf(week), today).found)
    }

    @Test fun clockOnlyVouchesForRelativeDaysTheEvidenceMentions() {
        fun unmatched(answer: String, page: String = beijingPage, goal: String = beijingGoal) =
            Grounding.check(goal, answer, listOf(page), today).unmatched.map { it.text }
        // "Tuesday" appears only in the answer: neither the goal nor the page mentions Tuesday, so the clock does not vouch for it
        assertEquals(listOf("10月27日"), unmatched("周二(10月27日)北京晴,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // The goal and the page say tomorrow (the 25th) and the day after (the 26th): the 27th is wrong
        assertEquals(listOf("10月27日"), unmatched(beijingAnswer.replace("10月25日", "10月27日"), beijingPage + "\n29日 小雨 10月29日"))
        // Neither the goal nor the page has a relative expression: a date within a week is not vouched for by the clock either
        val plainPage = "25日 小雨 12℃/9℃ <3级"
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」", plainPage, "北京天气"))
        // The answer says the date was read from the page (in the quote, in the basis), so the clock does not vouch
        assertTrue("10月25日" in unmatched("明天北京「10月25日 小雨」,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:页面写着 10月25日 小雨 12℃/9℃"))
        // A time of day is never vouched for by the clock
        assertEquals(listOf("10:27"), unmatched("今天 10:27 更新,明天小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // A distant date
        assertEquals(listOf("11月5日"), unmatched(beijingAnswer.replace("10月25日", "11月5日")))
        // The clock only vouches for dates, not for numbers: a made-up temperature is still blocked
        assertEquals(listOf("31"), unmatched(beijingAnswer.replace("最高气温:12℃", "最高气温:31℃")))
    }

    private val riverPages = listOf(
        "长江，中国境内太平洋水系河流，最后注入东海。全长6363千米 [16]（一说有6236千米，一说6380千米，一说6397千米，一说 6403千米，一说6300余千米 [66]）。是中国、亚洲第一大河。",
        "黄河，是位于中国北方地区的大河，最后在东营市垦利区流入渤海。黄河全长约5464千米 [51]，流域面积约79.47万平方千米。",
        "web_navigate:网址 https://baike.example.com/cj,网页标题「长江（中国境内太平洋水系河流）_百科」",
        "web_navigate:网址 https://baike.example.com/hh,网页标题「黄河（中国北方独流入渤海水系河流）_百科」",
    )
    private val riverGoal = "用网页分别查一下长江和黄河的全长,告诉我哪条更长、大约长多少公里"
    private fun riverAnswer(diff: String) = "长江和黄河的全长都已在百度百科词条正文中查到:\n\n" +
        "- 长江:全长 6363 千米(词条还注明另有 6236、6380、6397、6403、6300 余千米等说法)。\n- 黄河:全长约 5464 千米。\n\n" +
        "对比结果:长江更长,$diff。\n\n" +
        "依据:百度百科「长江(中国境内太平洋水系河流)」词条正文「全长6363千米」;「黄河(中国北方独流入渤海水系河流)」词条正文「全长约5464千米」。"

    // Observed in web-rivers-length-compare: the difference 899 = 6363 − 5464 is naturally not on screen. It only counts when the computation is written out and both operands are in the evidence
    @Test fun explicitComputationOfGroundedNumbersIsGrounded() {
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=899 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363 千米 - 5464 千米 = 899 千米"), riverPages).found)
        // Only when an approximation is stated (≈, or "= about") is it rounded to the trailing zeros
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464≈900 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=约900 公里"), riverPages).found)
        // Percentage: the result of ÷ is rounded to the decimal places written
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363÷5464=116.5%"), riverPages).found)
        // Difference of prices
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(true, Grounding.check("比较价格", "手机 ¥1999,耳机 ¥299,¥1999 − ¥299 = ¥1700\n依据:「手机 ¥1999」", prices).found)
        // A single number with a stated approximation, rounded to the written precision, equals the number in the evidence
        assertEquals(emptyList<String>(), Grounding.check("多少钱", "售价约 2000元\n依据:「售价 1999元」", listOf("售价 1999元")).unmatched.map { it.text })
    }

    @Test fun inventedNumberIsStillRejected() {
        fun unmatched(goal: String, answer: String, screens: List<String>) = Grounding.check(goal, answer, screens).unmatched.map { it.text }
        // No computation written: the difference is simply "not on screen"
        assertEquals(listOf("899"), unmatched(riverGoal, riverAnswer("比黄河长约 899 公里"), riverPages))
        assertEquals(listOf("1234"), unmatched(riverGoal, riverAnswer("比黄河长 1234 公里"), riverPages))
        // The computation is wrong
        assertEquals(listOf("999"), unmatched(riverGoal, riverAnswer("6363−5464=999 公里"), riverPages))
        // An approximation may not be so coarse that it matches a whole range of numbers: 1000 is more than ten percent away from 899
        assertEquals(listOf("1000"), unmatched(riverGoal, riverAnswer("6363−5464≈1000 公里"), riverPages))
        // The operands are not in the evidence
        assertEquals(listOf("5063", "1300"), unmatched(riverGoal, riverAnswer("6363−5063=1300 公里"), riverPages))
        // Exactly the difference of the two prices, but with no computation written: words like "total" or "difference" are not evidence
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,共 ¥1700\n依据:「手机 ¥1999」", prices))
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,平板 ¥1700\n依据:「手机 ¥1999」", prices))
        // A made-up percentage: 96% is not 100%, nor 40%
        assertTrue("100" in unmatched("电量多少", "电量:100%\n依据:「电量 96%」", listOf("电量 96%")))
        assertTrue("40" in unmatched("电量多少", "电量 96%,已用约 40%\n依据:「电量 96%」", listOf("电量 96%")))
        // No approximation stated: 1999 yuan is not 2000 yuan
        assertEquals(listOf("2000"), unmatched("多少钱", "售价 2000元\n依据:「售价 1999元」", listOf("售价 1999元")))
        // A result computed from numbers given in the goal must still be read on screen (a calculator task: the screen has only the expression, not the result)
        assertEquals(listOf("3950.6"), unmatched("用计算器算 98765 除以 25", "98765÷25=3950.6\n依据:计算器屏上「98765÷25」", listOf("98765÷25")))
    }

    // Observed in web-rivers-length-compare: an 899 embedded in the hash of an image URL on the page was taken for a number on screen
    @Test fun numbersOnScreenMustBeWholeTokens() {
        val hash = "https://bkimg.cdn.bcebos.com/smart/d50735fae6cd7b899e51bc8a177c55a7d933c895a038-bkimg-process,v_1"
        val answer = riverAnswer("比黄河长约 899 公里")
        assertEquals(listOf("899"), Grounding.check(riverGoal, answer, riverPages + hash).unmatched.map { it.text })
        assertEquals(emptyList<String>(), Grounding.check(riverGoal, answer, riverPages + "长江比黄河长约 899 公里").unmatched.map { it.text })
        // A number inside an id, or a version number glued to letters, does not count; a unit written flush against the number (128GB), decimals and thousands separators do count
        assertEquals(false, Grounding.check("编号", "编号是 899 号码\n依据:「订单」", listOf("订单 a3f899c")).found)
        assertEquals(true, Grounding.check("存储多少", "存储:128GB\n依据:「存储 128GB」", listOf("存储 128GB")).found)
        assertEquals(true, Grounding.check("价格", "价格:1,299.50元\n依据:「价格」", listOf("价格 ¥1,299.50")).found)
        // A code embedded in letters is compared as a whole token: G7541 matches, a made-up G9999 is blocked
        assertEquals(emptyList<String>(), Grounding.check("车次", "车次:G7541\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        assertEquals(listOf("G9999"), Grounding.check("车次", "车次 G9999 05:52 出发\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        // Dates work the same way: the 1025 in a URL is not October 25
        assertEquals(listOf("10月25日"), Grounding.check("哪天", "日期是 10月25日\n依据:「图片」", listOf("图片 https://x.cn/p/img10x25b.jpg")).unmatched.map { it.text })
    }

    // Numbers in the goal are recognized by value: the goal says 1000.0 and the answer says 1000, which is not treated as a value to look for on screen
    @Test fun goalNumbersAreKnownByValue() {
        assertEquals(true, Grounding.check("用计算器算 1000.0 除以 8", "1000 除以 8 等于 125.0\n依据:计算器屏上「125」", listOf("125")).found)
    }

    private val trainRows = listOf(
        "预订G7541次车05:52从上海虹桥出发，06:51到杭州东,当日到达历时00:59的车票",
        "预订G1321次车06:07从上海虹桥出发，06:56到杭州东,当日到达历时00:49的车票",
        "预订G7331次车06:13从上海虹桥出发，07:07到杭州东,当日到达历时00:54的车票",
    ).joinToString("\n")
    private val trainAnswer = "明天(2026-10-25)上海到杭州的高铁车次:\n\n| G7541 | 上海虹桥 05:52 | 杭州东 06:51 |\n| G1321 | 上海虹桥 06:07 | 杭州东 06:56 |\n\n" +
        "依据:12306 官网余票页查询结果列表,页面元素逐条显示\"预订G7541次车05:52从上海虹桥出发,06:51到杭州东…\"\"预订G1321次车06:07从上海虹桥出发,06:56到杭州东…\"等车次信息。"

    // Observed in web-12306-trains: the answer itself shortened the second half of the quote with …, while those lines on screen were complete, and it was judged as truncated evidence
    @Test fun agentEllipsisOnCompleteLineIsNotTruncation() {
        assertFalse(Grounding.builtOnTruncated(trainAnswer, listOf(trainRows)))
        val pass = Verification(Verdict.Pass, listOf(CriterionCheck("列出至少三个车次号", "met", "预订G7541次车05:52从上海虹桥出发")), "屏上有")
        assertEquals(Verdict.Pass, Verifier.backstop(pass, "在 12306 上查明天上海到杭州的高铁", trainAnswer, listOf(trainRows), imageSent = false, today).verdict)
    }

    @Test fun truncatedScreenLineIsStillTruncation() {
        // That line on screen is itself truncated (ends in …) and the answer's quote runs right up to the cut: only part was seen
        val cut = "预订G7541次车05:52从上海虹桥出发，06:51到杭州东…\n预订G1321次车06:07从上海虹桥出发，06:56到杭州东…"
        assertTrue(Grounding.builtOnTruncated(trainAnswer, listOf(cut)))
        // The quote starts with … and the first half of that line was cut off on screen
        assertTrue(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf("…到杭州东,当日到达")))
        assertFalse(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf(trainRows)))
    }

    // ---------- Literal only: a correct answer is not blocked by wording (real-device record emu-web-smoke-2), wrong ones are still blocked ----------

    private val doubanRows = listOf("1", "肖申克的救赎 / The Shawshank Redemption", "9.7 3353059人评价", "4", "阿甘正传 / Forrest Gump", "9.5 2465343人评价",
        "5", "千与千寻 / 千と千尋の神隠し", "9.4 2577773人评价").joinToString("\n")

    // Observed in web-douban-top-five: with the next line's list index removed, "9.5" and the first character of the film title on the next line were joined into one magnitude-like token; the read date written in the basis was taken for a date to verify
    @Test fun answerLinesAreNotJoinedAndBasisProseIsNotAClaim() {
        val a = "前五如下:\n\n1. 肖申克的救赎 — 评分 9.7\n4. 阿甘正传 — 评分 9.5\n5. 千与千寻 — 评分 9.4\n\n" +
            "依据:网页正文「1 肖申克的救赎 … 9.7 3353059人评价」「4 阿甘正传 … 9.5 2465343人评价」「5 千与千寻 … 9.4 2577773人评价」(movie.douban.com/top250,2026-10-24 读取)。"
        val g = Grounding.check("豆瓣 Top250 前五的电影名和评分", a, listOf(doubanRows), today)
        assertEquals(g.unmatched.toString(), true, g.found)
        assertFalse(g.claims.toString(), g.claims.any { "千" in it.text && it.kind == Grounding.Kind.Number })
        // Quotes in the basis are still verified: a made-up rating count or a misquoted film title is blocked
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("2577773人评价", "2577999人评价"), listOf(doubanRows), today).found)
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("「5 千与千寻", "「5 天空之城"), listOf(doubanRows), today).found)
    }

    // Observed in web-rivers-length-compare: "Yellow River: total length about 5464 km (entry text …)" was taken for a "label: value" and the whole passage was required to be on screen
    @Test fun unquotedLabelValuesAreNotLiteralClaims() {
        val a = "- 黄河:全长约 5464 千米(词条正文「全长约5464千米 [51],流域面积约79.47万平方千米」)\n依据:黄河词条「全长约5464千米 [51],流域面积约79.47万平方千米」"
        assertEquals(true, Grounding.check(riverGoal, a, riverPages).found)
        assertEquals(listOf("5465"), Grounding.check(riverGoal, a.replaceFirst("5464 千米", "5465 千米"), riverPages).unmatched.map { it.text })
    }

    // Observed in web-github-trending-top: "today +N" uses N as a stand-in for a number the answer wrote itself, not literal text on screen
    @Test fun quoteThatNamesTheAnswersOwnWordingIsNotAScreenClaim() {
        val page = listOf("## cloudflare / security-audit-skill", "JavaScript 43,250 2,699 Built by", "2,271 stars today")
        val a = "1. cloudflare / security-audit-skill — 43,250 颗星(今日 +2,271)\n说明:「今日 +N」是当天新增星标数。\n依据:「2,271 stars today」"
        assertEquals(true, Grounding.check("GitHub Trending 前三", a, page).found)
        // The answer has no matching number: the quote must still be on screen; a made-up quote is blocked too
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("(今日 +2,271)", ""), page).found)
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("「今日 +N」", "「Top N」"), page).found)
    }

    // Observed in settings-android-version: the screen splits it across two lines, and a number embedded in letters (AQ3A) inside the quote is not treated as a separate number
    @Test fun basisQuoteAcrossTwoLinesOfOneEntryLocates() {
        val about = listOf("当前 App:com.android.settings\nAndroid 版本\n15 AQ3A.240829.003")
        val a = "安卓版本是 Android 15(系统版本号 AQ3A.240829.003)。\n依据:关于手机页显示「Android 版本：15 AQ3A.240829.003」"
        assertEquals(true, Grounding.check("安卓版本", a, about).found)
        // The parts of a quote must be in the same place: split across two reads does not count as located
        val split = listOf("当前 App:com.android.settings\nAndroid 版本\n16", "15 AQ3A.240829.003")
        assertEquals(false, Grounding.check("安卓版本", a, split).found)
    }
}
