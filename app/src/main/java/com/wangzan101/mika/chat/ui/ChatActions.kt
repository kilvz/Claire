package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.memory.RunNotes

/** What the UI can ask of the task service; implemented by TaskService on a real device, replaced by call recording in tests. */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** Text injected into its conversation while the task is running; [messageId] is used to track where this message ends up. */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    /** Queues the next item in this conversation while a task is running; persisted first, returns false on failure. With [startIfIdle] false it only persists and does not start ahead of the original task. */
    fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean = true): Boolean {
        start(text, conversationId)
        return true
    }
    /** Cancels a queued item that has not started yet; returns false once it has started. */
    fun cancelFollowUp(conversationId: String, messageId: String): Boolean = false
    /** When this conversation is opened, take the next queued item under it and start it if no task is running. */
    fun drainFollowUps(conversationId: String) {}
    fun withdrawSteer(conversationId: String, messageId: String): Boolean = false
    fun cancel()
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] identifies the approval request the card belongs to. */
    fun approve(key: Int)
    fun decline(key: Int)
    /** With parallel tasks, makes the service display this conversation's task (display only: the outward state stream follows it). */
    fun show(conversationId: String) {}

    // With parallel tasks, in-app buttons carry the conversation on screen and act only on that conversation's task; a no-op once it has no task. By default they match the versions without a conversation.
    fun cancelIn(conversationId: String) = cancel()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
    /** Toggles the sound of the current app of this conversation's task (restore / mute again); does nothing when there is no task. */
    fun togglePipSoundIn(conversationId: String) {}
}

/** Buttons of the memory and skill prompts on result cards; implemented by the memory store and personal skills on a real device (reads and writes on a background thread), replaced by call recording in tests. */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** When the undo token is no longer valid (after a restart), "No" deletes the pending practice directly. */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** The prompt changed (a line was removed after undo or approval): store it in the conversation and notify the UI. */
    suspend fun save(notes: RunNotes)
}
