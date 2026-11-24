package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.ChannelAdvisor
import com.wangzan101.mika.core.learn.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.ProfileContext
import com.wangzan101.mika.core.learn.knowledge.toolsNote
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.learn.replay.RouteAnchors
import com.wangzan101.mika.core.screen.map.ScreenMapRender
import com.wangzan101.mika.core.screen.uistate.interfaces
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.general.HISTORY_READ
import com.wangzan101.mika.core.general.VIEW_USER_SCREEN
import com.wangzan101.mika.core.agent.AgentLoop.LoopHistory
import com.wangzan101.mika.core.agent.AgentLoop.LoopOpening
import com.wangzan101.mika.core.agent.AgentLoop.Companion.STEER_MARKER

// Agent loop: opening and history preparation, knowledge injection, runtime state and steer assembly
internal suspend fun AgentLoop.prepareOpening(raw: String, rawPrior: List<Turn>): LoopOpening {
    // The screen the user sent: given in full for this sentence (the words, screenshots, details), condensed into one line for earlier turns; everything below uses this rewritten sentence
    val shots = UserScreenTurns(userScreens, zone, tools.seesImages, canAskVision = !tools.seesImages && tools.vision != null && tools.definitions().any { it.name == VIEW_USER_SCREEN },
        image = { tools.modelImage(it, null) }, seen = tools::noteUserScreen).also { screens = it }
    user.answerText = shots::asText
    val opening = shots.render(raw)
    val task = opening?.said ?: raw
    taskText = task
    latestWords = ownWords(raw, opening)
    val priorTurns = shots.collapse(rawPrior)
    // The notification-reminder turn has no user words, so it does not count as "what the user said in earlier turns"
    earlierGoals = priorTurns.filterNot(NoticeTurn::isNotice).map { it.userText }
    val context = Conversation.context(priorTurns)
    // Only "the user's explicit confirmation in the previous turn + the assistant's suggestion/question in the turn before that" may be referenced across turns by memory_save; earlier turns and the assistant's original text are not included.
    val confirmation = rawPrior.lastOrNull()
    val proposal = rawPrior.dropLast(1).lastOrNull()
    // Do not open this exception when the conversation contains notification reminders (third-party text): a reminder turn itself ends with a question, and the user replying "ok" cannot be grounds for quoting notification content.
    // All stored turns are considered: even several turns after a reminder, the assistant's answer may still be relaying the notification.
    // Nor when this task carries an "obtained from notifications" block: the previous turn's suggestion may have been based on that block, so the user replying "ok"
    // must not turn what is in it into a memory of "what the user said". What the user states explicitly (present in this turn's own words) can still be remembered.
    if (confirmation != null && proposal != null && !NoticeTurn.tainted(rawPrior) && observed == null) {
        utterances?.addPreviousConfirmation(UserWords.said(confirmation.userText, tools), proposal.outcome, proposal.status)
    }
    utterances?.add(UserWords.said(opening?.words ?: raw, tools))
    // Memory block: after the conversation context and before the task text; the entries used are recorded by code
    usedMemory = emptyList()
    // If the memory file could not be read last time (the keystore was temporarily unavailable), read it again; if it still cannot be read, this run goes without memory
    // The product path uses Auto: if the total amount of memory fits, inject all of it (so retrieval misses nothing); only fall back to retrieval when it does not fit
    val block = memory?.takeIf { it.reloadIfNeeded() }?.let {
        MemoryContext.build(it.items.value, task, priorTurns.lastOrNull()?.userText, tools.legacy::rewrite,
            mode = MemoryContext.Mode.Auto)
    }
    if (block?.message != null) {
        usedMemory = block.usedIds
        memory?.markUsed(block.usedIds)
        emit(RunEvent.MemoryUsed(now(), block.usedIds))
    }
    return LoopOpening(task, priorTurns, opening, context, block?.message)
}

internal suspend fun AgentLoop.prepareHistory(opening: LoopOpening): LoopHistory {
    val installed = try { installedApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    val labels = installed.toMap()
    val matched = knowledge?.matchTask(opening.task, Int.MAX_VALUE, installed).orEmpty()
    stages?.prepare(latestWords, installed, matched)
    val index = if (skills != null && recommendSkills) GuideRecommender.index(skills, opening.task) else null
    val start = ctx.start(
        opening.task, priorTurns = opening.context?.let { (it.parts.single() as Part.Text).text }?.let { prior ->
            // Say a word when what earlier turns read can be looked up (only for turns that have a recorded run, and when history_read is provided)
            if (tools.definitions().any { it.name == HISTORY_READ }) Feedback.format("history_hint", "turns" to prior) else prior
        },
        memory = listOfNotNull(ProfileContext.render(profileSnapshot), opening.memoryMessage).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        observed = observed,
        deskNote = deskNote,
        channel = if (knowledge != null) channelLine(matched) else null,
        knowledge = matched, knowledgeNote = toolsNote(tools.scriptsOffered()), withLinks = flags.deepLinks,
        skillsIndex = index,
        taskImages = opening.screen?.images.orEmpty(), screens = opening.screen?.details.orEmpty(),
    )
    val history = start.messages.toMutableList()
    // Carries the "obtained from notifications" block: record it (length only). It counts as given regardless of whether it ended up fitting
    observed?.let { emit(RunEvent.State(now(), ObservedContext.RUN_STATE, it.length.toString())) }
    start.knowledge.filter { it.trial }.forEach { emit(RunEvent.State(now(), "knowledge_trial_used", it.name)) }
    // Links are given only when open_link is available, and only the ones actually handed to the model are allowed
    if (flags.deepLinks) start.knowledge.forEach { if (it.links.isNotEmpty()) tools.allowLinks(it.links) }
    return LoopHistory(history, labels)
}

/** Add the reference material for the foreground app/site to the history (deterministically, without guessing from keywords); skills are all listed at the opening, so nothing is added here. For apps with screen maps, a section of the page structure is appended along with APP.md. */
internal suspend fun AgentLoop.injectKnowledge(history: MutableList<Message>, e: KnowledgeEntry?, pkg: String?) {
    ctx.knowledge(e, toolsNote(tools.scriptsOffered()), flags.deepLinks)?.let { m ->
        if (flags.deepLinks && e!!.links.isNotEmpty()) tools.allowLinks(e.links)
        history += m
        if (e?.trial == true) emit(RunEvent.State(now(), "knowledge_trial_used", e.name))
    }
    // The screen map is attempted only once: deduplication comes first, so if it is not wired up, already tried, or there is no map, neither the screen nor the disk is read again
    if (pkg == null || screenMaps == null || !ctx.firstScreenMapAttempt(pkg)) return
    val graph = screenMaps.load(pkg)
    if (graph == null || graph.nodes.isEmpty()) return
    val skeleton = tools.interfaces.peek()?.let { s -> RouteAnchors.texts(s).toSet() }
    ScreenMapRender.renderMapFor(graph, skeleton)?.let { map ->
        history += ctx.screenMap(pkg, map)
    }
}

/**
 * This step's runtime state is appended to the end of the last message of this step (the one not yet sent), and is
 * pruned and folded together with this step; messages already sent are never modified, so the request prefix only
 * grows monotonically. If the last message is an assistant message (which does not happen in the normal flow), a
 * separate one is appended.
 * The web version is read from the version actually used by the browser's current tab.
 */
internal suspend fun AgentLoop.appendRuntimeState(history: MutableList<Message>, place: Place?, labels: Map<String, String>, step: Int, pending: String?) {
    val mode = if (place?.kind == PlaceKind.Site) tools.placeMode() else null
    val state = ctx.runtimeState(place, place?.name?.let(labels::get), mode, step, maxSteps, now(), latestWords,
        listOfNotNull(pending, stages?.hint()).takeIf { it.isNotEmpty() }?.joinToString("\n"))
    val last = history.lastOrNull()
    if (last == null || last.role == Role.Assistant) history += Message(Role.User, listOf(state), Author.Mika)
    else history[history.lastIndex] = last.copy(parts = last.parts + state)
}

/**
 * The characters the user typed in this sentence: the screen block, the browser page block, the quoted passage and
 * the attachment lines are all removed in order (see [UserWords.typed]; when several are present all are removed), and
 * when none is present it is the original text; it is empty when only a screen, only a quoted passage, only selected
 * text, or only attachments were sent.
 * The sentence given to the model carries fixed English blocks and the steer marker, and may be followed by runtime
 * state, so it cannot be used to judge which language the user is using.
 */
internal fun AgentLoop.ownWords(raw: String, shown: UserScreenTurns.Rendered?): String = UserWords.typed(shown?.words ?: raw, tools)

internal suspend fun AgentLoop.channelLine(matched: List<KnowledgeEntry>): String? {
    val facts = channelFacts ?: return null
    val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
    return ChannelAdvisor.line(matched, tools.skillCatalog, f)
}

/**
 * A steer is an independent real user line; it is only appended between two steps and never rewrites messages already
 * sent to the model.
 * When a steer carries the user's current screen, it is rewritten just like the opening text: the words together with
 * screenshots, followed by the screen details.
 */
internal suspend fun AgentLoop.appendSteers(history: MutableList<Message>, inbox: RunInbox?): Boolean =
    appendSteers(history, inbox?.takeSteers().orEmpty())

internal suspend fun AgentLoop.appendSteers(history: MutableList<Message>, updates: List<RunInbox.UserSteer>): Boolean {
    updates.forEach { update ->
        val shown = screens?.render(update.text)
        val words = ownWords(update.text, shown)
        if (words.isNotBlank()) latestWords = words
        history += Message(Role.User, listOf(Part.Text("$STEER_MARKER\n${shown?.said ?: update.text}")) + shown?.images.orEmpty(), Author.User)
        shown?.details?.forEach { (source, body) -> history += ctx.userScreen(source, body) }
        utterances?.add(UserWords.said(shown?.words ?: update.text, tools))
        emit(RunEvent.State(now(), "steer_received", RunEvent.clip(Redactor.text(update.messageId))))
    }
    return updates.isNotEmpty()
}
