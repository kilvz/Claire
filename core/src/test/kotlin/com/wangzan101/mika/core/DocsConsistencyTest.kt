package com.wangzan101.mika.core

import com.wangzan101.mika.core.assembly.knownToolNames
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The docs and the code must not drift apart: file paths, Kotlin class names and tool names wrapped in backticks in the docs must really exist in the repo.
 * Scope: README.md, every .md under docs/ (including the per-language READMEs in docs/readme/), eval/README.md, tools/sandbox/README.md.
 * Also, these same docs must not hard-code counts of things that change (how many built-in knowledge entries, tools, providers or eval tasks), see [docsDoNotHardCodeCounts].
 *
 * Only the form "the whole backticked span is one identifier" is checked; none of the following are checked (these are rules, not a whitelist):
 *  - fenced code blocks (```) are skipped as a whole; an inline backticked span containing whitespace is a command or code fragment;
 *  - spans containing < > * … { } ( ) = : " ' $ , ; | # are placeholders, wildcards, calls, key/value pairs or example values;
 *  - spans starting with / or ~ are device or system paths, and ones containing :// are URLs;
 *  - paths: for ones containing /, only check when the first segment is a directory or file that really exists at the repo root (`files/sandbox/rootfs`, `read/system` and the like are runtime artifacts or category names);
 *    for ones without /, only check code-like extensions (kt, py, xml, etc.); json, md, txt and the like are usually runtime artifacts;
 *    when checking: it exists directly under the root, or it is the "module/package path/file" shorthand (`app/task/X.kt`), or the middle is elided with ... (`core/.../X.kt`), or the short file name appears anywhere in the repo;
 *  - class names: upper camel case with at least two uppercase letters (a word like `Claire` does not count as a class); the core and app sources (tests included) must have a class/object/interface/typealias
 *    declaration, a top-level function declaration, or a file of the same name; classes imported from packages outside this repo (Android, JDK, library classes) are not this repo's concern;
 *    a display name that appears as a string literal in the sources (such as the provider name `DeepSeek`) is not a class either, see [displayNames];
 *  - tool names: only check snake_case whose first segment is the first segment of an existing tool name (parameter names like `max_seconds` are not checked), against [knownToolNames],
 *    which is the list at the tool registry, not a golden snapshot; Android string resource names (`R.string.*`, such as `settings_all_ready_detail`)
 *    look like tool names but are not tools, see [stringResourceNames], not checked.
 */
class DocsConsistencyTest {
    private val root = File("..").canonicalFile
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "reference", "dist", ".claude", ".omc", "node_modules")

    private val repoFiles: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private val repoDirs: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    private val sourceFiles: List<File> by lazy {
        listOf("core/src", "app/src").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }
    private val sourceText: String by lazy { sourceFiles.joinToString("\n") { it.readText() } }
    private val sourceNames: Set<String> by lazy { sourceFiles.map { it.nameWithoutExtension }.toSet() }

    private fun docs(): List<File> {
        val list = mutableListOf(File(root, "README.md"), File(root, "eval/README.md"), File(root, "tools/sandbox/README.md"))
        File(root, "docs").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.let { list += it }
        return list.filter { it.isFile }
    }

    /** Strip fenced code blocks, then collect the contents of every inline backticked span. */
    private fun spans(text: String): List<String> {
        var fenced = false
        val prose = text.lines().filter { line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
        }.joinToString("\n")
        return Regex("`([^`\n]+)`").findAll(prose).map { it.groupValues[1].trim() }.toList()
    }

    private val notIdentifier = Regex("""[\s<>*…{}()=:"'$,;|#]""")
    private val pathExtensions = setOf("kt", "kts", "java", "md", "json", "xml", "txt", "yml", "yaml", "gradle", "toml", "properties", "py", "sh", "js", "html", "pro", "aidl", "cpp", "h")
    private val tool = Regex("""[a-z][a-z0-9]*(_[a-z0-9]+)+""")
    // Status codes are not model tool names even when their prefix matches a tool; field names in WeChat messages are the same (not tools exposed to the model).
    private val nonToolNames = setOf("copy_not_found", "copy_not_opened", "task_progress", "image_item")

    /**
     * Tools registered for the model in live voice sessions (defined in the app's call protocol layer, not in core's tool table):
     * when the docs mention them, let them pass as "really existing".
     */
    private val sessionToolNames = setOf("mika_request")
    private val classLike = Regex("""[A-Z][A-Za-z0-9]*""")

    private val toolNames: Set<String> by lazy { knownToolNames() }
    private val toolPrefixes: Set<String> by lazy { toolNames.map { it.substringBefore('_') }.toSet() }

    /**
     * Names of the computer/remote API, not tool names exposed to the model. `mika_` is now the prefix of our own tools,
     * so these remote call names in the docs can no longer pass automatically by "unknown prefix"; list them one by one.
     */
    private val remoteApiNames = setOf(
        "mika_run_task", "mika_task_status", "mika_wait_task", "mika_answer", "mika_steer_task",
        "mika_cancel_task", "mika_list_tasks", "mika_device_state", "mika_wake_device", "mika_unlock_device",
        // Names of the computer-side control API (not tools exposed to the model); `task_` became a tool prefix because of task_memory_*, so it must be listed one by one too.
        "task_steer", "task_id", "task_recovery", "task_files", "task_interrupted", "task_result_v2",
    )

    private fun isPath(s: String): Boolean =
        !s.startsWith("/") && !s.startsWith("~") && "://" !in s &&
            ('/' in s || s.substringAfterLast('.', "").let { it in pathExtensions && s.length > it.length + 1 })

    /** What these docs describe are things that only exist while the app runs (directories and files on the device), not in the repo; spell out the reason for each. */
    private val runtimeOnly = mapOf(
        "eval/logged-in.txt" to "manual eval login marker, kept in the app's external files directory on the device, not in the repo",
        "usb-start.sh" to "the phone assistant start script is written by the app to the device's external files directory, not in the repo",
        "connect-computer.sh" to "the computer connection script is written by the app to the device's external files directory, not in the repo",
    )

    /** Only paths that start with a top-level directory or file that really exists under the repo root, and code-like short file names, point at something in the repo; the rest are runtime artifacts or relative notations. */
    private val topLevel: Set<String> by lazy { File(root, ".").list().orEmpty().filter { it !in skipDirs }.toSet() }
    private val codeExtensions = setOf("kt", "kts", "java", "py", "sh", "gradle", "xml", "aidl", "cpp", "h")

    private fun pathExists(rel: String): Boolean {
        val clean = rel.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean in runtimeOnly) return true
        val dir = rel.endsWith("/")
        // The "..." elision form: the head is a real directory prefix, the tail is the end of some file path (the extension may be left off)
        if ("..." in clean) {
            val head = clean.substringBefore("...").trimEnd('/')
            val tail = clean.substringAfterLast("...").trimStart('/')
            return repoFiles.any { f -> f.startsWith(head) && (f.endsWith("/$tail") || f.substringBeforeLast('.').endsWith("/$tail")) }
        }
        if ('/' !in clean) {
            return if (clean.substringAfterLast('.', "") in codeExtensions) repoFiles.any { it == clean || it.endsWith("/$clean") } else true
        }
        val first = clean.substringBefore('/')
        if (first !in topLevel) return true
        if (File(root, clean).exists()) return true
        // The "module/package path/file" shorthand (e.g. app/task/X.kt means app/src/main/java/.../task/X.kt): any path under the module that ends with the remainder matches
        val rest = clean.substringAfter('/')
        return if (dir) repoDirs.any { it.startsWith("$first/") && (it.endsWith("/$rest")) }
        else repoFiles.any { it.startsWith("$first/") && it.endsWith("/$rest") }
    }

    private fun classExists(name: String): Boolean =
        name in sourceNames || Regex("""\b(class|object|interface|typealias|fun)\s+$name\b""").containsMatchIn(sourceText)

    private fun importedFromOutside(name: String): Boolean =
        Regex("""(?m)^import\s+(?!com\.wangzan101\.mika\.)[\w.]+\.$name\b""").containsMatchIn(sourceText)

    /**
     * A display name that appears as a string literal in the sources is not a class: provider/brand names (such as `DeepSeek`
     * shown on the settings screen) appear only in UI text; the docs mentioning one assert that this text exists, not that a class is referenced.
     */
    private val displayNames = setOf("DeepSeek")

    /**
     * Android string resource names (`R.string.*`): underscore-joined resource names like `settings_all_ready_detail`
     * and `phone_repair_sub` often have a first segment that collides with a tool prefix (`settings`, `phone`), but they are not model tools.
     * Checked against the name declared in strings*.xml in each values directory under app/src/main/res.
     */
    private val stringResourceNames: Set<String> by lazy {
        File(root, "app/src/main/res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name.startsWith("strings") }
            .flatMap { Regex("""name="([^"]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
    }

    private fun problems(doc: File): List<String> {
        val rel = doc.relativeTo(root).invariantSeparatorsPath
        return spans(doc.readText()).distinct().mapNotNull { s ->
            if (notIdentifier.containsMatchIn(s)) return@mapNotNull null
            when {
                isPath(s) -> if (pathExists(s)) null else "$rel: path `$s` not found in the repo"
                tool.matches(s) -> if (s in remoteApiNames || s in nonToolNames || s in sessionToolNames || s in stringResourceNames || s.substringBefore('_') !in toolPrefixes || s in toolNames) null else "$rel: tool name `$s` is not one of the existing tools"
                classLike.matches(s) && s.count { it.isUpperCase() } >= 2 && s.any { it.isLowerCase() } ->
                    if (classExists(s) || importedFromOutside(s) || s in displayNames) null else "$rel: class name `$s` not found in core/app sources"
                else -> null
            }
        }
    }

    /**
     * Things that change must not be pinned to a fixed count: phrasings like "18 apps", "60+ tools" or "128 eval tasks" (in Chinese, a numeral plus a measure word) go stale as soon as content is added.
     * To list things, use the generated sections in the README (`./gradlew updateReadme`); if a line really must state a count, append [countOk] to it and say why.
     */
    private val hardCount = Regex(
        """\d+\s*多?\s*(份|个|道|家)\s*(内置)?\s*(App|app|网站|站点|技能|工具|服务商|预设|(真机)?评测题)""" +
            """|\b\d+\+?\s+(built-in\s+)?(apps?|websites?|sites?|skills?|tools?|providers?|presets?|(on-device\s+)?eval\s+tasks?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val countOk = "<!-- count-ok -->"

    @Test fun docsDoNotHardCodeCounts() {
        val bad = docs().flatMap { doc ->
            val rel = doc.relativeTo(root).invariantSeparatorsPath
            doc.readLines().withIndex().filter { (_, line) -> countOk !in line && hardCount.containsMatchIn(line) }
                .map { (i, line) -> "$rel:${i + 1}: 「${hardCount.find(line)!!.value}」" }
        }
        assertTrue("docs hard-code counts that change; use wording without a count (use README's generated sections for lists):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun countPatternCatchesTypicalWording() {
        listOf("18 份 App", "12 份网站", "4 份技能", "60 多个工具", "128 道真机评测题", "7 家服务商",
            "18 apps", "12 websites", "4 skills", "60 tools", "60+ tools", "7 providers", "18 built-in apps", "128 eval tasks").forEach {
            assertTrue(it, hardCount.containsMatchIn(it))
        }
        listOf("最多 3 条知识", "一次最多 9 个", "每个任务最多 3 份,关联的技能",
            "Android 13 and later", "python3 tools/x.py", "up to 3 notes", "arm64 devices").forEach {
            assertTrue(it, !hardCount.containsMatchIn(it))
        }
    }

    @Test fun inputsAreFound() {
        assertTrue("repo root not found (the working directory should be core/)", File(root, "settings.gradle.kts").isFile)
        assertTrue("no docs found", docs().size >= 5)
        assertTrue(toolNames.contains("knowledge_read"))
        assertTrue(classExists("ToolDef"))
        assertTrue(spans(docs().joinToString("\n") { it.readText() }).size > 50)
    }

    @Test fun docsMentionOnlyThingsThatExist() {
        // Implementation plans intentionally name files and classes that later tasks will create.
        val bad = docs().filterNot {
            it.relativeTo(root).invariantSeparatorsPath.startsWith("docs/design/plans/")
        }.flatMap(::problems)
        assertTrue("docs mention things that are not in the repo:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
