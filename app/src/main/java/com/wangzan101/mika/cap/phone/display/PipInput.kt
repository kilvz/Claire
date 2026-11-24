package com.wangzan101.mika.cap.phone.display

/**
 * Input entry point for the virtual display's mini window in the conversation: taps / swipes on the preview image are converted into virtual display logical coordinates and injected.
 * [fx] / [fy] are 0..1 coordinates relative to the preview image (top-left 0,0, bottom-right 1,1); implementations convert them using the virtual display's current logical width and height.
 * Only called when the user has enabled "allow me to operate directly in the mini window" and the mini window is expanded.
 */
interface PipInput {
    /** Tap once at (fx, fy) on the preview image. */
    suspend fun tap(fx: Float, fy: Float)

    /** One finger swipes along [points] (0..1 coordinates), taking about [durationMs] milliseconds from press to lift; at least two points. */
    suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /** Press the back button once on the virtual display. */
    suspend fun back()
}
