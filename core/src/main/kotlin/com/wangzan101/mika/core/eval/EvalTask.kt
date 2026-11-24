package com.wangzan101.mika.core.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EvalCategory {
    @SerialName("read") Read,
    @SerialName("write") Write,
    @SerialName("irreversible") Irreversible,
    @SerialName("impossible") Impossible,
    @SerialName("barrier") Barrier,
}

@Serializable
enum class Expect {
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("confirmation") Confirmation,
    @SerialName("takeover") Takeover,
}

/** Objective check item; distinguished by "type" in JSON. */
@Serializable
sealed interface Check {
    @Serializable @SerialName("answer_contains")
    data class AnswerContains(val all: List<String>) : Check

    @Serializable @SerialName("answer_regex")
    data class AnswerRegex(val pattern: String) : Check

    /** Every item of the answer must appear in the screen text of the last few reads. */
    @Serializable @SerialName("answer_grounded")
    data class AnswerGrounded(val minItems: Int = 1) : Check

    /** [packageName] can be written as `{app:<capability>}` (see [APP_CAPABILITIES]) and is replaced with the App on this phone at run time. */
    @Serializable @SerialName("screen_has")
    data class ScreenHas(val packageName: String, val text: String, val deepLink: String? = null) : Check

    /** probe must be a name from [PROBE_ALLOWLIST]. */
    @Serializable @SerialName("probe_equals")
    data class ProbeEquals(val probe: String, val expected: String, val contains: Boolean = false) : Check

    /** At least [min] memories were carried into the start of this task (memory_used, as recorded by the code). Used by the second task of the memory test. */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(val min: Int = 1) : Check

    /**
     * Whether the Agent asked the user during this task (ask_user): it must have asked when [expected] is true, and never asked when false.
     * Used by "ask or decide yourself" tasks: if the goal lacks information only the user knows it should ask; if merely a preference was not stated it should decide sensibly and explain.
     */
    @Serializable @SerialName("asked_user")
    data class AskedUser(val expected: Boolean = true) : Check
    /** After the task, open [url] in a new web tab and read the whole page; [text] must be findable there (ignoring whitespace). */
    @Serializable @SerialName("web_has")
    data class WebHas(val url: String, val text: String) : Check

    @Serializable @SerialName("confirmation_shows")
    data class ConfirmationShows(
        val action: String? = null,
        val target: String? = null,
        val content: String? = null,
        val amount: String? = null,
    ) : Check
}

/**
 * One evaluation task. Strings in goal, marker and the check items can contain `{run}` and `{probe:<name>}`, replaced before the run by [EvalTasks.substitute];
 * `probesBefore` is "placeholder name → allowlisted probe name": those probes are executed before the task runs to obtain the expected values.
 * Package names in `apps` and screen_has can be written as `{app:<capability>}` (some kind of App that ships with the phone, see [APP_CAPABILITIES]) and are replaced by [EvalTasks.resolveApps] with the package name on this phone,
 * so tasks do not pin the package name of a particular brand.
 *
 * `requires` is written as: `installed:<package name>`; `app:<capability>` (this phone has an App that handles this kind of thing);
 * `signedIn:<package name or site>` (the user is signed in: for sites it looks at the registration under the "site sign-in" setting, for Apps at a manual mark; the old forms `login:<package name>` and `site:<site>` are still accepted).
 */
@Serializable
data class EvalTask(
    val id: String,
    val goal: String,
    val apps: List<String>,
    val category: EvalCategory,
    val requires: List<String> = emptyList(),
    val expect: Expect,
    val checks: List<Check>,
    val maxSteps: Int? = null,
    val timeoutSec: Int? = null,
    val marker: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val probesBefore: Map<String, String> = emptyMap(),
    /** An explanation for reviewers (why this task is designed this way); the evaluator does not use it. */
    val note: String? = null,
    /** Paired task: train (the round that learns the skill) or test (the round used for comparison); the evaluator selects it with `-e split`. */
    val split: String? = null,
    /** Tasks with the same session run in one conversation in id order (memory test: tell first, then use it). */
    val session: String? = null,
    /** Variants for replay evaluation: the same thing with different wording or different parameters; with `evalVariants on` the original task runs first to record the route, then these run. */
    val variants: List<Variant> = emptyList(),
    /** Forced channel: "web" or "app"; when not empty, "use the web" / "use the App" is prefixed to the goal (for comparing the two routes). */
    val channel: String? = null,
    /**
     * Task pool: tune (the tasks looked at while tuning prompts and knowledge) or holdout (results only looked at when deciding a release, never used for tuning); the evaluator selects it with `-e evalSplit`.
     * This is unrelated to [split] (the train/test rounds of paired tasks).
     */
    val partition: String? = null,
    /**
     * A task handed over to the user after hitting one of these barriers (visible on screen, see [RunFacts.takeoverBarrier]) is listed separately as "handed over" ([Scoring.handedOver]),
     * counting neither as accomplished nor as another kind of failure; whether it counts is left to humans. When empty the default comes from the category ([Scoring.takeoverKinds]): login for read tasks, none for the rest.
     */
    val acceptTakeoverOn: List<String>? = null,
)

/**
 * "Some kind of App that ships with the phone" is found via standard Android Intents: before the task these Intents are used to look up the Apps on this phone that can handle them (candidates),
 * and after the run the candidate the Agent actually opened is preferred. Kinds without a standard Intent (notes) can only use the App the Agent opened after the run.
 */
data class AppIntent(val action: String, val category: String? = null)

private const val MAIN = "android.intent.action.MAIN"

val APP_CAPABILITIES: Map<String, List<AppIntent>> = mapOf(
    "alarm" to listOf(AppIntent("android.intent.action.SET_ALARM"), AppIntent("android.intent.action.SHOW_ALARMS")),
    "calendar" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALENDAR")),
    "calculator" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALCULATOR")),
    "contacts" to listOf(AppIntent(MAIN, "android.intent.category.APP_CONTACTS")),
    "files" to listOf(AppIntent(MAIN, "android.intent.category.APP_FILES"), AppIntent("android.intent.action.OPEN_DOCUMENT_TREE")),
    "weather" to listOf(AppIntent(MAIN, "android.intent.category.APP_WEATHER")),
    "notes" to emptyList(),
)

object AppCapabilities {
    private val COMPONENT = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/\\S+")
    /** The system's "choose which App" UI is not a candidate. */
    private const val SYSTEM_PACKAGE = "android"

    /** The package names in the output of `cmd package resolve-activity --brief` / `query-activities --brief`, deduplicated in order of appearance. */
    fun packagesIn(output: String): List<String> =
        output.lines().mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }.filter { it != SYSTEM_PACKAGE }.distinct()

    /** The shell commands that query one Intent (the arguments come from [APP_CAPABILITIES] and cannot be written in a task file). */
    fun commands(i: AppIntent): List<String> {
        val args = "-a ${i.action}" + (i.category?.let { " -c $it" } ?: "")
        return listOf("cmd package resolve-activity --brief $args", "cmd package query-activities --brief $args")
    }

    /**
     * Which package `{app:<capability>}` resolves to after the run: candidates the Agent opened are preferred (a phone may have several calendars), then the first candidate.
     * When there are no candidates (no standard Intent, or none found on this phone), it only accepts the App in the foreground while the Agent was typing ([written], see [RunFacts.appsWrittenIn]),
     * and only when there is exactly one; if it cannot be told which it is, it stays undecided (the check counts as not found) instead of using "the first App opened" as a filler. [own] is Claire's own package name.
     */
    fun choose(candidates: List<String>, launched: List<String>, own: String? = null, written: List<String> = emptyList()): String? {
        val used = launched.filter { it != own && it != SYSTEM_PACKAGE }
        candidates.firstOrNull { it in used }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return written.filter { it != own && it != SYSTEM_PACKAGE }.distinct().singleOrNull()
    }
}

/** The values of [EvalTask.channel] and the wording prefixed to the goal. */
val CHANNEL_PREFIX = mapOf("web" to "用网页", "app" to "用 App")

/**
 * A variant of the original task. When [checks] is empty the original task's checks are reused. When parameters change, they must be spelled out verbatim in [goal] (replay only recognizes slot values that appear in the original wording).
 * All other fields (apps, category, steps, time limit, marker, etc.) are inherited from the original task.
 */
@Serializable
data class Variant(val goal: String, val checks: List<Check>? = null)

/** Read-only device probes: name → shell command. The evaluator only executes the commands listed here; task files cannot supply arbitrary commands. */
val PROBE_ALLOWLIST: Map<String, String> = mapOf(
    "android_release" to "getprop ro.build.version.release",
    "android_sdk" to "getprop ro.build.version.sdk",
    "device_model" to "getprop ro.product.model",
    "device_manufacturer" to "getprop ro.product.manufacturer",
    "timezone" to "getprop persist.sys.timezone",
    "date_today" to "date +%Y-%m-%d",
    "time_12_24" to "settings get system time_12_24",
    "font_scale" to "settings get system font_scale",
    "airplane_mode_on" to "settings get global airplane_mode_on",
    "wifi_on" to "settings get global wifi_on",
    "bluetooth_on" to "settings get global bluetooth_on",
    "auto_time" to "settings get global auto_time",
    "default_browser" to "cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com",
    "default_home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    /** Files and folders in the root of internal storage (used by folder-creation checks without depending on any particular file manager App). */
    "storage_root" to "ls -1 /sdcard/",
)

object EvalTasks {
    private val json = Json { explicitNulls = false }
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SPLITS = setOf("train", "test")
    /** The values of [EvalTask.partition]. */
    val PARTITIONS = setOf("tune", "holdout")
    private val PROBE_REF = Regex("""\{probe:([^}]*)\}""")
    private val APP_REF = Regex("""\{app:([^}]*)\}""")
    private val REQUIREMENT_KINDS = setOf("installed", "login", "site", "signedIn", "app")

    /** The `{app:<capability>}` references used by the task. */
    fun appCapabilities(t: EvalTask): Set<String> =
        APP_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()

    /** Replaces `{app:<capability>}` with the package name from [apps] (capability → package name); capabilities missing from [apps] are left as they are, and entries in the `apps` list that cannot be resolved are dropped. */
    fun resolveApps(t: EvalTask, apps: Map<String, String>): EvalTask {
        fun s(x: String) = APP_REF.replace(x) { m -> apps[m.groupValues[1]] ?: m.value }
        return t.copy(
            apps = t.apps.map(::s).filterNot { APP_REF.containsMatchIn(it) }.distinct(),
            checks = t.checks.map { c -> if (c is Check.ScreenHas) c.copy(packageName = s(c.packageName)) else c },
        )
    }

    /** Prefixes the channel wording to the goal ([CHANNEL_PREFIX]); if the goal already says so (ignoring whitespace) it is not added again. */
    fun withChannel(goal: String, channel: String?): String {
        val prefix = channel?.let { CHANNEL_PREFIX[it] } ?: return goal
        fun squash(x: String) = x.filterNot(Char::isWhitespace)
        return if (squash(prefix) in squash(goal)) goal else prefix + goal
    }

    fun parse(json: String): EvalTask = this.json.decodeFromString(EvalTask.serializer(), json)

    /** The original task's variants, numbered `<original>~v<n>` (starting at 1); empty when there are no variants. */
    fun variantsOf(t: EvalTask): List<EvalTask> = t.variants.mapIndexed { i, v ->
        t.copy(id = "${t.id}~v${i + 1}", goal = v.goal, checks = v.checks ?: t.checks, variants = emptyList())
    }

    /** Validity of a single task (including all its variants); an empty list means valid. */
    fun validate(t: EvalTask): List<String> {
        val errs = mutableListOf<String>()
        // Variants are validated under the original task's id, with errors prefixed by which variant they belong to
        variantsOf(t).forEachIndexed { i, v -> validate(v.copy(id = t.id)).forEach { errs += "${t.id}: variant[${i + 1}]: $it" } }
        if (!ID.matches(t.id)) errs += "id \"${t.id}\" must be lowercase letters and digits joined by hyphens"
        if (t.goal.isBlank()) errs += "${t.id}: goal is empty"
        t.channel?.let { if (it !in CHANNEL_PREFIX) errs += "${t.id}: channel must be one of ${CHANNEL_PREFIX.keys.joinToString("/")}" }
        t.maxSteps?.let { if (it <= 0) errs += "${t.id}: maxSteps must be positive" }
        t.split?.let { if (it !in SPLITS) errs += "${t.id}: split must be train or test" }
        t.partition?.let { if (it !in PARTITIONS) errs += "${t.id}: partition must be tune or holdout" }
        t.session?.let { if (!ID.matches(it)) errs += "${t.id}: session \"$it\" must be lowercase letters and digits joined by hyphens" }
        t.timeoutSec?.let { if (it <= 0) errs += "${t.id}: timeoutSec must be positive" }
        t.requires.forEach { r ->
            val kind = r.substringBefore(':')
            if (kind !in REQUIREMENT_KINDS || r.substringAfter(':', "").isBlank()) errs += "${t.id}: unknown precondition \"$r\""
            if (kind == "app" && r.substringAfter(':') !in APP_CAPABILITIES) errs += "${t.id}: precondition \"$r\" is not a known app capability"
        }
        appCapabilities(t).forEach { if (it !in APP_CAPABILITIES) errs += "${t.id}: {app:$it} is not a known app capability" }
        t.probesBefore.forEach { (name, probe) ->
            if (probe !in PROBE_ALLOWLIST) errs += "${t.id}: probesBefore.$name uses a probe outside the allowlist: \"$probe\""
        }
        if (t.checks.isEmpty() && (t.expect == Expect.Succeeded || t.expect == Expect.Confirmation)) {
            errs += "${t.id}: expect=${t.expect} needs at least one check"
        }
        t.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ProbeEquals -> {
                    if (c.probe !in PROBE_ALLOWLIST) errs += "${t.id}: checks[$i] uses a probe outside the allowlist: \"${c.probe}\""
                    if (c.expected.isBlank()) errs += "${t.id}: checks[$i] expected is empty"
                }
                is Check.AnswerGrounded -> if (c.minItems < 1) errs += "${t.id}: checks[$i] minItems must be >= 1"
                is Check.AnswerRegex ->
                    if (c.pattern.isEmpty()) errs += "${t.id}: checks[$i] pattern is empty"
                    else runCatching { Regex(c.pattern) }
                        .onFailure { errs += "${t.id}: checks[$i] invalid regex: ${it.message}" }
                        // A regex that can match the empty string holds for any answer
                        .onSuccess { if (it.containsMatchIn("")) errs += "${t.id}: checks[$i] regex can match the empty string, so it checks nothing" }
                is Check.AnswerContains ->
                    if (c.all.isEmpty() || c.all.any { it.isBlank() }) errs += "${t.id}: checks[$i] all is empty or contains an empty string"
                is Check.ConfirmationShows -> {
                    val fields = listOf(c.action, c.target, c.content, c.amount)
                    if (fields.all { it == null }) errs += "${t.id}: checks[$i] has no field to check"
                    if (fields.any { it != null && it.isBlank() }) errs += "${t.id}: checks[$i] has a blank field"
                }
                is Check.ScreenHas -> if (c.text.isBlank()) errs += "${t.id}: checks[$i] text is empty"
                is Check.MemoryUsed -> if (c.min < 1) errs += "${t.id}: checks[$i] min must be >= 1"
                is Check.AskedUser -> Unit
                is Check.WebHas -> {
                    val u = c.url.trim().lowercase()
                    if (!u.startsWith("https://") && !u.startsWith("http://")) errs += "${t.id}: checks[$i] url must be an http(s) URL"
                    if (c.text.isBlank()) errs += "${t.id}: checks[$i] text is empty"
                }
            }
        }
        val referenced = PROBE_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()
        (referenced - t.probesBefore.keys).forEach { errs += "${t.id}: placeholder {probe:$it} is not declared in probesBefore" }
        return errs
    }

    /** The whole task set: validate task by task, then check for duplicate ids. */
    fun validateAll(ts: List<EvalTask>): List<String> =
        ts.flatMap { validate(it) } +
            ts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "duplicate id \"$it\"" }

    /** Substitutes `{run}` and `{probe:<name>}`; probes is the result of running probesBefore (placeholder name → output). */
    fun substitute(t: EvalTask, run: String, probes: Map<String, String>): EvalTask {
        fun s(x: String): String = PROBE_REF.replace(x.replace("{run}", run)) { m ->
            probes[m.groupValues[1]]?.trim() ?: m.value
        }
        // Probe values inserted into a regex are treated as literals
        fun re(x: String): String = PROBE_REF.replace(x.replace("{run}", Regex.escape(run))) { m ->
            probes[m.groupValues[1]]?.let { Regex.escape(it.trim()) } ?: Regex.escapeReplacement(m.value)
        }
        val checks = t.checks.map { c ->
            when (c) {
                is Check.AnswerContains -> c.copy(all = c.all.map(::s))
                is Check.AnswerRegex -> c.copy(pattern = re(c.pattern))
                is Check.AnswerGrounded -> c
                is Check.MemoryUsed -> c
                is Check.AskedUser -> c
                is Check.ScreenHas -> c.copy(text = s(c.text), deepLink = c.deepLink?.let(::s))
                is Check.WebHas -> c.copy(url = s(c.url), text = s(c.text))
                is Check.ProbeEquals -> c.copy(expected = s(c.expected))
                is Check.ConfirmationShows -> Check.ConfirmationShows(
                    c.action?.let(::s), c.target?.let(::s), c.content?.let(::s), c.amount?.let(::s),
                )
            }
        }
        return t.copy(
            goal = withChannel(s(t.goal), t.channel),
            marker = t.marker?.let(::s),
            checks = checks,
            answers = t.answers.mapValues { s(it.value) },
        )
    }
}
