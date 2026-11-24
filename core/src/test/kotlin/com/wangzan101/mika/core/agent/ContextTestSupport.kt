package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part

/** Strips from a request the runtime state appended at the end of each step, so tool results and notices can be viewed at their original positions. */
internal fun List<Message>.withoutRuntime(): List<Message> = map(ContextBuilder::withoutRuntimeState).filter { it.parts.isNotEmpty() }

/** The message text (without runtime state): contexts have their envelope stripped and only the body kept; everything else stays as is. */
internal fun Message.plainText(): String = ContextBuilder.withoutRuntimeState(this).let { m ->
    ContextBuilder.bodyOf(m) ?: m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}

/** The bodies of one kind of context, in order of appearance. */
internal fun List<Message>.contextBodies(vararg kinds: ContextKind): List<String> =
    withoutRuntime().filter { ContextBuilder.kindOf(it) in kinds }.mapNotNull { ContextBuilder.bodyOf(it) }

/** The runtime-state bodies in one request, in order of appearance. */
internal fun List<Message>.runtimeStates(): List<String> = flatMap { it.parts }.filterIsInstance<Part.Text>()
    .filter { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }
    .map { ContextBuilder.bodyOf(com.wangzan101.mika.core.model.Message(com.wangzan101.mika.core.model.Role.User, listOf(it), com.wangzan101.mika.core.model.Author.Mika))!! }
