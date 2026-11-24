package com.wangzan101.mika.task

import android.graphics.Bitmap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Messages the user injects into the same conversation while a task is running: [Pending] just sent and not yet delivered to the task, [Received] the task's inbox has accepted it (it will be handled on the next step),
 * [Joined] handed to the current task's model, [Next] the task has finished and it becomes the next job, [Cancelled] a stop, or a remote steer that went unused when the current task finished, [Withdrawn] withdrawn by the user.
 */
enum class SteerState { Pending, Received, Joined, Next, Cancelled, Withdrawn }

/** A steer not yet handed to the model (it becomes the next job if the task finishes here, discarded on stop). */
val SteerState.unconsumed: Boolean get() = this == SteerState.Pending || this == SteerState.Received

/**
 * [interrupted] is the part of the reply that this steer cancelled which the user had already seen; [finished] is the reply that had already finished being shown before this steer was handed to the model.
 * Both are shown above the steer; null when there is none.
 */
data class SteerNote(
    val id: String, val text: String, val state: SteerState, val interrupted: String? = null, val finished: String? = null,
    val remote: Boolean = false,
)

/** The computer only sees where its own added messages went, never the steers from the phone or their text; remote steers can't be withdrawn (`RunInbox.withdrawSteer`), so [SteerState.Withdrawn] exists only for exhaustiveness and is reported as not applied. */
internal fun remoteSteers(notes: List<SteerNote>, finished: Boolean = false): List<RemoteSteer> = notes.filter { it.remote }.map {
    RemoteSteer(it.id, when (it.state) {
        SteerState.Pending, SteerState.Received -> if (finished) "not_applied" else "received"
        SteerState.Joined -> "joined"
        SteerState.Next, SteerState.Cancelled, SteerState.Withdrawn -> "not_applied"
    })
}

/**
 * Order in which the run events report steers being handed to the model: `reply_interrupted` ([interrupted]) or `reply_finished_before_steer` ([finished]) arrives first,
 * immediately followed by `steer_received` for each steer of the batch, and that text is attached only to the first of them ([joined]).
 */
class SteerJoin {
    @Volatile private var cut: String? = null
    @Volatile private var done: String? = null

    fun interrupted(text: String) { cut = text }

    fun finished(text: String) { done = text }

    /** The steer [id] has been handed to the model: mark it [SteerState.Joined] and attach the text just recorded, if any (cleared once used). */
    fun joined(notes: List<SteerNote>, id: String): List<SteerNote> {
        val c = cut.also { cut = null }
        val d = done.also { done = null }
        return notes.map {
            if (it.id == id) it.copy(state = SteerState.Joined, interrupted = c ?: it.interrupted, finished = d ?: it.finished) else it
        }
    }
}

/** Display state of the in-process TaskCenter, owned by the App container; the Service is only the host that writes to it. */
class TaskCenterState {
    val shownLock = Any()
    @Volatile var shownRun: TaskRun? = null

    val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()
    val _activeConversations = MutableStateFlow<List<String>>(emptyList())
    val activeConversations: StateFlow<List<String>> = _activeConversations.asStateFlow()
    val _shownTask = MutableStateFlow<Pair<String, TaskState>?>(null)
    val shownTask: StateFlow<Pair<String, TaskState>?> = _shownTask.asStateFlow()
    fun run(conversationId: String): TaskRun? = _runs.value.firstOrNull { it.conversationId == conversationId }
    fun show(conversationId: String) { run(conversationId)?.show() }

    @Volatile var secondDisplayFailed = false
    val _parallelLimit = MutableStateFlow(ParallelLimit(1, null))
    val parallelLimit: StateFlow<ParallelLimit> = _parallelLimit.asStateFlow()
    fun markSecondDisplayFailed() {
        secondDisplayFailed = true
        _parallelLimit.value = ParallelLimit(1, ParallelCapability.SECOND_DISPLAY_FAILED)
    }

    val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val _takeoverStage = MutableStateFlow(Takeover.Stage.None)
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.asStateFlow()
    val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()
    val _runId = MutableStateFlow<String?>(null)
    val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()
    /** Audio generated so far by the displayed task; saved into the conversation with this turn when it ends. */
    val _audios = MutableStateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>>(emptyList())
    val audios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> = _audios.asStateFlow()
    /** Images generated so far by the displayed task; saved into the conversation with this turn when it ends. */
    val _photos = MutableStateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>>(emptyList())
    val photos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> = _photos.asStateFlow()
    val _endedRunId = MutableStateFlow<String?>(null)
    val endedRunId: StateFlow<String?> = _endedRunId.asStateFlow()
    val _endedRunIds = MutableStateFlow<Map<String, String?>>(emptyMap())
    val endedRunIds: StateFlow<Map<String, String?>> = _endedRunIds.asStateFlow()
    /** Activity of the displayed task so far (what was actually done, taken from the run events). */
    val _activities = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.asStateFlow()
    /** Steers received by the displayed task and where they went. */
    val _steers = MutableStateFlow<List<SteerNote>>(emptyList())
    val steers: StateFlow<List<SteerNote>> = _steers.asStateFlow()
    /** Runs (run IDs) whose summary is being written after a stop; removed once it is written into the conversation or fails to be written. */
    val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()
    val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
    /** Picture-in-picture input port for the displayed task: only present when the virtual display exists and the "direct control" switch is on; back to null once the display is released. */
    val _pipInput = MutableStateFlow<com.wangzan101.mika.cap.phone.display.PipInput?>(null)
    val pipInput: StateFlow<com.wangzan101.mika.cap.phone.display.PipInput?> = _pipInput.asStateFlow()
    /** Whether the user has restored the sound of the displayed task's picture-in-picture target: the button shows "Restore sound" or "Mute" based on this. */
    val _pipSoundOn = MutableStateFlow(false)
    val pipSoundOn: StateFlow<Boolean> = _pipSoundOn.asStateFlow()
}
