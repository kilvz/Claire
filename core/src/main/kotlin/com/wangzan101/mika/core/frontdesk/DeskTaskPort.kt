package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.StateFlow

/** What the front desk can do to background tasks; a task id is that task's child conversation id. */
interface DeskTaskPort {
    /** Dispatch a new task: [words] are the user's original words and [note] the front desk's addition (delivered separately to the background), under [parentConversation]. */
    fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome

    /**
     * Dispatch a new task carrying files (a message the user sent with attachments from the input field): open the child conversation first (under [parentConversation]),
     * [prepare] puts the files into the child conversation's workspace and returns the words to give the background (the user's words followed by file lines); when it returns null (files not placed)
     * or fails, the child conversation is deleted and nothing is dispatched. No note: this message does not go through the front-desk model. Ports without file support return Failed.
     */
    suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome =
        DispatchOutcome.Failed("files_unsupported")

    /** This task's state; null when the id is unknown. */
    fun state(taskId: String): StateFlow<TaskState>?

    /**
     * Whether run [runKey] belongs to the task this port is following; if so, return the task id. Later runs started in a task's child conversation
     * do not count; null when the port does not distinguish runs.
     */
    fun followedTaskOf(runKey: String): String? = null

    /** Position in the start queue while still queued (starting at 1); null once started or when unknown. */
    fun queuePosition(taskId: String): Int?

    /** Pass the user's message to a running task; false when it was not accepted. */
    fun steer(taskId: String, words: String): Boolean

    /** Answer the question a task is asking; false when the task is not waiting for an answer. */
    fun answer(taskId: String, words: String): Boolean

    /** Cancel the task; false when it already ended or is unknown. */
    fun cancel(taskId: String): Boolean
}

/** Dispatch result: started, queued behind others ([position] starting at 1), or not dispatched. */
sealed interface DispatchOutcome {
    data class Started(val taskId: String) : DispatchOutcome
    data class Queued(val taskId: String, val position: Int) : DispatchOutcome
    data class Failed(val reason: String) : DispatchOutcome
}
