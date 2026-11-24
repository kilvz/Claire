package com.wangzan101.mika.capsule

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [Density] construction used to lock in the zoom: card scaling is done by swapping LocalDensity, so dp and sp must scale by the same ratio;
 * which component takes effect and by how much is settled by the measurements here (Compose's conversion formula is not publicly guaranteed).
 */
class CapsuleCardScaleDensityTest {
    private val base = Density(2.75f, 1.3f)

    private fun dpRatio(zoomed: Density): Float = with(zoomed) { 100.dp.toPx() } / with(base) { 100.dp.toPx() }
    private fun spRatio(zoomed: Density): Float = with(zoomed) { 100.sp.toPx() } / with(base) { 100.sp.toPx() }

    @Test fun zoomScalesDpAndSpByTheSameFactor() {
        val zoomed = base.zoomed(1.3f)
        assertEquals(1.3f, dpRatio(zoomed), 0.001f)
        assertEquals(dpRatio(zoomed), spRatio(zoomed), 0.001f)
    }
}
