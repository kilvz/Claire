package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.ModelChoice

/**
 * A conversation's own run settings, stored in `<id>.settings.json` in the conversation directory.
 * [main] null means follow the global main model; conversation settings only affect the next task.
 */
data class ConversationSettings(
    val main: ModelChoice? = null,
)
