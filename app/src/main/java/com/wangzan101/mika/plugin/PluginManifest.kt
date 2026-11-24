package com.wangzan101.mika.plugin

import com.wangzan101.mika.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Plugin manifest (Schema 1); P1a accepts skills only, and P1b/P2/P3 gradually open up tools, pages and settings. */
@Serializable
data class PluginManifest(
    val schema: Int = 0,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val kind: String = KIND_CAPABILITY,
    val minApp: String? = null,
    val author: String? = null,
    val description: String? = null,
    val permissions: List<String> = emptyList(),
    val tools: List<PluginToolDecl> = emptyList(),
    val skills: List<PluginSkillDecl> = emptyList(),
    val pages: List<PluginPageDecl> = emptyList(),
    val settings: List<PluginSettingDecl> = emptyList(),
    val promptTransform: String? = null,
) {
    /**
     * Validates the manifest; returns problem codes (empty means it passed). P1a fails closed: only plugins carrying
     * skills alone are accepted,
     * and any permission, tool, page or setting makes installation fail. [currentAppVersion] is used for the `minApp`
     * comparison.
     */
    fun validate(currentAppVersion: String = BuildConfig.VERSION_NAME): List<String> = buildList {
        if (schema != SCHEMA) add(ERR_SCHEMA)
        if (!ID.matches(id)) add(ERR_ID)
        if (name.isBlank()) add(ERR_NAME)
        if (!SemVer.isValid(version)) add(ERR_VERSION)
        if (kind != KIND_CAPABILITY) add(ERR_KIND)
        minApp?.let {
            if (!SemVer.isValid(it)) add(ERR_MIN_APP)
            else if (SemVer.compare(it, currentAppVersion) > 0) add(ERR_MIN_APP_TOO_HIGH)
        }
        for (p in permissions) if (p !in ALLOWED_PLUGIN_PERMISSIONS) add(ERR_INVALID_PERMISSION)
        if (pages.isNotEmpty() || settings.isNotEmpty()) add(ERR_ONLY_SKILLS)
        val toolNames = mutableSetOf<String>()
        for (t in tools) {
            if (!validScriptPath(t.script)) add(ERR_SCRIPT_NOT_SH)
            if (!toolNames.add(t.name)) add(ERR_TOOL)
        }
        if (promptTransform != null && !validScriptPath(promptTransform)) add(ERR_INVALID_PROMPT_TRANSFORM)
        val seen = mutableSetOf<String>()
        for (s in skills) {
            if (!SKILL_NAME.matches(s.name) || !seen.add(s.name)) add(ERR_SKILL)
            // A skill directory must be skills/<name> inside the package; no .., absolute path or alias allowed
            if (s.path != "skills/${s.name}" || s.path.contains("..")) add(ERR_SKILL_PATH)
        }
    }

    companion object {
        const val SCHEMA = 1
        const val KIND_CAPABILITY = "capability"

        /** Globally unique plugin id; also used as the `files/plugins/<id>/` directory name, and the regex guarantees no path can be built from it. */
        val ID = Regex("^[a-z0-9]+(\\.[a-z0-9_-]+)+$")
        val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

        const val ERR_SCHEMA = "schema_unsupported"
        const val ERR_ID = "invalid_id"
        const val ERR_NAME = "missing_name"
        const val ERR_VERSION = "invalid_version"
        const val ERR_KIND = "unsupported_kind"
        const val ERR_MIN_APP = "invalid_min_app"
        const val ERR_MIN_APP_TOO_HIGH = "app_too_old"
        const val ERR_PERMISSIONS = "permissions_unsupported"
        const val ERR_ONLY_SKILLS = "only_skills_supported"
        const val ERR_SCRIPT_NOT_SH = "script_not_sh"
        const val ERR_INVALID_PERMISSION = "invalid_permission"
        const val ERR_INVALID_PROMPT_TRANSFORM = "invalid_prompt_transform"
        const val ERR_TOOL = "invalid_tool"
        const val ERR_SKILL = "invalid_skill"
        const val ERR_SKILL_PATH = "invalid_skill_path"

        private val json = Json { ignoreUnknownKeys = true }

        /** Unknown fields are ignored (forward compatible); a format error throws [kotlinx.serialization.SerializationException]. */
        fun parse(text: String): PluginManifest = json.decodeFromString(text)
    }
}

@Serializable
data class PluginToolDecl(
    val name: String = "",
    val description: String = "",
    val script: String? = null,
    val timeoutSec: Int = 60,
    val risk: String? = null,
    val annotations: Map<String, Boolean> = emptyMap(),
    val maxCostUnits: Int? = null,
    val inputSchema: JsonObject? = null,
)

@Serializable
data class PluginSkillDecl(val name: String = "", val path: String = "", val display: String? = null)

@Serializable
data class PluginPageDecl(val id: String = "", val entry: String = "", val title: String? = null)

@Serializable
data class PluginSettingDecl(val key: String = "", val type: String = "string", val label: String? = null)

/** One entry in the registry; the manifest is the source of truth for content, while this stores enabled state and install info. */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
    val skillNames: List<String> = emptyList(),
    val description: String? = null,
    val permissions: List<String> = emptyList(),
)

/** Semantic version parsing and comparison (used only for `minApp`). */
internal object SemVer {
    private val RE = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )

    fun isValid(version: String): Boolean = RE.matches(version)

    /** -1/0/1; build metadata does not participate in the comparison, and a pre-release ranks below the release of the same version. */
    fun compare(a: String, b: String): Int {
        val (ac, ap) = parts(a) ?: return a.compareTo(b)
        val (bc, bp) = parts(b) ?: return a.compareTo(b)
        for (i in 0 until 3) ac[i].compareTo(bc[i]).let { if (it != 0) return it.sign() }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ai = ap.split('.')
        val bi = bp.split('.')
        for (i in 0 until minOf(ai.size, bi.size)) {
            val x = ai[i]
            val y = bi[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c.sign()
        }
        return ai.size.compareTo(bi.size).sign()
    }

    private fun parts(version: String): Pair<List<Int>, String?>? {
        val m = RE.matchEntire(version) ?: return null
        return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) to
            m.groupValues[4].takeIf { it.isNotEmpty() }
    }

    private fun Int.sign(): Int = if (this < 0) -1 else if (this > 0) 1 else 0
}

/** Allowlist of permissions plugin scripts can currently declare; anything not listed here is refused installation. */
private val ALLOWED_PLUGIN_PERMISSIONS = setOf("run-scripts", "rewrite-prompt")

/** A script path must stay inside the plugin package and be a `.sh`: non-empty, ending in `.sh`, not absolute, no backslashes, no `..` or empty segments. */
private fun validScriptPath(p: String?): Boolean =
    p != null && p.endsWith(".sh") && !p.startsWith("/") && !p.contains('\\') &&
        p.split('/').none { it == ".." || it.isEmpty() }
