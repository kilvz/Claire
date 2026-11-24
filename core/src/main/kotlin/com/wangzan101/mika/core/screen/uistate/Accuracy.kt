package com.wangzan101.mika.core.screen.uistate

import kotlin.math.hypot

/**
 * Features used to recognize "the same element" in a new screen. [context] is the nearest named element before it in
 * traversal order, which prevents a same-named button elsewhere from sliding up and being mistaken for it after
 * scrolling. [hint]: the name is an input field's placeholder/prefilled word (which rotates);
 * [inList]: the element is inside a list container (list items are recycled, so the same position may hold a
 * different entry).
 */
data class ElementLocator(
    val role: String,
    val name: String,
    val resourceId: String?,
    val centerX: Int,
    val centerY: Int,
    val context: String?,
    val hint: Boolean = false,
    val inList: Boolean = false,
)

sealed interface Relocation {
    /** [index] is the index in the new list. */
    data class Found(val index: Int) : Relocation
    /** Still there, but the context changed or it moved too far. */
    data object Moved : Relocation
    /** No name and no resource ID, or several identical ones. */
    data object Ambiguous : Relocation
    /** It is no longer in the new screen. */
    data object Gone : Relocation
}

/** Maximum center shift allowed when relocating: this fraction of the screen's short side. */
const val RELOCATE_MAX_SHIFT = 0.15

/** Shift limit for "still in place": this fraction of the screen's short side. */
const val SAME_SPOT_SHIFT = 0.02

/**
 * Finds the unique counterpart of [previous] in [after] after its ref has expired. All of these must hold to count as
 * found: same role, name and resource ID, and only this one such element in both the old list [before] and the new
 * list; the same context; a center shift of no more than [RELOCATE_MAX_SHIFT] of the short side.
 *
 * When the page as a whole has not moved (see [stationary]), two points are relaxed further, to cope with carousels,
 * countdowns and other pages where only part of the screen changes:
 * - the context changed but the element is still in place and not inside a list container (list items are recycled):
 *   it still counts as found;
 * - the name changed, but old and new share the same resource ID, the role is the same, it is still in place and is
 *   the only one there, and the names differ only in digits and whitespace ("Skip 3" → "Skip 2"), or the old name is
 *   an input field's rotating prefilled word (hint): it also counts as found.
 *   Changes of meaning such as "Follow" → "Following" or "Next" → "Confirm payment" do not count.
 */
fun relocate(previous: ElementLocator, before: List<ElementLocator>, after: List<ElementLocator>, shortSide: Int): Relocation {
    if (previous.name.isBlank() && previous.resourceId.isNullOrBlank()) return Relocation.Ambiguous
    val spot = shortSide * SAME_SPOT_SHIFT
    val still by lazy { stationary(before, after, spot) }
    fun same(e: ElementLocator) = e.role == previous.role && e.name == previous.name && e.resourceId == previous.resourceId
    val candidates = after.withIndex().filter { same(it.value) }
    if (candidates.isEmpty()) {
        if (previous.resourceId.isNullOrBlank() || !still) return Relocation.Gone
        val inPlace = after.withIndex().filter { (_, e) ->
            e.role == previous.role && e.resourceId == previous.resourceId && shift(e, previous) <= spot
        }
        val found = inPlace.singleOrNull() ?: return Relocation.Gone
        val renamedAlike = previous.hint || stripDigits(found.value.name) == stripDigits(previous.name)
        return if (renamedAlike) Relocation.Found(found.index) else Relocation.Gone
    }
    if (candidates.size > 1 || before.count(::same) > 1) return Relocation.Ambiguous
    val (index, found) = candidates.single()
    val moved = shift(found, previous)
    if (moved > shortSide * RELOCATE_MAX_SHIFT) return Relocation.Moved
    if (found.context != previous.context && !(still && moved <= spot && !previous.inList)) return Relocation.Moved
    return Relocation.Found(index)
}

private fun stripDigits(s: String) = s.filterNot { it.isDigit() || it.isWhitespace() }

private fun shift(a: ElementLocator, b: ElementLocator) =
    hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

/**
 * The page as a whole has not moved: at least 2 named elements appear uniquely both before and after (splash screens
 * and the like have very few elements), and more than 80% of them are still in place.
 * When a list scrolls or the page changes, most elements have moved or disappeared, so this does not hold.
 */
internal fun stationary(before: List<ElementLocator>, after: List<ElementLocator>, spot: Double): Boolean {
    fun key(e: ElementLocator) = Triple(e.role, e.name, e.resourceId)
    val afterByKey = after.filter { it.name.isNotBlank() }.groupBy(::key)
    var shared = 0
    var inPlace = 0
    for ((k, list) in before.filter { it.name.isNotBlank() }.groupBy(::key)) {
        val a = afterByKey[k]?.singleOrNull() ?: continue
        if (list.size != 1) continue
        shared++
        if (shift(list[0], a) <= spot) inPlace++
    }
    return shared >= 2 && inPlace >= shared * 0.8
}

/** Input field read/write. [read] returns the text the user actually sees (an empty string when a placeholder is shown), or null when it cannot be read (password field, node no longer present). */
interface TextField {
    fun read(): String?
    fun write(text: String): Boolean
    /** Retry on another channel when the readback after the first write does not match; fields with no other channel write again the same way by default. */
    fun retry(text: String): Boolean = write(text)
}

/** Strip all whitespace before comparing: an input field may automatically insert spaces to group digits. */
private fun normText(s: String) = s.filterNot { it.isWhitespace() }

/**
 * Whether the readback is the ambiguous form "original placeholder + whitespace + target text".
 * It may be the widget joining the label and the body text together, or the user's body text may itself start with the
 * label; the prefix must not be stripped and it must not be judged a match, so the caller should stop retrying and
 * report UNKNOWN.
 */
fun ambiguousFieldReadback(shown: String, placeholder: String?, typing: String?): Boolean {
    val label = placeholder?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val wanted = typing?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val value = shown.trim()
    if (!value.startsWith(label) || !value.endsWith(wanted)) return false
    val separatorLength = value.length - label.length - wanted.length
    if (separatorLength <= 0) return false
    return value.substring(label.length, label.length + separatorLength).all { it.isWhitespace() }
}

/**
 * Writes [text] and verifies it by reading back, retrying on mismatch, at most [attempts] times; [settle] waits for the
 * field to update after each write. Only "the text read after writing is exactly the target" counts; when the field
 * already held the target before writing it is cleared first, and if it cannot be cleared there is no way to tell
 * whether the write went in, so UNKNOWN is returned. An empty string means clear, and an empty readback is MATCH.
 * Some input fields always report the same text through accessibility (such as the placeholder), so the readback never
 * matches: when every readback equals the pre-write baseline, at least one write succeeded, and [reacted] says the UI
 * showed new text containing what was typed after the input (autocomplete suggestions etc.), the app did receive the
 * input, so UNKNOWN is returned instead of MISMATCH.
 * [secret] (password fields) writes once, with no retry and no readback: a password field cannot read back its
 * plaintext, and retrying would only write the same text into the field again.
 */
suspend fun typeAndVerify(
    field: TextField,
    text: String,
    attempts: Int = 2,
    settle: suspend () -> Unit = {},
    reacted: () -> Boolean = { false },
    secret: Boolean = false,
): Verification {
    if (secret) {
        return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
    }
    val want = normText(text)
    var baseline = field.read() ?: run {
        // Still write once even when it cannot be read, but there is no way to verify; a rejected write itself must not be reported as unknown
        return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
    }
    if (want.isNotEmpty() && normText(baseline) == want) {
        val cleared = field.write("")
        settle()
        baseline = field.read() ?: return if (cleared) Verification.UNKNOWN else Verification.MISMATCH
        if (normText(baseline) == want) {
            return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
        }
    }
    var stuck = true
    var wrote = false
    repeat(attempts) { attempt ->
        val accepted = if (attempt == 0) field.write(text) else field.retry(text)
        if (!accepted) return@repeat
        wrote = true
        settle()
        val actual = field.read() ?: return Verification.UNKNOWN
        if (normText(actual) == want) return Verification.MATCH
        if (actual != baseline) stuck = false
    }
    return if (wrote && stuck && reacted()) Verification.UNKNOWN else Verification.MISMATCH
}
