package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Test

class TaskRecoveryTest {
    private class FakeAudio(val modes: MutableMap<String, String>) : AudioPort {
        override fun mode(packageName: String) = modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean { modes[packageName] = "ignore"; return true }
        override fun setMode(packageName: String, mode: String): Boolean { modes[packageName] = mode; return true }
    }

    // Review Focus 5: two tasks each muted their own app and then the process was killed; both records must be restored on the next start
    @Test fun restoresAllMutedPackagesOfAllRuns() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow", "clock.app" to "default"))
        val a = MemoryMarker()
        val b = MemoryMarker()
        assertTrue(AudioMute(audio, a).mute("settings.app"))
        assertTrue(AudioMute(audio, b).mute("clock.app"))

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a, "run-b" to b), audio, live = emptySet()))
        assertEquals("allow", audio.modes["settings.app"])
        assertEquals("default", audio.modes["clock.app"])
        assertNull(a.saved)
        assertNull(b.saved)
    }

    // Recovery when the second task starts must not touch the mute state of the first task, which is still running
    @Test fun leavesLiveRunsAlone() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow"))
        val a = MemoryMarker()
        AudioMute(audio, a).mute("settings.app")

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a), audio, live = setOf("run-a")))
        assertEquals("ignore", audio.modes["settings.app"])
        assertFalse(a.saved.isNullOrEmpty())
    }

    @Test fun restoresDuplicateRecordsWithOneWritePerPackageAndUser() {
        val attempts = mutableListOf<Pair<String, String>>()
        val audio = object : AudioPort {
            override fun mode(packageName: String) = error("unexpected read")
            override fun mute(packageName: String) = error("unexpected mute")
            override fun setMode(packageName: String, mode: String): Boolean {
                attempts += packageName to mode
                return packageName != "clock.app@10"
            }
        }
        val modes = mapOf("clock.app" to "default", "clock.app@10" to "deny")
        val records = (1..500).associate { "run-$it" to MemoryMarker().apply { save(modes) } }

        repeat(2) {
            attempts.clear()
            assertFalse(TaskRecovery.restoreRuns(records, audio, live = emptySet()))
            val expected = if (it == 0) modes.toList() else listOf("clock.app@10" to "deny")
            assertEquals(expected, attempts)
            assertTrue(records.values.all { marker -> marker.saved == mapOf("clock.app@10" to "deny") })
        }
    }

    @Test fun restoresDifferentModesInOrderForTheSamePackage() {
        val audio = FakeAudio(mutableMapOf())
        val records = listOf("allow", "deny", "allow").mapIndexed { index, mode ->
            "run-$index" to MemoryMarker().apply { save(mapOf("clock.app" to mode)) }
        }.toMap()

        assertTrue(TaskRecovery.restoreRuns(records, audio, live = emptySet()))
        assertEquals("allow", audio.modes["clock.app"])
        assertTrue(records.values.all { it.saved == null })
    }

    private class XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    // The clipboard half of Focus 5: Claire was killed while pasting through the clipboard, so the next start puts the user's original text back; the backup is encrypted and deleted once used
    @Test fun restoresClipboardFromEncryptedBackupAfterKill() {
        var stored: String? = null
        val backup = ClipBackup({ stored }, { stored = it }, XorCipher())
        backup.save("用户原来的文字", "Claire 要粘贴的")
        assertFalse(stored!!.contains("用户原来的文字"))

        val restored = mutableListOf<Pair<String, String>>()
        assertTrue(TaskRecovery.restoreClip(ClipBackup({ stored }, { stored = it }, XorCipher())) { o, u ->
            restored += o to u
            true
        })
        assertEquals(listOf("用户原来的文字" to "Claire 要粘贴的"), restored)
        assertNull(stored)
        // With no backup saved, nothing is done
        assertTrue(TaskRecovery.restoreClip(backup) { _, _ -> error("nothing to restore") })
    }

    @Test fun unreadableClipboardKeepsTheEncryptedBackupForAnotherAttempt() {
        var stored: String? = null
        val backup = ClipBackup({ stored }, { stored = it }, XorCipher())
        backup.save("用户原来的文字", "Claire 要粘贴的")
        assertFalse(TaskRecovery.restoreClip(backup) { _, _ -> false })
        assertTrue(stored != null)
        assertTrue(TaskRecovery.restoreClip(backup) { _, _ -> true })
        assertNull(stored)
    }
}
