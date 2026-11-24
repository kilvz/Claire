package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The repo's eval task set (eval/tasks/<category>/…/<id>.json). */
class TaskSetTest {
    private val evalDir = listOf("eval", "../eval").map(::File).first { File(it, "tasks").isDirectory }
    private val tasksDir = File(evalDir, "tasks")
    private val files = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
    private val tasks = files.map { it to EvalTasks.parse(it.readText()) }

    /** The file's directory relative to tasks, e.g. read/content. */
    private fun group(f: File) = f.parentFile.relativeTo(tasksDir).invariantSeparatorsPath

    /** Special task suites (skill pairs, memory tasks) live in their own directory instead of being split by category. */
    private fun suite(f: File) = group(f).substringBefore('/').takeIf { it in SUITES }

    @Test fun allTasksParseAndValidate() {
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), EvalTasks.validateAll(tasks.map { it.second }))
        tasks.forEach { (f, t) ->
            // Web tasks sit in the web directory: their categories vary, and apps may be empty when no phone app is touched
            if (group(f) == WEB) {
                assertTrue("${t.id}: 网页题的 id 以 web- 开头", t.id.startsWith("web-"))
                t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
                return@forEach
            }
            // The category directory matches the task's category: the harness selects tasks by directory
            if (suite(f) == null) assertEquals(f.path, t.category.name.lowercase(), group(f).substringBefore('/'))
            // Tasks that are impossible by nature and need no particular app (the moon's temperature) may omit apps
            assertTrue("${t.id}: apps 为空", t.apps.isNotEmpty() || t.category == EvalCategory.Impossible)
            t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
            if (t.category == EvalCategory.Impossible) assertEquals(t.id, Expect.Failed, t.expect)
            if (t.category == EvalCategory.Barrier) assertEquals(t.id, Expect.Takeover, t.expect)
            if (t.category == EvalCategory.Read) assertEquals(t.id, Expect.Succeeded, t.expect)
        }
    }

    /** Third-party apps need installed:; built-in phone apps are written {app:<capability>} and do not require installed:. */
    private fun pinnedOk(t: EvalTask, app: String) = APP_TOKEN.matches(app) || "installed:$app" in t.requires

    @Test fun idsUniqueAndMatchFileNames() {
        tasks.forEach { (f, t) -> assertEquals(f.path, f.nameWithoutExtension, t.id) }
        assertEquals(tasks.size, tasks.map { it.second.id }.toSet().size)
    }

    @Test fun compositionMeetsMinimums() {
        assertTrue("总数 ${tasks.size}", tasks.size >= 60)
        val count = tasks.filter { suite(it.first) == null }.groupingBy { group(it.first) }.eachCount()
        val minimums = mapOf(
            "read/system" to 10,
            "write" to 10,
            "read/content" to 15,
            "read/shopping" to 10,
            "irreversible" to 8,
            "impossible" to 5,
            "barrier" to 2,
            WEB to 10,
        )
        minimums.forEach { (g, min) -> assertTrue("$g 只有 ${count[g] ?: 0} 题", (count[g] ?: 0) >= min) }
        assertEquals("有题不在规定的目录里", minimums.keys, count.keys)
    }

    @Test fun skillPairsAreTrainTest() {
        val skills = tasks.filter { suite(it.first) == "skills" }.map { it.second }
        assertEquals(20, skills.size)
        val groups = skills.groupBy { it.id.substringBeforeLast('-') }
        assertEquals(10, groups.size)
        groups.forEach { (g, ts) ->
            assertEquals(g, setOf("train", "test"), ts.map { it.split }.toSet())
            ts.forEach { assertEquals(it.id, "$g-${it.split}", it.id) }
            assertEquals("$g: 一组两题用同一个 App", 1, ts.map { it.apps }.toSet().size)
            assertEquals("$g: 一组两题同一类", 1, ts.map { it.category }.toSet().size)
            assertTrue("$g: 两题参数要不同", ts[0].goal != ts[1].goal)
        }
        assertTrue(skills.none { it.session != null })
    }

    @Test fun memorySessionsTellThenUse() {
        val mem = tasks.filter { suite(it.first) == "memory" }.map { it.second }
        val sessions = mem.groupBy { it.session }
        assertEquals(5, sessions.size)
        sessions.forEach { (session, ts) ->
            assertTrue(session != null)
            val (tell, use) = ts.sortedBy { it.id }.also { assertEquals(2, it.size) }
            assertTrue("${tell.id}: 第一题要让 Claire 记住", "记住" in tell.goal)
            assertTrue("${tell.id}: 第一题不核对记忆", tell.checks.none { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题要核对用到了记忆", use.checks.any { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题不再提「记住」", "记住" !in use.goal)
        }
        assertTrue(mem.none { it.split != null })
    }

    @Test fun partitionsAreStratified() {
        // Every task goes to the tuning pool or the holdout pool; the holdout is about 30%, stratified by directory so every directory has holdouts
        tasks.forEach { (f, t) -> assertTrue("${f.path}: 缺 partition", t.partition in EvalTasks.PARTITIONS) }
        val holdout = tasks.count { it.second.partition == "holdout" }
        assertTrue("保留池 $holdout / ${tasks.size}", holdout * 100 in tasks.size * 25..tasks.size * 35)
        tasks.groupBy { group(it.first) }.forEach { (g, ts) ->
            val h = ts.count { it.second.partition == "holdout" }
            assertTrue("$g: 保留 $h / ${ts.size}", h >= 1 && h * 100 in ts.size * 20..ts.size * 40)
        }
        // A skill pair and a memory session stay in one pool together and are never split
        tasks.filter { suite(it.first) == "skills" }.map { it.second }.groupBy { it.id.substringBeforeLast('-') }
            .forEach { (g, ts) -> assertEquals(g, 1, ts.map { it.partition }.toSet().size) }
        tasks.filter { suite(it.first) == "memory" }.map { it.second }.groupBy { it.session }
            .forEach { (s, ts) -> assertEquals("$s", 1, ts.map { it.partition }.toSet().size) }
    }

    @Test fun askingTasksCanContinue() {
        // Tasks that must ask first need canned answers; otherwise nobody answers during the eval and the run just waits until it times out
        tasks.map { it.second }.filter { t -> t.checks.any { it is Check.AskedUser && it.expected } }
            .also { assertTrue(it.isNotEmpty()) }
            .forEach { assertTrue("${it.id}: 要问的题缺 answers", it.answers.isNotEmpty()) }
    }

    @Test fun writeTasksHaveMarker() {
        val writes = tasks.filter { suite(it.first) != "memory" }.map { it.second }.filter { it.category == EvalCategory.Write }
        assertTrue(writes.isNotEmpty())
        writes.forEach { t ->
            val marker = t.marker
            assertTrue("${t.id}: 没有 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里没写 marker", marker!! in t.goal)
            // The data created must be findable again by the harness itself
            assertTrue("${t.id}: 没有按 marker 核对", t.checks.any { (it is Check.ScreenHas && marker in it.text) || (it is Check.ProbeEquals && marker in it.expected) })
        }
    }

    /** Only the step that spends money asks first: order tasks stop at the confirmation card; tasks that spend nothing, like sending or deleting, are finished without asking first. */
    @Test fun irreversibleTasksAskOnlyBeforeSpending() {
        val irreversible = tasks.map { it.second }.filter { it.category == EvalCategory.Irreversible }
        assertTrue(irreversible.isNotEmpty())
        val (spending, free) = irreversible.partition { it.expect == Expect.Confirmation }
        assertTrue(spending.isNotEmpty() && free.isNotEmpty())
        spending.forEach { t ->
            val card = t.checks.filterIsInstance<Check.ConfirmationShows>()
            // The card must show where, what and how much
            assertTrue("${t.id}: 只有下单要确认", card.isNotEmpty() && card.all { it.action == "下单" })
            assertTrue("${t.id}: 下单要核对对象和金额", card.any { it.target != null && it.amount != null })
            // Spends real account money: run only with the user present and the account manually marked as signed in
            assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
            assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
        }
        free.forEach { t ->
            assertEquals(t.id, Expect.Succeeded, t.expect)
            assertTrue("${t.id}: 不花钱的题不该有确认卡片", t.checks.none { it is Check.ConfirmationShows })
            // If the user said clearly what to do, it should not ask again; it asks only for things only the user knows, like which item to delete
            val asked = t.checks.filterIsInstance<Check.AskedUser>()
            assertTrue("${t.id}: 要核对问没问用户", asked.size == 1)
            // Irreversible actions are done only on data the task itself marked
            val marker = t.marker
            assertTrue("${t.id}: 要有含 {run} 的 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里要写 marker", marker!! in t.goal)
            // Tasks that send to a real account: run only with the user present and the account manually marked as signed in, and verify in the app after sending
            if (t.apps.any { !it.startsWith("{app:") }) {
                assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
                assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
                assertTrue("${t.id}: 要按 marker 核对发出去了", t.checks.any { it is Check.ScreenHas && marker in it.text })
            }
        }
    }

    @Test fun systemAppsAreNotPinned() {
        // The package name of a built-in phone app varies by model: the task names a capability, the run finds the one on this phone via the standard Intent, and the check doesn't pin the package name either
        val system = tasks.map { it.second }.filter { "手机自带" in it.goal }
        assertTrue(system.isNotEmpty())
        system.forEach { t ->
            t.apps.forEach { assertTrue("${t.id}: 手机自带的 App 写 {app:<能力>},不写包名:$it", APP_TOKEN.matches(it)) }
            t.checks.filterIsInstance<Check.ScreenHas>().forEach { assertTrue("${t.id}: screen_has 不钉包名", APP_TOKEN.matches(it.packageName)) }
            t.requires.forEach { assertFalse("${t.id}: 手机自带的 App 不写 installed:", it.startsWith("installed:")) }
        }
    }

    private companion object {
        val SUITES = setOf("skills", "memory")
        val APP_TOKEN = Regex("^\\{app:[a-z]+\\}$")
        /** The directory for web tasks. */
        const val WEB = "web"
    }

    @Test fun noLocalPaths() {
        val scanned = evalDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(scanned.size >= files.size)
        scanned.forEach { f ->
            assertFalse("${f.path} mentions a local path", f.readText().contains("/Users/"))
        }
    }
}
