package com.wangzan101.mika.teach

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.learn.teach.TeachPhase
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bar pinned to the top of the screen while recording and while paused. It uses an accessibility overlay, the same kind of window as the floating capsule, and requests neither notification permission nor "display over other apps".
 * Returns false if it cannot be added. It belongs to Claire's own UI, so it is dropped from the trace.
 */
class TeachChip(
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onEnd: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val phase = MutableStateFlow(TeachPhase.Paused)
    private var window: OverlayWindow? = null

    fun visible(): Boolean = window?.isOpen == true

    /** Must be called on the main thread. When already shown it only swaps the text. */
    fun show(target: TeachPhase): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (target != TeachPhase.Recording && target != TeachPhase.Paused) {
            hideOnMain()
            return false
        }
        phase.value = target
        if (window?.isOpen == true) return true
        val service = ScreenAccessibility.instance.value ?: return false
        val top = service.overlays().area().insetTop + (8 * service.resources.displayMetrics.density).toInt()
        val opened = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = top)) {
            TeachChipBar(phase, onPause, onResume, onEnd)
        } ?: return false
        window = opened
        return opened.isOpen
    }

    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) hideOnMain() else main.post { hideOnMain() }
    }

    private fun hideOnMain() {
        window?.close()
        window = null
    }
}

@Composable
private fun TeachChipBar(
    phase: MutableStateFlow<TeachPhase>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    val current by phase.collectAsState()
    val recording = current == TeachPhase.Recording
    MikaTheme {
        val c = MikaTheme.colors
        Row(
            Modifier.testTag("teach-chip").clip(RoundedCornerShape(MikaRadius.Bubble)).background(c.ink).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (recording) R.string.teach_learning else R.string.teach_paused),
                style = MikaType.Body,
                color = c.bg,
            )
            ChipAction(stringResource(if (recording) R.string.teach_pause else R.string.teach_resume)) {
                if (recording) onPause() else onResume()
            }
            ChipAction(stringResource(R.string.teach_end), onEnd)
        }
    }
}

@Composable
private fun ChipAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        style = MikaType.Body,
        color = MikaTheme.colors.bg,
    )
}
