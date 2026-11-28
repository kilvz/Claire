package com.wangzan101.mika.core.workspace

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "Write a temp file first, then swap it into place" for small files, plus "keep the bad file around" when one cannot be read.
 *
 * Call sites originally had different disk-write strategies; this only consolidates the mechanics, and **each call site passes its strategy exactly as before**:
 * - [durable]: fsync before the rename (a power cut cannot leave a renamed but empty file). Without it, nothing is flushed to disk.
 * - [Move]: how the temp file is swapped onto the target. A failed `renameTo` just returns false; a failed `Files.move` throws.
 * - [Fallback]: what to do when the swap fails (report to the caller / overwrite the target directly / delete the target and rename once more).
 *
 * Parent directories are not created: call sites that need them create them (those that did not still fail when the parent is missing).
 */
object AtomicFiles {
    enum class Move {
        /** `File.renameTo`; returns false on failure. */
        Rename,
        /** `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`; throws on failure. */
        Atomic,
        /** Tries `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` first, then `renameTo` once more if it throws. */
        AtomicThenRename,
    }

    enum class Fallback {
        /** No recovery: returns false and leaves the temp file for the caller. */
        None,
        /** Overwrites the target directly (falling back to a non-atomic write), then deletes the temp file. */
        WriteDirect,
        /** Deletes the target and renames once more; that result is the return value. */
        DeleteAndRetry,
    }

    /** Default temp file: `<name>.tmp` next to the target. */
    fun tempFor(target: File): File = File(target.parentFile, target.name + ".tmp")

    /**
     * Writes [bytes] to [temp], then swaps it onto [target] using [move]; when the swap fails, handles it with [fallback].
     * Returns whether the content is now on [target]. Writing the temp file and a failing [Move.Atomic] swap still throw.
     */
    fun write(
        target: File,
        bytes: ByteArray,
        durable: Boolean,
        move: Move = Move.Atomic,
        fallback: Fallback = Fallback.None,
        temp: File = tempFor(target),
    ): Boolean {
        if (durable) FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        else temp.writeBytes(bytes)
        if (replace(temp, target, move)) return true
        return when (fallback) {
            Fallback.None -> false
            Fallback.WriteDirect -> { target.writeBytes(bytes); temp.delete(); true }
            Fallback.DeleteAndRetry -> { target.delete(); temp.renameTo(target) }
        }
    }

    /** Writes text as UTF-8; everything else is the same as [write]. */
    fun write(
        target: File,
        text: String,
        durable: Boolean,
        move: Move = Move.Atomic,
        fallback: Fallback = Fallback.None,
        temp: File = tempFor(target),
    ): Boolean = write(target, text.toByteArray(Charsets.UTF_8), durable, move, fallback, temp)

    private fun replace(temp: File, target: File, move: Move): Boolean = when (move) {
        Move.Rename -> temp.renameTo(target)
        Move.Atomic -> {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }
        Move.AtomicThenRename -> try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            temp.renameTo(target)
        }
    }

    /** How a bad file is kept around. Names are always `<original name>.<tag>-<time>`; with [dedupe], an existing name gets `-1`, `-2`, ... */
    enum class Quarantine(internal val tag: String) {
        /** Makes a copy called `.corrupt-<millis>` and leaves the original in place (it is then rewritten as usual). Throws if the copy fails. */
        CopyCorrupt("corrupt"),
        /** Renames to `.corrupt-<millis>`. Returns null if the rename fails. */
        RenameCorrupt("corrupt"),
        /** Renames to `.bad-<millis>`. Returns null if the rename fails. */
        RenameBad("bad"),
        /** Moves with `Files.move(ATOMIC_MOVE)` to `.broken-<yyyyMMddHHmmss>` (local time zone). Throws if it cannot move. */
        MoveBroken("broken"),
    }

    /**
     * Keeps [file] around according to [style] and returns the copy that was kept; [Quarantine.RenameCorrupt] and [Quarantine.RenameBad] return null when the rename fails.
     * With [dedupe] false, duplicate names are not checked (an existing name is left to `renameTo` and system behaviour), for call sites that never checked.
     */
    fun quarantine(file: File, style: Quarantine, now: Long = System.currentTimeMillis(), dedupe: Boolean = true): File? {
        val stamp = if (style == Quarantine.MoveBroken) SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now) else now.toString()
        val base = "${file.name}.${style.tag}-$stamp"
        var backup = File(file.parentFile, base)
        var n = 1
        if (dedupe) while (backup.exists()) backup = File(file.parentFile, "$base-${n++}")
        return when (style) {
            Quarantine.CopyCorrupt -> { file.copyTo(backup); backup }
            Quarantine.RenameCorrupt, Quarantine.RenameBad -> backup.takeIf { file.renameTo(it) }
            Quarantine.MoveBroken -> { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE); backup }
        }
    }
}
