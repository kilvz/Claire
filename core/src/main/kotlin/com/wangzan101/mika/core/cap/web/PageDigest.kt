package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a single web_read_page asks for: [url] null reads the current tab, otherwise the page is read in a temporary background tab; when [unfold] is true, folded list blocks are also given in full
 * (paging offsets are computed with the same unfold).
 */
data class PageRead(
    val maxChars: Int,
    val offset: Int,
    val unfold: Boolean = false,
    val url: String? = null,
    /** Scroll through once before reading to trigger lazy loading and wait for the text to settle; false when gathering evidence for a check, so the page is only read and not touched. */
    val lazy: Boolean = true,
)

/** How this page read turned out: whether there is enough body text, whether signing in first is required, whether it was refused, whether the content is mainly in images. [wire] is the spelling handed to the model. */
enum class ReadVerdict(val wire: String) {
    OK("ok"), THIN("thin"), NEEDS_SIGN_IN("needs_sign_in"), REFUSED("refused"), IMAGE_TEXT("image_text"),
}

/**
 * Quality signals of a page read: [chars] is the body character count of the whole page (under this read's folding), [textDensity] is the share of body text that is not link text,
 * [cause] is a short note on what the verdict rests on; [published]/[updated] are the times the page declares for itself; [dialogs] is the number of open dialogs (placed at the very front of the body text),
 * [folded] is the number of list blocks folded into a single line.
 */
data class ReadQuality(
    val chars: Int,
    val textDensity: Double,
    val verdict: ReadVerdict,
    val cause: String?,
    val published: String? = null,
    val updated: String? = null,
    val dialogs: Int = 0,
    val folded: Int = 0,
)

/**
 * The whole-page read result from the page script (page.js's digest) is handed here: it decides which list blocks get folded, assembles the body text, judges the quality and picks out the declared times.
 * The page script gathers by structure only (landmark roles, link share, repeated substructures, image positions); all judging happens here and is not specialized per site.
 * The thresholds the page script uses to pick candidate blocks are also supplied from here ([scriptOptions]), so both sides share a single copy.
 */
object PageDigest {
    /** Body text shorter than this counts as thin. */
    const val THIN_CHARS = 200
    /** Candidate block: at least this many links, with link text making up at least this share (the page script picks candidates by it, and it is judged in more detail here). */
    const val CANDIDATE_MIN_LINKS = 5
    const val CANDIDATE_LINK_SHARE = 0.5
    /** Once link text makes up this share of a block's text, the block is a list of links (recommendations, table of contents). */
    const val LINK_LIST_SHARE = 0.7
    /** When the children repeat the same structure (a card list), this link share is enough. */
    const val REPEATED_LINK_SHARE = 0.5
    const val MIN_REPEAT = 5
    /** A landmark block (navigation, footer, sidebar) is folded only when links make up at least this share; text-heavy sidebars and footers are still read. */
    const val LANDMARK_LINK_SHARE = 0.3
    /** When a list block reaches this share of the text outside landmarks, it is the body text itself (a directory page, a search-results page) and is not folded. */
    const val DOMINANT_SHARE = 0.4
    /** At least this share of the text must remain after folding; if not, blocks are put back starting from the largest. */
    const val MIN_LEFT_SHARE = 0.1
    /** When images and canvases cover this share of the top of the page and there is little text, it counts as image_text. */
    const val PICTURE_SHARE = 0.5
    const val PICTURE_MAX_CHARS = THIN_CHARS * 4
    /** Images with an edge shorter than this (CSS pixels) do not count: icons, avatars, dividers. */
    const val MIN_PICTURE_PX = 48
    /** When the sign-in box (the block containing the password field) holds this share of the whole page's text, the page is a sign-in page. */
    const val SIGN_IN_BOX_SHARE = 0.5
    private const val COVER_GRID = 64
    private const val PREVIEW_ITEMS = 6
    private const val PREVIEW_CHARS = 120
    private val LANDMARKS = setOf("navigation", "footer", "complementary")

    /** When these words appear in the sign-in box, the title or a dialog, and a password field exists, signing in first is required. Whitespace is removed and case ignored when comparing. */
    private val SIGN_IN_WORDS = listOf("登录", "登入", "登陆", "signin", "login", "logon", "账号", "帐号", "密码", "password")

    /** Parameters for the page script's digest (candidate block thresholds). */
    val scriptOptions: String get() = """{"minLinks":$CANDIDATE_MIN_LINKS,"linkShare":$CANDIDATE_LINK_SHARE}"""

    /** One link: [group] is the list block it belongs to (-1 when it is not inside a block). */
    data class Link(val text: String, val url: String, val group: Int)

    /** One segment of the body text: a line of text, or a list block that can be folded. */
    sealed interface Part {
        data class Line(val text: String) : Part
        /**
         * [kind] is navigation/footer/complementary (recognized as landmarks) or links (a list recognized by its link share); [lines] is the text in the block,
         * [chars]/[linkChars] are the character counts of all text and of link text in the block, [repeat] is the number of children sharing the same structure, [links] is the number of links in the block.
         */
        data class Group(
            val kind: String, val label: String, val lines: List<String>,
            val chars: Int, val linkChars: Int, val repeat: Int, val links: Int,
        ) : Part {
            val landmark: Boolean get() = kind in LANDMARKS
            val linkShare: Double get() = if (chars > 0) linkChars.toDouble() / chars else 0.0
        }
    }

    /** Position of an image on the page (document coordinates, CSS pixels). */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Page signals used for the verdict (all come from the page script). */
    data class Signals(
        val passwordFields: Int = 0,
        /** Share of the whole page's text taken up by the sign-in box itself (the largest block going upward from the password field, excluding body text, stopping at a form or a dialog). */
        val signInBoxShare: Double = 0.0,
        /** Where to look for sign-in wording: the sign-in box's text, the page title, the beginning of dialogs. */
        val signInText: List<String> = emptyList(),
        /** Prominent text (title, h1–h3, beginning of dialogs), used for the interception verdict. */
        val prominent: List<String> = emptyList(),
        /** The region at the top of the page (width, height) and the images, canvases and videos inside it. */
        val regionW: Int = 0,
        val regionH: Int = 0,
        val pictures: List<Box> = emptyList(),
        val frames: List<String> = emptyList(),
        val textChars: Int = 0,
        val linkChars: Int = 0,
        /** Times declared by the page: (source, raw value), with sources such as ld:datePublished, meta:article:published_time, itemprop:dateModified, time. */
        val dates: List<Pair<String, String>> = emptyList(),
    )

    data class Digest(
        val url: String,
        val title: String,
        val dialogs: List<List<String>>,
        val parts: List<Part>,
        val links: List<Link>,
        val images: List<Pair<String, String>>,
        val signals: Signals,
    )

    /** The assembled result; [folded] is how many blocks were folded this time. */
    data class Rendered(val text: String, val links: List<Pair<String, String>>, val folded: Int)

    // ---------- Parsing ----------

    fun parse(o: JsonObject): Digest {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun int(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun strings(e: JsonElement?) = (e as? JsonArray).orEmpty().map(::str).filter { it.isNotEmpty() }
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
            when (p) {
                is JsonPrimitive -> str(p).takeIf { it.isNotEmpty() }?.let(Part::Line)
                is JsonObject -> Part.Group(str(p["g"]), str(p["label"]), strings(p["lines"]), int(p["chars"]), int(p["linkChars"]), int(p["repeat"]), int(p["links"]))
                else -> null
            }
        }
        val links = (o["links"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Link(str(m["text"]), url, (m["p"] as? JsonPrimitive)?.intOrNull ?: -1)
        }
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["alt"]) to url
        }
        val s = o["signals"] as? JsonObject
        val dates = (s?.get("dates") as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val v = str(m["v"]).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["k"]) to v
        }
        val region = (s?.get("region") as? JsonArray).orEmpty().map(::int)
        val pictures = (s?.get("pictures") as? JsonArray).orEmpty().mapNotNull { e ->
            val a = (e as? JsonArray)?.map(::int)?.takeIf { it.size == 4 } ?: return@mapNotNull null
            Box(a[0], a[1], a[2], a[3])
        }
        return Digest(
            url = str(o["url"]), title = str(o["title"]),
            dialogs = (o["dialogs"] as? JsonArray).orEmpty().map(::strings).filter { it.isNotEmpty() },
            parts = parts, links = links, images = images,
            signals = Signals(
                passwordFields = int(s?.get("password")),
                signInBoxShare = num(s?.get("signInShare")),
                signInText = strings(s?.get("signInText")),
                prominent = strings(s?.get("prominent")),
                regionW = region.getOrElse(0) { 0 }, regionH = region.getOrElse(1) { 0 },
                pictures = pictures,
                frames = strings(s?.get("frames")),
                textChars = int(s?.get("textChars")),
                linkChars = int(s?.get("linkChars")),
                dates = dates,
            ),
        )
    }

    // ---------- Folding ----------

    /**
     * Which blocks get folded (indexed by the block's position among [Digest.parts], counting blocks only). A landmark block is folded when it has enough links; a link list needs a high link share
     * and must not make up the bulk of the text outside landmarks (that is the body text itself); when the largest block is a link list and holds more than the text outside blocks, it is the body text and is not folded;
     * if less than [MIN_LEFT_SHARE] of the text would remain after folding, blocks are put back starting with the largest.
     */
    fun foldSet(parts: List<Part>): Set<Int> {
        val groups = parts.filterIsInstance<Part.Group>()
        if (groups.isEmpty()) return emptySet()
        fun Part.size() = when (this) { is Part.Line -> text.length + 1; is Part.Group -> lines.sumOf { it.length + 1 } }
        val total = parts.sumOf { it.size() }
        val free = parts.filterIsInstance<Part.Line>().sumOf { it.size() }
        val outsideLandmarks = free + groups.filter { !it.landmark }.sumOf { it.size() }
        val largest = groups.indices.maxBy { groups[it].size() }
        val fold = groups.indices.filter { i ->
            val g = groups[i]
            val wanted = if (g.landmark) g.linkShare >= LANDMARK_LINK_SHARE
            else (g.linkShare >= LINK_LIST_SHARE || (g.repeat >= MIN_REPEAT && g.linkShare >= REPEATED_LINK_SHARE)) &&
                g.size() < outsideLandmarks * DOMINANT_SHARE
            // If the largest block is a link list and holds more than the text outside blocks, it is the body text (landmarks are still folded as the page itself declares)
            wanted && !(i == largest && !g.landmark && g.size() > free)
        }.toMutableSet()
        while (fold.isNotEmpty() && total - fold.sumOf { groups[it].size() } < total * MIN_LEFT_SHARE) {
            fold.remove(fold.maxBy { groups[it].size() })
        }
        return fold
    }

    /** Assemble the body text: dialogs come first, and each folded block takes a single line. The link list keeps only links still visible, and each URL appears once. */
    fun render(d: Digest, unfold: Boolean): Rendered {
        val folded = if (unfold) emptySet() else foldSet(d.parts)
        val lines = ArrayList<String>()
        for (dl in d.dialogs) {
            lines += "<<dialog>>"
            lines += dl
            lines += "<<end of dialog>>"
        }
        var groupIndex = -1
        for (p in d.parts) when (p) {
            is Part.Line -> lines += p.text
            is Part.Group -> {
                groupIndex++
                if (groupIndex in folded) lines += collapsed(p) else lines += p.lines
            }
        }
        val links = d.links.filter { it.group < 0 || it.group !in folded }.distinctBy { it.url }.map { it.text to it.url }
        return Rendered(lines.joinToString("\n"), links, folded.size)
    }

    private fun collapsed(g: Part.Group): String {
        val name = g.kind + (g.label.takeIf { it.isNotBlank() }?.let { " \"${it.take(60)}\"" } ?: "")
        val sample = g.lines.take(PREVIEW_ITEMS).joinToString(" | ").let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…" else it }
        val count = if (g.links > 0) "${g.links} links" else "${g.lines.size} lines"
        return "<<collapsed $name · $count · $sample>>"
    }

    // ---------- Verdict ----------

    /**
     * Judge how this page read went. [check] is the web barrier verdict (made from prominent text, cross-origin frames and the HTTP status, see [barrierFacts]),
     * [httpStatus] is the HTTP error status of the main document. Refusal is checked first, then sign-in, then whether the content is an image, and the character count last.
     */
    fun verdict(chars: Int, s: Signals, check: ScreenCheck?, httpStatus: Int?): Pair<ReadVerdict, String?> {
        val reason = check?.takeoverReason
        if (reason == "captcha" || reason == "access_blocked") return ReadVerdict.REFUSED to (check.cause ?: reason)
        if (reason == ScreenDiagnosis.UNSUPPORTED_BROWSER) return ReadVerdict.REFUSED to "the site refuses this browser"
        if (httpStatus != null && httpStatus >= 400 && chars < PICTURE_MAX_CHARS) return ReadVerdict.REFUSED to "HTTP $httpStatus"
        if (s.passwordFields > 0) {
            val cue = signInCue(s.signInText)
            if (cue != null && (s.signInBoxShare >= SIGN_IN_BOX_SHARE || chars < PICTURE_MAX_CHARS)) return ReadVerdict.NEEDS_SIGN_IN to "password field and \"$cue\""
        } else if (reason == "login" && chars < PICTURE_MAX_CHARS) {
            return ReadVerdict.NEEDS_SIGN_IN to (check.cause ?: "login")
        }
        val cover = pictureShare(s)
        if (cover >= PICTURE_SHARE && chars < PICTURE_MAX_CHARS) return ReadVerdict.IMAGE_TEXT to "pictures cover ${(cover * 100).toInt()}% of the page top"
        if (chars < THIN_CHARS) return ReadVerdict.THIN to "$chars characters of text"
        return ReadVerdict.OK to null
    }

    private fun signInCue(texts: List<String>): String? {
        val text = texts.joinToString("\n").lowercase().filterNot(Char::isWhitespace)
        return SIGN_IN_WORDS.firstOrNull { it in text }
    }

    /**
     * Share of the page's top region covered by images: lay a grid over the region and count the cells whose center falls inside an image; overlapping images count only once,
     * and images with an edge shorter than [MIN_PICTURE_PX] are ignored.
     */
    fun pictureShare(s: Signals): Double {
        val w = s.regionW
        val h = s.regionH
        if (w <= 0 || h <= 0) return 0.0
        val boxes = s.pictures.filter { it.w >= MIN_PICTURE_PX && it.h >= MIN_PICTURE_PX }
        if (boxes.isEmpty()) return 0.0
        val cols = COVER_GRID
        val rows = maxOf(1, (COVER_GRID.toLong() * h / w).toInt())
        var covered = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5) * w / cols
            val y = (r + 0.5) * h / rows
            if (boxes.any { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }) covered++
        }
        return covered.toDouble() / (rows * cols)
    }

    /** Facts handed to [WebBarrier.classify]: only prominent text is used (a "captcha" mentioned inside an article does not count), and password fields are judged separately. */
    fun barrierFacts(d: Digest, httpStatus: Int?, host: String?): WebFacts = WebFacts(
        base = ScreenFacts(
            currentApp = host, hasWindow = true, readableElements = 1, changing = false, blackFrames = emptyList(),
            texts = d.signals.prominent + d.dialogs.flatten(), passwordField = false,
        ),
        httpStatus = httpStatus, frameUrls = d.signals.frames, host = host,
    )

    /** Share of the body text that is not link text, rounded to two decimals; 0 when there is no text. */
    fun density(s: Signals): Double {
        if (s.textChars <= 0) return 0.0
        val share = 1.0 - s.linkChars.coerceAtMost(s.textChars).toDouble() / s.textChars
        return Math.round(share * 100) / 100.0
    }

    // ---------- Times ----------

    /**
     * Publication and update times declared by the page: structured declarations first (JSON-LD, then meta, then itemprop), and the <time> elements in the body text only last;
     * within the same kind, in order of appearance. Values that cannot be understood are skipped.
     */
    fun dates(s: Signals): Pair<String?, String?> {
        fun rank(src: String) = when {
            src.startsWith("ld:") -> 0
            src.startsWith("meta:") -> 1
            src.startsWith("itemprop:") -> 2
            else -> 3
        }
        fun pick(kind: (String) -> Boolean) = s.dates.withIndex()
            .filter { kind(it.value.first.lowercase()) }
            .sortedWith(compareBy({ rank(it.value.first) }, { it.index }))
            .firstNotNullOfOrNull { DeclaredTime.read(it.value.second) }
        val published = pick { k -> PUBLISHED_HINTS.any { it in k } || k == "time" || k == "time:pubdate" || k in PLAIN_DATE_KEYS }
        val updated = pick { k -> UPDATED_HINTS.any { it in k } }
        return published to updated
    }

    private val PUBLISHED_HINTS = listOf("published", "datecreated", "issued", "created", "pubdate", "publishdate", "publish_date")
    private val UPDATED_HINTS = listOf("modified", "updated", "lastmod", "revised")
    private val PLAIN_DATE_KEYS = setOf("meta:date", "meta:dc.date")

    /** The quality of a whole-page read. */
    fun quality(d: Digest, r: Rendered, check: ScreenCheck?, httpStatus: Int?): ReadQuality {
        val (v, cause) = verdict(r.text.length, d.signals, check, httpStatus)
        val (pub, upd) = dates(d.signals)
        return ReadQuality(r.text.length, density(d.signals), v, cause, pub, upd, d.dialogs.size, r.folded)
    }
}

/**
 * Reads a time written on the page into ISO-8601. Numeric fields are taken in order: a four-digit year, then month and day, then optionally hour, minute and second (fractional seconds are dropped);
 * a Z or ±hour[:minute] after the time is the time zone. Any separator may sit between year, month and day (-, /, ., or the CJK year/month/day characters). Null when the date cannot be recognized or the values are invalid.
 */
object DeclaredTime {
    private class Scan(val s: String) {
        var i = 0
        fun skipNonDigits() { while (i < s.length && !s[i].isDigit()) i++ }
        fun digits(): String {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i)
        }
        fun peek(): Char? = s.getOrNull(i)
    }

    fun read(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 60) return null
        val sc = Scan(text)
        sc.skipNonDigits()
        val year = sc.digits().takeIf { it.length == 4 }?.toInt() ?: return null
        val nums = ArrayList<Int>()
        // Month and day: separated by one delimiter each
        repeat(2) {
            val sep = sc.peek() ?: return null
            if (sep.isDigit() || sep.isWhitespace() && nums.isEmpty()) return null
            sc.i++
            val n = sc.digits().takeIf { it.length in 1..2 } ?: return null
            nums += n.toInt()
        }
        val date = runCatching { LocalDate.of(year, nums[0], nums[1]) }.getOrNull() ?: return null
        if (sc.peek() == '日') sc.i++
        // Time: hour:minute[:second] after a T or a space
        val tStart = sc.i
        if (sc.peek() == 'T' || sc.peek() == 't' || sc.peek() == ' ') sc.i++ else return date.toString()
        val hour = sc.digits().takeIf { it.length in 1..2 }?.toInt()
        if (hour == null || sc.peek() != ':') { sc.i = tStart; return date.toString() }
        sc.i++
        val minute = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return date.toString()
        var second = 0
        if (sc.peek() == ':') {
            sc.i++
            second = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return null
        }
        if (sc.peek() == '.' || sc.peek() == ',') { sc.i++; sc.digits() }
        val time = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
        val local = "$date" + "T" + "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        return local + (zone(sc) ?: "")
    }

    /** A time zone such as Z, +08:00, +0800, -05; null when there is none. */
    private fun zone(sc: Scan): String? {
        while (sc.peek() == ' ') sc.i++
        val c = sc.peek() ?: return null
        if (c == 'Z' || c == 'z') return "Z"
        if (c != '+' && c != '-') return null
        sc.i++
        val a = sc.digits()
        val (hh, mm) = when {
            a.length == 4 -> a.substring(0, 2) to a.substring(2)
            a.length in 1..2 && sc.peek() == ':' -> { sc.i++; a to sc.digits() }
            a.length in 1..2 -> a to "00"
            else -> return null
        }
        val h = hh.toIntOrNull() ?: return null
        val m = mm.toIntOrNull() ?: return null
        if (h > 14 || m > 59) return null
        if (h == 0 && m == 0) return "Z"
        return "%c%02d:%02d".format(c, h, m)
    }
}
