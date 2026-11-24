package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestPoolTest {
    private val utc = ZoneOffset.UTC
    private val day = LearnTuning.DAY_MS
    private val t0 = 20_000 * day + 9 * 3_600_000L
    private var n = 0
    private fun pool(data: DigestData = DigestData()) = DigestPool(data) { "d${++n}" }
    private fun entry(at: Long, title: String = "家长群", pkg: String = "com.chat") = NoticeEntry(pkg, at, title, "王老师", "x", keyId = "k", sig = at.toInt())

    @Test fun notesCarryTheirGroundsNotTheText() {
        val p = pool()
        val entries = listOf(entry(t0), entry(t0 + 60_000), entry(t0 + 120_000, title = "另一个群"))
        assertEquals(2, p.add("com.chat", entries, listOf(NoteSignal(listOf(0, 1), "王老师通知明天开会"), NoteSignal(listOf(1, 2, 9), "两个群都在说春游")), utc))
        val (a, b) = p.notes(20_000)
        assertEquals(DigestNote("d1", "com.chat", 20_000, t0, t0 + 60_000, 2, "家长群", "王老师通知明天开会"), a)
        // The referenced messages aren't from the same conversation: no conversation name is written
        assertNull(b.title)
        assertEquals(3 - 1, b.count)
        // No grounds and no text: not recorded
        assertEquals(0, p.add("com.chat", entries, listOf(NoteSignal(listOf(7), "x"), NoteSignal(listOf(0), " ")), utc))
    }

    // The day comes from the newest referenced notification; the conversation name goes through the same checks as a contact display name (phone numbers and URLs are not stored as names)
    @Test fun dayAndTitle() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0 + day - 9 * 3_600_000L - 1), entry(t0 + day)), listOf(NoteSignal(listOf(0, 1), "跨了午夜")), utc)
        assertEquals(20_001L, p.all().single().day)
        p.add("com.sms", listOf(entry(t0, title = "13812345678", pkg = "com.sms")), listOf(NoteSignal(listOf(0), "有人发来短信")), utc)
        assertNull(p.all().last().title)
    }

    @Test fun purgeExpireAndRoundTrip() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "a")), utc)
        p.add("com.mail", listOf(entry(t0 + day, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "b")), utc)
        assertEquals(p.all(), pool(p.data()).all())
        assertFalse(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS))
        assertTrue(p.expire(20_001 + LearnTuning.NOTES_KEEP_DAYS))
        assertEquals(listOf("b"), p.all().map { it.text })
        assertTrue(p.removePackages { it != "com.mail" })
        assertTrue(p.all().isEmpty())
        assertFalse(p.clear())
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "c")), utc)
        assertTrue(p.delete(p.all().single().id))
    }

    private fun seeded(): DigestPool = pool().also { p ->
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "聊天的事")), utc)
        p.add("com.mail", listOf(entry(t0 + 60_000, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "邮件的事")), utc)
        p.add("com.chat", listOf(entry(t0 + day)), listOf(NoteSignal(listOf(0), "第二天的事")), utc)
    }

    // Which day is due: the earliest day before today that has notes, has no complete digest yet, and whose notifications have all been segmented
    @Test fun dueDayIsTheEarliestSettledPastDayWithoutAFullDigest() {
        val p = seeded()
        assertNull(p.dueDay(20_000) { true })
        assertEquals(20_000L, p.dueDay(20_001) { true })
        assertNull(p.dueDay(20_001) { false })
        assertEquals(20_000L, p.dueDay(20_005) { true })
        // The "so far" version doesn't count as done; after a complete one the next day is due; a day with no lines also counts as done
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "x")), t0, partial = true)
        assertEquals(20_000L, p.dueDay(20_005) { true })
        p.setDay(20_000, p.dayInput(20_000), emptyList(), t0, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        assertEquals(emptyList<DigestLine>(), p.day(20_000)!!.lines)
        // Notifications that day still lack segmentation: wait, don't skip ahead to the next day
        assertNull(p.dueDay(20_005) { it != 20_001L })
    }

    // Each day-digest line records the notes it rests on and which apps they came from; lines that can't be traced to notes are not recorded
    @Test fun dayLinesKeepTheirGrounds() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "两件事"), NoteSignal(listOf(7), "没依据"), NoteSignal(listOf(1), " ")), t0 + 5, partial = false)
        val d = p.day(20_000)!!
        assertEquals(listOf(DigestLine("两件事", input.map { it.id }, listOf("com.chat", "com.mail"))), d.lines)
        assertEquals(2, d.noteCount)
        assertEquals(t0 + 5, d.madeAtMs)
        assertEquals(listOf(20_000L), p.days().map { it.day })
        assertEquals(p.data(), pool(p.data()).data())
    }

    // An app that is no longer summarized: its notes go, and so do the day-digest lines that drew on it (even if they also drew on other apps)
    @Test fun purgingAnAppRemovesTheDayLinesThatDrewOnIt() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "两个 App 的"), NoteSignal(listOf(1), "只有邮件的")), t0, partial = false)
        assertTrue(p.removePackages { it != "com.chat" })
        assertEquals(listOf("只有邮件的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(listOf("邮件的事"), p.all().map { it.text })
        assertFalse(p.removePackages { it != "com.chat" })
    }

    @Test fun daysExpireAndCanBeDeleted() {
        val p = seeded()
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "x")), t0, partial = false)
        assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertEquals(1, p.days().size)
        assertTrue(p.expire(20_000 + LearnTuning.DAYS_KEEP + 1))
        assertTrue(p.days().isEmpty())
        p.setDay(20_010, emptyList(), emptyList(), t0, partial = false)
        assertTrue(p.deleteDay(20_010))
        assertFalse(p.deleteDay(20_010))
        p.setDay(20_011, emptyList(), emptyList(), t0, partial = false)
        assertTrue(p.clear())
        assertTrue(p.days().isEmpty())
    }

    // On unlock yesterday's digest was made first, and last night's notifications were collected only afterwards and turned into notes: that day must be redone, and only once
    @Test fun aDayIsRedoneWhenNotesAreWrittenAfterItsDigest() {
        val p = seeded()
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "早上做的")), 1_000, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        p.add("com.chat", listOf(entry(t0 + 3_600_000)), listOf(NoteSignal(listOf(0), "后来才补收到的")), utc, nowMs = 2_000)
        assertEquals(20_000L, p.dueDay(20_005) { true })
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(2), "重做的")), 3_000, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        // Even with more notes than fit in one request it doesn't keep redoing (what matters is whether new notes were written, not whether the count adds up)
        val busy = pool()
        repeat(LearnTuning.DAY_NOTES_MAX + 5) { i -> busy.add("com.chat", listOf(entry(t0 + i)), listOf(NoteSignal(listOf(0), "n$i")), utc, nowMs = 10) }
        busy.setDay(20_000, busy.dayInput(20_000), emptyList(), 20, partial = false)
        assertNull(busy.dueDay(20_005) { true })
    }

    // Retention works the same at all four levels: keep N days counted from the last day it covers, drop it on day N+1
    @Test fun everyLevelIsKeptTheSameWayCountedFromItsLastDay() {
        val today = 20_000L
        fun line() = listOf(DigestLine("x", apps = listOf("com.chat")))
        val week = DigestPool.weekOf(today - 300)
        val month = DigestPool.monthOf(today - 800)
        val monthEnd = java.time.LocalDate.ofEpochDay(month).plusMonths(1).toEpochDay() - 1
        val data = DigestData(
            notes = listOf(DigestNote("n", "com.chat", today, 1L, 1L, 1, text = "x", madeAtMs = 1L)),
            days = listOf(DayDigest(today, 1L, line())),
            weeks = listOf(SpanDigest(week, 1L, line())),
            months = listOf(SpanDigest(month, 1L, line())),
        )
        // Each level: still there N days after its last day, gone on day N+1
        fun alive(at: Long) = pool(data).also { it.expire(at) }
        assertEquals(1, alive(today + LearnTuning.NOTES_KEEP_DAYS).all().size)
        assertEquals(0, alive(today + LearnTuning.NOTES_KEEP_DAYS + 1).all().size)
        assertEquals(1, alive(today + LearnTuning.DAYS_KEEP).days().size)
        assertEquals(0, alive(today + LearnTuning.DAYS_KEEP + 1).days().size)
        assertEquals(1, alive(week + 6 + LearnTuning.WEEKS_KEEP_DAYS).spans(SpanKind.Week).size)
        assertEquals(0, alive(week + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1).spans(SpanKind.Week).size)
        // Month digests count from the last day of that month (counting from the first day would drop them almost a month early)
        assertEquals(1, alive(monthEnd + LearnTuning.MONTHS_KEEP_DAYS).spans(SpanKind.Month).size)
        assertEquals(0, alive(monthEnd + LearnTuning.MONTHS_KEEP_DAYS + 1).spans(SpanKind.Month).size)
        assertEquals(1, alive(month + LearnTuning.MONTHS_KEEP_DAYS + 1).spans(SpanKind.Month).size)
    }

    // A day with more notes than fit in one request: take turns across apps, keeping the newest for each app (a chatty app doesn't crowd the others out), and count what didn't fit;
    // a nonzero count doesn't mean a redo is due (that would redo forever)
    @Test fun aDayWithTooManyNotesKeepsEveryAppAndCountsWhatWasLeftOut() {
        val p = pool()
        val max = LearnTuning.DAY_NOTES_MAX
        // Morning: one line each from the other apps; afterwards the chat app writes more notes than the cap in one go
        p.add("com.mail", listOf(entry(t0, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "上午的邮件")), utc, nowMs = 10)
        p.add("com.bank", listOf(entry(t0 + 1, pkg = "com.bank")), listOf(NoteSignal(listOf(0), "上午的到账")), utc, nowMs = 10)
        repeat(max + 10) { i -> p.add("com.chat", listOf(entry(t0 + 100 + i)), listOf(NoteSignal(listOf(0), "聊天 $i")), utc, nowMs = 10) }
        val input = p.dayInput(20_000)
        assertEquals(max, input.size)
        assertTrue(input.any { it.text == "上午的邮件" } && input.any { it.text == "上午的到账" })
        // The chat app keeps the newer ones; the whole input is ordered oldest to newest
        assertTrue(input.any { it.text == "聊天 ${max + 9}" })
        assertFalse(input.any { it.text == "聊天 0" })
        assertEquals(input.sortedBy { it.fromMs }, input)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "这一天")), 20, partial = false)
        assertEquals(12, p.day(20_000)!!.leftOut)
        assertEquals(max, p.day(20_000)!!.noteCount)
        assertNull(p.dueDay(20_005) { true })
        // A day that fits: nothing left out
        val small = seeded()
        small.setDay(20_000, small.dayInput(20_000), emptyList(), 20, partial = false)
        assertEquals(0, small.day(20_000)!!.leftOut)
    }

    // Span (week/month) input that doesn't fit is counted the same way
    @Test fun aSpanRecordsHowManyLowerLinesDidNotFit() {
        val long = "长".repeat(LearnTuning.SPAN_INPUT_CHARS / 2 - 10)
        val week = DigestPool.weekOf(20_000)
        val p = pool(DigestData(days = (0..2).map { d -> DayDigest(week + d, 1L, listOf(DigestLine("$d$long", apps = listOf("com.chat")))) }))
        val input = p.spanInput(SpanKind.Week, week, withPartial = false)
        assertEquals(2, input.size)
        p.setSpan(SpanKind.Week, week, input, listOf(NoteSignal(listOf(0), "这一周")), 5, partial = false)
        assertEquals(1, p.spans(SpanKind.Week).single().leftOut)
        // All of the week's lines fit, but one day's own digest left out 7 lines: the week still isn't seen in full; the month digest carries that count upward
        val month = DigestPool.monthOfWeek(week)
        val q = pool(DigestData(days = listOf(
            DayDigest(week, 1L, listOf(DigestLine("周一", apps = listOf("com.chat"))), leftOut = 7),
            DayDigest(week + 1, 1L, listOf(DigestLine("周二", apps = listOf("com.chat")))),
        )))
        q.setSpan(SpanKind.Week, week, q.spanInput(SpanKind.Week, week, withPartial = false), listOf(NoteSignal(listOf(0, 1), "这一周")), 5, partial = false)
        assertEquals(7, q.spans(SpanKind.Week).single().leftOut)
        q.setSpan(SpanKind.Month, month, q.spanInput(SpanKind.Month, month, withPartial = false), listOf(NoteSignal(listOf(0), "这个月")), 6, partial = false)
        assertEquals(7, q.spans(SpanKind.Month).single().leftOut)
    }

    // An app whose lines only survive in week/month digests (its day-level notes have expired): purging it drops those lines too, while other apps' lines stay
    @Test fun purgingAnAppAlsoDropsWeekAndMonthLinesThatCiteOnlyThem() {
        val p = pool(DigestData(
            weeks = listOf(SpanDigest(19_999, 1L, listOf(DigestLine("聊天的一周", apps = listOf("com.chat")), DigestLine("邮件的一周", apps = listOf("com.mail"))))),
            months = listOf(SpanDigest(19_980, 1L, listOf(DigestLine("两个合在一起的", apps = listOf("com.chat", "com.mail"))))),
        ))
        assertTrue(p.removePackages { it != "com.chat" })
        assertEquals(listOf("邮件的一周"), p.spans(SpanKind.Week).single().lines.map { it.text })
        assertTrue(p.spans(SpanKind.Month).single().lines.isEmpty())
        assertFalse(p.removePackages { it != "com.chat" })
    }

    // An app was purged while the request was in flight: the whole line that merged it is dropped (its text still mentions it), not just struck from the grounds
    @Test fun aLineThatDrewOnAPurgedNoteIsDroppedWhole() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.removePackages { it != "com.mail" }
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "聊天和邮件合在一起的"), NoteSignal(listOf(0), "只有聊天的")), t0, partial = false)
        assertEquals(listOf("只有聊天的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(listOf("com.chat"), p.day(20_000)!!.lines.single().apps)
    }

    // The user deletes a note: day-digest lines that drew on it go too (even if they also drew on other notes); other lines stay, and the day isn't redone because of it
    @Test fun deletingANoteRemovesTheDayLinesBuiltOnIt() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0), entry(t0 + 1), entry(t0 + 2)),
            listOf(NoteSignal(listOf(0), "第一句"), NoteSignal(listOf(1), "第二句"), NoteSignal(listOf(2), "第三句")), utc, nowMs = t0 + 10)
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "合了一二"), NoteSignal(listOf(2), "只有三")), t0 + 20, partial = false)
        assertTrue(p.delete(input[0].id))
        assertEquals(listOf("只有三"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(2, p.notes(20_000).size)
        assertNull(p.dueDay(20_001) { true })
        // No such note: nothing changes
        assertFalse(p.delete("nope"))
    }


    // ---- One-line summary of a digest ----

    // Stored and read back; older saves lack this field, so it reads back empty
    @Test fun theBriefIsStoredWithTheDigestAndSurvivesARoundTrip() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "聊天和邮件各一件")
        assertEquals("聊天和邮件各一件", p.day(20_000)!!.brief)
        assertEquals("聊天和邮件各一件", pool(p.data()).day(20_000)!!.brief)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(DigestData.serializer(), """{"days":[{"day":20000,"madeAtMs":1,"lines":[{"text":"x"}]}]}""")
        assertEquals("", pool(old).day(20_000)!!.brief)
        // Not given, or not given on a redo: empty (the previous one isn't kept)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的")), t0 + 1, partial = false)
        assertEquals("", p.day(20_000)!!.brief)
    }

    // Fewer lines accepted than given (grounds gone or untraceable), or no lines at all: the summary is not kept
    @Test fun theBriefIsNotKeptWhenALineWasNotStored() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(7), "没依据")), t0, partial = false, brief = "两件事")
        assertEquals("", p.day(20_000)!!.brief)
        p.setDay(20_000, input, emptyList(), t0, partial = false, brief = "没什么事")
        assertEquals("", p.day(20_000)!!.brief)
        // The app was purged while the request was in flight: the line that drew on it is dropped whole, and so is the summary
        p.removePackages { it != "com.mail" }
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "聊天和邮件")
        assertEquals(listOf("聊天的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals("", p.day(20_000)!!.brief)
    }

    // Lines in this digest were removed: its summary is cleared too (deleted things must not linger in a summary); untouched digests keep theirs
    @Test fun removingALineClearsThatDigestsBrief() {
        fun fresh() = seeded().also { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "头一天")
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的")), t0 + day, partial = false, brief = "第二天")
        }
        // Delete a note
        fresh().let { p ->
            assertTrue(p.delete(p.notes(20_000).first().id))
            assertEquals(listOf("邮件的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // Purge an app
        fresh().let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // The deleted note wasn't referenced by any stored line (the extras the model wrote but that weren't stored may have cited it): the summary is cleared anyway
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            val mail = p.notes(20_000).single { it.packageName == "com.mail" }
            assertTrue(p.delete(mail.id))
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
        }
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(1, p.day(20_000)!!.lines.size)
            assertEquals("", p.day(20_000)!!.brief)
        }
        // A note was deleted while the request was in flight; the reply's stored lines lost none (they all reference the surviving note), yet the summary was written against all of them: reject it
        seeded().let { p ->
            val sent = p.dayInput(20_000)
            assertTrue(p.delete(sent.single { it.packageName == "com.mail" }.id))
            val chat = sent.indexOfFirst { it.packageName == "com.chat" }
            p.setDay(20_000, sent, listOf(NoteSignal(listOf(chat), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
        }
        // A day digest was deleted while the week request was in flight: likewise no summary (the same applies when a week digest is deleted while the month request is in flight)
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "第一天的事")), t0, partial = false)
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的事")), t0 + LearnTuning.DAY_MS, partial = false)
            val week = DigestPool.weekOf(20_000)
            val sent = p.spanInput(SpanKind.Week, week, true)
            assertEquals(2, sent.size)
            assertTrue(p.deleteDay(20_000))
            val kept = sent.indexOfFirst { it.line.text == "第二天的事" }
            p.setSpan(SpanKind.Week, week, sent, listOf(NoteSignal(listOf(kept), "只写了第二天")), t0 + 2 * LearnTuning.DAY_MS, partial = true, brief = "两天的事")
            assertEquals(listOf("只写了第二天"), p.span(SpanKind.Week, week)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, week)!!.brief)
        }
        // Purging an app after its notes expired: which apps appeared on those days can no longer be looked up, and the stored lines don't cite it either (the extras the model wrote did).
        // The summary records which apps' lines it was written from, so it is cleared anyway; other apps are unaffected
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的")), t0 + day, partial = false, brief = "第二天")
            assertEquals(listOf("com.chat", "com.mail"), p.day(20_000)!!.briefApps)
            assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 2))
            assertTrue(p.all().isEmpty())
            assertFalse("和这几份都无关的 App:什么都不动", p.removePackages { it != "com.other" })
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // Week-digest summaries record their sources too
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false)
            val week = DigestPool.weekOf(20_000)
            val sent = p.spanInput(SpanKind.Week, week, true)
            val chat = sent.indexOfFirst { it.line.apps == listOf("com.chat") }
            p.setSpan(SpanKind.Week, week, sent, listOf(NoteSignal(listOf(chat), "只写了聊天的")), t0 + day, partial = true, brief = "聊天和邮件")
            // No stored week line cites mail; the day-level line that does will be dropped, so the week summary is cleared by its sources
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(listOf("只写了聊天的"), p.span(SpanKind.Week, week)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, week)!!.brief)
        }
        // A summary with no recorded sources (saved before this field existed): cleared whenever any app is purged
        pool(DigestData(days = listOf(DayDigest(20_000, t0, listOf(DigestLine("聊天的", apps = listOf("com.chat"))), brief = "以前存下的概述")))).let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals(1, p.day(20_000)!!.lines.size)
        }
        // Expiry of notes only strikes the grounds recorded on lines; the lines remain, so the summary stays
        fresh().let { p ->
            assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 1))
            assertEquals(2, p.day(20_000)!!.lines.size)
            assertEquals("头一天", p.day(20_000)!!.brief)
        }
    }

    // ---- Auto-making today's "so far" digest ----

    /** Record [n] notes for today (20_000), all written at [madeAt]. */
    private fun today(p: DigestPool, n: Int, madeAt: Long) {
        val entries = (0 until n).map { entry(t0 + it) }
        p.add("com.chat", entries, entries.indices.map { NoteSignal(listOf(it), "第 ${p.all().size + it + 1} 句") }, utc, nowMs = madeAt)
    }

    // Auto-make only once enough notes have gathered, or two or more have gathered and they've waited long enough; a single note never triggers it
    @Test fun todaysDigestIsDueOnceEnoughNotesHaveGatheredOrWaited() {
        val hour = 3_600_000L
        val p = pool()
        assertFalse(p.todayAutoDue(20_000, t0))
        today(p, 1, t0)
        assertFalse("只有一句", p.todayAutoDue(20_000, t0 + 10 * hour))
        today(p, 1, t0 + hour)
        assertFalse("两句,最早的才等了一个多小时", p.todayAutoDue(20_000, t0 + LearnTuning.TODAY_AUTO_WAIT_MS - 1))
        assertTrue("两句,最早的等够了", p.todayAutoDue(20_000, t0 + LearnTuning.TODAY_AUTO_WAIT_MS))
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES - 2, t0 + hour)
        assertTrue("够数了就不用等", p.todayAutoDue(20_000, t0 + hour))
        // Notes from other days don't count as today's
        assertFalse(p.todayAutoDue(20_001, t0 + 10 * hour))
    }

    // After one digest exists: there must be new notes, and enough time since the last one
    @Test fun afterOneDigestTheNextWaitsForNewNotesAndTheInterval() {
        val hour = 3_600_000L
        val p = pool()
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES, t0)
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "到现在为止")), t0 + hour, partial = true)
        assertFalse("没有新句子", p.todayAutoDue(20_000, t0 + 20 * hour))
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES, t0 + 2 * hour)
        assertFalse("有新句子,但离上一份还不够久", p.todayAutoDue(20_000, t0 + hour + LearnTuning.TODAY_AUTO_INTERVAL_MS - 1))
        assertTrue(p.todayAutoDue(20_000, t0 + hour + LearnTuning.TODAY_AUTO_INTERVAL_MS))
        // The user deleted today's: it isn't auto-made again
        assertTrue(p.deleteDay(20_000))
        assertFalse(p.todayAutoDue(20_000, t0 + 20 * hour))
    }
}
