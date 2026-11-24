package com.wangzan101.mika.core.screen.visual

/** Grid and thresholds for the coarse comparison of frames before and after an action (corroborating evidence for judging the action's effect). */
object FrameTuning {
    /** Column and row counts of the frame grid. */
    const val GRID_COLS = 24
    const val GRID_ROWS = 48
    /** A cell counts as changed when its luma difference exceeds this. */
    const val CELL_DELTA = 16
    /** At least this fraction of all cells (about 1%) must change for it to count as a visible change. */
    const val FRAME_VISIBLE = 0.01
    /** At least this fraction of the target region's cells must change for it to count as a visible change within the region. */
    const val REGION_VISIBLE = 0.15
    /** This fraction of all cells changing counts as a large-area change. */
    const val FRAME_LARGE = 0.25
}
