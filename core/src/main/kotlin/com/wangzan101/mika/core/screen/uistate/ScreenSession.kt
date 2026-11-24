package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.visual.FrameDiff
import com.wangzan101.mika.core.screen.visual.FrameGrid
import com.wangzan101.mika.core.screen.visual.Recall
import com.wangzan101.mika.core.screen.visual.Shots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import com.wangzan101.mika.core.screen.vision.LocateRegistry
import com.wangzan101.mika.core.screen.vision.Locator
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.screen.observation.InputHints
import com.wangzan101.mika.core.screen.observation.Look
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.HINT_PROTECTED
import com.wangzan101.mika.core.tool.HINT_DISPLAY_CAPTURE
import com.wangzan101.mika.core.tool.MAX_READ_CHARS
import com.wangzan101.mika.core.tool.NOTE_CHANGED_DURING_READ
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.net.UrlHost

internal const val MAX_ELEMENTS = 200
/** Maximum number of diffs chained after one full screen read. */
private const val MAX_CHAIN_DIFFS = 5
/** Upper bound on waiting for the interface to settle after a write operation and before reading the screen. */
private const val INLINE_SETTLE_MS = 1500L
/** OCR supplements the screen read and must not stall the next model call indefinitely. */
private const val OCR_WAIT_MS = 2000L
/** When the tree is not enough, on-screen text is the main material: wait longer (the port first waits for the picture to settle before recognizing). */
private const val OCR_WAIT_POOR_MS = 5000L
/** Irreversibility checks need to find an element by the screen read the ref belongs to, so keep the most recent few. */
private const val RECENT_SNAPSHOTS = 8
/** How many issued snapshotIds to remember. */
private const val ISSUED_MEMORY = 256
/** Maximum length kept for the "limit reached" sentence in results. */
private const val LIMIT_TEXT_MAX = 120
/** Wait this long and read once more when the very first read yields an empty tree: a window just switched away, a redraw in progress, or accessibility not having emitted nodes yet can all leave this screen temporarily empty. */
private const val BLANK_RETRY_MS = 300L

/** A point found by find_visually: what was looked for, the text given, the words the vision model saw; irreversibility checks consider all of them. [unverified]: based on looking at the picture only, with no corroboration. */
internal class Located(val target: String, val text: String?, val seen: String?, val unverified: Boolean = false)

/**
 * Screen-read state of one screen: stable refs, the screen and diff chain the model last received, the most recent
 * screen reads, and the points found visually. One per screen; that set of screen tools only uses its own copy;
 * screenshot ids and the recall of recent screens are shared ([shots]).
 */
internal class ScreenSession(
    val surface: Surface,
    stableRefs: Boolean,
    private val visionEnabled: Boolean,
    private val recordFrames: Boolean,
    /** The interface text read most recently (shared across screens); after an action, text identical to it is not recorded again. */
    private val lastRead: () -> String?,
    val shots: Shots,
    vision: VisionService? = null,
    private val adaptiveVision: Boolean = false,
) {
    /** Phone model results are not truncated by element count; the web still uses its own output budget. */
    private val modelElementsLimit get() = if (surface.id == "phone") Int.MAX_VALUE else MAX_ELEMENTS
    val screen: ScreenPort get() = surface.screen
    private var textOnlyDepth = 0

    /** Inside composite phone commands only text and state are kept; images are requested by the model when needed. */
    suspend fun <T> textOnly(block: suspend () -> T): T {
        textOnlyDepth++
        try { return block() } finally { textOnlyDepth-- }
    }

    val stabilizer = if (stableRefs) RefStabilizer(surface.family.snapshotPrefix) else null
    /** The screen the model received most recently (observe, or the screen in a write result); the base for diffs. */
    private var shown: ModelSnapshot? = null
    /** snapshotIds of the latest full screen read and each diff after it: a diff can only be understood through the whole chain, so a full read is sent when one link is missing. */
    private val chain = mutableListOf<String>()
    /** Screen-read snapshotIds the model can still see right now; null means the loop has not reported yet (all visible). */
    var visible: Set<String>? = null
    var lastSnapshot: ScreenSnapshot? = null
    val recentSnapshots = ArrayDeque<ScreenSnapshot>()
    val readAt = mutableMapOf<String, Long>()
    /** snapshotIds this screen has issued (longer than [RECENT_SNAPSHOTS]: the model may use a much older ref). */
    private val issued = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ISSUED_MEMORY
    }

    /** Points found by find_visually on this screen (@vN); cleared after any write operation on this screen. */
    val located = LocateRegistry()
    val locatedInfo = mutableMapOf<String, Located>()
    /** describe has been looked at since the last write operation: coordinate taps in that window are treated as points obtained from the picture. */
    var described = false
    val locator = vision?.let { Locator(screen, it, located, surface.family) }

    fun clearLocated() { located.clear(); locatedInfo.clear(); described = false }

    private var accessRevision = screen.appAccess.current().revision

    suspend fun checkAppAccess(current: Boolean = true) {
        val state = screen.appAccess.current()
        if (state.revision != accessRevision) {
            clearLocated()
            clearPrimed()
            lastSnapshot = null
            recentSnapshots.clear()
            readAt.clear()
            shown = null
            chain.clear()
            stale = true
            accessRevision = state.revision
        }
        if (current) screen.checkAppAccess()
    }

    /** This snapshotId (of a screen read, or of a point found visually) was issued by this screen. */
    fun owns(snapshotId: String): Boolean = snapshotId in issued || located.has(snapshotId)

    /** How many times this screen has been read (reads that register refs); used when judging effects to know whether a read happened after the action. */
    var reads = 0
        private set

    /**
     * The latest screen in the model's hands has gone stale: the interface changed after the action, and the result
     * did not carry the screen from after the action. Stays true until the next screen read.
     */
    var stale = false
        private set

    /** Claire itself touched this screen (a gentle scroll while dwelling): the positions in the model's copy are no longer accurate, so mark it stale. */
    fun movedByMika() { stale = true }

    /**
     * Read one screen and register refs. [internal]: not for the model (when locating a target from a description), so whether the model's copy went stale is unchanged;
     * the screen exactly as the port returned it is kept as the "before" for the following action ([probe] uses it instead of reading again).
     * When the first read yields an empty tree, wait [BLANK_RETRY_MS] and read again, using the attempt that got content: a momentary failure to read should not mean this screen is really blank.
     */
    suspend fun snapshot(internal: Boolean = false): ScreenSnapshot = screen.snapshot().let { first ->
        // Empty tree on the first read: wait a moment and read again; only hand it to the verdict if both reads are empty. A momentary miss should not get the whole screen judged unusable and push the model to whole-page OCR.
        val second = if (blankTree(first)) {
            delay(BLANK_RETRY_MS)
            screenAttempt { screen.snapshot() }
        } else null
        (if (second != null && !blankTree(second)) second else first).let { p ->
            (stabilizer?.next(p)?.screen ?: p).also { primed = if (internal) it.snapshotId to p else null }
        }
    }.also {
        reads++
        if (!internal) stale = false
        lastSnapshot = it
        recentSnapshots.addLast(it)
        readAt[it.snapshotId] = System.currentTimeMillis()
        issued[it.snapshotId] = Unit
        while (recentSnapshots.size > RECENT_SNAPSHOTS) recentSnapshots.removeFirst().snapshotId.let { id -> readAt.remove(id) }
    }

    /** Not a single named element in this screen's tree: most likely just switched away / redrawing / nodes not emitted yet, so it is worth reading once more. */
    private fun blankTree(s: ScreenSnapshot): Boolean {
        if (s.protected) return false
        // Do not retry when the window cannot be read (unreadableApp): that is a persistent state at the accessibility service or permission level, not nodes yet to be emitted, and re-reading will not help;
        // treating it as a momentary problem would waste one extra read on every screen read of this App.
        if (s.unreadableApp != null) return false
        if (!s.hasWindow) return false
        val named = s.elements.count { it.name.isNotBlank() }
        return s.elements.isEmpty() || (named == 0 && s.elements.size <= 1)
    }

    /** Read the screen again right now without registering refs or changing the model's screen read; null when unreadable. */
    suspend fun peek(): ScreenSnapshot? = screenAttempt { screen.peek() }

    suspend fun observe(withImage: Boolean, adaptiveImage: Boolean = false, recordFrame: Boolean = true): ToolExecution {
        val s = snapshot()
        val (tree, ocr, ocrStatus) = readTree(s)
        val imageReason = if (adaptiveImage && tree.poor) tree.reason else null
        val sendImage = withImage || imageReason != null
        stabilizer?.latest?.let { shown = it; chain.clear(); chain += it.snapshotId }
        val seen = interfaceRead(s, ocr.orEmpty())
        val (elements, hidden) = ScreenJson.elements(s, modelElementsLimit)
        val shotId = shots.next()
        val content = okObj {
            val w = s.web
            if (w == null) put("currentApp", s.packageName ?: s.unreadableApp)
            else { put("currentSite", UrlHost.of(w.url)); put("url", w.url); put("title", w.title); put("tabId", w.tabId) }
            w?.dialog?.let { put("dialog", dialogJson(it)) }
            put("snapshotId", s.snapshotId)
            put("screenshotId", shotId)
            imageReason?.let { put("imageReason", it) }
            quality(tree)?.let { put("treeQuality", it) }
            ocrStatus?.let { put("ocr", it) }
            put("screenChangedDuringRead", s.changedDuringRead)
            if (s.changedDuringRead) put("note", NOTE_CHANGED_DURING_READ)
            s.opened?.let { put("opened", openedJson(it)) }
            if (s.captureError != null) {
                put("captureError", s.captureError)
                put("hint", HINT_DISPLAY_CAPTURE)
            }
            else if (s.protected) { put("protected", true); put("hint", HINT_PROTECTED) }
            else if (unreadable(s) != null && ocr.isNullOrEmpty()) {
                put("readable", false)
                // A window that cannot be read does not mean the picture is unusable: with a screenshot and visual locating available, it must not be reduced to Back or handing over to the user.
                put("hint", unreadableHint(s, tree, ocrStatus))
            }
            else if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) put("hint", Feedback[surface.family.emptyHint])
            else treeHint(tree, ocrStatus)?.let { put("hint", it) }
            put("viewport", buildJsonObject { put("width", s.width); put("height", s.height) })
            put("elements", elements)
            if (surface.id == "phone" && s.windows.isNotEmpty()) put("tree", UiTreeJson.windows(s))
            ocr?.let { put("visibleText", VisibleText.json(it)) }
            if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { put("screenMap", it) }
            val omitted = s.web?.omitted ?: 0
            put("truncated", hidden > 0 || omitted > 0)
            if (omitted > 0) put("omitted", hidden + omitted)
        }
        // Protected frames and persistently black frames are never given to the vision model; the latter still keeps readable controls for degraded operation.
        val png = if ((sendImage && textOnlyDepth == 0 || (recordFrames && recordFrame && textOnlyDepth == 0)) &&
            !s.protected && s.captureError == null) screen.screenshotPng() else null
        // Keep the image only when the main model can see pictures; payment pages and ordinary pages follow the same rule
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, observationText(s, ocr.orEmpty())))
        val image = png.takeIf { sendImage }
        return ToolExecution(content, image = image, frame = png, imageIds = listOfNotNull(image?.let { shotId }), observations = listOf(seen))
    }

    /**
     * One frame of the current screen (the original image) and its screenshotId when the main model can see pictures, registered for recall. Same rules as screen reads:
     * no capture on protected pages; null when no App is on the screen yet or the capture fails.
     */
    /** Local replay frame: does not rely on the vision model, does not register a model screenshot id, and does not update screen-read state. */
    suspend fun playbackFrame(): ByteArray? = kotlinx.coroutines.withTimeoutOrNull(1_000L) {
        if (!recordFrames || textOnlyDepth != 0) return@withTimeoutOrNull null
        screenAttempt {
            screen.checkAppAccess()
            val s = screen.peek()
            if (s.protected || !s.hasWindow || screen.isProtected()) null else screen.screenshotPng()
        }
    }

    suspend fun currentFrame(): Pair<ByteArray, Int>? {
        if (!visionEnabled) return null
        val s = screenAttempt { screen.peek() } ?: return null
        if (s.protected || s.captureError != null) return null
        if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) return null
        val png = screenAttempt { if (screen.isProtected()) null else screen.screenshotPng() } ?: return null
        val shotId = shots.next()
        shots.remember(Recall(shotId, shots.step, s.packageName, png, screenText(s)))
        return png to shotId
    }

    /**
     * The screen sent after an action when the main model can see pictures: the picture and the element table must be from the same moment; the model acts via the ref/snapshotId in the
     * element table and uses the picture only to understand. Same set as observe: no picture on protected pages; null when no App is on the screen yet.
     */
    suspend fun currentView(): ToolDispatcher.Glance? {
        if (!visionEnabled && !adaptiveVision) return null
        val peeked = screenAttempt { screen.peek() } ?: return null
        if (peeked.packageName == null && peeked.elements.isEmpty() && !peeked.hasWindow) return null
        if (textOnlyDepth > 0) {
            val obs = screenAttempt { observe(withImage = false, recordFrame = false) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), null, shotId, obs.observations)
        }
        if (adaptiveVision) {
            val obs = screenAttempt { observe(withImage = false, adaptiveImage = visionEnabled) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), obs.image, shotId, obs.observations)
        }
        // Read the element table first (registering the snapshotId), then decide from this screen whether a capture is allowed: no capture on protected pages
        val obs = screenAttempt { observe(withImage = false) } ?: return null
        val s = lastSnapshot ?: return null
        val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val png = if (s.protected || s.captureError != null) null else screenAttempt { obs.frame ?: if (screen.isProtected()) null else screen.screenshotPng() }
        // Fill in the picture for this entry in the recall list
        val recalls = shots.recalls
        if (png != null) recalls.indexOfLast { it.id == shotId }.takeIf { it >= 0 }?.let { i ->
            val r = recalls[i]; recalls[i] = Recall(r.id, r.step, r.app, png, r.elementsText)
        }
        return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), png, shotId, obs.observations)
    }

    /** After a successful action, attach the current screen (the port already waited for an interface change inside the action): the element table goes into the screen field, the picture is attached separately, and its screenshotId is written into the result. */
    suspend fun withFrame(r: ToolExecution): ToolExecution {
        val v = currentView() ?: return r
        return r.with(content = JsonObject(r.content + ("screen" to v.content) + ("screenshotId" to JsonPrimitive(v.imageId))), images = listOfNotNull(v.image),
            screenElements = v.content["elements"] as? JsonArray ?: r.screenElements, imageIds = if (v.image != null) listOf(v.imageId) else emptyList(),
            observations = r.observations + v.observations)
    }

    /**
     * After a successful write operation: wait for the interface to settle (at most [INLINE_SETTLE_MS]), read one screen, compare it with the screen the model received last, and put the diff into the screen field.
     * When the base or the latest full screen is no longer visible to the model, send a full read. If the screen cannot be read, return the action result as is.
     */
    suspend fun withScreen(r: ToolExecution): ToolExecution {
        val cur = try {
            screen.waitStable(INLINE_SETTLE_MS)
            snapshot()
            stabilizer!!.latest!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowAppAccess()

            return r
        }
        val v = visible
        // Send a full read after at most MAX_CHAIN_DIFFS consecutive diffs: the longer the chain, the harder it is to read, and the more round trips compression has to preserve
        val seen = chain.isNotEmpty() && chain.size - 1 < MAX_CHAIN_DIFFS && (v == null || chain.all { it in v })
        val s = cur.screen
        val (tree, ocr, ocrStatus) = readTree(s)
        val shotId = shots.next()
        val diff = if (s.protected) {
            // Protected pages expose neither picture nor elements; the next read starts over from a full screen
            shown = null; chain.clear()
            buildJsonObject {
                put("snapshotId", cur.snapshotId); put("currentApp", s.packageName); put("full", false)
                put("protected", true); put("hint", HINT_PROTECTED)
                put("screenshotId", shotId)
            }
        } else {
            val d = InterfaceUpdate.between(shown, cur, seen, modelElementsLimit)
            shown = cur
            if (d["full"] == JsonPrimitive(true)) chain.clear()
            chain += cur.snapshotId
            JsonObject(d + ("screenshotId" to JsonPrimitive(shotId)) +
                (ocr?.let { mapOf("visibleText" to VisibleText.json(it)) } ?: emptyMap()) +
                (if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { mapOf("screenMap" to it) } ?: emptyMap() else emptyMap()) +
                (if (s.changedDuringRead) mapOf("note" to JsonPrimitive(NOTE_CHANGED_DURING_READ)) else emptyMap()) +
                (s.opened?.let { mapOf("opened" to openedJson(it)) } ?: emptyMap()) +
                (s.web?.dialog?.let { mapOf("dialog" to dialogJson(it)) } ?: emptyMap()) +
                (quality(tree)?.let { mapOf("treeQuality" to JsonPrimitive(it)) } ?: emptyMap()) +
                (ocrStatus?.let { mapOf("ocr" to JsonPrimitive(it)) } ?: emptyMap()) +
                (s.captureError?.let { mapOf("captureError" to JsonPrimitive(it), "hint" to JsonPrimitive(HINT_DISPLAY_CAPTURE)) } ?:
                    (unreadable(s)?.takeIf { ocr.isNullOrEmpty() }?.let {
                        mapOf("readable" to JsonPrimitive(false), "hint" to JsonPrimitive(unreadableHint(s, tree, ocrStatus)))
                    } ?: treeHint(tree, ocrStatus)?.let { mapOf("hint" to JsonPrimitive(it)) } ?: emptyMap())))
        }
        val text = observationText(s, ocr.orEmpty())
        // If it is identical to the previous read, do not record it again, so that earlier screen reads are not pushed out
        val read = if (lastRead() != text) interfaceRead(s, ocr.orEmpty()) else null
        // Same rules as observe: no capture of unusable pictures; keep the image for recall only when the main model can see pictures
        val imageReason = if (adaptiveVision && visionEnabled && tree.poor) tree.reason else null
        val sendImage = visionEnabled && (!adaptiveVision || imageReason != null)
        val png = if ((recordFrames || sendImage) && textOnlyDepth == 0 && !s.protected && s.captureError == null) {
            screenAttempt { screen.screenshotPng() }
        } else null
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, text))
        // Legacy mode always attaches the picture; adaptive mode attaches it only when the elements are clearly insufficient.
        val attach = kept?.takeIf { sendImage && r.images.isEmpty() }
        val shownDiff = if (imageReason == null) diff else JsonObject(diff + ("imageReason" to JsonPrimitive(imageReason)))
        return r.with(content = JsonObject(r.content + ("screen" to onSurface(shownDiff))),
            frame = r.frame ?: png?.takeIf { recordFrames }, images = attach?.let { listOf(it) } ?: r.images,
            screenElements = ScreenJson.elements(s, modelElementsLimit).first, imageIds = if (attach != null) listOf(shotId) else r.imageIds).observing(read)
    }

    /** Interface state before the action (refs not registered) plus one frame, used for judging effects; null when unreadable. */
    class EffectProbe internal constructor(before: ScreenSnapshot?, frame: FrameGrid?) {
        var before: ScreenSnapshot? = before
            internal set
        var frame: FrameGrid? = frame
            internal set
    }

    /** The look taken before this action, kept until its effect is judged: re-looked by [reprobe] when Claire itself touched the interface again right before the action started. */
    private var pendingProbe: EffectProbe? = null

    /** The effect of this action has been judged (or is no longer judged). */
    fun probeDone() { pendingProbe = null }

    /**
     * Claire itself touched the interface right before the action started (a gentle page scroll while dwelling on the web): look again as the pre-action state, so that this scrolling is not counted as the action's
     * effect; the positions in the model's copy are no longer accurate either, so mark it stale. Does nothing when no action is waiting for effect judgement.
     */
    suspend fun reprobe() {
        val p = pendingProbe ?: return
        stale = true
        withTimeoutOrNull(EffectTuning.PROBE_MS) { p.before = peek(); p.frame = grid() }
    }

    /** The screen just read for locating a target from a description: the snapshotId given to the model and the screen exactly as the port returned it; valid until taken by [probe] or discarded. */
    private var primed: Pair<String, ScreenSnapshot>? = null

    fun clearPrimed() { primed = null }

    /**
     * Take a look before the action, spending at most [EffectTuning.PROBE_MS]; the part that does not fit in time is null (afterwards only the port's facts are used).
     * When the action uses exactly the screen just read from a description ([snapshotId] matches), reuse it instead of reading again.
     */
    suspend fun probe(snapshotId: String? = null): EffectProbe {
        val ready = primed?.takeIf { it.first == snapshotId }?.second
        primed = null
        if (ready != null) {
            var frame: FrameGrid? = null
            withTimeoutOrNull(EffectTuning.PROBE_MS) { frame = grid() }
            return EffectProbe(ready, frame).also { pendingProbe = it }
        }
        var before: ScreenSnapshot? = null
        var frame: FrameGrid? = null
        withTimeoutOrNull(EffectTuning.PROBE_MS) { before = peek(); frame = grid() }
        return EffectProbe(before, frame).also { pendingProbe = it }
    }

    private suspend fun grid(): FrameGrid? = screenAttempt { screen.frameGrid() }

    /**
     * After a successful action, judge its actual effect on the interface state (see [ActionEffect]), write it into the result's effect field, and give hints based on the effect; the result's changed follows the effect.
     * If a read already happened after the action (another read after [readsAtAction], i.e. the result carries the interface), use that one. Otherwise: if the port already waited for the interface inside the action (it reported
     * whether anything changed or the page settled), just take a look; only wait for the interface to settle (at most [EffectTuning.SETTLE_MS]) when it did not. When it looks unchanged, wait [EffectTuning.RECHECK_MS] and look once more (asynchronous update).
     * The whole judgement spends at most [EffectTuning.BUDGET_MS] extra; once reached, conclude from the port's facts. It only reads the interface and takes frames, never performs any action.
     * When the interface changed but the result carries no new interface, mark the model's copy as stale ([stale]).
     */
    suspend fun withEffect(
        r: ToolExecution, probe: EffectProbe, verb: Verb, readsAtAction: Int, target: UiElement?, region: List<Int>?, typed: String?,
    ): ToolExecution {
        val c = r.content
        val opened = c["opened"] as? JsonObject
        val port = PortFacts(
            changed = r.changed,
            switchedTo = (c["app_switched"] as? JsonPrimitive)?.content,
            verified = c["verification"] == JsonPrimitive("match"),
            loading = c["settled"] == JsonPrimitive(false),
            onUserScreen = opened?.takeIf { it["onUserScreen"] == JsonPrimitive(true) }?.let { (it["package"] as? JsonPrimitive)?.content },
        )
        val portWaited = r.changed != null || "settled" in c
        val fresh = reads > readsAtAction
        fun judge(after: ScreenSnapshot?, frame: FrameGrid?) = ActionEffect.classify(
            verb, probe.before, after, port, FrameDiff.compare(probe.frame, frame, region), target, typed,
            pressedEnter = c["pressedEnter"] == JsonPrimitive(true), secret = c["secret"] == JsonPrimitive(true),
        )
        var effect: ActionEffect? = judge(null, null)
        // A "limit reached" message newly appearing in the interface seen after the action (counted if either of the two looks has it: the banner disappears after a moment)
        var limit: String? = null
        withTimeoutOrNull(EffectTuning.BUDGET_MS) {
            if (!fresh && !portWaited) settle(EffectTuning.SETTLE_MS)
            val after = if (fresh) lastSnapshot else peek()
            limit = LimitNotice.find(probe.before, after)
            effect = judge(after, grid())
            if (effect?.kind == EffectKind.NoEffect) {
                // The interface may update asynchronously: wait a moment and look again; only count it as no effect if it is still unchanged
                delay(EffectTuning.RECHECK_MS)
                val again = peek()
                limit = limit ?: LimitNotice.find(probe.before, again)
                effect = judge(again, grid())
            }
        }
        // Where the tree cannot expose the tap point (judged on the phone only: the web has no notion of a tree being good enough): no visible change does not mean the tap missed
        val e = effect?.let { judged ->
            if (surface.id != "phone") return@let judged
            val quality = probe.before?.let { TreeAdequacy.ofTree(it).quality } ?: lastTree?.quality
            if (judged.kind == EffectKind.NoEffect && ActionEffect.treeBlind(verb, quality, probe.before, target, region)) judged.copy(treeBlind = true) else judged
        } ?: return r
        if ("screen" !in c && e.kind != EffectKind.NoEffect) stale = true
        val changed = e.kind != EffectKind.NoEffect
        val hint = listOfNotNull(limit?.let { Feedback.format("effect.limit_reached", "text" to it.take(LIMIT_TEXT_MAX)) },
            EffectFeedback.hint(e, verb, portSwitched = "app_switched" in c || opened != null),
            Feedback["effect.stale"].takeIf { stale && e.kind != EffectKind.SmallChange }, (c["hint"] as? JsonPrimitive)?.content)
        val content = JsonObject(c - "hint" + ("effect" to e.toJson()) +
            (if ("changed" in c) mapOf("changed" to JsonPrimitive(changed)) else emptyMap()) +
            (if (verb == Verb.Scroll && e.kind == EffectKind.NoEffect) mapOf("atEnd" to JsonPrimitive(true)) else emptyMap()) +
            (if (stale) mapOf("interfaceStale" to JsonPrimitive(true)) else emptyMap()) +
            (limit?.let { mapOf("limitReached" to JsonPrimitive(it.take(LIMIT_TEXT_MAX))) } ?: emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap()))
        return r.with(content = content, changed = changed)
    }

    /** Whether the tree judged on the latest screen read was adequate; used when judging effects to say "no visible change" versus "the tap missed". */
    private var lastTree: TreeCheck? = null

    /**
     * Whether this screen's tree is missing a piece or unusable (judged on the phone only, by the same measures as treeQuality in read results): when judging from the tree alone is unreliable, use the verdict of the latest screen read (which read on-screen text).
     * When looking for an input field from a description fails in the tree, this is what switches to looking visually.
     */
    fun treeBlind(s: ScreenSnapshot): Boolean = surface.id == "phone" &&
        (TreeAdequacy.ofTree(s).quality ?: lastTree?.quality).let { it == TreeQuality.Partial || it == TreeQuality.Unusable }

    private suspend fun settle(ms: Long) {
        screenAttempt { screen.waitStable(ms) }
    }

    /** An App just pulled back from the user's home screen before this screen read: the same fields as opened in action results, plus that explanatory note. */
    private fun openedJson(o: OpenedElsewhere): JsonObject = JsonObject(o.toJson() + ("note" to JsonPrimitive(openedHint(o))))

    /**
     * The note given to the model when the foreground App is open but its window cannot be read (this is not "not opened", so do not start it again); null when readable or when the foreground is unknown.
     */
    private fun unreadable(s: ScreenSnapshot): String? {
        val app = s.unreadableApp ?: return null
        if (s.elements.any { it.meaningful }) return null
        return Feedback.format("unreadable_window", "app" to app)
    }

    /** When the window is unreadable but screenshots work, keep the "do not start it again" part and let the following route use this tree's OCR/vision fallback. */
    private fun unreadableHint(s: ScreenSnapshot, tree: TreeCheck, ocrStatus: String?): String {
        val fallback = treeHint(tree, ocrStatus) ?: return unreadable(s)!!
        return Feedback.format("unreadable_window.fallback", "app" to s.unreadableApp!!, "fallback" to fallback)
    }

    /** The interface state read this time: text of readable elements, where it is, the web page title, and the text inside input fields. */
    private fun interfaceRead(s: ScreenSnapshot, ocr: List<VisibleTextLine> = emptyList()): Observation {
        val f = inputFields(s)
        val place = s.web?.url?.let(Place::page) ?: s.packageName?.let(Place::app)
        return Observation(ObsKind.Read, observationText(s, ocr), source = surface.id, place = place, title = s.web?.title,
            trust = if (s.web != null) Trust.Untrusted else Trust.Trusted, inputs = InputHints(f.names, f.focused))
    }

    /**
     * Whether this screen's tree is adequate ([TreeAdequacy]), and the on-screen text to supplement it (OCR, keeping only what the tree lacks and dropping misrecognized lines).
     * When the tree is clearly adequate or the page is protected, do not read text; when the verdict is uncertain, read text and then decide; when the tree is insufficient, always read text: the main model that cannot see pictures relies
     * on it, and a main model that can see pictures also uses its positions to tap; the evidence ledger (answer sources, verification) and phone_collect can also only learn what the screen says from here.
     */
    private suspend fun readTree(s: ScreenSnapshot): TreeRead {
        val tree = TreeAdequacy.ofTree(s)
        if (tree.quality == TreeQuality.Adequate || tree.quality == TreeQuality.Protected) return TreeRead(tree, null, null).also { lastTree = it.tree }
        val (ocr, status) = visibleText(s, if (tree.poor) OCR_WAIT_POOR_MS else OCR_WAIT_MS)
        val checked = TreeAdequacy.withText(tree, ocr)
        lastTree = checked
        // The tree is insufficient and no on-screen text was read either: state plainly why, so the model knows to look visually instead of reading again
        return TreeRead(checked, ocr, status.takeIf { quality(checked) != null && ocr.isNullOrEmpty() })
    }

    /**
     * The on-screen text the tree does not contain, and the reason when not a single line was read: empty (no text recognized on the picture), illegible (everything recognized was misrecognition),
     * timeout (not finished recognizing within the limit), unavailable (the port cannot provide it: no picture, or the picture keeps changing drastically), failed (recognition error).
     * Both are null when not reading (web, protected, no window). It reads even if the tree changed during the read: the text positions come from the picture itself,
     * the port waits for the picture to settle by itself, and it re-recognizes if the whole page changes during recognition.
     */
    private suspend fun visibleText(s: ScreenSnapshot, waitMs: Long): Pair<List<VisibleTextLine>?, String?> {
        if (surface.id != "phone" || s.protected || s.captureError != null || !s.hasWindow) return null to null
        var timedOut = true
        val raw = try {
            withTimeoutOrNull(waitMs) { screen.readVisibleText().also { timedOut = false } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowAppAccess(); return null to "failed" }
        if (raw == null) return null to if (timedOut) "timeout" else "unavailable"
        if (raw.isEmpty()) return raw to "empty"
        val legible = VisibleText.legible(raw)
        if (legible.isEmpty()) return legible to "illegible"
        return VisibleText.missing(s, legible) to null
    }

    /** The treeQuality written into read results: only on the phone and only when the tree is insufficient (the web has no on-screen text to supplement it). */
    private fun quality(tree: TreeCheck): String? = tree.quality?.takeIf { surface.id == "phone" && tree.poor }?.wire

    /** The note given to the model when the tree is insufficient: where on-screen text comes from and how to tap it; when a vision model is configured, also how to find icons with no text; when even text cannot be read, why and how to look instead. */
    private fun treeHint(tree: TreeCheck, ocrStatus: String? = null): String? = when {
        quality(tree) == null -> null
        ocrStatus != null && locator != null -> Feedback.format("tree_poor.no_text.visual", "ocr" to ocrStatus)
        ocrStatus != null -> Feedback.format("tree_poor.no_text", "ocr" to ocrStatus)
        locator != null -> Feedback.format("tree_poor.visual", "tree_poor" to Feedback["tree_poor"])
        else -> Feedback["tree_poor"]
    }

    /**
     * One more look before verification: read this screen again right now (without registering refs, so the model's refs are unaffected), and when [withImage] also take a screenshot (no capture on capture-protected pages);
     * when the read succeeds, also add [extra] supplied by the capability (the start of a web page body: a screen read only has the elements in the viewport). It is returned even when unreadable (no observation).
     */
    suspend fun freshLook(withImage: Boolean, extra: (suspend () -> Observation?)? = null): Look {
        val s = screenAttempt { screen.snapshot() }
        val png = if (withImage && s != null && !s.protected && s.captureError == null) {
            screenAttempt { screen.screenshotPng() }
        } else null
        // Read on-screen text as usual when the tree is insufficient: verification cares about what the screen says
        val seen = s?.let { interfaceRead(it, readTree(it).ocr.orEmpty()) }?.takeIf { it.text.isNotEmpty() }
        val more = if (s != null) extra?.invoke() else null
        return Look(listOfNotNull(seen, more), png, s?.protected == true)
    }

    /** Report "where" the way this screen words it (on the web it is the site): as in observe, the phone's and the web's "where" are never mixed. */
    private fun onSurface(o: JsonObject): JsonObject {
        val key = surface.family.placeKey
        if (key == ScreenFamily.APP_PLACE || ScreenFamily.APP_PLACE !in o) return o
        return JsonObject(o.filterKeys { it != ScreenFamily.APP_PLACE } + (key to (o[ScreenFamily.APP_PLACE] ?: JsonNull)))
    }
}

/** The text inside this screen's input fields (not hints, not password fields), and the one with input focus; the evidence ledger uses this to distinguish input echo from text the user typed during takeover. */
internal fun inputFields(s: ScreenSnapshot): InputFields {
    val fields = s.elements.filter { it.editable && !it.hint && !it.secret && it.name.isNotBlank() }
    return InputFields(fields.map { it.name.replace('\n', ' ') }, fields.firstOrNull { it.focused }?.name?.replace('\n', ' '))
}

internal class InputFields(val names: List<String>, val focused: String?)

/** The tree verdict of one screen read, the on-screen text it supplemented, and the reason when the tree was insufficient and not a single line of text was read (see ScreenSession.visibleText). */
internal data class TreeRead(val tree: TreeCheck, val ocr: List<VisibleTextLine>?, val ocrStatus: String?)

/** The element text of a screen read, one per line; the first line is the foreground App. Empty string when there is no text. Text in password fields is not recorded. */
internal fun screenText(s: ScreenSnapshot): String {
    val names = s.elements.filter { it.meaningful && it.name.isNotBlank() && !it.secret }.map { it.name.replace('\n', ' ') }
    // The web page title is not in the elements, but it is also a fact of this screen (supplied by the page and, like element text, material to be verified); keep it even for web pages with no readable elements
    val title = s.web?.title?.takeIf { it.isNotBlank() }?.let { "Page title: ${it.replace('\n', ' ')}" }
    val dialog = s.web?.dialog?.let { "Page dialog (${it.kind}): ${it.message.replace('\n', ' ')}" }
    if (names.isEmpty() && title == null && dialog == null) return ""
    val where = s.web?.let { "Current page: ${it.url}" } ?: "Current app: ${s.packageName ?: "unknown"}"
    return (listOfNotNull(where, title, dialog) + names).joinToString("\n").take(MAX_READ_CHARS)
}

/** Accessibility text is still the primary evidence; local OCR only appends on-screen text missing from the tree. */
private fun observationText(s: ScreenSnapshot, ocr: List<VisibleTextLine>): String {
    val tree = screenText(s)
    if (ocr.isEmpty()) return tree
    return listOf(tree, "Visible on-screen text (OCR):", ocr.joinToString("\n") { it.text }).filter(String::isNotEmpty)
        .joinToString("\n").take(MAX_READ_CHARS)
}

internal fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

/** A page dialog in a screen-read result: the model must first reply via web_interact's dialog before the page continues running. */
internal fun dialogJson(d: PageDialog): JsonObject = buildJsonObject {
    put("kind", d.kind)
    put("message", d.message.take(PageDialog.MAX_MESSAGE))
    d.defaultText?.let { put("defaultText", it.take(PageDialog.MAX_MESSAGE)) }
    put("hint", Feedback["page_dialog"])
}
