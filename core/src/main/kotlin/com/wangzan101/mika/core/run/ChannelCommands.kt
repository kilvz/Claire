package com.wangzan101.mika.core.run

import java.util.Locale

/**
 * Whole-sentence commands the owner sends in a channel. Only a few exact whole-sentence spellings are recognized (leading and trailing whitespace stripped, case-insensitive);
 * ordinary messages containing these words still become new tasks, so a task like "stop playing music" is not mistaken for a command.
 * A bare "cancel" does not count: it is often an answer to one of Mika's options.
 */
object ChannelCommands {
    private val STOP = setOf("停止", "停止任务", "取消任务", "全部停止", "/停止", "stop", "/stop", "/cancel")

    fun isStop(text: String): Boolean = text.trim().lowercase(Locale.ROOT) in STOP
}
