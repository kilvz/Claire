package com.wangzan101.mika.core.screen.uistate

/** One accessibility window on a screen. The window is kept even when its root node cannot be read, so the model knows there is an overlay or a loading UI. */
data class UiWindowTree(
    val id: Int,
    val type: String,
    val layer: Int,
    val title: String?,
    val packageName: String?,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<UiTreeNode>,
)

/** A visible accessibility node; only nodes that can be resolved from this screen's element table carry a [ref]. */
data class UiTreeNode(
    val role: String,
    val bounds: List<Int>,
    val ref: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val secret: Boolean = false,
    /** Looks like a wheel picker (time, date, region, … turned notch by notch): use the wheel tool to turn straight to the value. */
    val wheel: Boolean = false,
    val children: List<UiTreeNode> = emptyList(),
) {
    /** Stable numbering only replaces the model-facing address; the node itself and the Android-side reference table are left untouched. */
    fun withRefs(refs: Map<String, String>): UiTreeNode = copy(
        ref = ref?.let { refs[it] ?: it },
        children = children.map { it.withRefs(refs) },
    )
}
