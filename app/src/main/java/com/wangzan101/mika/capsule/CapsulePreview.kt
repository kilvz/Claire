package com.wangzan101.mika.capsule

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** Maximum frames per second for the small preview on the card. */
const val PREVIEW_FPS = 5

/**
 * The frames to push for the card preview: at most one every [intervalMs], keeping only the latest in between; if it is the same as the last pushed frame ([same]), it is not pushed.
 * It is only collected while the card is expanded and cancelled when it collapses; when nobody collects, nothing is pushed.
 */
fun <T : Any> Flow<T>.previewFrames(intervalMs: Long = 1_000L / PREVIEW_FPS, same: (T, T) -> Boolean = { a, b -> a === b }): Flow<T> = flow {
    var last: T? = null
    conflate().collect { f ->
        val prev = last
        if (prev != null && (prev === f || same(prev, f))) return@collect
        last = f
        emit(f)
        delay(intervalMs)
    }
}

/**
 * Do not push when the new frame equals the previous one (the same image, or the same size with identical pixels): the web page image is a new copy every time.
 * On the virtual display side old frames get recycled, so check [Bitmap.isRecycled] first instead of comparing pixels of a recycled image.
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || !a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b)
