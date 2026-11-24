package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.AgentLoop

/**
 * The history sent to the model after a steer interrupts a streaming reply (see AgentLoop.keepInterrupted): the user's words,
 * the truncated text-only reply (the sentence may break off mid-way, with no thinking and no tool calls), then the steer and the runtime state as two user messages.
 * Every provider's adapter must convert it verbatim into a valid request.
 */
internal object InterruptedHistory {
    const val ASK = "详细介绍一下光合作用"
    const val PARTIAL = "光合作用是绿色植物利用光能,把二氧化碳和水"
    val STEER = "${AgentLoop.STEER_MARKER}\n简短点"
    const val RUNTIME = "Current place: no screen or page read yet"

    val messages = listOf(
        Message(Role.User, listOf(Part.Text(ASK)), Author.User),
        Message(Role.Assistant, listOf(Part.Text(PARTIAL)), Author.Mika),
        Message(Role.User, listOf(Part.Text(STEER)), Author.User),
        Message(Role.User, listOf(Part.Text(RUNTIME)), Author.Mika),
    )
}
