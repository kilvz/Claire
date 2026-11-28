package com.wangzan101.mika.task

import java.io.File

/**
 * 账本读不出来时把原文件复制一份 `.corrupt-时间戳` 留作现场,原文件随后照常被重写。
 * 每次损坏各留一份,不用固定名覆盖:再坏一次也不会盖掉上一次的现场。同一毫秒重复时加序号。
 */
internal fun File.keepCorruptCopy(now: Long = System.currentTimeMillis()): File {
    var backup = File(parentFile, "$name.corrupt-$now")
    var n = 1
    while (backup.exists()) backup = File(parentFile, "$name.corrupt-$now-${n++}")
    copyTo(backup)
    return backup
}

/** [keepCorruptCopy] 留下的现场文件,新的在后。 */
internal fun File.corruptCopies(): List<File> =
    parentFile?.listFiles { f -> f.name.startsWith("$name.corrupt-") }.orEmpty().sortedBy { it.name }
