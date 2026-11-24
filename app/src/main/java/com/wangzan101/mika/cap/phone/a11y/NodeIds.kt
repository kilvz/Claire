package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.core.cap.phone.PHONE_SNAPSHOT_PREFIX
import com.wangzan101.mika.core.screen.uistate.ElementLocator
import com.wangzan101.mika.core.screen.uistate.UiElement
import java.util.UUID

/**
 * snapshotId for phone screen reading: the phone prefix plus a random string (the ID alone tells it is the
 * phone, distinguishable from web pages).
 */
internal fun snapshotId(): String = PHONE_SNAPSHOT_PREFIX + UUID.randomUUID()

/** One element in a snapshot: the [element] handed to the model, the [locator] used to identify it, the [node] that can be acted on directly. */
internal class NodeEntry(val element: UiElement, val locator: ElementLocator, val node: AccessibilityNodeInfo)

internal class NodeSnapshot(val id: String, val entries: List<NodeEntry>) {
    val locators: List<ElementLocator> get() = entries.map { it.locator }
}

/**
 * Number registry: numbering restarts on every snapshot (from @e1), and only the numbers of the most recent few
 * snapshots are recognized. Numbers of the latest snapshot can be used directly; older snapshots are only used to
 * relocate an element by its original features.
 */
internal class NodeIds(private val keep: Int = 4) {
    sealed interface Lookup {
        class Current(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        class Old(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        data object Unknown : Lookup
    }

    private val history = ArrayDeque<NodeSnapshot>()

    @Synchronized
    fun put(snapshot: NodeSnapshot) {
        history.addFirst(snapshot)
        while (history.size > keep) history.removeLast()
    }

    @Synchronized
    fun clear() { history.clear() }

    @Synchronized
    fun current(): NodeSnapshot? = history.firstOrNull()

    @Synchronized
    fun lookup(ref: String, snapshotId: String): Lookup {
        val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return Lookup.Unknown
        val i = history.indexOfFirst { it.id == snapshotId }
        if (i < 0) return Lookup.Unknown
        val snap = history[i]
        val entry = snap.entries.getOrNull(index) ?: return Lookup.Unknown
        return if (i == 0) Lookup.Current(entry, snap) else Lookup.Old(entry, snap)
    }
}
