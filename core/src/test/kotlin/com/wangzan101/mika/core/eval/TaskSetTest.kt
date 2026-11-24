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
                assertTrue("${t.id}: web task ids must start with web-", t.id.startsWith("web-"))
                t.apps.forEach { assertTrue("${t.id}: missing installed:$it", pinnedOk(t, it)) }
                return@forEach
            }
            // The category directory matches the task's category: the harness selects tasks by directory
            if (suite(f) == null) assertEquals(f.path, t.category.name.lowercase(), group(f).substringBefore('/'))
            // Tasks that are impossible by nature and need no particular app (the moon's temperature) may omit apps
            assertTrue("${t.id}: apps is empty", t.apps.isNotEmpty() || t.category == EvalCategory.Impossible)
            t.apps.forEach { assertTrue("${t.id}: missing installed:$it", pinnedOk(t, it)) }
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
        assertTrue("total ${tasks.size}", tasks.size >= 60)
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
        minimums.forEach { (g, min) -> assertTrue("$g only has ${count[g] ?: 0} tasks", (count[g] ?: 0) >= min) }
        assertEquals("some task is not in an expected directory", minimums.keys, count.keys)
    }

    @Test fun skillPairsAreTrainTest() {
        val skills = tasks.filter { suite(it.first) == "skills" }.map { it.second }
        assertEquals(20, skills.size)
        val groups = skills.groupBy { it.id.substringBeforeLast('-') }
        assertEquals(10, groups.size)
        groups.forEach { (g, ts) ->
            assertEquals(g, setOf("train", "test"), ts.map { it.split }.toSet())
            ts.forEach { assertEquals(it.id, "$g-${it.split}", it.id) }
            assertEquals("$g: both tasks in a pair use the same app", 1, ts.map { it.apps }.toSet().size)
            assertEquals("$g: both tasks in a pair share a category", 1, ts.map { it.category }.toSet().size)
            assertTrue("$g: the two tasks need different parameters", ts[0].goal != ts[1].goal)
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
            assertTrue("${tell.id}: the first task must make Claire remember", "记住" in tell.goal)
            assertTrue("${tell.id}: the first task must not check memory", tell.checks.none { it is Check.MemoryUsed })
            assertTrue("${use.id}: the second task must check that memory was used", use.checks.any { it is Check.MemoryUsed })
            assertTrue("${use.id}: the second task must not mention the remember keyword again", "记住" !in use.goal)
        }
        assertTrue(mem.none { it.split != null })
    }

    @Test fun partitionsAreStratified() {
        // Every task goes to the tuning pool or the holdout pool; the holdout is about 30%, stratified by directory so every directory has holdouts
        tasks.forEach { (f, t) -> assertTrue("${f.path}: missing partition", t.partition in EvalTasks.PARTITIONS) }
        val holdout = tasks.count { it.second.partition == "holdout" }
        assertTrue("holdout pool $holdout / ${tasks.size}", holdout * 100 in tasks.size * 25..tasks.size * 35)
        tasks.groupBy { group(it.first) }.forEach { (g, ts) ->
            val h = ts.count { it.second.partition == "holdout" }
            assertTrue("$g: holdout $h / ${ts.size}", h >= 1 && h * 100 in ts.size * 20..ts.size * 40)
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
            .forEach { assertTrue("${it.id}: an asking task is missing answers", it.answers.isNotEmpty()) }
    }

    @Test fun writeTasksHaveMarker() {
        val writes = tasks.filter { suite(it.first) != "memory" }.map { it.second }.filter { it.category == EvalCategory.Write }
        assertTrue(writes.isNotEmpty())
        writes.forEach { t ->
            val marker = t.marker
            assertTrue("${t.id}: no marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: marker not written into the goal", marker!! in t.goal)
            // The data created must be findable again by the harness itself
            assertTrue("${t.id}: no check against the marker", t.checks.any { (it is Check.ScreenHas && marker in it.text) || (it is Check.ProbeEquals && marker in it.expected) })
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
            assertTrue("${t.id}: only ordering should ask for confirmation", card.isNotEmpty() && card.all { it.action == "下单" })
            assertTrue("${t.id}: ordering must check the target and the amount", card.any { it.target != null && it.amount != null })
            // Spends real account money: run only with the user present and the account manually marked as signed in
            assertTrue("${t.id}: missing signedIn: precondition", t.apps.all { "signedIn:$it" in t.requires })
            assertTrue("${t.id}: missing note", !t.note.isNullOrBlank())
        }
        free.forEach { t ->
            assertEquals(t.id, Expect.Succeeded, t.expect)
            assertTrue("${t.id}: a task that spends nothing should not have a confirmation card", t.checks.none { it is Check.ConfirmationShows })
            // If the user said clearly what to do, it should not ask again; it asks only for things only the user knows, like which item to delete
            val asked = t.checks.filterIsInstance<Check.AskedUser>()
            assertTrue("${t.id}: must check whether the user was asked", asked.size == 1)
            // Irreversible actions are done only on data the task itself marked
            val marker = t.marker
            assertTrue("${t.id}: need a marker containing {run}", marker != null && "{run}" in marker)
            assertTrue("${t.id}: the goal must contain the marker", marker!! in t.goal)
            // Tasks that send to a real account: run only with the user present and the account manually marked as signed in, and verify in the app after sending
            if (t.apps.any { !it.startsWith("{app:") }) {
                assertTrue("${t.id}: missing signedIn: precondition", t.apps.all { "signedIn:$it" in t.requires })
                assertTrue("${t.id}: missing note", !t.note.isNullOrBlank())
                assertTrue("${t.id}: must verify against the marker that it was sent", t.checks.any { it is Check.ScreenHas && marker in it.text })
            }
        }
    }

    @Test fun systemAppsAreNotPinned() {
        // The package name of a built-in phone app varies by model: the task names a capability, the run finds the one on this phone via the standard Intent, and the check doesn't pin the package name either
        val system = tasks.map { it.second }.filter { "手机自带" in it.goal }
        assertTrue(system.isNotEmpty())
        system.forEach { t ->
            t.apps.forEach { assertTrue("${t.id}: built-in apps are written {app:<capability>}, not a package name:$it", APP_TOKEN.matches(it)) }
            t.checks.filterIsInstance<Check.ScreenHas>().forEach { assertTrue("${t.id}: screen_has must not pin the package name", APP_TOKEN.matches(it.packageName)) }
            t.requires.forEach { assertFalse("${t.id}: built-in apps must not use installed:", it.startsWith("installed:")) }
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
