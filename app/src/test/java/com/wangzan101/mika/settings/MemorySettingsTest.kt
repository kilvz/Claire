package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemorySettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun learnSkillsOnByDefault() {
        assertTrue(MemorySettings(File(tmp.root, "memory.json")).switches.value.learnSkills)
    }

    @Test fun olderSettingsUpgradeOnceThenUserChoiceSticks() {
        val file = File(tmp.root, "memory.json")
        // Stored by an older version: off by default back then
        file.writeText("""{"useMemory":true,"autoRemember":false,"learnSkills":false}""")
        val settings = MemorySettings(file)
        assertTrue(settings.switches.value.learnSkills)
        assertFalse(settings.switches.value.autoRemember)
        settings.update { it.copy(learnSkills = false) }
        assertFalse(MemorySettings(file).switches.value.learnSkills)
    }

    // "Let Claire summarise and remember on its own" is off by default; a missing entry in old files also means off; once turned on it is remembered
    @Test fun inferMemoriesOffByDefaultAndSticks() {
        val file = File(tmp.root, "memory.json")
        assertFalse(MemorySettings(file).switches.value.inferMemories)
        file.writeText("""{"useMemory":true,"autoRemember":true,"learnSkills":true}""")
        val settings = MemorySettings(file)
        assertFalse(settings.switches.value.inferMemories)
        settings.update { it.copy(inferMemories = true) }
        assertTrue(MemorySettings(file).switches.value.inferMemories)
        // Other switches are unaffected
        assertTrue(MemorySettings(file).switches.value.autoRemember)
    }

    @Test fun rememberTasksOnByDefaultAndSticks() {
        val file = File(tmp.root, "memory.json")
        // Old files that never stored rememberTasks: on by default
        file.writeText("""{"useMemory":true,"autoRemember":true,"learnSkills":true}""")
        assertTrue(MemorySettings(file).switches.value.rememberTasks)
        // Once the user turns it off it stays off
        val settings = MemorySettings(file)
        settings.update { it.copy(rememberTasks = false) }
        assertFalse(MemorySettings(file).switches.value.rememberTasks)
    }

    @Test fun taskMemoryRequiresBothSwitchesAndKeepsTheStoredPreference() {
        val file = File(tmp.root, "memory.json")
        file.writeText("""{"useMemory":false}""")
        val settings = MemorySettings(file)
        assertTrue(settings.switches.value.rememberTasks)
        assertFalse(settings.switches.value.taskMemoryEnabled)

        settings.update { it.copy(useMemory = true) }
        assertTrue(settings.switches.value.taskMemoryEnabled)
        settings.update { it.copy(rememberTasks = false) }
        assertFalse(MemorySettings(file).switches.value.taskMemoryEnabled)
    }
}
