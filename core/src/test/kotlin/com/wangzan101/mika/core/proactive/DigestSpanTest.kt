package com.wangzan101.mika.core.proactive

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The week (level 3) and month (level 4) digest parts of [DigestPool]: which span is due, the input, recording, deletion propagating upward, and how long each is kept. */
class DigestSpanTest {
    private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val utc = ZoneOffset.UTC
    private val dayMs = LearnTuning.DAY_MS
    private var n = 0
    private fun pool(data: DigestData = DigestData()) = DigestPool(data) { "d${++n}" }
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    // 2024-09-30 is a Monday; this week runs to 10-06 (Sunday) and counts as October
    private val mon = day(2024, 9, 30)
    private val nextMon = mon + 7
    private val oct = day(2024, 10, 1)
    private val nov = day(2024, 11, 1)

    private val noWait = { _: Long, _: Boolean -> false }
    private val waitIfOwed = { _: Long, owed: Boolean -> owed }
    private val noSkip = { _: Long -> false }

    /** Record a few notes for [day] (one note per notification) without making the day digest. Returns those notes. */
    private fun notes(p: DigestPool, day: Long, vararg texts: String, pkg: String = "com.chat", madeAt: Long = day * dayMs + 20 * 3_600_000L): List<DigestNote> {
        val before = p.all().map { it.id }.toSet()
        val entries = texts.indices.map { NoticeEntry(pkg, day * dayMs + 9 * 3_600_000L + it, "群", "甲", "x", keyId = "k", sig = it) }
        p.add(pkg, entries, texts.mapIndexed { i, t -> NoteSignal(listOf(i), "句:$t") }, utc, nowMs = madeAt)
        return p.all().filter { it.id !in before }
    }

    /** Record notes for [day] and make a day digest: one line per note (the text is [texts]). */
    private fun dayWith(p: DigestPool, day: Long, vararg texts: String, pkg: String = "com.chat", partial: Boolean = false, madeAt: Long = day * dayMs + 23 * 3_600_000L): List<DigestNote> {
        val added = notes(p, day, *texts, pkg = pkg, madeAt = madeAt - 1)
        val input = p.dayInput(day)
        p.setDay(day, input, input.indices.map { NoteSignal(listOf(it), input[it].text.removePrefix("句:")) }, madeAt, partial)
        return added
    }

    private fun week(p: DigestPool, start: Long, at: Long, partial: Boolean = false, pick: (List<SpanInput>) -> List<NoteSignal> = { all -> all.indices.map { NoteSignal(listOf(it), "周:" + all[it].line.text) } }) {
        val input = p.spanInput(SpanKind.Week, start, withPartial = partial)
        p.setSpan(SpanKind.Week, start, input, pick(input), at, partial)
    }

    @Test fun weeksRunMondayToSundayAndBelongToTheMonthTheyEndIn() {
        assertEquals(mon, DigestPool.weekOf(mon))
        assertEquals(mon, DigestPool.weekOf(mon + 6))
        assertEquals(nextMon, DigestPool.weekOf(mon + 7))
        assertEquals(mon - 7, DigestPool.weekOf(mon - 1))
        // 1970-01-01 is a Thursday: the Monday of its week lies before the epoch
        assertEquals(-3L, DigestPool.weekOf(0))
        assertEquals(oct, DigestPool.monthOf(day(2024, 10, 31)))
        assertEquals(nov, DigestPool.monthOf(nov))
        // The week starting 9-30 ends on 10-06: it counts as October; the week starting 10-28 ends on 11-03: it counts as November
        assertEquals(oct, DigestPool.monthOfWeek(mon))
        assertEquals(nov, DigestPool.monthOfWeek(day(2024, 10, 28)))
    }

    // Which week is due: the earliest week that has finished, has complete day digests, and has no complete week digest yet; days inside it that still owe a day digest make it wait
    @Test fun dueWeekIsTheEarliestFinishedWeekWhoseDaysAreDone() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 2, "周三的事")
        dayWith(p, nextMon, "下周一的事")
        // This week isn't over yet
        assertNull(p.dueWeek(mon + 6, waitIfOwed, noSkip))
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        // Friday has notes with no day digest yet: wait; when told not to wait (several tries failed) go ahead
        notes(p, mon + 4, "周五的事")
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(mon, p.dueWeek(nextMon, noWait, noSkip))
        // The caller can also make it wait itself (that day still has notifications without segmentation)
        assertNull(p.dueWeek(nextMon, { d, _ -> d == mon + 5 }, noSkip))
        assertNull(p.dueWeek(nextMon, noWait) { it == mon })
        // After it's done the next week is due (if that week has finished)
        week(p, mon, at = nextMon * dayMs)
        assertNull(p.dueWeek(nextMon + 6, noWait, noSkip))
        assertEquals(nextMon, p.dueWeek(nextMon + 7, noWait, noSkip))
    }

    // After the week digest was made, one of its day digests was redone (notifications were collected late): the week is redone as well, and only once
    @Test fun aWeekIsRedoneWhenOneOfItsDaysIsRedoneLater() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        week(p, mon, at = nextMon * dayMs)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重做的周一")), nextMon * dayMs + 5, partial = false)
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        week(p, mon, at = nextMon * dayMs + 9)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(listOf("周:重做的周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        // Made-at is never earlier than the level below: even if the clock is turned back, it won't keep redoing
        p.setDay(mon, p.dayInput(mon), emptyList(), nextMon * dayMs + 100, partial = false)
        week(p, mon, at = 5)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(nextMon * dayMs + 100, p.span(SpanKind.Week, mon)!!.madeAtMs)
    }

    // The "so far" version: automatic runs don't take half-finished day digests as input; once the week is over, a so-far week digest must be remade as complete
    @Test fun partialsAreOnlyForTheUserAndAreRedoneOnceThePeriodEnds() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 1, "周二到现在的", partial = true)
        assertEquals(listOf("周一的事"), p.spanInput(SpanKind.Week, mon, withPartial = false).map { it.line.text })
        assertEquals(listOf(mon to "周一的事", mon + 1 to "周二到现在的"), p.spanInput(SpanKind.Week, mon, withPartial = true).map { it.from to it.line.text })
        week(p, mon, at = (mon + 1) * dayMs + 1, partial = true)
        assertTrue(p.span(SpanKind.Week, mon)!!.partial)
        assertEquals(2, p.span(SpanKind.Week, mon)!!.inputCount)
        // Until Tuesday is over, this week still owes a day digest
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        p.setDay(mon + 1, p.dayInput(mon + 1), listOf(NoteSignal(listOf(0), "周二完整的")), (mon + 2) * dayMs, partial = false)
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        // A week with only half-finished day digests: nothing to auto-make
        val q = pool()
        dayWith(q, mon, "到现在的", partial = true)
        assertNull(q.dueWeek(nextMon, noWait, noSkip))
    }

    // Input too long: take turns across days, keeping each day's leading lines rather than only the first few days
    @Test fun longInputKeepsTheFirstLinesOfEveryDay() {
        val p = pool()
        val long = "长".repeat(280)
        for (d in 0 until 7) dayWith(p, mon + d, *(0 until 15).map { "$d-$it$long" }.toTypedArray())
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        assertTrue(input.sumOf { it.line.text.length } <= LearnTuning.SPAN_INPUT_CHARS)
        val perDay = input.groupBy { it.from }.mapValues { it.value.size }
        assertEquals((0 until 7).map { mon + it }.toSet(), perDay.keys)
        assertTrue(perDay.toString(), perDay.values.max() - perDay.values.min() <= 1)
        // What each day keeps is its leading lines, ordered by day and in the original order
        assertEquals(input.map { it.from }, input.map { it.from }.sorted())
        assertTrue(input.filter { it.from == mon }.mapIndexed { i, l -> l.line.text.startsWith("0-$i") }.all { it })
        // When it isn't long, everything fits
        val q = pool()
        dayWith(q, mon, "a", "b", "c")
        assertEquals(3, q.spanInput(SpanKind.Week, mon, withPartial = false).size)
    }

    // Each week-digest line records its grounds all the way down: which days, which notes, which apps. Lines with untraceable or vanished grounds are dropped whole
    @Test fun weekLinesKeepTheirGroundsAllTheWayDown() {
        val p = pool()
        val a = dayWith(p, mon, "周一聊天的事")
        val b = dayWith(p, mon + 2, "周三邮件的事", pkg = "com.mail")
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合起来"), NoteSignal(listOf(9), "没依据"), NoteSignal(listOf(1), " ")), 77, partial = false)
        val w = p.span(SpanKind.Week, mon)!!
        assertEquals(
            listOf(DigestLine("两天合起来", notes = (a + b).map { it.id }, apps = listOf("com.chat", "com.mail"), days = listOf(mon, mon + 2))),
            w.lines,
        )
        assertEquals(2, w.inputCount)
        assertEquals(listOf(mon), p.spans(SpanKind.Week).map { it.start })
        assertEquals(p.data(), pool(p.data()).data())
        // Wednesday's line was deleted while the request was in flight (the user deleted the notes it rested on): the line that merged it is dropped whole
        p.delete(b.single().id)
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合起来"), NoteSignal(listOf(0), "只有周一")), 78, partial = false)
        assertEquals(listOf("只有周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
    }

    /** Two weeks, each with a week digest, then combined into October's month digest (one line per week line). */
    private fun twoWeeksAndAMonth(p: DigestPool): Pair<List<DigestNote>, List<DigestNote>> {
        val first = dayWith(p, mon, "第一周聊天的事") + dayWith(p, mon + 1, "第一周邮件的事", pkg = "com.mail")
        val second = dayWith(p, nextMon, "第二周聊天的事")
        week(p, mon, at = nextMon * dayMs)
        week(p, nextMon, at = (nextMon + 7) * dayMs)
        val input = p.spanInput(SpanKind.Month, oct, withPartial = false)
        p.setSpan(SpanKind.Month, oct, input, input.indices.map { NoteSignal(listOf(it), "月:" + input[it].line.text) } +
            NoteSignal(input.indices.toList(), "月:三件事合在一起"), nov * dayMs, partial = false)
        return first to second
    }

    // Month digest: the input is the week digests of the weeks that end in this month; each line records which weeks, days, notes and apps it rests on
    @Test fun monthLinesAreBuiltFromTheWeeksThatEndInTheMonth() {
        val p = pool()
        val (first, second) = twoWeeksAndAMonth(p)
        val m = p.span(SpanKind.Month, oct)!!
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第一周邮件的事", "月:周:第二周聊天的事", "月:三件事合在一起"), m.lines.map { it.text })
        assertEquals(DigestLine("月:周:第一周邮件的事", notes = listOf(first[1].id), apps = listOf("com.mail"), days = listOf(mon + 1), weeks = listOf(mon)), m.lines[1])
        val all = m.lines[3]
        assertEquals(listOf(mon, nextMon), all.weeks)
        assertEquals(listOf(mon, mon + 1, nextMon), all.days)
        assertEquals((first + second).map { it.id }, all.notes)
        assertEquals(listOf("com.chat", "com.mail"), all.apps)
        assertEquals(p.data(), pool(p.data()).data())
    }

    // Which month is due: the earliest month that has finished, has complete week digests, and has no complete month digest yet; weeks inside it that still owe a week digest make it wait
    @Test fun dueMonthWaitsForItsWeeks() {
        val p = pool()
        dayWith(p, mon, "第一周的事")
        dayWith(p, nextMon, "第二周的事")
        week(p, mon, at = nextMon * dayMs)
        // October isn't over yet
        assertNull(p.dueMonth(day(2024, 10, 31), waitIfOwed, noSkip))
        // The second week's digest isn't made yet: wait; when told not to wait, go ahead
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
        assertNull(p.dueMonth(nov, noWait) { it == oct })
        week(p, nextMon, at = (nextMon + 7) * dayMs)
        assertEquals(oct, p.dueMonth(nov, waitIfOwed, noSkip))
        // One week in this month has only notes, not even a day digest: wait as well (once that's done, that week will owe a week digest)
        notes(p, nextMon + 7, "第三周的事")
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
        val input = p.spanInput(SpanKind.Month, oct, withPartial = false)
        p.setSpan(SpanKind.Month, oct, input, emptyList(), nov * dayMs, partial = false)
        assertNull(p.dueMonth(nov, noWait, noSkip))
        // A week was redone afterwards: this month is redone as well
        week(p, mon, at = nov * dayMs + 5)
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
    }

    // Deletion travels upward: deleting a note drops the lines at every level that rested on it; nothing else changes
    @Test fun deletingANoteRemovesEveryLineBuiltOnIt() {
        val p = pool()
        val (first, _) = twoWeeksAndAMonth(p)
        assertTrue(p.delete(first[1].id))
        assertTrue(p.day(mon + 1)!!.lines.isEmpty())
        assertEquals(listOf("周:第一周聊天的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        // No redo from this (the level below wasn't redone)
        assertNull(p.dueWeek(nov, waitIfOwed, noSkip))
        assertNull(p.dueMonth(nov + 31, waitIfOwed, noSkip))
    }

    // Deleting a day digest: week and month lines that rested on that day are dropped; deleting a week digest: month lines that rested on it are dropped
    @Test fun deletingADayOrAWeekRemovesTheLinesAbove() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.deleteDay(mon))
        assertEquals(listOf("周:第一周邮件的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周邮件的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertTrue(p.deleteSpan(SpanKind.Week, nextMon))
        assertNull(p.span(SpanKind.Week, nextMon))
        assertEquals(listOf("月:周:第一周邮件的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertFalse(p.deleteSpan(SpanKind.Week, nextMon))
        assertTrue(p.deleteSpan(SpanKind.Month, oct))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
        // Deleting a month digest leaves the levels below alone
        assertEquals(1, p.spans(SpanKind.Week).size)
    }

    // Purging an app: lines at every level that rested on it are dropped (even if they also rested on other apps)
    @Test fun purgingAnAppReachesEveryLevel() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.removePackages { it != "com.mail" })
        assertEquals(listOf("周:第一周聊天的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertFalse(p.removePackages { it != "com.mail" })
        assertTrue(p.clear())
        assertTrue(p.spans(SpanKind.Week).isEmpty() && p.spans(SpanKind.Month).isEmpty())
    }

    // Expiry doesn't travel upward: when the lower level expires, the lines above stay (they merely stop referencing notes that are gone), and nothing is redone; each level has its own lifetime
    @Test fun expiryDoesNotTravelUpward() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.expire(nextMon + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertTrue(p.all().isEmpty())
        assertEquals(4, p.span(SpanKind.Month, oct)!!.lines.size)
        assertTrue((p.days().flatMap { it.lines } + p.spans(SpanKind.Week).flatMap { it.lines } + p.spans(SpanKind.Month).flatMap { it.lines }).all { it.notes.isEmpty() })
        assertTrue(p.expire(nextMon + LearnTuning.DAYS_KEEP + 1))
        assertTrue(p.days().isEmpty())
        assertEquals(2, p.spans(SpanKind.Week).size)
        assertNull(p.dueWeek(nextMon + 100, waitIfOwed, noSkip))
        assertNull(p.dueMonth(nextMon + 100, waitIfOwed, noSkip))
        // Week digests count from the day they end and are kept WEEKS_KEEP_DAYS days; month digests count from the last day of that month and are kept MONTHS_KEEP_DAYS days
        assertFalse(p.expire(mon + 6 + LearnTuning.WEEKS_KEEP_DAYS))
        assertTrue(p.expire(mon + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1))
        assertEquals(listOf(nextMon), p.spans(SpanKind.Week).map { it.start })
        assertTrue(p.expire(nextMon + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1))
        assertEquals(4, p.span(SpanKind.Month, oct)!!.lines.size)
        val octEnd = java.time.LocalDate.ofEpochDay(oct).plusMonths(1).toEpochDay() - 1
        assertFalse(p.expire(octEnd + LearnTuning.MONTHS_KEEP_DAYS))
        assertTrue(p.expire(octEnd + LearnTuning.MONTHS_KEEP_DAYS + 1))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
    }

    // Before summarizing upward, whether today's digest must be (re)made first: today has notes but no digest, or new notes arrived after it was made
    @Test fun todayIsStaleWhenNotesCameAfterItsDigest() {
        val p = pool()
        assertFalse(p.todayStale(mon))
        notes(p, mon, "早上的事", madeAt = 100)
        assertTrue(p.todayStale(mon))
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "到现在的")), 200, partial = true)
        assertFalse(p.todayStale(mon))
        notes(p, mon, "下午的事", madeAt = 300)
        assertTrue(p.todayStale(mon))
    }

    // Saved by an older version (no weeks or months, no days/weeks on lines): still decodes
    @Test fun olderFilesStillDecode() {
        val old = """{"version":1,"notes":[],"days":[{"day":$mon,"madeAtMs":5,"lines":[{"text":"旧的一句","notes":["d1"],"apps":["com.chat"]}],"noteCount":1}]}"""
        val data = lenientJson.decodeFromString(DigestData.serializer(), old)
        val p = pool(data)
        assertEquals(DigestLine("旧的一句", listOf("d1"), listOf("com.chat")), p.day(mon)!!.lines.single())
        assertTrue(p.spans(SpanKind.Week).isEmpty())
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
    }

    // Notes expired while the week request was in flight (the ids recorded on the line are struck): the line itself is still there, so the result is recorded as usual and must not be discarded whole as "grounds gone"
    @Test fun notesExpiringDuringARequestDoNotDropTheResult() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        assertTrue(p.expire(mon + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertTrue(p.day(mon)!!.lines.single().notes.isEmpty())
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0), "周:周一的事")), 5, partial = false)
        val line = p.span(SpanKind.Week, mon)!!.lines.single()
        assertEquals("周:周一的事", line.text)
        assertTrue(line.notes.isEmpty())
        assertEquals(listOf(mon), line.days)
    }

    // Which days a month digest actually covers: from the Monday of the first week counted into it to the Sunday of the last week counted into it
    @Test fun aMonthCoversTheWeeksThatEndInIt() {
        // October 2024: 10-01 is a Tuesday, so its week starts on 9-30; 10-31 is a Thursday, its week ends on 11-03 and counts as November, so October covers up to 10-27
        assertEquals(day(2024, 9, 30)..day(2024, 10, 27), DigestPool.monthCover(oct))
        // 11-30 is a Saturday, its week ends on 12-01 and counts as December: November covers up to 11-24
        assertEquals(day(2024, 10, 28)..day(2024, 11, 24), DigestPool.monthCover(nov))
        // When the month ends on a Sunday: cover through month end; when the 1st is a Monday: start on the 1st
        assertEquals(day(2025, 8, 31), DigestPool.monthCover(day(2025, 8, 1)).last)
        assertEquals(day(2025, 9, 1), DigestPool.monthCover(day(2025, 9, 1)).first)
        // Every day falls in exactly one month, matching monthOfWeek
        for (d in day(2024, 1, 1)..day(2026, 12, 31)) {
            val m = DigestPool.monthOfWeek(DigestPool.weekOf(d))
            assertTrue("$d", d in DigestPool.monthCover(m))
        }
    }

    // ---- Once deleted, never auto-made again ----

    // Deleting a day digest: readers no longer see it; even though it still has notes, and new notes arrive later, it is never auto-made again; still so after saving and reloading
    @Test fun aDeletedDayIsNotMadeAgain() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        assertTrue(p.deleteDay(mon))
        assertFalse(p.deleteDay(mon))
        assertNull(p.day(mon))
        assertTrue(p.days().isEmpty())
        assertTrue(p.dayRemoved(mon))
        assertEquals(1, p.notes(mon).size)
        assertNull(p.dueDay(mon + 1) { true })
        // Later, notifications for that day are collected and turned into notes
        notes(p, mon, "后来补收到的", madeAt = (mon + 1) * dayMs + 5)
        assertNull(p.dueDay(mon + 1) { true })
        val back = pool(p.data())
        assertNull(back.dueDay(mon + 1) { true })
        assertTrue(back.dayRemoved(mon) && back.days().isEmpty())
        // A day that was never made has nothing to delete
        assertFalse(p.deleteDay(mon + 3))
        // The user asks for it again: it is made and the marker is gone; that one is "so far", and after the day ends it is completed as usual
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重新要的")), (mon + 1) * dayMs + 9, partial = true)
        assertFalse(p.dayRemoved(mon))
        assertEquals(listOf("重新要的"), p.day(mon)!!.lines.map { it.text })
        assertEquals(mon, p.dueDay(mon + 1) { true })
    }

    // A deleted day simply doesn't exist for its week digest: it isn't input, doesn't cause a redo, and a week left with only that day isn't recorded as "done, nothing there"
    @Test fun aDeletedDayIsInvisibleToItsWeek() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 2, "周三的事")
        week(p, mon, at = nextMon * dayMs)
        assertTrue(p.deleteDay(mon + 2))
        assertEquals(listOf("周:周一的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(listOf("周一的事"), p.spanInput(SpanKind.Week, mon, withPartial = true).map { it.line.text })
        // That day was deleted while the request was in flight: lines that rested on it aren't written back
        val q = pool()
        dayWith(q, mon, "周一的事")
        dayWith(q, mon + 2, "周三的事")
        val input = q.spanInput(SpanKind.Week, mon, withPartial = false)
        q.deleteDay(mon + 2)
        q.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合的"), NoteSignal(listOf(0), "只有周一")), nextMon * dayMs, partial = false)
        assertEquals(listOf("只有周一"), q.span(SpanKind.Week, mon)!!.lines.map { it.text })
        // The only day in the week was deleted: the week has nothing to do and owes nothing
        val r = pool()
        dayWith(r, mon, "周一的事")
        r.deleteDay(mon)
        assertNull(r.dueWeek(nextMon, waitIfOwed, noSkip))
        assertTrue(r.spanInput(SpanKind.Week, mon, withPartial = true).isEmpty())
        assertTrue(r.spans(SpanKind.Week).isEmpty())
    }

    // Deleting a week's digest: never auto-made again — even if a day inside it is redone later; still so after saving and reloading; only a fresh user request brings it back
    @Test fun aDeletedWeekIsNotMadeAgain() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        week(p, mon, at = nextMon * dayMs)
        assertTrue(p.deleteSpan(SpanKind.Week, mon))
        assertFalse(p.deleteSpan(SpanKind.Week, mon))
        assertNull(p.span(SpanKind.Week, mon))
        assertTrue(p.spans(SpanKind.Week).isEmpty())
        assertEquals(setOf(mon), p.removedSpans(SpanKind.Week))
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        // The level below was redone afterwards
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重做的周一")), nextMon * dayMs + 50, partial = false)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        val back = pool(p.data())
        assertNull(back.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(setOf(mon), back.removedSpans(SpanKind.Week))
        // A week digest that was never made has nothing to delete
        assertFalse(p.deleteSpan(SpanKind.Week, nextMon))
        // The user asks for it again
        week(p, mon, at = nextMon * dayMs + 60, partial = true)
        assertTrue(p.removedSpans(SpanKind.Week).isEmpty())
        assertEquals(listOf("周:重做的周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
    }

    // A deleted week doesn't exist for its month digest: the month doesn't wait for it, doesn't use it as input, and isn't redone because of it; a deleted month digest is never auto-made again either
    @Test fun aDeletedWeekIsInvisibleToItsMonthAndADeletedMonthStaysDeleted() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.deleteSpan(SpanKind.Week, nextMon))
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第一周邮件的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(listOf(mon, mon), p.spanInput(SpanKind.Month, oct, withPartial = true).map { it.from })
        // While the month digest hasn't been made: the deleted week doesn't make it wait indefinitely
        val q = pool()
        dayWith(q, mon, "第一周的事")
        dayWith(q, nextMon, "第二周的事")
        week(q, mon, at = nextMon * dayMs)
        week(q, nextMon, at = (nextMon + 7) * dayMs)
        q.deleteSpan(SpanKind.Week, nextMon)
        assertEquals(oct, q.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(listOf("周:第一周的事"), q.spanInput(SpanKind.Month, oct, withPartial = false).map { it.line.text })
        // The only week in the month was deleted: the month has nothing to do and isn't recorded as "done, nothing there"
        val r = pool()
        dayWith(r, mon, "第一周的事")
        week(r, mon, at = nextMon * dayMs)
        r.deleteSpan(SpanKind.Week, mon)
        assertNull(r.dueMonth(nov, noWait, noSkip))
        assertTrue(r.spanInput(SpanKind.Month, oct, withPartial = true).isEmpty())
        // Deleting the month digest: even if a week is redone later it is never auto-made again; still so after reloading
        assertTrue(p.deleteSpan(SpanKind.Month, oct))
        assertEquals(setOf(oct), p.removedSpans(SpanKind.Month))
        week(p, mon, at = nov * dayMs + 5)
        assertNull(p.dueMonth(nov, noWait, noSkip))
        assertNull(pool(p.data()).dueMonth(nov, noWait, noSkip))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
    }

    // Today's digest was deleted: asking for this week's or this month's so-far digest doesn't bring it back along the way; the marker lives as long as its level and is cleared with clear-all
    @Test fun deletedMarkersDoNotDragTodayBackAndExpireWithTheirLevel() {
        val p = pool()
        dayWith(p, mon, "今天的事", partial = true)
        p.deleteDay(mon)
        notes(p, mon, "后来的", madeAt = mon * dayMs + 23 * 3_600_000L + 5)
        assertFalse(p.todayStale(mon))
        assertTrue(p.dayRemoved(mon))
        assertTrue(p.expire(mon + LearnTuning.DAYS_KEEP + 1))
        assertFalse(p.dayRemoved(mon))
        val q = pool()
        dayWith(q, mon, "周一的事")
        week(q, mon, at = nextMon * dayMs)
        q.deleteSpan(SpanKind.Week, mon)
        q.deleteDay(mon)
        assertTrue(q.clear())
        assertTrue(q.removedSpans(SpanKind.Week).isEmpty() && !q.dayRemoved(mon))
    }


    // Week/month digest summaries: stored; deleting a day (or week) they rested on, or purging an app, also clears the summary of any digest that lost lines
    @Test fun spanBriefsAreStoredAndClearedWithTheLinesTheyDescribed() {
        fun fresh() = pool().also { p ->
            dayWith(p, mon, "周一的事")
            dayWith(p, mon + 1, "周二的事", pkg = "com.mail")
            val w = p.spanInput(SpanKind.Week, mon, withPartial = false)
            p.setSpan(SpanKind.Week, mon, w, w.indices.map { NoteSignal(listOf(it), "周:" + w[it].line.text) }, (mon + 7) * dayMs, partial = false, brief = "这一周两件事")
            val m = p.spanInput(SpanKind.Month, oct, withPartial = true)
            p.setSpan(SpanKind.Month, oct, m, m.indices.map { NoteSignal(listOf(it), "月:" + m[it].line.text) }, (mon + 8) * dayMs, partial = true, brief = "这个月两件事")
        }
        fresh().let { p ->
            assertEquals("这一周两件事", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("这个月两件事", p.span(SpanKind.Month, oct)!!.brief)
            assertEquals("这一周两件事", pool(p.data()).span(SpanKind.Week, mon)!!.brief)
        }
        // Deleting a day digest: week and month lines that rested on it are dropped and the summaries cleared
        fresh().let { p ->
            assertTrue(p.deleteDay(mon))
            assertEquals(listOf("周:周二的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // Deleting a week digest: month lines that rested on it are dropped and the summary cleared
        fresh().let { p ->
            assertTrue(p.deleteSpan(SpanKind.Week, mon))
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // Purge an app
        fresh().let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // Deleting a note: the week and month summaries covering that day are cleared even when no line is dropped because of it (e.g. the note already expired and lines no longer record it)
        fresh().let { p ->
            val extra = notes(p, mon + 2, "没进当日归纳的一句").single()
            assertTrue(p.delete(extra.id))
            assertEquals(2, p.span(SpanKind.Week, mon)!!.lines.size)
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // Fewer lines accepted than given: not kept
        fresh().let { p ->
            val w = p.spanInput(SpanKind.Week, mon, withPartial = false)
            p.setSpan(SpanKind.Week, mon, w, listOf(NoteSignal(listOf(0), "只有一句"), NoteSignal(listOf(9), "没依据")), (mon + 9) * dayMs, partial = false, brief = "两件事")
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
        }
    }
}
