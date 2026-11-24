package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.AgentTool

/** General tools (not tied to any screen): wait, look back, ask the user, request confirmation, hand over to the user, handle obstacles, end when not done, search and read knowledge and skills, save a skill at the user's request, memory, and reading records of earlier turns of this conversation. In the order sent to the model. */
internal object GeneralGroup {
    val tools: List<AgentTool> = listOf(
        Wait, ViewPastScreens, ViewUserScreen, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
        KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, MemoryEdit, ProfileEdit, HistoryRead, NoticesRead, WebSearchTool, ScheduleTask, SpawnAgent,
        TaskMemorySearch, TaskMemoryTimeline, TaskMemoryStepRead,
        com.wangzan101.mika.core.self.SettingsList, com.wangzan101.mika.core.self.SettingsSet,
    ) + com.wangzan101.mika.core.self.SelfGroup.tools
}
