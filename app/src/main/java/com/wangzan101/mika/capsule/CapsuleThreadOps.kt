package com.wangzan101.mika.capsule

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.chat.ConversationStore
import kotlinx.coroutines.withContext
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.chat.ui.ScreenCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Companion.IME_SHRINK_JUMP_DP

// Capsule: loading the idle conversation, paging through history, and timed fading
/** Tap the idle icon: first read which conversation the user is in ([loadIdle]), then expand (the exchange is read by [updateThread]). */
internal fun CapsuleController.openIdle() {
    scope.launch {
        idle = loadIdle()
        open()
        refresh()
    }
}

/**
 * The conversation the idle card continues: the one the user is in inside the app, or the most recent one when nothing is recorded (see [CapsuleIdle]); when there is none at all, start a new conversation.
 * It does not guess from conversation file times: a new conversation has no file yet, so the guess would be the previous one.
 */
internal suspend fun CapsuleController.loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
    runCatching {
        val store = ConversationStore.shared(context)
        val recent = store.list().firstOrNull()?.let { CardView.Chat(it.id, title = it.title?.let(ScreenCopy::plain)) }
        CapsuleIdle.chat(store.currentChat(), recent) { id ->
            // Same as the sidebar: the user-given name or the first sentence
            (store.title(id) ?: store.turns(id).firstOrNull()?.userText)?.let(ScreenCopy::plain)
        }
    }.getOrElse { CardView.Chat(null) }
}

/**
 * The exchange the chat card needs: when the conversation changes it is read from the start (the latest [CapsuleThreads.PAGE] turns first); when tasks in this conversation appear, finish, or are removed it re-reads the recorded turns
 * (a turn is only recorded after its task finishes); otherwise it only re-orders by the current tasks (the streaming answer grows as it is written). Cleared when this is not a chat card.
 */
internal fun CapsuleController.updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
    val id = chat?.target
    if (id == null) {
        threadJob?.cancel()
        threadOf = null
        past = null
        threadMark = null
        shownTurns = CapsuleThreads.PAGE
        // A new conversation on the idle card: the exchange is empty
        thread = if (chat != null) ChatThread(emptyList()) else null
        return
    }
    val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
    if (id != threadOf) {
        threadOf = id
        past = null
        shownTurns = CapsuleThreads.PAGE
        thread = null
    }
    if (mark != threadMark) {
        threadMark = mark
        loadPast(id)
    }
    rebuildThread()
}

/** Build the exchange from the recorded turns and the current tasks in the card; while the recorded turns have not been read yet it stays empty. */
internal fun CapsuleController.rebuildThread() {
    val id = threadOf ?: return
    val p = past ?: return
    val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
    val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
    thread = pastTitle?.let { built.copy(title = it) } ?: built
}

/** Read the turns recorded for conversation [id] in the background, along with the apps and sites operated by the turns being laid out (read from the run record, remembered once read), then re-order when they arrive. */
internal fun CapsuleController.loadPast(id: String) {
    threadJob?.cancel()
    val known = HashMap(placesOf)
    val n = shownTurns
    threadJob = scope.launch {
        val (turns, found) = withContext(Dispatchers.IO) {
            val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
            turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
        }
        val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
        placesOf += found
        if (threadOf != id) return@launch
        pastTitle = title
        past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
        rebuildThread()
    }
}

/** The apps and sites that run operated; empty when the record has been cleared or cannot be read (treated as pure chat). Call on a background thread. */
internal fun CapsuleController.placesIn(runId: String): List<String> = runCatching {
    CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
}.getOrDefault(emptyList())

/** Scrolled to the top: lay out [CapsuleThreads.PAGE] more turns backwards (reading the apps they operated first). */
internal fun CapsuleController.loadEarlier() {
    val id = threadOf ?: return
    val p = past ?: return
    if (p.size <= shownTurns) return
    shownTurns += CapsuleThreads.PAGE
    loadPast(id)
}

/** Follow the IME on the user's screen: re-place when it appears, disappears, or grows taller. The card is only placed using the tallest IME of this round, so it does not keep changing with the candidate row while typing (see [cardImeTop]). */
internal fun CapsuleController.watchIme(service: ScreenAccessibility) {
    imeJob?.cancel()
    imeJob = scope.launch {
        service.overlays().imeTop().collect {
            imeTop = it
            cardImeTop = CapsuleDocking.tallestImeTop(cardImeTop, it, dp(IME_SHRINK_JUMP_DP))
            place()
        }
    }
}

/** Recompute once the moment the model named arrives (sentence swap, shrinking after finishing, disappearing). */
internal fun CapsuleController.schedule(at: Long?) {
    tick?.cancel()
    tick = at?.let { t ->
        scope.launch {
            delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
            ticks.value++
        }
    }
}

/** Touched: restore immediately and restart the timer from now. */
internal fun CapsuleController.wake() {
    lastActive = System.currentTimeMillis()
    fade()
}

internal fun CapsuleController.fade() {
    fadeJob?.cancel()
    val v = view ?: return
    val idleFor = if (lastActive == 0L) CapsuleFade.IDLE_MS else (System.currentTimeMillis() - lastActive).coerceAtLeast(0L)
    val wantHidden = CapsuleEdge.hidden(last?.hideAtEdge == true, v.kind, v.pill, v.needCount,
        expanded || capturing, dragging, talk.active, note != null, idleFor)
    // 收起:先让一颗放一遍收起动效([OverlayMotion] 的 open=false),播完由 [finishTuck] 挪窗口、下一帧换半圆;
    // 收起过程中又醒了(点它、拖它、来了新事项)就把动效放回去
    if (tucking && !wantHidden) tucking = false
    if (tuckPending && !wantHidden) {
        // 刚好在换半圆之前那一帧醒了:窗口挪回停靠位,不换半圆
        tuckJob?.cancel()
        tuckPending = false
        place()
    }
    val before = edgeHidden
    if (!wantHidden && edgeHidden) edgeHidden = false
    if (wantHidden && !edgeHidden && !tucking && !tuckPending) tucking = true
    alpha = if (expanded || dragging) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor, last?.opacity?.alpha ?: CapsuleIdleOpacity.DEFAULT.alpha)
    if (before != edgeHidden) {
        applyDrag()
        place()
    }
    if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
        delay(CapsuleFade.IDLE_MS - idleFor)
        fade()
    }
}

/**
 * 收起动效播完:一颗已经淡到看不见了。先把窗口挪到屏幕外(这一帧画面上什么都没有),
 * **下一帧**才换成半圆([commitTuck]):半圆一出来就在实边上,不会先在停靠位闪一下。
 * 窗口尺寸不变(半圆和一颗共用一块窗口,见 [CapsuleEdge.tuckedOffset])。
 */
internal fun CapsuleController.finishTuck() {
    if (!tucking) return
    tucking = false
    tuckPending = true
    place()
    tuckJob?.cancel()
    // 浮层收不到下一帧时也要换过去:不能一直停在"一颗已经淡没了、半圆还没出来"
    tuckJob = scope.launch {
        delay(CapsuleController.TUCK_COMMIT_MS)
        commitTuck()
    }
    Choreographer.getInstance().postFrameCallback { commitTuck() }
}

/** [finishTuck] 之后的那一帧:窗口已经在屏幕外了,换成贴边半圆。 */
internal fun CapsuleController.commitTuck() {
    if (!tuckPending) return
    tuckJob?.cancel()
    if (dragging || view == null) {
        tuckPending = false
        place()
        return
    }
    tuckPending = false
    edgeHidden = true
    applyDrag()
    place()
}

/** 只给展开的那件事收画面;换了一件或收起就停。 */
internal fun CapsuleController.watchPreview(key: String?) {
    if (key == previewKey) return
    previewKey = key
    previewJob?.cancel()
    previewJob = null
    preview = null
    zoomed = false
    val run = key?.let(::runFor) ?: return
    previewJob = scope.launch {
        launch { run.frames.collect { if (it == null) preview = null } }
        run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect {
            preview = it.takeIf { run.frames.value != null }
        }
    }
}

/** The card for the page left running in the background: collect the image of the display it shows (the same throttle as the task card preview). */
internal fun CapsuleController.watchKept(id: Long) {
    val key = "kept:$id"
    if (key == previewKey) return
    previewKey = key
    previewJob?.cancel()
    preview = null
    zoomed = false
    previewJob = scope.launch {
        keeper.frames().filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
    }
}
