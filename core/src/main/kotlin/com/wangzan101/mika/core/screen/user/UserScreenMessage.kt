package com.wangzan101.mika.core.screen.user

/**
 * How to write a user message that carries their current screen: a line `[[screen:<id>]]` on its own, with every other line being the accompanying words (which may be absent).
 * The message is stored into the conversation, handed to the task, and used as an interjection or answer as usual; the conversation page and cards draw it as a screenshot bubble from this, and when given to the model it is replaced by a description (see [UserScreenPrompt]).
 */
object UserScreenMessage {
    private val LINE = Regex("^\\[\\[screen:([A-Za-z0-9_-]{1,64})]]$")

    /** A message split apart: [screens] are the screen ids it carries (in order of appearance), [words] are the accompanying words (trimmed, an empty string when absent). */
    data class Parsed(val screens: List<String>, val words: String) {
        val hasScreen: Boolean get() = screens.isNotEmpty()
    }

    fun line(id: String): String {
        require(LINE.matches("[[screen:$id]]")) { "bad screen id" }
        return "[[screen:$id]]"
    }

    /** The screen line comes first and the accompanying words follow; when the words are empty only that line remains. */
    fun compose(id: String, words: String): String = listOf(line(id), words.trim()).filter(String::isNotEmpty).joinToString("\n")

    fun parse(text: String): Parsed {
        val screens = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) screens += m.groupValues[1] else rest += l
        }
        return Parsed(screens, if (screens.isEmpty()) text.trim() else rest.joinToString("\n").trim())
    }

    fun has(text: String): Boolean = text.lineSequence().any { LINE.matches(it.trim()) }
}
