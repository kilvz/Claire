package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One summary ([RunMetrics]) per run, one per line in [file] (JSON Lines), keeping only the most recent [keep]; run records keep only the last 30 runs,
 * and the summaries are not pruned along with them, so statistics cover more runs. One is recorded at run wrap-up and added after the outcome is judged following the handover of the answer ([update]).
 * Lines that cannot be read are skipped and a wholly corrupt file is treated as empty; a write goes to a temporary file in full and then replaces the old one, so a failed write loses only this entry.
 * Several instances may exist for the same file (one per page, one per run); reads and writes share the same in-process lock.
 */
class RunMetricsStore(private val file: File, private val keep: Int = DEFAULT_KEEP) {
    init { require(keep > 0) { "keep must be positive" } }

    /** Oldest first. */
    fun all(): List<RunMetrics> = synchronized(LOCK) { read() }

    /**
     * The summaries of these runs, keyed by run id; runs that are not there are absent. Only lines containing one of these ids are parsed (the id is checked again after parsing),
     * instead of parsing the whole file (at most [keep] entries).
     */
    fun byIds(runIds: Set<String>): Map<String, RunMetrics> = synchronized(LOCK) {
        val ids = runIds.filter { it.isNotEmpty() }
        if (ids.isEmpty() || !file.isFile) return@synchronized emptyMap()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.asSequence()
            .filter { line -> ids.any { line.contains(it) } }
            .mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
            .filter { it.runId in runIds }
            .associateBy { it.runId }
    }

    /** The most recent [n] (oldest first); all of them when [n] is null. */
    fun recent(n: Int?): List<RunMetrics> = all().let { if (n == null) it else it.takeLast(n) }

    /** Records one; an existing entry for the same run is replaced. */
    fun append(m: RunMetrics) = synchronized(LOCK) {
        write(read().filter { m.runId.isEmpty() || it.runId != m.runId } + m)
    }

    /** Changes the entry of one run; does nothing if it is absent. */
    fun update(runId: String, change: (RunMetrics) -> RunMetrics) = synchronized(LOCK) {
        val list = read()
        if (list.none { it.runId == runId }) return@synchronized
        write(list.map { if (it.runId == runId) change(it) else it })
    }

    private fun read(): List<RunMetrics> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.filter { it.isNotBlank() }.mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
    }

    private fun write(list: List<RunMetrics>) {
        runCatching {
            file.parentFile?.mkdirs()
            AtomicFiles.write(file, list.takeLast(keep).joinToString("") { JSON.encodeToString(RunMetrics.serializer(), it) + "\n" }, durable = false)
        }
    }

    companion object {
        const val DEFAULT_KEEP = 500
        const val FILE_NAME = "run-metrics.jsonl"
        private val LOCK = Any()
        val JSON = Json { ignoreUnknownKeys = true }

        /** For export and human analysis: a single JSON array, oldest first. */
        fun export(list: List<RunMetrics>): String = EXPORT.encodeToString(ListSerializer(RunMetrics.serializer()), list)

        private val EXPORT = Json { encodeDefaults = true }
    }
}
