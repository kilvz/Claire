package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.learn.skills.PLUGIN_KIND
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.PluginSkills
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.learn.teach.TeachSkill

/** Built-in app knowledge (kind `app`), site knowledge (`site`), skills (`skill`), the user's personal skills (`personal`) or plugin skills (`plugin`); [files] are the readable files (main file first, the rest are attachments). */
data class GuideInfo(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val aliases: List<String>,
    val terms: List<String>,
    val files: List<String>,
    /** Names of the associated apps / sites. */
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
)

/** The apps (package names) and websites (domains) a skill states it uses; both empty means none are declared. */
data class SkillPlaces(val packages: Set<String> = emptySet(), val domains: Set<String> = emptySet()) {
    val declared: Boolean get() = packages.isNotEmpty() || domains.isNotEmpty()
}

sealed interface GuideView {
    data class Found(
        val info: GuideInfo, val file: String, val content: String, val truncated: Boolean, val totalChars: Int,
    ) : GuideView

    data class Failed(val error: String, val message: String) : GuideView
}

private const val SKILL_FILE = "SKILL.md"
/** Documents may mention tools this phone does not have; this warns at injection and at the knowledge_read / skill_read boundary. */
const val UNAVAILABLE_TOOLS_NOTE = "(Note: scripts mentioned in the text and tools this phone does not have are not available; use only the tools in your tool list.)"
/** The note when the device supports the sandbox: skill scripts that the manifest marks runnable can be run. */
const val SANDBOX_TOOLS_NOTE = "(Note: scripts marked runnable can be run with run_bundled_script; other scripts and tools this phone does not have are not available.)"

/** The note used when injecting references and in knowledge_read / skill_read, depending on whether sandbox tools are offered. */
fun toolsNote(sandboxAvailable: Boolean): String = if (sandboxAvailable) SANDBOX_TOOLS_NOTE else UNAVAILABLE_TOOLS_NOTE
const val VIEW_LIMIT = 12_000
const val FIND_LIMIT = 10
/** The score threshold for proposing app/site knowledge from the task text at task start: the name/display name/alias must match at least partially. */
const val RECOMMEND_MIN_SCORE = 60
const val PERSONAL_KIND = "personal"
/** App and website knowledge (managed by knowledge_search / knowledge_read); skills are a separate group (built-in skills and personal skills). */
val KNOWLEDGE_KINDS = setOf(KnowledgeIndex.APP, KnowledgeIndex.SITE)
val SKILL_KINDS = setOf(KnowledgeIndex.SKILL, PERSONAL_KIND, PLUGIN_KIND)

/**
 * A read-only catalog: find by name/alias/description, and read text. The built-in part comes from [KnowledgeIndex.RESOURCE] generated at build time and the respective directories,
 * then the user's personal skills are merged in (kind = `personal`, only those in use; a same-named one replaces the built-in one entirely).
 */
class GuideCatalog private constructor(
    private val loader: ClassLoader,
    internal val rows: List<IndexEntry>,
    private val personal: PersonalSkills? = null,
    /** Rewrite applied when a personal skill body is loaded (tool names from before a rename replaced with the current ones); unchanged by default. */
    private val rewrite: (String) -> String = { it },
    /** Skills from enabled plugins, read-only; the names carry the plugin namespace, so they never override built-in/personal skills. */
    private val plugins: PluginSkills? = null,
) {
    private val bundledItems: List<GuideInfo> = rows.map(::info)

    /** Personal and plugin skills are read fresh each time: accepting, disabling and toggling plugins happens at any moment. Plugins do not override built-in/personal skills. */
    val items: List<GuideInfo>
        get() {
            val mine = personalEntries().map { it.info }
            val personalNames = mine.map { it.name }.toSet()
            val bundled = bundledItems.filter { it.name !in personalNames }
            val reserved = personalNames + bundled.map { it.name }
            return mine + pluginEntries().filter { it.name !in reserved } + bundled
        }

    private fun pluginEntries(): List<GuideInfo> = plugins?.entries().orEmpty()

    /**
     * For [route] `android`: app knowledge named [name] exists; for `browser`: site knowledge named [name] exists, or an app named [name] lists sites.
     */
    fun hasRoute(name: String, route: String): Boolean = when (route) {
        "android" -> bundledItems.any { it.kind == KnowledgeIndex.APP && it.name == name }
        "browser" -> bundledItems.any { it.name == name && (it.kind == KnowledgeIndex.SITE || (it.kind == KnowledgeIndex.APP && it.sites.isNotEmpty())) }
        else -> false
    }

    /**
     * Scoring: whole-word match on name/display name/alias > contains (or appears whole inside the query term) > search terms > description; each whitespace-separated word scores separately,
     * and a result is returned only if at least one word hits and the total is not below [minScore]. When [among] is given, only those kinds are searched (see [KNOWLEDGE_KINDS], [SKILL_KINDS]).
     */
    fun find(query: String, kind: String? = null, limit: Int = FIND_LIMIT, minScore: Int = 0, among: Set<String>? = null): List<GuideInfo> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return items.filter { (kind == null || it.kind == kind) && (among == null || it.kind in among) }
            .mapNotNull { s -> score(s, tokens).takeIf { it > 0 && it >= minScore }?.let { s to it } }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    private fun score(s: GuideInfo, tokens: List<String>): Int {
        val names = (listOf(s.name, s.display) + s.aliases).map { it.lowercase() }
        val terms = s.terms.map { it.lowercase() }
        val desc = s.description.lowercase()
        return tokens.map { t ->
            when {
                names.any { it == t } -> 100
                // A single ASCII character used as a substring would hit almost every English name and description, so only exact equality counts
                t.length == 1 && t[0].code < 128 -> 0
                // Chinese targets usually carry no spaces and the whole sentence is one word: a name appearing inside the sentence also counts as a partial match
                names.any { it.contains(t) || (it.length >= 2 && t.contains(it)) } -> 60
                terms.any { it == t } -> 50
                terms.any { it.contains(t) } -> 40
                desc.contains(t) -> 20
                else -> 0
            }
        }.sum()
    }

    /** [name] may be written `app:meituan` / `site:jd.com` / `skill:documents` / `personal:xxx` / `plugin:<plugin id>/<skill>`, or just the name (a duplicate name reports ambiguity). */
    /** [note] is the note placed before the body (see [toolsNote]). When [among] is given, only those kinds are read; when the name belongs to another group, it says which tool should read it. */
    fun view(name: String, file: String? = null, note: String = UNAVAILABLE_TOOLS_NOTE, among: Set<String>? = null): GuideView {
        val n = name.trim()
        val prefixed = n.substringBefore(':', "").takeIf { it in KnowledgeIndex.KINDS || it == PERSONAL_KIND || it == PLUGIN_KIND }
        val id = if (prefixed != null) n.substringAfter(':') else n
        val named = items.filter { it.name == id && (prefixed == null || it.kind == prefixed) }
        val hits = named.filter { among == null || it.kind in among }
        val knowledge = among == KNOWLEDGE_KINDS
        val info = when (hits.size) {
            0 -> return GuideView.Failed(if (knowledge) "knowledge_not_found" else "skill_not_found", when {
                named.isNotEmpty() && knowledge -> "'$n' is a skill, not App or website knowledge; read it with skill_read."
                named.isNotEmpty() -> "'$n' is App or website knowledge, not a skill; read it with knowledge_read."
                knowledge -> "No App or website knowledge named '$n'. Use knowledge_search to find it."
                else -> "No skill named '$n'. Use skill_search to find it."
            })
            1 -> hits.single()
            else -> return GuideView.Failed(if (knowledge) "ambiguous_name" else "ambiguous_skill",
                "'$n' exists as ${hits.joinToString { it.kind }}; use ${hits.joinToString(" or ") { "${it.kind}:${it.name}" }}.")
        }
        val main = info.files.first()
        val path = (file?.trim()?.takeIf { it.isNotEmpty() } ?: main).removePrefix("./")
        if (info.kind == PERSONAL_KIND) return viewPersonal(info, path)
        if (info.kind == PLUGIN_KIND) return viewPlugin(info, path, note)
        if (path !in info.files)
            return GuideView.Failed("file_not_found", "No file '$path' in ${info.kind}:${info.name}. Available: ${info.files.joinToString()}.")
        val text = (if (path == main) fullText(info) else read(info, path))
            ?: return GuideView.Failed("file_not_found", "File '$path' could not be read.")
        val cut = text.length > VIEW_LIMIT
        return GuideView.Found(info, path, note + "\n" + (if (cut) text.take(VIEW_LIMIT) + "\n…(truncated; use file_path to view the other files)" else text), cut, text.length)
    }

    // A broken personal skill reports an explicit error instead of falling back to a same-named built-in skill
    private fun viewPersonal(info: GuideInfo, path: String): GuideView {
        if (path != SKILL_FILE) return GuideView.Failed("file_not_found", "Personal skill ${info.name} has only $SKILL_FILE.")
        val body = personalEntries().find { it.info.name == info.name }?.body
            ?: return GuideView.Failed("personal_skill_invalid",
                "The user's personal skill '${info.name}' is damaged and cannot be read; do not rely on it. The user can fix or delete it in Memory & skills.")
        val cut = body.length > VIEW_LIMIT
        return GuideView.Found(info, SKILL_FILE, if (cut) body.take(VIEW_LIMIT) + "\n…(truncated)" else body, cut, body.length)
    }

    // Plugin skills are third-party content: state the source and treat the body as untrusted content
    private fun viewPlugin(info: GuideInfo, path: String, note: String): GuideView {
        if (path !in info.files) {
            return GuideView.Failed("file_not_found", "No file '$path' in plugin skill ${info.name}. Available: ${info.files.joinToString()}.")
        }
        val body = plugins?.read(info, path)
            ?: return GuideView.Failed("file_not_found", "Plugin skill ${info.name} could not be read; the plugin may have been disabled or removed.")
        val pluginId = info.name.substringBefore('/')
        val text = "(third-party plugin skill: $pluginId)\n$note\n$body"
        val cut = text.length > VIEW_LIMIT
        return GuideView.Found(info, path, if (cut) text.take(VIEW_LIMIT) + "\n…(truncated)" else text, cut, text.length)
    }

    /** The body of the main file (APP.md / SITE.md / SKILL.md) with the header removed. Null for a broken personal skill. */
    fun fullText(info: GuideInfo): String? {
        if (info.kind == PERSONAL_KIND) return personalEntries().find { it.info.name == info.name }?.body
        if (info.kind == PLUGIN_KIND) return plugins?.read(info, info.files.first())
        return read(info, KnowledgeIndex.fileOf(info.kind))
    }

    private fun read(info: GuideInfo, file: String): String? =
        resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/$file")?.let { FrontMatter.parse(it.toString(Charsets.UTF_8)).second }

    /** The directory inside the sandbox (under `/opt/mika/skills`), also the source argument of run_bundled_script, e.g. `skills/documents`, `skills/research`. Plugin skills do not go through this entry point. */
    fun scriptSource(info: GuideInfo): String =
        if (info.kind == PLUGIN_KIND) "" else "${KnowledgeIndex.dirOf(info.kind).substringAfterLast('/')}/${info.name}"

    /**
     * The scripts to sync into the sandbox: for each entry with a `scripts/manifest.json`, the manifest itself plus the files it lists.
     * Keys are paths relative to `/opt/mika/skills`.
     */
    fun scriptFiles(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        for (info in bundledItems) {
            val src = "${KnowledgeIndex.dirOf(info.kind)}/${info.name}"
            val manifest = resource("$src/scripts/manifest.json") ?: continue
            val dir = scriptSource(info)
            out["$dir/scripts/manifest.json"] = manifest
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) resource("$src/${e.file}")?.let { out["$dir/${e.file}"] = it }
        }
        return out
    }

    /** Scripts the manifest marks runnable (and of a script type that can run), as `directory/file`, e.g. `skills/documents/scripts/extract_pdf.py`. */
    fun runnableScripts(): Set<String> {
        val out = sortedSetOf<String>()
        for (info in bundledItems) {
            val manifest = resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/scripts/manifest.json") ?: continue
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) {
                if (e.runnable && ScriptManifest.interpreterFor(e.file) != null) out += "${scriptSource(info)}/${e.file}"
            }
        }
        return out
    }

    /** Whether any built-in site knowledge declares site commands (if none does, the tool for running site commands is not offered). */
    fun hasSiteCommands(): Boolean = rows.any { it.kind == KnowledgeIndex.SITE && it.commands.isNotEmpty() }

    /**
     * The site commands declared by the site that host [host] belongs to, with script bodies; empty when there are none. The site is identified by the same rule as when injecting site knowledge ([SiteMatch], most specific domain first),
     * and if the identified site has no commands then there are none: it does not fall back to another site.
     */
    fun siteCommands(host: String): List<SiteCommand> {
        val site = SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()
            ?: return emptyList()
        val dir = "${KnowledgeIndex.dirOf(KnowledgeIndex.SITE)}/${site.name}"
        return site.commands.mapNotNull { d ->
            resource("$dir/${d.file}")?.toString(Charsets.UTF_8)?.let { SiteCommand(site.name, d, SiteCommandFile.parse(d.file, it).body) }
        }
    }

    /** The pace written in the site knowledge host [host] belongs to (the site is identified by the same rule as [siteCommands]); null when there is none. */
    fun sitePace(host: String): String? =
        SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()?.pace

    /**
     * The apps and websites declared in the header of personal skill [name], converted to package names and domains. Each entry is recognized by app/site knowledge names and aliases (case-insensitive);
     * recognized ones are replaced with the package names and domains that knowledge registers, and the associations app and site knowledge register with each other are counted as well (writing the website also counts when it is actually done in its app);
     * unrecognized entries that look like a package name or domain (contain a dot, no whitespace) are kept as they are (self-learned skills write package names), while others (display names, typos) do not count as declared,
     * so that one unmatched entry does not permanently stop the skill from being recognized as following it. Null when this personal skill does not exist (or is neither in use nor on trial).
     */
    fun skillPlaces(name: String): SkillPlaces? {
        val skill = personal?.get(name)?.takeIf { it.status == SkillStatus.active || it.status == SkillStatus.trial } ?: return null
        val meta = FrontMatter.parse(skill.markdown).first
        fun many(k: String) = (meta[k] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull((meta[k] as? String)?.takeIf { it.isNotEmpty() })
        fun known(kind: String, entry: String) = rows.filter { r ->
            r.kind == kind && (r.name.equals(entry, true) || r.aliases.any { it.equals(entry, true) } ||
                entry in r.packages || r.domains.any { it.equals(entry, true) })
        }
        fun raw(entry: String) = entry.takeIf { '.' in it && entry.none(Char::isWhitespace) }
        val entries = many("apps") + many("sites") + many("domains")
        val apps = entries.flatMap { known(KnowledgeIndex.APP, it) }.toMutableSet()
        val sites = entries.flatMap { known(KnowledgeIndex.SITE, it) }.toMutableSet()
        // Where app and site knowledge register each other: the Bilibili app entry writes sites: [bilibili.com] and the site entry writes apps: [bilibili]
        apps += sites.flatMap { s -> s.apps.flatMap { known(KnowledgeIndex.APP, it) } }
        sites += apps.flatMap { a -> a.sites.flatMap { known(KnowledgeIndex.SITE, it) } }
        val unresolved = entries.filter { e -> known(KnowledgeIndex.APP, e).isEmpty() && known(KnowledgeIndex.SITE, e).isEmpty() }.mapNotNull(::raw)
        return SkillPlaces(
            packages = apps.flatMap { it.packages }.toSet() + unresolved,
            domains = sites.flatMap { it.domains }.toSet() + unresolved,
        )
    }

    /** The pace written by the app knowledge package [packageName] belongs to (the package is in its packages); null when there is none. */
    fun appPace(packageName: String): String? =
        rows.firstOrNull { it.kind == KnowledgeIndex.APP && packageName in it.packages }?.pace

    /** Package names of built-in app knowledge apps marked as limiting browsing pace (pace: careful) (the initial content of the per-app operation pace list in settings). */
    fun carefulApps(): List<String> =
        rows.filter { it.kind == KnowledgeIndex.APP && it.pace == "careful" }.flatMap { it.packages }.sorted()

    private fun resource(rel: String): ByteArray? = loader.getResourceAsStream(rel)?.use { it.readBytes() }

    /** [body] is null when the SKILL.md header is missing, the name does not match, or the body is empty. */
    private class PersonalEntry(val info: GuideInfo, val body: String?)

    private fun personalEntries(): List<PersonalEntry> =
        personal?.list().orEmpty().filter { it.status == SkillStatus.active || it.status == SkillStatus.trial }.map { s ->
            // Tool names written before the rename become the current ones, so the model only reads names that work
            val (meta, body) = FrontMatter.parse(s.markdown).let { (m, b) -> m to rewrite(b) }
            fun one(k: String) = meta[k] as? String
            fun many(k: String) = (meta[k] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(k)?.takeIf { it.isNotEmpty() })
            val valid = one("name") == s.name && body.isNotBlank()
            val demonstrated = TeachSkill.isDemonstrated(s)
            val trialNote = if (s.status == SkillStatus.trial) "[Unverified personal skill draft: use it only as a trial; the result must be checked.]\n" else ""
            val demoNote = if (demonstrated) TeachSkill.USE_NOTE + "\n" else ""
            PersonalEntry(
                GuideInfo(
                    kind = PERSONAL_KIND, name = s.name, display = one("display")?.takeIf { it.isNotEmpty() } ?: s.name,
                    description = (if (s.status == SkillStatus.trial) "[unverified: check the result when trying it] " else "") +
                        (if (demonstrated) TeachSkill.INDEX_NOTE else "") + (one("description") ?: ""),
                    aliases = many("aliases"),
                    terms = many("apps") + many("domains"), files = listOf(SKILL_FILE),
                    apps = many("apps"), sites = many("sites") + many("domains"),
                ),
                body.takeIf { valid }?.let { demoNote + trialNote + it },
            )
        }

    companion object {
        fun loadBundled(loader: ClassLoader = GuideCatalog::class.java.classLoader): GuideCatalog {
            val rows = loader.getResourceAsStream(KnowledgeIndex.RESOURCE)?.use { KnowledgeIndex.decode(it.readBytes().toString(Charsets.UTF_8)) }
            return GuideCatalog(loader, rows.orEmpty())
        }

        /** Built-in catalog + personal skills + plugin skills; [rewrite] is the rewrite applied when a personal skill body is loaded (tool names from before a rename become the current ones). */
        fun withPersonal(
            bundled: GuideCatalog,
            personal: PersonalSkills,
            rewrite: (String) -> String = { it },
            plugins: PluginSkills? = null,
        ): GuideCatalog =
            GuideCatalog(bundled.loader, bundled.rows, personal, rewrite, plugins)

        /** Built-in catalog + plugin skills only (for tests or scenarios without personal skills). */
        fun withPlugins(bundled: GuideCatalog, plugins: PluginSkills): GuideCatalog =
            GuideCatalog(bundled.loader, bundled.rows, null, { it }, plugins)

        private fun info(e: IndexEntry) = GuideInfo(
            kind = e.kind, name = e.name, display = e.display, description = e.description,
            aliases = e.aliases, terms = e.terms, files = listOf(KnowledgeIndex.fileOf(e.kind)) + e.files,
            apps = e.apps, sites = e.sites,
        )
    }
}
