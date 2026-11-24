package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec

/**
 * Tools are sent to the model in two layers: the ones always present (the core general tools, unfolded capabilities), and one group per capability (while folded only the entry tool, with a short description;
 * on first use the whole group is expanded and the full text comes back, and it stays that way for the rest of the task). General tools offered only when one of the screen groups is expanded (takeover, report obstacle, look back at the screen, several steps at once)
 * count as the [SCREENS] group, and expand whenever any screen expands.
 *
 * The order is fixed: always-present → [SCREENS] → the capability groups (in registration order). Expanding a group only changes the part from that group on,
 * the preceding bytes stay the same; the request prefix is invalidated by an expansion at most once.
 */
internal object ToolGroups {
    /** The group shared by the screen groups. */
    const val SCREENS = "screens"

    /** One frozen tool: the full definition, the entry definition used while folded (null when it is not an entry), and the group it belongs to (null for always-present). */
    class Slot(val full: ToolSpec, val entry: ToolSpec?, val group: String?)

    /**
     * The tools sent to the model right now: [expanded] holds the expanded groups (including [SCREENS]); [extra] holds definitions the loop adds itself for some group
     * (several steps at once belongs to [SCREENS]), placed at the end of that group and given only when that group is expanded.
     */
    fun layout(slots: List<Slot>, expanded: Set<String>, extra: Map<String, List<ToolSpec>> = emptyMap()): List<ToolSpec> =
        order(slots).flatMap { g ->
            val members = slots.filter { it.group == g }
            if (g == null || g in expanded) members.map { it.full } + extra[g].orEmpty()
            else members.mapNotNull { it.entry }
        }

    /** The order of the groups: always-present, [SCREENS], then capabilities in order of first appearance. */
    fun order(slots: List<Slot>): List<String?> = listOf(null, SCREENS) + slots.mapNotNull { it.group }.filter { it != SCREENS }.distinct()
}
