package com.wangzan101.mika.core.screen.uistate

/**
 * Whether this screen's accessibility tree is adequate; when it is not, this is written into treeQuality of the screen-read result.
 * [Partial]: the tree is missing a piece (most controls have no name, or the screen has a fair amount of text the tree lacks); [Unusable]: almost nothing can be read from the tree;
 * [Protected]: a screenshot-protected page whose visuals are also unavailable (still handled as protected).
 */
internal enum class TreeQuality(val wire: String) {
    Adequate("adequate"), Partial("partial"), Unusable("unusable"), Protected("protected"),
}

/** A single verdict. [quality] null: the tree alone is inconclusive, so the on-screen text must be read first (it stays null if no text can be read); [reason] is why the tree is inadequate, and when a frame is attached it becomes imageReason. */
internal data class TreeCheck(val quality: TreeQuality?, val reason: String? = null) {
    /** The tree is missing things or unusable: the model must fall back to the frame or the on-screen text. */
    val poor: Boolean get() = quality == TreeQuality.Partial || quality == TreeQuality.Unusable
}

/**
 * Judge from the cheap tree first, and only when that is inconclusive compare against the on-screen text (OCR). Text is skipped only when the tree is "clearly adequate": enough named elements, most controls named,
 * and no large region inside app windows that has not a single character (canvas, map, image, web page whose content cannot be read). Everything else that is uncertain always reads text, preferring one extra read over a wrong call.
 */
internal object TreeAdequacy {
    /** Fewer named elements than this is inconclusive: a simple page with only a few words looks the same as a tree missing a large piece. */
    private const val ENOUGH_NAMED = 6
    /** At this many named elements the tree text is clearly adequate: even if clickable controls are not all named, it is not judged "missing a piece", and no OCR fallback happens for that reason. */
    private const val RICH_NAMED = 20
    /** A node whose subtree has not a single character that covers this much of the screen (0–1) is a blind spot. */
    private const val BLIND_AREA = 0.2
    /** With this many named elements inside it, it is only a backing plate underneath (background image, container) and does not count as a blind spot. */
    private const val NAMED_INSIDE = 3
    /** If OCR finds this many lines of text absent from the tree, the tree counts as missing a piece. */
    private const val MISSING_LINES = 3
    private const val APPLICATION = "application"

    fun ofTree(s: ScreenSnapshot): TreeCheck {
        if (s.protected) return TreeCheck(TreeQuality.Protected)
        if (s.unreadableApp != null && s.elements.none { it.meaningful }) return TreeCheck(TreeQuality.Unusable, "unreadable_window")
        if (!s.hasWindow && s.web == null) return TreeCheck(TreeQuality.Adequate)
        if (s.elements.isEmpty()) return TreeCheck(TreeQuality.Unusable, "empty_elements")
        val named = s.elements.filter { it.name.isNotBlank() }
        val controls = s.elements.filter { it.clickable || it.editable }
        // resourceId is usually a developer-internal identifier and must not be treated as a control name the user can recognize.
        val namedControls = controls.count { it.name.isNotBlank() || !it.placeholder.isNullOrBlank() }
        // The tree already has enough named elements: the page is text-rich and the model can understand and act on it via refs, so a few unnamed controls do not make it "missing a piece"
        val richNamed = named.size >= RICH_NAMED
        if (!richNamed && controls.isNotEmpty() && (namedControls == 0 || (controls.size >= 4 && namedControls * 3 <= controls.size)))
            return TreeCheck(if (named.isEmpty()) TreeQuality.Unusable else TreeQuality.Partial, "unlabeled_controls")
        if (named.isEmpty()) return TreeCheck(TreeQuality.Unusable, "no_text")
        if (named.size < ENOUGH_NAMED || blindArea(s, named)) return TreeCheck(null)
        return TreeCheck(TreeQuality.Adequate)
    }

    /** When the tree is inconclusive, settle it by the text that OCR found missing from the tree ([missing], with misread lines already dropped); if no text was read ([missing] null) it stays inconclusive. */
    fun withText(tree: TreeCheck, missing: List<VisibleTextLine>?): TreeCheck {
        if (tree.quality != null || missing == null) return tree
        return if (missing.size >= MISSING_LINES) TreeCheck(TreeQuality.Partial, "text_outside_tree") else TreeCheck(TreeQuality.Adequate)
    }

    /** An app window has a blind spot: a subtree with no characters at all that is large enough and is not a backing plate beneath named elements. */
    private fun blindArea(s: ScreenSnapshot, named: List<UiElement>): Boolean {
        var found = false
        // Returns whether this subtree has any text; a single pass, visiting each node only once
        fun visit(node: UiTreeNode): Boolean {
            var text = !node.text.isNullOrBlank() || !node.description.isNullOrBlank() || !node.hintText.isNullOrBlank()
            for (child in node.children) if (visit(child)) text = true
            if (!text && !found && area(node.bounds) >= BLIND_AREA && named.count { inside(it.bounds, node.bounds) } < NAMED_INSIDE) found = true
            return text
        }
        s.windows.filter { it.type == APPLICATION }.forEach { w -> w.nodes.forEach { visit(it) } }
        return found
    }

    private fun area(b: List<Int>): Double =
        if (b.size != 4) 0.0 else (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0) / (999.0 * 999.0)

    private fun inside(e: List<Int>, b: List<Int>): Boolean {
        if (e.size != 4 || b.size != 4) return false
        val x = (e[0] + e[2]) / 2; val y = (e[1] + e[3]) / 2
        return x in b[0]..b[2] && y in b[1]..b[3]
    }
}
