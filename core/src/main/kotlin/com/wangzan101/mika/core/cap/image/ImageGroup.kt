package com.wangzan101.mika.core.cap.image

import com.wangzan101.mika.core.tool.AgentTool

/** The image group: generate/edit an image at a cost, and save workspace images to the album. The save tool is offered only when the user allows it. */
internal object ImageGroup {
    /** All declarations (unaware of this task's switches); used by knownTools and by run records to recognize names. */
    val tools: List<AgentTool> = listOf(ImageGenerate, ImageEdit, ImageSave(ask = true))

    /** The tools actually offered for this task: when saving is off, even the declaration is not sent. */
    fun forTask(saveEnabled: Boolean, askBeforeSave: Boolean): List<AgentTool> = buildList {
        add(ImageGenerate)
        add(ImageEdit)
        if (saveEnabled) add(ImageSave(askBeforeSave))
    }
}
