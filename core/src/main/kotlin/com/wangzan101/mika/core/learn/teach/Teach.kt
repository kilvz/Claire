package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次人工示范。只在 [TeachPhase.Recording] 收下事件。
 * [Ready] 是用户已看过说明、还没点「开始示范」。
 */
@Serializable
enum class TeachPhase {
    Idle, Ready, Recording, Paused, Reviewing, Drafting, Interrupted, Saved;

    /** 正在看用户操作，或暂停后还会继续看。这时不要另开任务。 */
    fun blocksTasks(): Boolean = this == Recording || this == Paused

    fun collects(): Boolean = this == Recording
}

enum class TeachEventKind { Click, LongClick, Text, Scroll, Window }

@Serializable
enum class TeachKind { Click, LongClick, Type, Scroll, Window }

/**
 * 无障碍回调当场抄下来的一份。 [text] 只在内存里用来判断敏不敏感，步骤里不保存。
 * [displayId] 0 是用户主屏。
 */
data class TeachObservation(
    val atMs: Long,
    val kind: TeachEventKind,
    val packageName: String?,
    val className: String?,
    val title: String?,
    val text: String?,
    val description: String?,
    val viewId: String?,
    val password: Boolean,
    val displayId: Int,
)

@Serializable
data class TeachStep(
    val id: Int,
    val atMs: Long,
    val packageName: String?,
    val window: String?,
    val kind: TeachKind,
    val nodeName: String?,
    val viewId: String?,
    val insufficient: Boolean,
    val sensitiveOmitted: Boolean,
    val needsClarification: Boolean,
    val correction: String? = null,
    val explanation: String? = null,
)

@Serializable
data class TeachSession(
    val id: String,
    val goal: String,
    val phase: TeachPhase,
    val steps: List<TeachStep> = emptyList(),
    val truncated: Boolean = false,
    val draftId: String? = null,
    val updatedAt: Long = 0L,
    /** 收下后展示给用户的技能名。旧会话没有这个字段。 */
    val acceptedName: String? = null,
    /** 整理时正文是模型写的（false 是按本机规则整理）；旧会话没有这个字段。 */
    val byModel: Boolean? = null,
) {
    fun step(id: Int): TeachStep? = steps.find { it.id == id }
}

/** 用户主屏。别的屏（虚拟屏上的任务）不记。 */
const val TEACH_MAIN_DISPLAY = 0

private const val MAX_STEPS = 24
private const val CLICK_DEDUP_MS = 400L
private const val SCROLL_MERGE_MS = 1_000L
private const val TYPE_MERGE_MS = 2_000L
private const val WINDOW_MERGE_MS = 800L
private val GENERIC_CLASS = setOf(
    "FrameLayout", "LinearLayout", "RelativeLayout", "View", "ViewGroup", "DecorView", "PopupDecorView",
)

/** 目标能不能当作示范的题目。敏感内容直接拒绝，不落盘。 */
fun teachGoalAccepted(goal: String): Boolean {
    val t = goal.trim()
    return t.isNotEmpty() && t.length <= 200 && SensitiveFilter.hit(t) == null
}

object TeachTrace {
    /**
     * 不在采集中、不是主屏、是 Claire 自己的界面、或没有包名：原样返回，不新增步骤。
     */
    fun reduce(session: TeachSession, event: TeachObservation, ownPackage: String): TeachSession {
        if (!session.phase.collects()) return session
        if (event.displayId != TEACH_MAIN_DISPLAY) return session
        val pkg = event.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return session
        if (pkg == ownPackage) return session
        val safe = if (event.password) {
            event.copy(className = null, title = null, text = null, description = null, viewId = null)
        } else event
        val built = build(session, safe, pkg) ?: return session
        val last = session.steps.lastOrNull()
        if (last != null && merge(last, built, event.atMs)) {
            val merged = last.copy(
                atMs = event.atMs,
                nodeName = last.nodeName ?: built.nodeName,
                viewId = last.viewId ?: built.viewId,
                window = last.window ?: built.window,
                sensitiveOmitted = last.sensitiveOmitted || built.sensitiveOmitted,
                insufficient = if (last.nodeName == null && last.viewId == null) built.insufficient else last.insufficient,
                needsClarification = if (last.nodeName == null && last.viewId == null) built.needsClarification else last.needsClarification,
            )
            return session.copy(steps = session.steps.dropLast(1) + merged, updatedAt = event.atMs)
        }
        if (session.steps.size >= MAX_STEPS) return session.copy(truncated = true, updatedAt = event.atMs)
        return session.copy(steps = session.steps + built, updatedAt = event.atMs)
    }

    fun remove(session: TeachSession, id: Int): TeachSession =
        session.copy(steps = session.steps.filterNot { it.id == id })

    /** 旧版可能把已标记为敏感的事件内容存成节点标签或窗口。 */
    fun redactStored(session: TeachSession): TeachSession = session.copy(steps = session.steps.map { step ->
        if (!step.sensitiveOmitted) step else {
            val insufficient = step.kind != TeachKind.Scroll
            step.copy(window = null, nodeName = null, viewId = null,
                insufficient = insufficient, needsClarification = insufficient)
        }
    })

    /** 订正或说明里有敏感内容则不改、不落盘。空白表示去掉。 */
    fun annotate(session: TeachSession, id: Int, correction: String?, explanation: String?): TeachEdit {
        val step = session.step(id) ?: return TeachEdit.Missing
        val nextCorrection = cleanNote(correction)
        val nextExplanation = cleanNote(explanation)
        if (nextCorrection is Note.Secret || nextExplanation is Note.Secret) return TeachEdit.Secret
        val updated = step.copy(
            correction = (nextCorrection as? Note.Ok)?.text,
            explanation = (nextExplanation as? Note.Ok)?.text,
        )
        return TeachEdit.Ok(session.copy(steps = session.steps.map { if (it.id == id) updated else it }))
    }

    private fun build(session: TeachSession, event: TeachObservation, pkg: String): TeachStep? {
        val kind = when (event.kind) {
            TeachEventKind.Click -> TeachKind.Click
            TeachEventKind.LongClick -> TeachKind.LongClick
            TeachEventKind.Text -> TeachKind.Type
            TeachEventKind.Scroll -> TeachKind.Scroll
            TeachEventKind.Window -> TeachKind.Window
        }
        val typedSecret = event.text?.takeIf {
            kind == TeachKind.Type && SensitiveFilter.hit(it) != null
        }
        val window = keep(event.title, typedSecret) ?: keep(specificClass(event.className), typedSecret)
        val name = when (kind) {
            TeachKind.Type -> keep(event.description, typedSecret ?: event.text)
            TeachKind.Window -> null
            else -> keep(event.description, typedSecret) ?: keep(event.text, typedSecret)
        }
        val scrub = if (kind == TeachKind.Type) typedSecret ?: event.text else typedSecret
        val viewId = keep(event.viewId?.substringAfterLast('/'), scrub)
        val sensitive = event.password || typedSecret != null ||
            (event.text != null && kind != TeachKind.Type && SensitiveFilter.hit(event.text) != null && name == null)
        val insufficient = when (kind) {
            TeachKind.Click, TeachKind.LongClick, TeachKind.Type -> name == null && viewId == null
            TeachKind.Window -> window == null
            TeachKind.Scroll -> false
        }
        val clarify = insufficient
        return TeachStep(
            id = (session.steps.maxOfOrNull { it.id } ?: 0) + 1,
            atMs = event.atMs,
            packageName = pkg,
            window = window,
            kind = kind,
            nodeName = name,
            viewId = viewId,
            insufficient = insufficient,
            sensitiveOmitted = sensitive,
            needsClarification = clarify,
        )
    }

    private fun merge(last: TeachStep, next: TeachStep, atMs: Long): Boolean {
        if (last.kind != next.kind || last.packageName != next.packageName) return false
        val gap = atMs - last.atMs
        return when (last.kind) {
            TeachKind.Scroll -> gap in 0..SCROLL_MERGE_MS && last.window == next.window
            TeachKind.Type -> gap in 0..TYPE_MERGE_MS && sameField(last, next)
            TeachKind.Click, TeachKind.LongClick -> gap in 0..CLICK_DEDUP_MS && sameField(last, next)
            TeachKind.Window -> gap in 0..WINDOW_MERGE_MS && last.window == next.window
        }
    }

    private fun sameField(a: TeachStep, b: TeachStep): Boolean =
        a.viewId == b.viewId && a.nodeName == b.nodeName
}

sealed interface TeachEdit {
    data class Ok(val session: TeachSession) : TeachEdit
    data object Secret : TeachEdit
    data object Missing : TeachEdit
}

private sealed interface Note {
    data class Ok(val text: String?) : Note
    data object Secret : Note
}

private fun cleanNote(raw: String?): Note {
    val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Note.Ok(null)
    if (SensitiveFilter.hit(t) != null) return Note.Secret
    return Note.Ok(t.take(200))
}

private fun keep(value: String?, secret: String?): String? {
    val v = value?.trim()?.replace('\n', ' ')?.take(80)?.takeIf { it.isNotEmpty() } ?: return null
    if (secret != null && secret.length >= 4 && secret in v) return null
    if (SensitiveFilter.hit(v) != null) return null
    return v
}

private fun specificClass(className: String?): String? {
    val simple = className?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: return null
    if (simple in GENERIC_CLASS) return null
    return simple
}

/** One English line for the model and the draft; never the typed text, never coordinates. */
fun teachLine(step: TeachStep): String {
    val where = listOfNotNull(step.window, step.packageName).joinToString(" · ").ifBlank { "unknown screen" }
    val target = step.nodeName ?: step.viewId
    val observed = when (step.kind) {
        TeachKind.Click -> if (target != null) "tapped \"$target\"" else "tapped, with no reliable UI node"
        TeachKind.LongClick -> if (target != null) "long-pressed \"$target\"" else "long-pressed, with no reliable UI node"
        TeachKind.Type -> when {
            step.sensitiveOmitted -> "typed into a protected field (not saved)"
            target != null -> "typed into \"$target\" (text not saved)"
            else -> "typed into an unnamed field (text not saved)"
        }
        TeachKind.Scroll -> "scrolled"
        TeachKind.Window -> if (step.window != null) "switched to \"${step.window}\"" else "switched screens, with no reliable window info"
    }
    val place = if (step.kind == TeachKind.Window) step.packageName.orEmpty() else where
    return buildString {
        append("[observed] ").append(observed)
        if (place.isNotBlank()) append(" (").append(place).append(")")
        if (step.insufficient) append(". Not enough information; this cannot be written as a coordinate action")
        step.correction?.let { append(". [changed by you] ").append(it) }
        step.explanation?.let { append("。[你说明的] ").append(it) }
    }
}

object TeachSkill {
    const val SOURCE = "demonstrated"
    const val PREFIX = "teach:"
    const val NOT_TRIAL_RUN = "Claire has not replayed this successfully yet."
    const val NOT_ABSENT = "An entrance that this demonstration did not use is not evidence that it does not exist."
    const val STOP_IF_UNMATCHED = "If the current screen does not show the entrance or preconditions written above, stop applying this skill and fall back to ordinary looking around and asking."
    /** 模型读到试用中的示范技能时加在正文前。 */
    const val USE_NOTE =
        "[Demonstrated, not trial-run. Claire has not successfully replayed this. " +
            "Apply it only when the current screen matches the entrance and preconditions written below. " +
            "If they do not match, stop using this skill and explore or ask. " +
            "An entrance that was not used in the demonstration is not evidence that it does not exist.]"
    const val INDEX_NOTE = "[demonstrated, not trial-run; stop if the screen does not match] "

    fun sessionId(learnedFrom: String?): String? =
        learnedFrom?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { ID.matches(it) }

    fun isDemonstrated(skill: PersonalSkill): Boolean {
        if (skill.source == SOURCE) return true
        return FrontMatter.parse(skill.markdown).first["source"] == SOURCE
    }

    /** 只有来源是示范、并且轨迹编号对得上，审核结束才删那份轨迹。别的技能不动它。 */
    fun retiresTrace(skill: PersonalSkill, traceId: String): Boolean =
        isDemonstrated(skill) && sessionId(skill.learnedFrom) == traceId

    val ID = Regex("[a-f0-9]{32}")
}

/**
 * 采集或暂停时必须有一个不靠通知权限的可见控件，上面能暂停或结束。
 * 控件加不上就不能开始；控件中途消失就停止采集。
 */
object TeachWatch {
    fun allowsCollecting(controlVisible: Boolean): Boolean = controlVisible

    fun afterControlLost(phase: TeachPhase): TeachPhase = when (phase) {
        TeachPhase.Recording, TeachPhase.Paused -> TeachPhase.Interrupted
        else -> phase
    }
}

/** 发给模型的脱敏摘要，或本机直接整理出的草稿。 */
object TeachDraft {
    /** 整理出来的技能，以及它是不是模型写的（不是就按本机规则整理）。 */
    data class Result(val skill: PersonalSkill, val byModel: Boolean)

    fun request(session: TeachSession, route: TeachRoute): String = buildString {
        appendLine("Goal: ${plain(session.goal)}")
        appendLine("This is the collapsed route of one human demonstration. Actions that existed only because a person was doing it — going to the home screen, hunting for an icon, opening the browser — have been omitted.")
        route.apps.forEach { app ->
            appendLine("App: ${app.label} (${app.packageName})${if (app.browser) ", the phone's default browser" else ""}")
        }
        if (route.folded.isNotEmpty()) appendLine("Omitted thanks to Claire's direct capabilities: ${route.folded.joinToString("; ")}")
        appendLine("How to write it down:")
        appendLine("- Going home, finding an icon and tapping the app are things only a person does: Claire opens the app directly. Do not write about the home screen or icons.")
        appendLine("- Opening the browser, tapping the address bar, typing and tapping search: write \"open <link> directly\" or \"search on <site>\"; the link and the keywords come from the request at use time and were not saved from the demonstration.")
        appendLine("- Tapping a field, typing and submitting: combine into one step, \"type <content> and submit\".")
        appendLine("- Write only the entrances, concrete buttons, options, checks and stop conditions that really decide the outcome; do not write waiting, repeated scrolling or pointless back presses.")
        appendLine("Typed text was not saved. Do not invent coordinates, passwords, verification codes or payment details.")
        appendLine("Every step comes from that one demonstration. The only sources are observed, changed by you, and explained by you. An entrance that is not written down cannot be inferred to not exist.")
        if (session.truncated) appendLine("There were many steps; only the first ${session.steps.size} were kept.")
        route.steps.forEachIndexed { i, step -> appendLine("${i + 1}. ${step.text}") }
        if (session.steps.none { it.needsClarification }) appendLine("No step needs extra explanation from the user.")
    }

    fun assemble(session: TeachSession, route: TeachRoute, name: String): String {
        val apps = route.apps.map { it.packageName }.distinct().take(8).joinToString(", ") { "\"$it\"" }
        val explained = session.steps.mapNotNull { it.explanation }
        val body = buildString {
            appendLine("## Entry")
            appendLine("Applies to: ${plain(session.goal)}")
            val first = route.apps.firstOrNull()
            if (first != null) appendLine("Claire opens ${first.label} (${first.packageName}) directly. Treat only screens that really appeared in this demonstration as preconditions.")
            else appendLine("This demonstration left no usable app entrance. Treat only screens that really appeared as preconditions.")
            explained.forEach { appendLine("[explained by you] $it") }
            appendLine("There are only three kinds of sources: observed, explained by you, and marked as guessed. This draft was assembled locally from the demonstrated route; nothing else was guessed.")
            appendLine("## Steps")
            if (route.steps.isEmpty()) appendLine("(no steps were kept)")
            route.steps.forEachIndexed { i, step -> appendLine("${i + 1}. ${step.text}") }
            appendLine("These are entrance hints and operating strategy, not coordinate macros.")
            appendLine(TeachSkill.NOT_ABSENT)
            appendLine("## Common pitfalls")
            val thin = session.steps.filter { it.insufficient }
            if (thin.isEmpty()) appendLine("Every step kept here has a screen name or a window.")
            else appendLine("${thin.size} step(s) lack information and cannot be rewritten as tapping a coordinate.")
            if (session.truncated) appendLine("Later actions were not recorded one by one.")
            appendLine(TeachSkill.NOT_ABSENT)
            appendLine("## How success is verified (evidence)")
            val last = session.steps.lastOrNull()
            if (last != null) appendLine("[observed] When the demonstration ended it was on ${last.packageName.orEmpty()} ${last.window.orEmpty()}.")
            appendLine(TeachSkill.NOT_TRIAL_RUN)
            appendLine("## When to stop and hand off")
            appendLine(TeachSkill.STOP_IF_UNMATCHED)
            appendLine("Stop and hand off whenever payment, sending or ordering is involved.")
        }
        val md = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("display: ${yaml(session.goal.ifBlank { "Demonstration" }.take(24))}")
            appendLine("description: ${yaml(session.goal.ifBlank { "Entrances and steps recorded from one human demonstration" }.take(80))}")
            appendLine("apps: [$apps]")
            appendLine("aliases: []")
            appendLine("source: ${TeachSkill.SOURCE}")
            appendLine("learned_from: ${TeachSkill.PREFIX}${session.id}")
            appendLine("---")
            append(body.trim())
        }
        return ensureCaveats(md)
    }

    /** 模型稿也补上这三句，并强制出处，避免把没走到的入口写成不存在或声称试跑成功。 */
    fun ensureCaveats(md: String): String {
        var text = md
        if (TeachSkill.NOT_ABSENT !in text) text = insertBefore(text, listOf("How success is verified", "怎样算办成"), TeachSkill.NOT_ABSENT)
        if (TeachSkill.NOT_TRIAL_RUN !in text) text = insertBefore(text, listOf("When to stop and hand off", "什么时候停下交给用户"), TeachSkill.NOT_TRIAL_RUN)
        if (TeachSkill.STOP_IF_UNMATCHED !in text) text = text.trimEnd() + "\n" + TeachSkill.STOP_IF_UNMATCHED
        return text
    }

    fun acceptsModel(md: String): Boolean {
        if ((("试跑成功" in md) || ("trial run succeeded" in md.lowercase()) || ("replayed successfully" in md.lowercase())) && TeachSkill.NOT_TRIAL_RUN !in md) return false
        if (SensitiveFilter.hit(md) != null) return false
        return true
    }

    /**
     * 有模型就先把脱敏摘要交给它；稿不可用或没有模型时，用本机整理的那份。
     * 返回的技能始终是待确认，且标明来自示范；[Result.byModel] 说明这次正文是不是模型写的。
     */
    suspend fun draft(
        session: TeachSession,
        route: TeachRoute,
        model: ChatModel?,
        toolNames: Set<String>,
        scriptTool: String?,
        runnableScripts: Set<String>,
        nameTaken: (String) -> Boolean,
        nowMs: Long,
    ): Result? {
        if (route.steps.none { it.kind != null } || !teachGoalAccepted(session.goal)) return null
        val fromModel = model?.let { ask(it, session, route) }?.let { raw ->
            val body = PersonalSkills.withoutField(SkillDrafter.stripFence(raw), "updates")
            // 「试跑成功」要查模型原稿:先补上出处提示再查,那句话就被盖过去了,永远查不出来。
            if (!acceptsModel(body)) return@let null
            val caveated = ensureCaveats(body)
            caveated.takeIf { SkillDrafter.validate(caveated, toolNames, scriptTool, runnableScripts) == null }
        }
        val base = fromModel ?: assemble(session, route, "placeholder")
        val useModel = fromModel != null && SkillDrafter.validate(base, toolNames, scriptTool, runnableScripts) == null
        val md0 = if (useModel) base else assemble(session, route, "placeholder")
        if (SkillDrafter.validate(md0, toolNames, scriptTool, runnableScripts) != null) return null
        val name = freshName(session.id, nameTaken)
        val md = PersonalSkills.withField(
            PersonalSkills.withField(PersonalSkills.withName(md0, name), "source", TeachSkill.SOURCE),
            "learned_from", TeachSkill.PREFIX + session.id,
        )
        if (SkillDrafter.validate(md, toolNames, scriptTool, runnableScripts) != null) return null
        return Result(
            skill = PersonalSkill(
                name = name,
                markdown = md,
                status = SkillStatus.pending,
                learnedFrom = TeachSkill.PREFIX + session.id,
                replacesDraftOf = null,
                createdAt = nowMs,
                outcomes = emptyList(),
                source = TeachSkill.SOURCE,
            ),
            byModel = useModel,
        )
    }

    private suspend fun ask(model: ChatModel, session: TeachSession, route: TeachRoute): String? = try {
        val reply = model.complete(
            Prompts.role("teach_skill"),
            listOf(Message(Role.User, listOf(Part.Text(request(session, route))), Author.Mika)),
            emptyList(),
        )
        reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun freshName(id: String, taken: (String) -> Boolean): String {
        val stem = "demo-" + id.take(8)
        if (!taken(stem)) return stem
        var n = 2
        while (taken("$stem-$n") && n < 50) n++
        return "$stem-$n"
    }

    private fun insertBefore(md: String, headingPrefixes: List<String>, line: String): String {
        val lines = md.replace("\r\n", "\n").split("\n").toMutableList()
        val at = lines.indexOfFirst { l ->
            val t = l.trimStart()
            headingPrefixes.any { p -> t.startsWith("## $p") || t.startsWith("##$p") }
        }
        if (at < 0) return md.trimEnd() + "\n" + line
        lines.add(at, line)
        return lines.joinToString("\n")
    }
}

private fun plain(s: String) = s.replace('`', '\'').replace(Regex("\\s+"), " ").trim()

private fun yaml(s: String) = "\"" + plain(s).replace("\\", "\\\\").replace("\"", "'") + "\""

object TeachCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(session: TeachSession): String = json.encodeToString(TeachSession.serializer(), session)
    fun decode(text: String): TeachSession = json.decodeFromString(TeachSession.serializer(), text)
}

/** 只允许放进待确认，不允许直接启用。 */
object TeachPublish {
    fun toPending(store: PersonalSkills, id: String, skill: PersonalSkill) {
        require(skill.status == SkillStatus.pending) { "demonstrated skill must stay pending" }
        require(TeachSkill.isDemonstrated(skill)) { "not a demonstrated skill" }
        require(TeachSkill.sessionId(skill.learnedFrom) != null) { "missing teach session" }
        store.putPending(id, skill.copy(status = SkillStatus.pending))
    }
}
