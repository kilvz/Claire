package com.wangzan101.mika.cap.phone.a11y

import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.screen.uistate.TextField
import com.wangzan101.mika.core.screen.uistate.typeAndVerify
import com.wangzan101.mika.core.screen.uistate.UnseenField
import com.wangzan101.mika.core.screen.uistate.normalize
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * The whole handling of typing / pasting / clearing: writing into an input field, or falling back to the clipboard
 * or the input method for custom-drawn editors.
 * Fields and methods shared with [AndroidScreen] are accessed through [screen] as a forward reference.
 */
internal class TextInput(private val screen: AndroidScreen) {

    internal suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val found = when (val f = screen.findField(ref, snapshotId)) {
            is AndroidScreen.Field.Found -> f
            is AndroidScreen.Field.Missing -> return@withContext when {
                f.reason == "not_editable" -> {
                    if (ref != null && snapshotId != null) {
                        contextPasteAnchor(ref, snapshotId)?.let { anchor ->
                            pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                        }
                    }
                    ActionResult.Failed(f.reason, "The referenced control did not expose an editor or a verified Paste menu.")
                }
                f.reason != AndroidScreen.NO_FOCUS -> ActionResult.Failed(f.reason)
                // Nothing on screen holds input focus and no input method is showing: no field can receive a paste, so
                // do not paste (once pasted, any change elsewhere would be taken as "text may have gone in")
                !screen.somethingHoldsInputFocus() -> ActionResult.Failed(AndroidScreen.NO_FOCUS)
                else -> typeUnseen(text)
            }
        }
        typeField(found, text)
    }
    internal suspend fun typeField(found: AndroidScreen.Field.Found, text: String): ActionResult {
        var field = found.node
        var original = AndroidScreen.FieldShape.of(field)
        var focusUncertain = false
        var replaced = false
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.refresh()
            // Both ACTION_FOCUS's return value and the focus flag can be unreliable: when focus is not
            // confirmed, tap it once more like an ordinary input field.
            if (!field.isFocused) {
                val focusedBefore = screen.focusedEditable()
                screen.withEventClock { clock ->
                    clock.mark()
                    screen.click(field).also { if (it) clock.awaitQuiet(AndroidScreen.QUIET_MS, AndroidScreen.FOCUS_WAIT_MS) }
                }
                val now = screen.focusedEditable()
                when {
                    now == null -> Unit
                    screen.sameField(original, now) -> field = now
                    // After the tap the original field is gone and a new field holding focus appeared in the same app
                    // (this is what happens when tapping a search bar jumps to a separate search page):
                    // rewrite this new field like an input entry point; do not switch while the original field is still
                    // on screen, lest the text be written into another field
                    now != focusedBefore && now.packageName == field.packageName &&
                        !(field.refresh() && screen.visibleInputTarget(field)) -> {
                        field = now
                        original = AndroidScreen.FieldShape.of(now)
                        replaced = true
                    }
                }
            }
            // SET_TEXT does not require focus, so it can still be attempted; the read-back check is what counts
            // in the end, but pass the unconfirmed focus to the caller truthfully.
            focusUncertain = !field.isFocused
        }
        val web = screen.inWebView(field)
        // A web editor only receives input by pasting; password fields do not go through the clipboard
        var pasteUnavailable = web && field.isPassword
        var inputUncertain = false
        val placeholder = ScreenReader.placeholderOf(field)
        fun writeByInput(value: String): Boolean = when (val r = screen.inputText(field, value)) {
            null -> { pasteUnavailable = true; screen.setText(field, value) }
            AsciiTextKeys.NOT_SENT -> false
            AsciiTextKeys.DISPATCHED -> true
            else -> {
                // When the action outcome is uncertain the text may already have been written: keep the
                // read-back check at UNKNOWN and never rewrite it by another means.
                inputUncertain = true
                true
            }
        }
        val io = object : TextField {
            override fun read(): String? {
                if (inputUncertain) return null
                if (field.refresh() && screen.visibleInputTarget(field)) return ScreenReader.fieldText(field, placeholder, text)
                // Some apps replace the input control whenever content changes: only re-read the one currently holding
                // input focus when its type, resource ID and position all match the original, otherwise treat it as
                // unreadable and do not write into another field either
                val now = screen.focusedEditable()?.takeIf { screen.sameField(original, it) } ?: return null
                field = now
                return ScreenReader.fieldText(now, placeholder, text)
            }
            override fun write(text: String): Boolean {
                if (!web || pasteUnavailable) return screen.setText(field, text)
                return writeByInput(text)
            }
            override fun retry(text: String): Boolean {
                if (web || pasteUnavailable) return write(text)
                // When the native field's first SET_TEXT did not take effect, the second attempt switches to
                // select-and-paste so the editor receives real input.
                return writeByInput(text)
            }
        }
        val textsBefore = screen.read().texts.toSet()
        // Type it out segment by segment like a human (see TypingPacing); password fields and web fields that only
        // receive input by pasting are still written in one go.
        // Stop when writing fails or the field does not accept partial text; the whole-string write and read-back below
        // still run as before
        screen.typing(field.packageName?.toString()).forField(web, field.isPassword).leadIn(io, text)
        var frozen = false
        val verification = typeAndVerify(
            io, text, AndroidScreen.TYPE_ATTEMPTS,
            settle = { screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.READBACK_QUIET_MS, AndroidScreen.READBACK_CAP_MS) } },
            // When the read-back never changes, look for new on-screen text containing the typed text (suggestion words etc.)
            reacted = {
                val typed = text.filterNot { it.isWhitespace() }
                (screen.read().texts.toSet() - textsBefore).any { typed in it.filterNot { c -> c.isWhitespace() } }.also { frozen = it }
            },
            secret = field.isPassword,
        )
        val hint = listOfNotNull(
            AndroidScreen.HINT_TAPPED_TO_FOCUS.takeIf { found.tapped },
            AndroidScreen.HINT_FIELD_REPLACED.takeIf { replaced },
            AndroidScreen.HINT_FOCUS_UNCONFIRMED.takeIf { focusUncertain },
            AndroidScreen.HINT_READBACK_FROZEN.takeIf { frozen },
            AndroidScreen.HINT_PASTE_UNAVAILABLE.takeIf { pasteUnavailable },
        ).joinToString(" ").ifEmpty { null }
        // The text may only be displayed and never received by the app: do not report "matched"
        val reported = if (pasteUnavailable && verification != Verification.MISMATCH) Verification.UNKNOWN else verification
        return screen.ok(verification = reported, hint = hint, secret = field.isPassword, relocatedTo = found.relocatedTo)
    }

    internal suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val found = when (val f = screen.findField(ref, snapshotId, tapToFocus = false)) {
            is AndroidScreen.Field.Found -> f
            is AndroidScreen.Field.Missing -> return@withContext when {
                f.reason != AndroidScreen.NO_FOCUS -> ActionResult.Failed(f.reason)
                !screen.somethingHoldsInputFocus() -> ActionResult.Failed(AndroidScreen.NO_FOCUS)
                else -> clearUnseen()
            }
        }
        clearField(found)
    }

    internal suspend fun clearField(found: AndroidScreen.Field.Found): ActionResult {
        val field = found.node
        val cleared = if (screen.inWebView(field)) screen.inputText(field, "") else {
            if (screen.setText(field, "")) AsciiTextKeys.DISPATCHED else AsciiTextKeys.NOT_SENT
        }
        return when (cleared) {
            null -> ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> ActionResult.Failed("action_failed")
            else -> {
                val result = screen.verified(relocatedTo = found.relocatedTo) { true }
                if (cleared == AsciiTextKeys.UNCONFIRMED && result is ActionResult.Ok) result.copy(
                    verification = Verification.UNKNOWN,
                    hint = "The clear action did not confirm delivery. Read the field before trying again.",
                ) else result
            }
        }
    }

    // ---------- Input fields invisible in the tree ----------

    /**
     * Types into the input field at this point: tap it first and wait for the UI to settle; if that brings up an input
     * field visible in the tree and holding focus, type into it as usual, otherwise paste and verify per [typeUnseen],
     * reading the on-screen text before pasting only after the tap (the caret and keyboard it brings up do not count as
     * typed text).
     * When the tap switches to another app (a link or a button was hit), do not type.
     */
    internal suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val hit = pointHit(x, y)
        val anchor = hit?.contextPasteAnchor()
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: anchor
        val original = anchor?.let(AndroidScreen.FieldShape::of)
        val keyTask = if (anchor != null && AsciiTextKeys.supports(text)) {
            runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        } else -1
        val tapped = screen.verified { screen.tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // The tap opened another app (including one opened on the user's main screen and moved back): do not
        // type; report the result of this tap as it is
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = screen.focusedField()) {
            is AndroidScreen.Field.Missing -> if (focused.reason == AndroidScreen.EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
            }
            is AndroidScreen.Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.",
                    )
                }
                return@withContext typeField(focused, text)
            }
        }
        val typed = if (target != null && pointEditorFocusMismatch(target)) {
            // The tap did not give the target input focus; while another hidden editor still holds focus, key
            // presses would go into that field; this text has not been sent yet.
            ActionResult.Failed(AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was sent.")
        } else {
            typeUnseen(text, atPoint = true)
        }
        if (typed is ActionResult.Failed && anchor != null && original != null && tapped.appSwitched == null) {
            // Only switch input channels when the paste key definitively failed to send, or the clipboard was rejected
            // before any change.
            // "Sent but no change observed" may already have been written, so never take another path that actually types.
            if (keyTask >= 0 && (typed.reason == AndroidScreen.INPUT_NOT_SENT || typed.reason == AndroidScreen.PASTE_UNAVAILABLE)) {
                typeWithKeys(anchor, original, keyTask, text)?.let { return@withContext it }
            }
            if (ContextPastePolicy.canRetry(typed.reason)) {
                val sameEditorFocused = pointEditorHasFocus(anchor, original)
                // The local Paste command itself confirms that the custom control can accept text.
                pasteFromContextMenu(anchor, text)?.let { return@withContext it }
                if (sameEditorFocused && keyTask < 0) {
                    return@withContext typed.copy(hint = "The editor did not accept clipboard input. Call hand_to_user to enter the text.")
                }
            }
        }
        typed
    }

    /**
     * Hardware-key fallback for an explicitly tapped custom-drawn editor: first confirm the select-all was
     * delivered, then type ASCII; never replay after a paste.
     */
    internal suspend fun typeWithKeys(
        anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape, taskId: Int, text: String,
    ): ActionResult? {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        if (imeBounds() != null || screen.focusedEditable() != null || !pointEditorHasFocus(anchor, original)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        currentCoroutineContext().ensureActive()
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return null
            AsciiTextKeys.UNCONFIRMED -> return ActionResult.Failed(
                "key_input_unconfirmed", "Select-all may have reached the field. Check it before entering text again.",
            )
        }
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val sent = runCatching { screen.shell.injectAsciiText(screen.displayId, taskId, pkg, text) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)
        return when (sent) {
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> screen.ok(
                verification = Verification.UNKNOWN,
                hint = "Sent keyboard events after select-all. Check the field before retrying or submitting.",
            )
            else -> ActionResult.Failed("key_input_unconfirmed", "Some keys may have reached the field. Check it before retrying.")
        }
    }

    /** True when focus clearly lands on a node other than the tapped point; when focus cannot be read, keep the original hidden-editor input path. */
    internal fun pointEditorFocusMismatch(anchor: AccessibilityNodeInfo): Boolean {
        val window = ScreenReader.contentWindows(screen.windows()).firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: return false
        if (window.id != anchor.windowId) return true
        val focus = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !samePointFocus(anchor, focus)
    }

    internal fun samePointFocus(target: AccessibilityNodeInfo, focus: AccessibilityNodeInfo): Boolean =
        target.windowId == focus.windowId && (within(target, focus) || within(focus, target))

    internal fun pointEditorHasFocus(anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape): Boolean {
        if (anchor.isCheckable || anchor.isScrollable) return false
        val window = contextPasteScope(anchor, original)?.second?.singleOrNull()?.takeIf { it.isFocused } ?: return false
        if (anchor.isFocused) return true
        val root = window.root ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return !focus.isPassword && (within(anchor, focus) || within(focus, anchor))
    }

    /** Whether [node] is [possibleAncestor] itself or a node below it. */
    internal fun within(node: AccessibilityNodeInfo, possibleAncestor: AccessibilityNodeInfo): Boolean =
        screen.ancestorOrSelf(node) { it == possibleAncestor } != null

    /** Clears the input field at this point, the same way as [typeAt]. */
    internal suspend fun clearAt(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val hit = pointHit(x, y)
        val target = hit?.node?.takeIf(ScreenReader::canSetText) ?: hit?.contextPasteAnchor()
        val tapped = screen.verified { screen.tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // The tap opened another app (including one opened on the user's main screen and moved back): do not
        // clear; report the result of this tap as it is
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        when (val focused = screen.focusedField()) {
            is AndroidScreen.Field.Missing -> if (focused.reason == AndroidScreen.EDITOR_NOT_VISIBLE) {
                return@withContext ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
            }
            is AndroidScreen.Field.Found -> {
                if (target != null && !samePointFocus(target, focused.node)) {
                    return@withContext ActionResult.Failed(
                        AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
                    )
                }
                return@withContext clearField(focused)
            }
        }
        if (target != null && pointEditorFocusMismatch(target)) {
            return@withContext ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The tapped editor did not take input focus; no text was cleared.",
            )
        }
        clearUnseen(atPoint = true)
    }

    /**
     * No input field in the tree holds focus: the field may be in UI the app draws itself, which the tree cannot read,
     * with focus on it.
     * Select all first (replacing the existing text, the same as setting text on a node), then paste via the
     * clipboard; key presses are sent only to Claire's screen; afterwards compare the text read on screen before and
     * after typing ([UnseenField.typed]). The input method need not be showing: key presses go to the focused window on
     * this screen, and the field receives them without the input method; if it is not showing and nothing changed at
     * all, ask the model to tap the input field first. While the input method is showing, the text on its keyboard does
     * not count as on-screen text.
     */
    internal suspend fun typeUnseen(text: String, atPoint: Boolean = false): ActionResult {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val ime = imeBox()
        val before = screen.readVisibleText()?.let { UnseenField.outside(it, ime) }
        val beforeTree = screen.treeHash()
        val beforeFrame = screen.frameSignature()
        var pasted: Int? = null
        val changed = screen.withEventClock { clock ->
            clock.mark()
            if (screen.blockedFocusedEditor()) return@withEventClock null
            pasted = pasteUnseen(text)
            pasted != null && pasted != AsciiTextKeys.NOT_SENT && screen.awaitChange(clock, beforeTree, beforeFrame)
        } ?: return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        when (pasted) {
            null -> return ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The input shortcut was not delivered; no text was sent by this attempt.",
            )
            AsciiTextKeys.UNCONFIRMED -> return screen.ok(
                changed = changed, verification = Verification.UNKNOWN,
                hint = "The paste shortcut may have been partially delivered. Check the field before retrying.",
            )
        }
        val after = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox() ?: ime) }
        return when (val r = UnseenField.typed(text, before, after, changed, keyboardUp = ime != null, atPoint = atPoint)) {
            is ActionResult.Ok -> screen.ok(changed = r.changed, verification = r.verification, hint = r.hint)
            else -> r
        }
    }

    /**
     * Replaces the clipboard with [text], selects all, pastes, gives the app time to read it asynchronously, then
     * restores it. Returns null when the clipboard cannot be used safely (the user's original content cannot be read):
     * nothing has been touched. Key-press results keep [AsciiTextKeys]'s three states; NOT_SENT only when the text
     * key-down definitively was not delivered.
     * When the standard paste action exists, it is called once only; a false return is also treated as uncertain and
     * Ctrl+V is not sent on top.
     */
    internal fun pasteUnseen(text: String): Int? =
        screen.withClipboardText(text) {
            if (screen.blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            when (ctrlKey(KeyEvent.KEYCODE_A)) {
                AsciiTextKeys.NOT_SENT -> return@withClipboardText AsciiTextKeys.NOT_SENT
                AsciiTextKeys.UNCONFIRMED -> return@withClipboardText AsciiTextKeys.UNCONFIRMED
            }
            if (screen.blockedFocusedEditor()) return@withClipboardText AsciiTextKeys.NOT_SENT
            val focus = ScreenReader.contentWindows(screen.windows()).firstOrNull { it.isFocused }?.root
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
            val sent = if (focus?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } == true) {
                if (focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
            } else {
                ctrlKey(KeyEvent.KEYCODE_V)
            }
            // The selection or caret may redraw first, and the app reads the clipboard asynchronously afterwards.
            Thread.sleep(AndroidScreen.PASTE_WAIT_MS)
            sent
        }

    /** Retain the node at an explicit point independently of context-menu eligibility. */
    internal fun pointHit(x: Int, y: Int): NodeEntry? {
        if (screen.handedOver() || imeBounds()?.contains(x, y) == true) return null
        val window = ScreenReader.contentWindows(screen.windows()).firstOrNull()
            ?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        val entries = ScreenReader.read(listOf(window), screen.screenWidth, screen.screenHeight).entries
        return entries.mapNotNull { entry ->
            val bounds = Rect().also(entry.node::getBoundsInScreen)
            if (bounds.contains(x, y)) entry to bounds.width().toLong() * bounds.height() else null
        }.minByOrNull { it.second }?.first
    }

    internal fun NodeEntry.contextPasteAnchor(): AccessibilityNodeInfo? = node.takeIf { contextPasteCandidate(this) }

    internal fun contextPasteAnchor(ref: String, snapshotId: String): AccessibilityNodeInfo? {
        if (screen.handedOver()) return null
        val node = screen.resolve(ref, snapshotId)?.first ?: return null
        val entry = screen.read().entries.firstOrNull { it.node == node } ?: return null
        return node.takeIf { contextPasteCandidate(entry) }
    }

    internal fun contextPasteCandidate(entry: NodeEntry): Boolean = ContextPastePolicy.canProbe(
        entry.node.isEnabled, entry.node.isPassword, entry.locator.inList, entry.element.role,
    )

    /** The editor must still be present, with only its own app's windows above it. */
    internal fun contextPasteScope(
        anchor: AccessibilityNodeInfo, original: AndroidScreen.FieldShape,
    ): Pair<Rect, List<AccessibilityWindowInfo>>? {
        if (!screen.hasDisplay || screen.handedOver() || !anchor.refresh() || !screen.visibleInputTarget(anchor) || screen.blockedFocusedEditor() ||
            anchor.isPassword || (anchor.textSelectionStart >= 0 && anchor.textSelectionEnd >= 0 &&
                anchor.textSelectionStart != anchor.textSelectionEnd)) return null
        val current = AndroidScreen.FieldShape.of(anchor)
        val bounds = current.rect
        val ime = imeBounds()
        // Focusing an editor can resize or move it, even when there is no IME.
        // A changed rectangle must still belong to the original, focused node.
        val sameSize = bounds.width() == original.rect.width() && bounds.height() == original.rect.height()
        if (current.cls != original.cls || current.rid != original.rid ||
            (bounds != original.rect && !anchor.isFocused && (ime == null || !sameSize)) ||
            ime?.contains(bounds.centerX(), bounds.centerY()) == true ||
            !Rect(0, 0, screen.screenWidth, screen.screenHeight).contains(bounds)) return null
        val pkg = anchor.packageName?.toString() ?: return null
        val content = ScreenReader.contentWindows(screen.windows())
        val index = content.indexOfFirst { it.id == anchor.windowId }
        if (index < 0) return null
        val scoped = content.take(index + 1)
        if (scoped.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION || it.root?.packageName?.toString() != pkg }) return null
        return bounds to scoped
    }

    /** Confirm a newly opened editor menu before using its local Paste command. */
    internal suspend fun pasteFromContextMenu(anchor: AccessibilityNodeInfo, text: String): ActionResult? {
        if (!anchor.refresh()) return null
        val original = AndroidScreen.FieldShape.of(anchor)
        if (contextPasteScope(anchor, original) == null) return null
        val beforeText = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
        val beforeTree = screen.treeHash()
        val beforeFrame = screen.frameSignature()
        val menu = ContextEditMenu(Resources.getSystem().getString(android.R.string.paste))
        fun isPaste(node: AccessibilityNodeInfo): Boolean {
            val label = ScreenReader.ownLabel(node)
            return menu.isPaste(node.viewIdResourceName, node.className?.toString()?.substringAfterLast('.'), label)
        }
        fun controls(scoped: List<AccessibilityWindowInfo>) =
            ScreenReader.read(scoped, screen.screenWidth, screen.screenHeight).entries.filter { it.node.isClickable && it.node.isEnabled }
        fun near(bounds: Rect, controls: List<NodeEntry>): List<AccessibilityNodeInfo> {
            val margin = min(screen.screenWidth, screen.screenHeight) / 5
            val vicinity = Rect(bounds).apply { inset(-margin, -margin) }
            val ime = imeBounds()
            return controls.filter { entry ->
                val x = entry.element.centerX
                val y = entry.element.centerY
                vicinity.contains(x, y) && ime?.contains(x, y) != true
            }.map { it.node }
        }
        val context = currentCoroutineContext()
        val pasted = screen.withClipboardText(text) {
            context.ensureActive()
            val (startBounds, scoped) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
            val before = controls(scoped)
            if (near(startBounds, before).any(::isPaste)) return@withClipboardText AsciiTextKeys.NOT_SENT
            val existing = before.map { it.node }.toSet()
            // A custom-drawn editor's long-press threshold may be longer than the system setting.
            val hold = maxOf(AndroidScreen.PASTE_MENU_MIN_HOLD_MS, ViewConfiguration.getLongPressTimeout().toLong() + 2 * AndroidScreen.POLL_MS)
            for (attempt in 0..1) {
                context.ensureActive()
                val (bounds, windows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                if (attempt > 0 && (!anchor.isFocused || windows.singleOrNull()?.isFocused != true ||
                        controls(windows).any { it.node !in existing })) {
                    return@withClipboardText AsciiTextKeys.NOT_SENT
                }
                val point = GesturePacing.Point(bounds.centerX().toDouble(), bounds.centerY().toDouble(), 0)
                if (!screen.injectStroke(listOf(point, point.copy(t = hold)))) return@withClipboardText AsciiTextKeys.NOT_SENT
                val deadline = SystemClock.uptimeMillis() + AndroidScreen.PASTE_MENU_WAIT_MS
                var previous: AccessibilityNodeInfo? = null
                var sawNewControl = false
                do {
                    context.ensureActive()
                    val (currentBounds, currentWindows) = contextPasteScope(anchor, original) ?: return@withClipboardText AsciiTextKeys.NOT_SENT
                    val current = controls(currentWindows)
                    sawNewControl = sawNewControl || current.any { it.node !in existing }
                    // Some editors add a clickable backdrop around the existing field.
                    // Its ancestors are containers, not newly offered edit commands.
                    val containers = generateSequence(anchor) { it.parent }.toSet()
                    val paste = menu.freshPaste(existing, near(currentBounds, current), ::isPaste, containers)
                    if (paste != null && paste == previous) {
                        if (!paste.refresh() || !paste.isVisibleToUser || !paste.isEnabled || !paste.isClickable || !isPaste(paste) || screen.handedOver()) {
                            return@withClipboardText AsciiTextKeys.NOT_SENT
                        }
                        context.ensureActive()
                        // performAction=false cannot prove the click did not land on the menu; finish as uncertain and do not send other input.
                        if (!paste.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
                        Thread.sleep(AndroidScreen.PASTE_WAIT_MS)
                        return@withClipboardText AsciiTextKeys.DISPATCHED
                    }
                    previous = paste
                    Thread.sleep(AndroidScreen.POLL_MS)
                } while (SystemClock.uptimeMillis() < deadline)
                // The editor may ignore the first long press; press again only when no menu control has appeared.
                if (sawNewControl) return@withClipboardText AsciiTextKeys.NOT_SENT
            }
            AsciiTextKeys.NOT_SENT
        }
        return when (pasted) {
            null -> ActionResult.Failed(AndroidScreen.PASTE_UNAVAILABLE, AndroidScreen.HINT_PASTE_BLOCKED)
            AsciiTextKeys.NOT_SENT -> null
            AsciiTextKeys.DISPATCHED -> {
                val afterText = screen.readVisibleText()?.let { UnseenField.outside(it, imeBox()) }
                val changed = beforeTree != screen.treeHash() || !beforeFrame.contentEquals(screen.frameSignature())
                val verification = UnseenField.verify(text, beforeText, afterText, changed)
                screen.ok(changed = changed, verification = verification, hint = "Used and checked the editor's Paste menu.")
            }
            else -> screen.ok(
                verification = Verification.UNKNOWN,
                hint = "The Paste menu click did not confirm delivery. Check the field before retrying.",
            )
        }
    }

    /**
     * Same preconditions as [typeUnseen]; delete only after the select-all delivery is confirmed, stop when
     * uncertain, and never retry over another path.
     */
    internal suspend fun clearUnseen(atPoint: Boolean = false): ActionResult {
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        val keyboardUp = imeBox() != null
        when (ctrlKey(KeyEvent.KEYCODE_A)) {
            AsciiTextKeys.NOT_SENT -> return ActionResult.Failed(
                AndroidScreen.INPUT_NOT_SENT, "The select-all key-down was not delivered; delete was not sent.",
            )
            AsciiTextKeys.UNCONFIRMED -> return screen.ok(
                verification = Verification.UNKNOWN,
                hint = "Select-all may have been partially delivered; delete was not sent. Check the field before retrying.",
            )
        }
        if (screen.blockedFocusedEditor()) return ActionResult.Failed(AndroidScreen.EDITOR_NOT_VISIBLE)
        if (!screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_DEL)) return screen.ok(
            verification = Verification.UNKNOWN,
            hint = "Delete did not confirm delivery. Check the field before trying to clear it again.",
        )
        return UnseenField.cleared(screen.verified { true }, keyboardUp, atPoint)
    }

    internal fun ctrlKey(keyCode: Int): Int =
        runCatching { screen.shell.injectCtrlKey(screen.displayId, keyCode) }.getOrDefault(AsciiTextKeys.UNCONFIRMED)

    /** The input method is showing on this screen (pressing back then dismisses the input method first on Android). */
    fun keyboardUp(): Boolean = screen.hasDisplay && imeBox() != null

    /**
     * Bounds of the input method window on this screen ([l,t,r,b] in 0–999); null when the input method is not
     * showing. While it is showing, text on it does not count as on-screen text.
     */
    internal fun imeBox(): List<Int>? {
        val r = imeBounds() ?: return null
        return listOf(normalize(r.left, screen.screenWidth), normalize(r.top, screen.screenHeight), normalize(r.right, screen.screenWidth), normalize(r.bottom, screen.screenHeight))
    }

    internal fun imeBounds(): Rect? {
        val w = screen.windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        return Rect().also(w::getBoundsInScreen).takeUnless { it.isEmpty }
    }
}
