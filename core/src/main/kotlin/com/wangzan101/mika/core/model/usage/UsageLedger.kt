package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Which cell a count belongs to: which provider, which model, which purpose. */
data class UsageKey(val providerId: String, val model: String, val purpose: UsagePurpose)

/** The numbers accumulated in one cell: input (including cache hits), output, cache-hit tokens, actual HTTP request count, and how many of those had no corresponding final usage. */
data class UsageCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    operator fun plus(o: UsageCounts) =
        UsageCounts(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    companion object {
        fun of(usage: Usage?, httpAttempts: Int = 1): UsageCounts {
            val calls = httpAttempts.coerceAtLeast(0).toLong()
            val withoutUsage = (calls - if (usage == null) 0 else 1).coerceAtLeast(0)
            return UsageCounts(
                usage?.inputTokens?.toLong() ?: 0,
                usage?.outputTokens?.toLong() ?: 0,
                usage?.cachedInputTokens?.toLong() ?: 0,
                calls = calls,
                callsWithoutUsage = withoutUsage,
            )
        }
    }
}

/** [hour] is the UTC hour bucket: the number of hours since 1970-01-01T00:00Z. */
data class UsageRow(val hour: Long, val key: UsageKey, val counts: UsageCounts)

/** Everything the ledger holds right now; [providerNames] is each provider's name at its most recent record (kept even after the provider is deleted). */
data class UsageSnapshot(val rows: List<UsageRow>, val providerNames: Map<String, String>) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), emptyMap())
    }
}

/**
 * The on-device token usage ledger, accumulated per UTC hour. If local midnight falls inside an hour, usage after midnight is kept
 * in a separate cell for the daily budget.
 * The local time zone is read on each use ([zone]): if the phone's time zone changes while the process is running, later cell
 * splitting and "today" follow the new zone.
 *
 * Recording ([record]) only adds to memory and is safe from several threads at once; after a change a background thread writes the
 * whole ledger out after [flushDelayMs] (temp file first, then rename, so losing power mid-write cannot corrupt the original file);
 * at most the last few seconds are lost if the process is killed.
 * Only the most recent [keepDays] days are kept; older ones are dropped on read and on write. If the file cannot be read
 * (corrupted), it starts over as an empty ledger.
 */
class UsageLedger(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushDelayMs: Long = 2_000,
    private val keepDays: Int = KEEP_DAYS,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : UsageSink {
    private data class Cell(val hour: Long, val key: UsageKey, val offsetMs: Long = 0) {
        val startMs: Long get() = hour * HOUR_MS + offsetMs
    }

    private val lock = Any()
    private val writeLock = Any()
    private val cells = HashMap<Cell, UsageCounts>()
    private val names = HashMap<String, String>()
    private var dirty = false
    private var flushQueued = false

    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-ledger").apply { isDaemon = true } }
    }

    private val _version = MutableStateFlow(0L)
    /** Incremented on every record; the UI recomputes on it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        load()
    }

    override fun record(source: UsageSource, usage: Usage?) = record(source, usage, httpAttempts = 1)

    override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) {
        // When local assembly already fails and no request was ever sent, the run record still counts a logical call, but there is no count or token for the usage ledger to record.
        if (httpAttempts <= 0 && usage == null) return
        val at = now()
        val hour = Math.floorDiv(at, HOUR_MS)
        val z = zone()
        val midnight = Instant.ofEpochMilli(at).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
        val offset = (midnight - hour * HOUR_MS).coerceAtLeast(0)
        val cell = Cell(hour, UsageKey(source.providerId, source.model, source.purpose), offset)
        val counts = UsageCounts.of(usage, httpAttempts)
        val queue = synchronized(lock) {
            cells[cell] = (cells[cell] ?: UsageCounts()) + counts
            if (source.providerName.isNotEmpty()) names[source.providerId] = source.providerName
            dirty = true
            !flushQueued.also { flushQueued = true }
        }
        _version.update { it + 1 }
        if (queue) writer.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): UsageSnapshot = synchronized(lock) {
        val hours = cells.entries.groupingBy { it.key.copy(offsetMs = 0) }
            .fold(UsageCounts()) { total, entry -> total + entry.value }
        UsageSnapshot(hours.map { (c, n) -> UsageRow(c.hour, c.key, n) }, HashMap(names))
    }

    /** Reported input plus output for this provider on the current local calendar day; proactive usage has its own budget and is not included. */
    fun providerTokensToday(providerId: String): Long =
        tokensToday { it.providerId == providerId && it.purpose != UsagePurpose.Proactive }

    /** Reported input plus output for proactive capabilities on the current local calendar day, summed across providers. */
    fun proactiveTokensToday(): Long = tokensToday { it.purpose == UsagePurpose.Proactive }

    /** The number of requests proactive capabilities actually sent on the current local calendar day, summed across providers; tokens stay 0 when a provider reports no usage, and this covers that case. */
    fun proactiveCallsToday(): Long = today({ it.purpose == UsagePurpose.Proactive }) { it.calls }

    /** Input plus output of the cells selected by [key] on the current local calendar day. */
    private fun tokensToday(key: (UsageKey) -> Boolean): Long = today(key) { it.input + it.output }

    /** Takes [amount] from each cell selected by [key] on the current local calendar day and sums them. */
    private fun today(key: (UsageKey) -> Boolean, amount: (UsageCounts) -> Long): Long {
        val z = zone()
        val today = Instant.ofEpochMilli(now()).atZone(z).toLocalDate()
        val start = today.atStartOfDay(z).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return synchronized(lock) {
            cells.entries.sumOf { (cell, counts) ->
                if (key(cell.key) && cell.startMs in start until end) amount(counts) else 0L
            }
        }
    }

    /** Writes pending changes out immediately (called periodically by the background thread; tests and shutdown may call it directly). On write failure the changes are kept for the next attempt. */
    fun flush() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                flushQueued = false
                if (!dirty) return
                dirty = false
                prune()
                serialize()
            }
            try {
                writeAtomically(text)
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                throw e
            }
        }
    }

    private fun cutoffHour(): Long = Math.floorDiv(now(), HOUR_MS) - keepDays * 24L

    private fun prune() {
        val cutoff = cutoffHour()
        cells.keys.removeAll { it.hour < cutoff }
    }

    private fun serialize(): String {
        val keys = cells.keys.map { it.key }.distinct()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        return buildJsonObject {
            put("version", FORMAT)
            putJsonObject("names") { names.forEach { (id, n) -> put(id, n) } }
            putJsonArray("keys") {
                keys.forEach { k -> addJsonObject { put("provider", k.providerId); put("model", k.model); put("purpose", k.purpose.key) } }
            }
            putJsonArray("rows") {
                cells.entries.sortedBy { it.key.hour }.forEach { (c, n) ->
                    addJsonArray {
                        add(JsonPrimitive(c.hour)); add(JsonPrimitive(index.getValue(c.key)))
                        add(JsonPrimitive(n.input)); add(JsonPrimitive(n.output)); add(JsonPrimitive(n.cached))
                        add(JsonPrimitive(n.calls)); add(JsonPrimitive(n.callsWithoutUsage))
                        if (c.offsetMs != 0L) add(JsonPrimitive(c.offsetMs))
                    }
                }
            }
        }.toString()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["names"]?.jsonObject?.forEach { (id, n) -> names[id] = n.jsonPrimitive.content }
            val keys = root["keys"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                UsageKey(
                    o.getValue("provider").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
                    UsagePurpose.of(o.getValue("purpose").jsonPrimitive.content),
                )
            }
            val cutoff = cutoffHour()
            root["rows"]?.jsonArray.orEmpty().forEach { e ->
                val r = (e as JsonArray).map { it.jsonPrimitive }
                val hour = r[0].long
                if (hour < cutoff) return@forEach
                // Older rows have no midnight split; keep their original hour-start timestamp.
                val offset = r.getOrNull(7)?.longOrNull?.takeIf { it in 0 until HOUR_MS } ?: 0L
                val cell = Cell(hour, keys[r[1].int], offset)
                val n = UsageCounts(r[2].long, r[3].long, r[4].long, r[5].long, r[6].long)
                cells[cell] = (cells[cell] ?: UsageCounts()) + n
            }
        }.onFailure {
            cells.clear()
            names.clear()
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    companion object {
        const val KEEP_DAYS = 400
        const val HOUR_MS = 3_600_000L
        private const val FORMAT = 1
    }
}
