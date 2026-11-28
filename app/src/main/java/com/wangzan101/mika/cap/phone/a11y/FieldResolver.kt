package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.EDITOR_NOT_VISIBLE
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.FOCUS_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.NO_FOCUS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.QUIET_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SAME_FIELD_SIZE
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SEARCH_WORDS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Field
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.FieldShape
import com.wangzan101.mika.core.screen.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.relocate
import kotlin.math.min

// Ref resolution and field lookup: resolve a ref from the snapshot back to a node, find the field to type into and the field holding input focus. All extensions on [AndroidScreen], same package.

/**
 * Resolve a ref into an operable node; when relocated, the second value is the current name of the recovered element, otherwise null.
 * If the node from the latest snapshot is still there and unchanged, use it directly; if the snapshot is stale or the node changed, find the unique match in the current UI by the original element's traits.
 */
internal fun AndroidScreen.resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
    val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
        is NodeIds.Lookup.Unknown -> return null
        is NodeIds.Lookup.Current -> {
            if (unchanged(l.entry)) return l.entry.node to null
            l.entry to l.snapshot
        }
        is NodeIds.Lookup.Old -> l.entry to l.snapshot
    }
    val now = read()
    return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(screenWidth, screenHeight))) {
        is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
        else -> null
    }
}

/** The node is still there, with the same type, name, resource ID and position as in the snapshot (a recycled list node may hold different content). */
internal fun AndroidScreen.unchanged(entry: NodeEntry): Boolean {
    val n = entry.node
    if (!n.refresh()) return false
    val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
    return now == entry.locator.copy(context = null)
}


/**
 * Find the field to type into. With a ref: the element itself or an input field inside it; if neither and it looks like an input entry (the "fake search box" on a home page:
 * clickable, not a button, with a hint or a search-like name), tap it first, and only use the resulting field if the tap succeeded and a focused input field appeared that was not there before the tap.
 * Without a ref: the field currently holding input focus.
 */
internal suspend fun AndroidScreen.findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
    if (ref != null && snapshotId != null) {
        val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
        val editable = if (ScreenReader.canSetText(node)) node
            else descendant(node) { ScreenReader.canSetText(it) && visibleInputTarget(it) }
        if (editable != null) {
            if (!editable.refresh() || !visibleInputTarget(editable)) return Field.Missing(EDITOR_NOT_VISIBLE)
            return Field.Found(editable, relocatedTo = relocatedTo)
        }
        if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
        val focusedBefore = focusedEditable()
        val clicked = withEventClock { clock ->
            clock.mark()
            click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
        }
        val now = focusedField()
        return when {
            clicked && now is Field.Found && now.node != focusedBefore ->
                Field.Found(now.node, tapped = true, relocatedTo = relocatedTo)
            clicked && now is Field.Missing && now.reason == EDITOR_NOT_VISIBLE -> now
            else -> Field.Missing("not_editable")
        }
    }
    return focusedField()
}


internal fun AndroidScreen.sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
    val b = FieldShape.of(n)
    if (a.pkg != b.pkg || a.windowId != b.windowId || a.cls != b.cls || a.rid != b.rid) return false
    // Sizes must be nearly equal; position may shift a little with the page (pages often move up while typing), using the same limit as ref recovery
    val short = min(screenWidth, screenHeight)
    val sizeTol = short * SAME_FIELD_SIZE
    val moveTol = short * RELOCATE_MAX_SHIFT
    return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
        kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
}

/** Looks like an input entry: clickable, not a button class, and with a hint or a search-like name. */
internal fun AndroidScreen.looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
    val target = ancestorOrSelf(n) { it.isClickable } ?: return false
    val cls = target.className?.toString().orEmpty()
    if (cls.endsWith("Button")) return false
    if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
    val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
    return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
}

/**
 * Something on this display holds input focus (even if it is not an input field in the tree, e.g. an app-drawn UI holding focus as a whole), or the IME is showing.
 * If neither, keys have no field to go to: an input field invisible in the tree only receives pastes when it holds focus.
 */
internal fun AndroidScreen.somethingHoldsInputFocus(): Boolean =
    textInput.imeBounds() != null || focusedInput() != null

internal fun AndroidScreen.focusedInput(): AccessibilityNodeInfo? {
    val content = ScreenReader.contentWindows(windows())
    val focusedWindow = content.firstOrNull { it.isFocused }
    if (focusedWindow != null) return focusedWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    return content.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
}

internal fun AndroidScreen.visibleInputTarget(node: AccessibilityNodeInfo): Boolean =
    node.isEnabled && ScreenReader.locatorOf(node, screenWidth, screenHeight) != null

/** Keep a known hidden native editor distinct from a custom editor absent from the tree. */
internal fun AndroidScreen.focusedField(): Field {
    val focus = focusedInput() ?: return Field.Missing(NO_FOCUS)
    val editor = if (ScreenReader.canSetText(focus)) focus
        else descendant(focus) { it.isFocused && ScreenReader.canSetText(it) } ?: return Field.Missing(NO_FOCUS)
    return if (editor.refresh() && visibleInputTarget(editor)) Field.Found(editor) else Field.Missing(EDITOR_NOT_VISIBLE)
}

internal fun AndroidScreen.focusedEditable(): AccessibilityNodeInfo? = (focusedField() as? Field.Found)?.node

internal fun AndroidScreen.blockedFocusedEditor(): Boolean = (focusedField() as? Field.Missing)?.reason == EDITOR_NOT_VISIBLE
