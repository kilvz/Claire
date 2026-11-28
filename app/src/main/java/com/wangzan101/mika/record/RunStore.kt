package com.wangzan101.mika.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.run.record.RunMetricsStore
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.record.RunSummary
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "RunStore"
private const val MAX_WIDTH = 720
private const val JPEG_QUALITY = 70

/**
 * Run records on disk: under `files/runs/<yyyyMMdd-HHmmss>-<6 chars>/` there are run.json (summary), events.jsonl (one event per line),
 * and screens/NNNN.jpg (screen captures). Only the most recent [keep] runs are kept. Write errors are only logged and do not affect the task.
 * Per-run metrics ([RunMetrics]) are stored separately in `files/run-metrics.jsonl` ([metrics]) and are not removed along with the records.
 */
class RunStore internal constructor(
    filesDir: File,
    private val externalDir: () -> File?,
    private val keep: Int,
    private val clock: () -> Long,
    private val encode: (ByteArray) -> ByteArray?,
) {
    constructor(context: Context, keep: Int = 30) : this(
        context.applicationContext.filesDir,
        { context.applicationContext.getExternalFilesDir(null) },
        keep,
        System::currentTimeMillis,
        ::toJpeg,
    )

    init { require(keep > 0) { "keep must be positive" } }

    private val root = File(filesDir, "runs")

    /** Per-run metrics; recorded by the writer thread when the run record is closed (see [RunHandle]). */
    val metrics = RunMetricsStore(File(filesDir, RunMetricsStore.FILE_NAME))

    fun open(goal: String): RunHandle {
        val start = clock()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(start))
        val suffix = (1..6).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        val id = "$stamp-$suffix"
        val dir = File(root, id)
        dir.mkdirs()
        val handle = RunHandle(id, dir, RunSummary(id = id, goal = Redactor.text(goal), startedAtMs = start), encode, metrics::append)
        prune()
        return handle
    }

    /** Newest first. */
    fun list(): List<RunSummary> = runDirs().reversed().mapNotNull { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** The summary of one run; null when the record has already been cleaned up or cannot be read. */
    fun summary(runId: String): RunSummary? = dirOf(runId)?.let { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** Hands a read-only view to the core (to look up the records of earlier turns of this conversation, see history_read); reads on the IO thread. */
    fun reader(): RunRecords = object : RunRecords {
        override suspend fun summary(runId: String) = withContext(Dispatchers.IO) { this@RunStore.summary(runId) }
        override suspend fun events(runId: String) = withContext(Dispatchers.IO) { this@RunStore.events(runId) }
    }

    fun events(runId: String): List<RunEvent> {
        val f = File(dirOf(runId) ?: return emptyList(), EVENTS)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
    }

    /** Extracts the usage fields from the existing JSONL in their original order. Nulls in old records, multiple replies of the same step, and compressed events without a step number are preserved as they are. */
    fun usageLog(runId: String): String {
        val dir = dirOf(runId) ?: throw FileNotFoundException("Run is unavailable")
        return File(dir, EVENTS).useLines { lines ->
            buildString {
                for ((index, line) in lines.withIndex()) {
                    if (line.isBlank()) continue
                    val event = try {
                        RunEvent.json.parseToJsonElement(line) as? JsonObject
                    } catch (_: SerializationException) { null }
                    val type = event?.get("type") as? JsonPrimitive
                    if (event == null || type == null) {
                        // A process interruption may leave a partially written line. Mark the gap and keep the remaining events.
                        appendLine("""{"type":"unreadable_event","line":${index + 1}}""")
                        continue
                    }
                    val fields = USAGE_FIELDS[type.content] ?: continue
                    val metadata = event.filter { (key, value) ->
                        value is JsonPrimitive && (key == "type" || key == "atMs" || key in fields)
                    }
                    appendLine(JsonObject(metadata).toString())
                }
            }.ifEmpty { throw IOException("No run usage events") }
        }
    }

    /**
     * Records one further event after the run record has been closed (the outcome judged after the answer was handed over): appended to events.jsonl and counted into the run.json summary.
     * If the record has already been cleaned up or the write fails, it is only logged and does not affect the task.
     */
    @Synchronized
    fun append(runId: String, e: RunEvent) {
        val dir = dirOf(runId) ?: return
        runCatching {
            File(dir, EVENTS).appendText(RunEvent.encode(e) + "\n")
            val summaryFile = File(dir, RUN_JSON)
            val summary = RunEvent.json.decodeFromString(RunSummary.serializer(), summaryFile.readText()).with(e)
            val text = RunEvent.json.encodeToString(RunSummary.serializer(), summary)
            if (!AtomicFiles.write(summaryFile, text, durable = false, move = AtomicFiles.Move.Rename)) error("rename failed")
            if (e is RunEvent.OutcomeJudged) metrics.update(runId) { it.copy(judged = e.outcome) }
        }.onFailure { runCatching { Log.w(TAG, "could not append to run $runId", it) } }
    }

    fun screen(runId: String, id: Int): File? = dirOf(runId)?.let { File(it, screenPath(id)) }?.takeIf { it.isFile }

    /** Copies into runs/ of the external files directory so it can be pulled to a dev machine (used by debug builds and evaluation); only the most recent [keep] runs are kept there as well. */
    fun mirrorToExternal(runId: String) {
        val ext = File(externalDir() ?: return, "runs")
        if (copyTo(runId, File(ext, runId))) runCatching { dirsIn(ext).dropLast(keep).forEach { it.deleteRecursively() } }
    }

    /** Copies the whole directory of one run to [dest] (evaluation puts each task's records under its own result directory). */
    fun copyTo(runId: String, dest: File): Boolean {
        val src = dirOf(runId) ?: return false
        return runCatching { src.copyRecursively(dest, overwrite = true) }
            .onFailure { runCatching { Log.w(TAG, "could not copy run $runId", it) } }
            .getOrDefault(false)
    }

    /** Accepts only runs that actually exist under this directory, to prevent other paths from being assembled. */
    private fun dirOf(runId: String): File? =
        File(root, runId).takeIf { ID_RE.matches(runId) && it.isDirectory }

    private fun runDirs(): List<File> = dirsIn(root)

    private fun dirsIn(parent: File): List<File> =
        parent.listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun prune() {
        runDirs().dropLast(keep).forEach { it.deleteRecursively() }
    }

    companion object {
        internal const val RUN_JSON = "run.json"
        internal const val EVENTS = "events.jsonl"
        private val USAGE_FIELDS = mapOf(
            "task_started" to setOf("model"),
            "model_reply" to setOf("step", "model", "role", "tool", "inputTokens", "cachedTokens",
                "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs"),
            "compressed" to setOf("foldedSteps"),
            "model_calls" to setOf("calls", "httpCalls", "unreported", "httpUnreported", "inputTokens", "cachedTokens", "outputTokens"),
            "task_ended" to setOf("outcome", "kind"),
        )
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val ID_RE = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

        internal fun screenPath(id: Int) = "screens/%04d.jpg".format(Locale.US, id)
    }
}

/**
 * The record of one run; [close] it when the task ends.
 * Both writing to disk and compressing frames happen on one dedicated thread; [event]/[screen] only enqueue and never block the Agent loop; the metrics ([RunMetrics]) are also written and computed as it goes on this thread,
 * and once the last event is written they are handed to [finished] (only for runs that actually started);
 * when more than [MAX_PENDING_FRAMES] frames pile up, the oldest are dropped. [close] waits for the queue to be written out (at most [CLOSE_WAIT_MS]).
 */
class RunHandle internal constructor(
    val runId: String,
    private val dir: File,
    private var summary: RunSummary,
    private val encode: (ByteArray) -> ByteArray?,
    private val finished: (RunMetrics) -> Unit = {},
) : RunSink, Closeable {
    private val lock = Object()
    private val events = ArrayDeque<RunEvent>()
    private val frames = ArrayDeque<Pair<Int, ByteArray>>()
    private var closing = false
    private var sinceSummary = 0
    private val metrics = RunMetrics.Builder(runId)
    private val writer = Thread(::drain, "mika-run-$runId").apply { isDaemon = true }

    init {
        writeSummary()
        writer.start()
    }

    override fun event(e: RunEvent) {
        synchronized(lock) {
            if (closing) return
            events.addLast(e)
            lock.notifyAll()
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        synchronized(lock) {
            if (closing) return
            if (frames.size >= MAX_PENDING_FRAMES) frames.removeFirst()
            frames.addLast(mediaId to png)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            lock.notifyAll()
        }
        writer.join(CLOSE_WAIT_MS)
    }

    private fun drain() {
        while (true) {
            var batch: List<RunEvent> = emptyList()
            var frame: Pair<Int, ByteArray>? = null
            var done = false
            synchronized(lock) {
                while (events.isEmpty() && frames.isEmpty() && !closing) lock.wait()
                if (events.isEmpty() && frames.isEmpty()) {
                    writeSummary()
                    done = true
                } else {
                    batch = events.toList().also { events.clear() }
                    // Events take priority; frames go one at a time, checking back in between for new events
                    frame = if (batch.isEmpty()) frames.removeFirstOrNull() else null
                }
            }
            if (done) {
                // Metrics are built outside the lock: they go to a different file and do not hold up other threads
                metrics.build()?.let { m -> safely("metrics") { finished(m) } }
                return
            }
            batch.forEach(::write)
            frame?.let { (id, png) -> writeFrame(id, png) }
        }
    }

    private fun write(e: RunEvent) {
        // Fold into the summary before appending, so the outcome reaches run.json even if the append fails
        summary = summary.with(e)
        metrics.add(e)
        safely("event") { File(dir, RunStore.EVENTS).appendText(RunEvent.encode(e) + "\n") }
        sinceSummary += 1
        // Refresh the summary periodically so that run.json holds approximate step counts and usage if the process is killed
        if (e is RunEvent.TaskEnded || sinceSummary >= SUMMARY_EVERY) writeSummary()
    }

    private fun writeFrame(id: Int, png: ByteArray) {
        val jpg = safely("screen") { encode(png) } ?: return
        safely("screen") {
            val f = File(dir, RunStore.screenPath(id))
            f.parentFile?.mkdirs()
            f.writeBytes(jpg)
        }
    }

    private fun writeSummary() {
        sinceSummary = 0
        safely("summary") {
            val text = RunEvent.json.encodeToString(RunSummary.serializer(), summary)
            if (!AtomicFiles.write(File(dir, RunStore.RUN_JSON), text, durable = false, move = AtomicFiles.Move.Rename)) error("rename failed")
        }
    }

    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        runCatching { Log.w(TAG, "could not write run $what", e) }
        null
    }

    internal companion object {
        const val MAX_PENDING_FRAMES = 4
        const val SUMMARY_EVERY = 10
        const val CLOSE_WAIT_MS = 10_000L
    }
}

/** Reads only the dimensions first, decodes with power-of-two downsampling, then scales to width ≤ 720 at JPEG quality 70. */
private fun toJpeg(png: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
    val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scaled = if (src.width <= MAX_WIDTH) src
    else Bitmap.createScaledBitmap(src, MAX_WIDTH, (src.height.toLong() * MAX_WIDTH / src.width).toInt().coerceAtLeast(1), true)
    return try {
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } finally {
        if (scaled !== src) scaled.recycle()
        src.recycle()
    }
}
