package com.wangzan101.mika.core.screen.uistate

import kotlin.math.hypot

/**
 * Points at an element by description instead of by ref: [text] is the text it displays or its accessibility name
 * (when typing, it may also be the input field's placeholder), [role] is optional, and when [field] is true only
 * input fields are searched (used for typing).
 */
data class ElementQuery(val text: String, val role: String? = null, val field: Boolean = false)

sealed interface DescribedMatch {
    /** [exact]: the name (or placeholder) is exactly the description; otherwise it was found by prefix or containment, and the result must state which element was actually matched. */
    data class Found(val element: UiElement, val exact: Boolean) : DescribedMatch
    /** More than one match; [candidates] lists the actionable ones first, the rest in screen-read order. */
    data class Ambiguous(val candidates: List<UiElement>) : DescribedMatch
    data object None : DescribedMatch
}

/**
 * Finds the unique element on a screen by description. Whitespace is stripped and case ignored before comparing.
 * Searches layer by layer from strict to loose, and uses the first layer that yields a result:
 * name (or placeholder) exactly equal → name starts with the description → name contains the description (this layer
 * only accepts actionable elements, so a passage of body text that happens to contain those characters is not
 * picked). Within a layer, actionable ones are preferred (for typing, whether it accepts input; otherwise whether it
 * is clickable; unavailable ones do not count): exactly one is it; several, or none actionable but several matches,
 * is [DescribedMatch.Ambiguous], with no guessing. When [role] is given only that role is considered.
 */
object DescribedTarget {
    fun match(elements: List<UiElement>, q: ElementQuery): DescribedMatch {
        val want = norm(q.text)
        if (want.isEmpty()) return DescribedMatch.None
        val pool = elements.filter { e ->
            (!q.field || e.editable) && (q.role == null || e.role.equals(q.role.trim(), ignoreCase = true))
        }
        val tiers: List<Pair<(String) -> Boolean, Boolean>> = listOf(
            { s: String -> s == want } to false, { s: String -> s.startsWith(want) } to false, { s: String -> want in s } to true,
        )
        for ((i, tier) in tiers.withIndex()) {
            val (test, actionableOnly) = tier
            val hits = pool.filter { e -> labels(e, q.field).any(test) && (!actionableOnly || usable(e, q.field)) }
            if (hits.isEmpty()) continue
            val usable = hits.filter { usable(it, q.field) }
            return when {
                usable.size == 1 -> DescribedMatch.Found(usable[0], exact = i == 0)
                usable.isEmpty() && hits.size == 1 -> DescribedMatch.Found(hits[0], exact = i == 0)
                else -> DescribedMatch.Ambiguous(usable + (hits - usable.toSet()))
            }
        }
        return DescribedMatch.None
    }

    /** Actionable: for typing, whether it accepts input; otherwise whether it is clickable or editable; ones the page marks unavailable do not count. */
    fun usable(e: UiElement, field: Boolean): Boolean =
        UiStates.DISABLED !in e.states && (if (field) e.editable else e.clickable || e.editable)

    /** The nearest other named piece of text to [e] (with a different name): used to tell same-named candidates apart. */
    fun neighbour(e: UiElement, elements: List<UiElement>): String? = elements
        .filter { it !== e && it.name.isNotBlank() && norm(it.name) != norm(e.name) }
        .minByOrNull { hypot((it.centerX - e.centerX).toDouble(), (it.centerY - e.centerY).toDouble()) }?.name

    /** The text an element can be described by: its name; an input field also has its own placeholder. */
    private fun labels(e: UiElement, field: Boolean): List<String> =
        listOfNotNull(e.name, e.placeholder.takeIf { field || e.editable }).map(::norm).filter { it.isNotEmpty() }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}
