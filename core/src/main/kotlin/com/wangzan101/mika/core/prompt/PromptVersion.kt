package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.model.ToolSpec
import java.security.MessageDigest

/** The version of this task's prompt: a digest of the system prompt plus the tool definitions, written into the run record; comparative evaluation uses it to tell prompt versions apart. */
object PromptVersion {
    fun of(system: String, tools: List<ToolSpec>): String {
        val text = system + tools.joinToString("") { "\n\n" + it.name + "\n" + it.description + "\n" + it.parametersJsonSchema }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}
