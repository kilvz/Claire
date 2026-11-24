package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One entry listed when the system asks "which one to open". [clone]: whether it is the one in another user profile (clone, work profile); null when it cannot be told. */
data class ChoiceOption(val label: String, val clone: Boolean?)

/** The "which one to open" dialog the system shows after opening an app: [packageName] is the package of the window that asks, [options] are the options that mention this app. */
data class LaunchChoice(val packageName: String, val options: List<ChoiceOption>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        put("options", buildJsonArray {
            for (o in options) add(buildJsonObject { put("label", o.label); o.clone?.let { put("clone", it) } })
        })
    }
}

/**
 * Recognizes the "which one to open" dialog: after opening an app, the topmost window has switched to another system
 * package, and the options in that window mention this app's name.
 * When the same app is installed in two user profiles (clone, work profile), some systems ask which one to use first;
 * if this is not recognized, it looks like the app never opened, and the model keeps reopening it and getting asked
 * again and again.
 */
object LaunchChooser {
    /**
     * The clickable options among the window's elements that mention the app name [appLabel]; null when there are
     * none (this is not a "which one to open" dialog). Only clickable ones count: explanatory text such as permission
     * dialogs also mentions the app name, but its buttons do not carry it.
     * [profiles] are the names of the phone's other user profiles: an option containing one of them is another copy,
     * and an option whose name is exactly the app name is the original copy.
     */
    fun options(appLabel: String, elements: List<UiElement>, profiles: List<String>): List<ChoiceOption>? {
        val label = key(appLabel)
        val picked = optionIndices(appLabel, elements)?.map { elements[it] } ?: return null
        val others = profiles.map(::key).filter { it.isNotEmpty() }
        val counts = picked.groupingBy { key(it.name) }.eachCount()
        return picked.map { e ->
            val name = key(e.name)
            val clone = when {
                others.any { name.contains(it) } -> true
                // Two entries have identical names (told apart only by a badge): the text alone cannot tell which is the original copy
                counts.getValue(name) > 1 -> null
                name == label -> false
                else -> null
            }
            ChoiceOption(e.name.replace('\n', ' ').trim(), clone)
        }
    }

    /** The indices in [elements] of the options recognized by [options] (order preserved); null when this is not a "which one to open" dialog. Used when tapping an option. */
    fun optionIndices(appLabel: String, elements: List<UiElement>): List<Int>? {
        val label = key(appLabel)
        if (label.isEmpty()) return null
        return elements.indices.filter { elements[it].let { e -> e.clickable && e.name.isNotBlank() && key(e.name).contains(label) } }.ifEmpty { null }
    }

    private fun key(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}

/** One user profile on this phone (a line of `pm list users`): [id] the user ID, [name] the name (e.g. clone, work profile), [running] running. */
data class UserProfile(val id: Int, val name: String, val running: Boolean)

/** Parses the output of `pm list users` and `pm list packages`. */
object AppProfiles {
    private val USER = Regex("""UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}(\s+running)?""")
    private val PACKAGE = Regex("""^package:([A-Za-z0-9_.]+)\s*$""")

    fun parseUsers(output: String): List<UserProfile> = output.lineSequence().mapNotNull { line ->
        val m = USER.find(line) ?: return@mapNotNull null
        UserProfile(m.groupValues[1].toInt(), m.groupValues[2].trim(), m.groupValues[3].isNotEmpty())
    }.toList()

    fun parsePackages(output: String): Set<String> =
        output.lineSequence().mapNotNull { PACKAGE.find(it.trim())?.groupValues?.get(1) }.toSet()

    /** Which other user profiles each package is also installed in: [others] maps user → the packages installed in that profile. */
    fun alsoIn(others: Map<UserProfile, Set<String>>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for ((user, packages) in others) for (p in packages) out.getOrPut(p) { mutableListOf() } += user.name.ifEmpty { "user ${user.id}" }
        return out
    }
}
