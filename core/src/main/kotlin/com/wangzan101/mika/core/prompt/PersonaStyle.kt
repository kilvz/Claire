package com.wangzan101.mika.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The part of the persona determined by UI options. Every option that is not the default corresponds to one fixed sentence in
 * `prompts/persona/style.md`, spliced into the persona block ahead of the user's own instructions; when everything is default, nothing is added.
 *
 * The options are deliberately few: one base style sets the tone, three three-level options cover the three things most often asked to be
 * changed (emoji, answer length, verbosity of progress notes), and everything else goes into the instructions. They only fall within what the
 * fixed head allows the persona to change: tone, answer length, how progress notes are written, formatting preferences.
 * Things like "ask me less" and "skip verification" belong to authorization and evidence rules and are not made into options.
 */
@Serializable
data class PersonaStyle(
    /** Base style and tone. */
    val base: Base = Base.Default,
    /** Emoji. */
    val emoji: Level = Level.Default,
    /** Answer length. */
    val length: Level = Level.Default,
    /** Verbosity of progress notes while working on a task. */
    val progress: Level = Level.Default,
) {
    /** [wire] is the spelling used in saved data and config files, and also the second half of the keys in style.md. */
    @Serializable
    enum class Base(val wire: String) {
        @SerialName("default") Default("default"),
        @SerialName("crisp") Crisp("crisp"),
        @SerialName("warm") Warm("warm"),
        @SerialName("playful") Playful("playful"),
    }

    @Serializable
    enum class Level(val wire: String) {
        @SerialName("less") Less("less"),
        @SerialName("default") Default("default"),
        @SerialName("more") More("more"),
    }

    val isDefault: Boolean get() = this == DEFAULT

    /** The style.md keys of the selected options, in a fixed order (the same option set always yields byte-for-byte identical text). */
    internal fun keys(): List<String> = buildList {
        if (base != Base.Default) add("base.${base.wire}")
        for ((name, level) in levels()) if (level != Level.Default) add("$name.${level.wire}")
    }

    /** The three-level options: name -> value, in UI order. */
    fun levels(): List<Pair<String, Level>> = listOf("emoji" to emoji, "length" to length, "progress" to progress)

    companion object {
        val DEFAULT = PersonaStyle()
    }
}
