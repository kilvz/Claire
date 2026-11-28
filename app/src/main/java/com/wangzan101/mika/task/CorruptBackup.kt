package com.wangzan101.mika.task

import java.io.File

/**
 * When the ledger cannot be read, copy the original to `.corrupt-<timestamp>` as evidence; the original is then rewritten as usual.
 * Every corruption keeps its own copy instead of overwriting a fixed name: a second corruption never covers the previous evidence. Repeats in the same millisecond get a sequence number.
 */
internal fun File.keepCorruptCopy(now: Long = System.currentTimeMillis()): File {
    var backup = File(parentFile, "$name.corrupt-$now")
    var n = 1
    while (backup.exists()) backup = File(parentFile, "$name.corrupt-$now-${n++}")
    copyTo(backup)
    return backup
}

/** Evidence files left by [keepCorruptCopy], newest last. */
internal fun File.corruptCopies(): List<File> =
    parentFile?.listFiles { f -> f.name.startsWith("$name.corrupt-") }.orEmpty().sortedBy { it.name }
