package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.run.record.RunEvent
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Offline rescoring: rechecks recorded runs with the current [Grounding] (no model calls). Skipped when no directory is set.
 *
 *   MIKA_RESCORE_DIR=/path/to/runs ./gradlew :core:test --tests '*RescoreRecordedRuns*' --rerun
 *
 * Recursively finds every events.jsonl under the directory; for each verification (verifier event) it takes the answer from the finish it checked, paired by [RecordedVerifications] (whatever their order);
 * the evidence is all screen-read text and every string in the tool results before the verification (excluding the checked finish's own), and relative dates are resolved from the clock at that verification (the time zone comes from
 * MIKA_RESCORE_ZONE, or the local one when unset). Results go to <dir>/rescore.tsv: the run, the verdict and reason at the time, the current fallback verdict, and the facts not found.
 */
class RescoreRecordedRuns {
    @Test fun rescore() {
        val dir = (System.getenv("MIKA_RESCORE_DIR") ?: System.getProperty("mika.rescore.dir"))?.let(::File)
        assumeTrue("MIKA_RESCORE_DIR not set", dir != null && dir.isDirectory)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val rows = mutableListOf("run\tthen\tthen_reason\tnow\tnot_found")
        dir!!.walk().filter { it.name == "events.jsonl" }.sorted().forEach { f ->
            val events = f.readLines().mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
            val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal.orEmpty()
            for ((i, fi) in RecordedVerifications.pairs(events)) {
                val e = events[i] as RunEvent.Verifier
                val answer = RecordedVerifications.answer(events, fi) ?: continue
                val g = Grounding.check(goal, answer, RecordedVerifications.sources(events, i, fi),
                    java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate())
                val now = when (g.found) { true -> "found"; false -> "not_found"; null -> "nothing_checkable" }
                val thenReason = RecordedVerifications.arg(e.detailJson, "reason").orEmpty()
                rows += listOf(f.parentFile.name, e.verdict, thenReason, now, g.unmatched.joinToString(" | ") { "${it.kind}:${it.text}" })
                    .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            }
        }
        File(dir, "rescore.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
