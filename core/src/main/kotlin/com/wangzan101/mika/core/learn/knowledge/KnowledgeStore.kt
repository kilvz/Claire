package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.prompt.Feedback
import java.io.File

private val GENERIC = setOf("app", "apps", "web", "www", "site", "browser")

// Chinese words that are too generic: only a hit when explicitly named as "open/enter/go to" + the word ("set an alarm" does not count)
private val GENERIC_ZH = setOf(
    "设置", "浏览器", "搜索", "地图", "相机", "相册", "日历", "时钟", "天气", "音乐",
    "视频", "文件", "短信", "电话", "邮件", "商城", "应用", "手机",
)
private val NAMING_PREFIXES = listOf("打开", "进入", "去")

// Common system app names. When a brand word is immediately followed by one of them ("<brand> app store"), the text means that app, not the brand's own store and similar entries
private val APP_KINDS = listOf(
    "应用市场", "应用商店", "浏览器", "钱包", "日历", "时钟", "相机", "图库", "文件管理", "主题", "云空间",
    "运动健康", "计算器", "便签", "备忘录", "录音机", "游戏中心", "手机管家", "天气",
)

/**
 * Built-in knowledge + user notes: [notes] are keyed by entry name (app name or site domain), appended after the matching entry's body and marked as written by the user;
 * [learnedNotes] are app knowledge supplements self-learned by Claire, appended at the end and marked as on trial or verified.
 * Matching is deterministic string matching and does not call a model.
 */
class KnowledgeStore(private val bundled: List<KnowledgeEntry>, notes: Map<String, String> = emptyMap(),
    learnedNotes: Map<String, LearnedNote> = emptyMap()) {
    private val entries: List<KnowledgeEntry> = bundled.map { e ->
        val personal = notes[e.name]
        val learned = learnedNotes[e.name]
        if (personal == null && learned == null) return@map e
        e.copy(body = listOfNotNull(e.body, personal?.let { "$NOTES_HEADER\n$it" },
            learned?.let { Feedback.format(if (it.trial) "knowledge.learned_trial" else "knowledge.learned", "notes" to it.text) }).joinToString("\n\n"),
            trial = learned?.trial == true)
    }

    /** The built-in body of entry [name] itself (without user notes or self-learned supplements); taken as the "existing description" when drafting a self-learned supplement. */
    fun builtIn(name: String): String? = bundled.firstOrNull { it.name == name && it.body != null }?.body

    private fun pick(c: List<KnowledgeEntry>): KnowledgeEntry? = c.firstOrNull { it.body != null } ?: c.firstOrNull()

    fun byPackage(pkg: String): KnowledgeEntry? = pick(entries.filter { pkg in it.packages })

    /** Site knowledge matching the host name (most specific domain first, see [SiteMatch]). */
    fun byDomain(host: String): KnowledgeEntry? = pick(SiteMatch.best(host, entries) { it.domains })

    /**
     * Entries that hit the task text. Entries for installed apps come first, then those with a body, then by matched term length (longer is more specific).
     * [installed] is the installed apps (package name, display name): when the matched term is immediately followed by the name of an installed app,
     * the text refers to that app (e.g. "<brand>" + "app store") and it does not count as a hit.
     */
    fun matchTask(text: String, limit: Int = 2, installed: List<Pair<String, String>> = emptyList()): List<KnowledgeEntry> {
        val t = text.lowercase()
        val packages = installed.map { it.first }.toSet()
        val followers = (APP_KINDS + installed.map { it.second.trim().lowercase() }.filter { it.length >= 2 }).distinct()
        return entries.mapNotNull { e -> score(e, t, followers)?.let { e to it } }
            .sortedWith(compareBy({ it.first.packages.none(packages::contains) }, { it.first.body == null }, { -it.second }))
            .map { it.first }
            .distinctBy { it.service } // For one service, only the higher-ranked of the app and the site is given; the other is given by package name/domain once inside that app/site
            .take(limit)
    }

    /** “Xiaohongshu” → com.xingin.xhs; exact match on name/display name/alias (case-insensitive), looking only at Android entries. */
    fun packageFor(nameOrAlias: String): String? {
        val q = nameOrAlias.trim().lowercase()
        if (q.isEmpty()) return null
        return entries.filter { it.packages.isNotEmpty() }
            .filter { e -> (listOf(e.name, e.display) + e.aliases).any { it.lowercase() == q } }
            .let { c -> c.firstOrNull { it.body != null } ?: c.firstOrNull() }
            ?.let { e -> e.packageAliases.entries.firstOrNull { it.key.lowercase() == q }?.value ?: e.packages.first() }
    }

    private fun score(e: KnowledgeEntry, text: String, followers: List<String>): Int? {
        var best = -1
        val terms = (listOf(e.display, e.name) + e.aliases).map { it.trim().lowercase() }
        // The entry's own name (e.g. "<brand> mall") does not count as "another app"
        val others = followers.filter { f -> terms.none { it.endsWith(f) } }
        for (x in terms) {
            if (x.length < 2 || x in GENERIC || (x.all { it.isDigit() } && x.length < 3)) continue
            val hit = if (x in GENERIC_ZH) NAMING_PREFIXES.any { text.contains(it + x) } else containsTerm(text, x, others)
            if (hit) best = maxOf(best, x.length)
        }
        for (d in e.domains) {
            val x = d.trim().lowercase()
            // Domains count as English words too and need boundaries as well: otherwise x.com would hit xx.com and zhihu.com would hit nzhihu.com
            if (x.length >= 3 && containsTerm(text, x)) best = maxOf(best, x.length)
        }
        return best.takeIf { it >= 0 }
    }

    // English/numeric words require word boundaries so "mm" does not hit "command"; Chinese is matched as a plain substring
    // [others] are other apps' names: when the matched term is immediately followed by one, the term is only part of that app's name
    private fun containsTerm(text: String, term: String, others: List<String> = emptyList()): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(term, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + term.length)
            val leftOk = !term.first().isAsciiAlnum() || before == null || !before.isAsciiAlnum()
            val rightOk = !term.last().isAsciiAlnum() || after == null || !after.isAsciiAlnum()
            val namesOther = others.any { text.startsWith(it, i + term.length) }
            if (leftOk && rightOk && !namesOther) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'

    companion object {
        /** The heading of user notes appended after app/site knowledge. */
        const val NOTES_HEADER = "## The user's own notes (written by the user; when they differ from the text above or the current screen, the current screen takes precedence)"

        /**
         * Reads app (APP.md, Android) and site (SITE.md, web) knowledge from the index generated at build time; the body is the main file minus its header,
         * and apps and sites are linked to each other through [KnowledgeEntry.related].
         */
        fun loadBundled(loader: ClassLoader = KnowledgeStore::class.java.classLoader): List<KnowledgeEntry> {
            val catalog = GuideCatalog.loadBundled(loader)
            val infos = catalog.items.associateBy { it.kind to it.name }
            return catalog.rows.filter { it.kind == KnowledgeIndex.APP || it.kind == KnowledgeIndex.SITE }.map { e ->
                val app = e.kind == KnowledgeIndex.APP
                KnowledgeEntry(
                    name = e.name, display = e.display, surface = if (app) Surface.Android else Surface.Web,
                    packages = e.packages, domains = e.domains, aliases = e.aliases,
                    open = e.open, description = e.description,
                    body = catalog.fullText(infos.getValue(e.kind to e.name))?.trim()?.ifEmpty { null },
                    packageAliases = e.packageAliases,
                    links = e.links.map { raw -> raw.split('|').map(String::trim).let { Link(it[0], it[1], it.getOrNull(2)) } },
                    related = if (app) e.sites else e.apps,
                    commands = e.commands,
                )
            }
        }

        /** The user's notes for an app/site: `<dir>/apps/<id>/NOTES.md`, `<dir>/sites/<id>/NOTES.md`, keyed by entry name. */
        fun loadNotes(dir: File): Map<String, String> =
            listOf("apps", "sites").flatMap { sub ->
                File(dir, sub).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
                    File(d, "NOTES.md").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { d.name to it }
                }
            }.toMap()
    }
}
