package com.wangzan101.mika.core.screen.uistate

/**
 * Recognizes a "back" control among a screen's interactive elements, used as a fallback when injecting back fails.
 *
 * Recognition order (earlier is more reliable): name exactly matches a back word → name contains a back word →
 * an unnamed icon-like control in the top-left corner.
 * Returns null when nothing is recognized, leaving it to the caller to decide whether to tap blindly — no blind taps
 * here.
 */
object BackAffordance {
    private val EXACT = setOf(
        "返回", "后退", "回退", "返回键", "向上导航",
        "back", "navigate up", "previous",
        "←", "↩", "‹", "＜",
    )
    // "back" is excluded: as a substring it would also hit feedback/backup/background
    private val CONTAINS = listOf("返回", "后退", "回退", "向上导航", "previous", "navigate up")
    private val ICON_ROLES = setOf("ImageButton", "AppCompatImageButton", "ImageView", "Image")
    // Trailing segments of back controls' resource IDs (with the package name and /id/ prefix removed)
    private val RESOURCE_SEGMENTS = setOf("back", "navigate_up", "arrow_back", "btn_back", "toolbar_back", "action_bar_up")

    fun find(elements: List<UiElement>, screenWidth: Int, screenHeight: Int): UiElement? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val clickable = elements.filter { it.clickable }
        clickable.firstOrNull { isExact(it.name) }?.let { return it }
        clickable.firstOrNull { isBackResource(it.resourceId) }?.let { return it }
        clickable.firstOrNull { isBackLike(it.name) }?.let { return it }
        return clickable.firstOrNull { it.name.isBlank() && isTopLeftIcon(it, screenWidth, screenHeight) }
    }

    private fun isExact(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && n in EXACT
    }

    private fun isBackLike(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && CONTAINS.any { n.contains(it) }
    }

    private fun isBackResource(id: String?): Boolean {
        if (id == null) return false
        return id.substringAfterLast('/').lowercase() in RESOURCE_SEGMENTS
    }

    private fun isTopLeftIcon(e: UiElement, screenWidth: Int, screenHeight: Int): Boolean =
        e.role in ICON_ROLES &&
            e.centerX <= screenWidth * TOP_LEFT_X &&
            e.centerY <= screenHeight * TOP_LEFT_Y

    private const val TOP_LEFT_X = 0.2
    private const val TOP_LEFT_Y = 0.15
}
