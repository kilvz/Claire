package com.wangzan101.mika.core.tool

/**
 * Lets a tool wait inside its own execute for the user to answer one question (the same consent as for self operations).
 * Returns null when there is no one to ask (unattended, timed out, the channel cannot answer); the caller must treat that as a refusal and never approve automatically.
 */
internal interface AskPort {
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String?
}
