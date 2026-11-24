package com.wangzan101.mika.core.screen.vision

import java.util.UUID

/** 0–999 normalized coordinates, in the same coordinate system as tap. */
data class Point(val x: Int, val y: Int)

/**
 * Points found by find_visually: registered by (ref, snapshotId), keeping only the most recent [capacity] (first in, first out),
 * and recording the hash of the screenshot taken when the point was found, which tap uses to tell whether the frame changed. The caller [clear]s them after any write action.
 */
class LocateRegistry(private val capacity: Int = 20) {
    private class Entry(val point: Point, val hash: Long)

    private var next = 1
    private val entries = LinkedHashMap<String, Entry>()

    /** Returns (ref, snapshotId), shaped like ("@v3", "loc_…"). Ids are not reused within a task. */
    fun put(x: Int, y: Int, hash: Long): Pair<String, String> {
        val ref = "@v${next++}"
        val snapshotId = "loc_${UUID.randomUUID()}"
        entries[key(ref, snapshotId)] = Entry(Point(x, y), hash)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return ref to snapshotId
    }

    fun get(ref: String, snapshotId: String): Pair<Point, Long>? = entries[key(ref, snapshotId)]?.let { it.point to it.hash }

    fun clear() = entries.clear()

    /** Whether [snapshotId] belongs to a point registered here that is still retained. */
    fun has(snapshotId: String): Boolean = entries.keys.any { it.startsWith("$snapshotId|") }

    private fun key(ref: String, snapshotId: String) = "$snapshotId|$ref"
}
