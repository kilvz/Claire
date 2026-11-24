package com.wangzan101.mika.core.learn.replay

/**
 * Takes the parameters from between the template's fixed literal fragments, preserving the exact original text
 * (including whitespace at either end of a parameter). Only a unique split is accepted.
 * No semantic rewriting is done; complex or ambiguous templates are left to the original model, and both paths are
 * validated by RouteMatcher.validated.
 */
internal object RouteSlots {
    private val SLOT = Regex("\\{(\\d+)\\}")
    private const val MAX_CHARS = 8_000
    private const val MAX_SLOTS = 8
    private const val MAX_SEARCH = 128

    fun extract(route: Route, goal: String): List<String>? {
        if (goal.length > MAX_CHARS || route.template.length > MAX_CHARS || route.slots !in 0..MAX_SLOTS) return null
        val holes = SLOT.findAll(route.template).toList()
        val ids = holes.map { it.groupValues[1].toIntOrNull() ?: return null }
        // the recorder numbers slots in the order of operations, not necessarily in utterance order; templates with
        // duplicate or missing numbers are not guessed at.
        if (ids.sorted() != (1..route.slots).toList()) return null
        val parts = SLOT.split(route.template)
        if (holes.isEmpty()) return emptyList<String>().takeIf {
            goal.filterNot(Char::isWhitespace) == parts.single().filterNot(Char::isWhitespace)
        }
        // parameter boundaries are matched against the original literal text; leading whitespace and trailing newlines
        // must not be dropped by stripping whitespace first.
        // When a fixed fragment's whitespace is written differently, hand it back to the original model rather than
        // guessing which whitespace belongs to a parameter.
        if (!goal.startsWith(parts.first())) return null
        if (parts.drop(1).dropLast(1).any(String::isBlank)) return null
        val slots = MutableList(route.slots) { "" }
        val matches = mutableListOf<List<String>>()
        var searched = 0
        var exhausted = false
        fun visit(hole: Int, start: Int) {
            if (matches.size > 1 || exhausted) return
            if (++searched > MAX_SEARCH) { exhausted = true; return }
            if (hole == holes.size) {
                if (start == goal.length && RouteMatcher.validated(route, slots, goal) != null) matches += slots.toList()
                return
            }
            val next = parts[hole + 1]
            if (next.isEmpty()) {
                if (start < goal.length) {
                    slots[ids[hole] - 1] = goal.substring(start)
                    visit(hole + 1, goal.length)
                }
                return
            }
            var end = goal.indexOf(next, start + 1)
            while (end >= 0 && matches.size <= 1 && !exhausted) {
                slots[ids[hole] - 1] = goal.substring(start, end)
                visit(hole + 1, end + next.length)
                end = goal.indexOf(next, end + 1)
            }
        }
        visit(0, parts.first().length)
        return matches.singleOrNull().takeUnless { exhausted }
    }
}
