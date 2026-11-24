package com.wangzan101.mika.core.cap.audio

import com.wangzan101.mika.core.tool.AgentTool

/** The audio group: synthesize a passage into a file, save workspace audio to the music library, and let Claire speak a line proactively. Saving/speaking are offered only when the user allows them. */
internal object AudioGroup {
    val tools: List<AgentTool> = listOf(AudioGenerate, AudioSave(ask = true), Speak)

    fun forTask(saveEnabled: Boolean, askBeforeSave: Boolean, speakTool: Boolean): List<AgentTool> = buildList {
        add(AudioGenerate)
        if (speakTool) add(Speak)
        if (saveEnabled) add(AudioSave(askBeforeSave))
    }
}
