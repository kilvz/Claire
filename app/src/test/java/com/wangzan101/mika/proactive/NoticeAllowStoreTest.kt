package com.wangzan101.mika.proactive

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeAllowStoreTest {
    private fun tempFile() = File(Files.createTempDirectory("allow").toFile(), "proactive-allow.json")

    @Test fun emptyByDefault() = assertEquals(emptySet<String>(), NoticeAllowStore(tempFile()).current())

    @Test fun roundTrip() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        store.setAllowed("com.tencent.mm", true)
        store.setAllowed("com.example", true)
        store.setAllowed("com.example", false)
        assertEquals(setOf("com.tencent.mm"), store.state.value)
        assertEquals(setOf("com.tencent.mm"), NoticeAllowStore(f).current())
        assertEquals(false, File(f.parentFile, f.name + ".tmp").exists())
    }

    // An unreadable allow-list counts as empty (better to read too little), but it must be able to say "this was unreadable, not that the user selected nothing": nothing else may delete based on it
    @Test fun unreadableFileReadsAsEmptyAndSaysSo() {
        val f = tempFile()
        f.parentFile.mkdirs()
        assertEquals(true, NoticeAllowStore(f).readable)
        for (bad in listOf("{not json", "", """{"version":2,"allowed":["a"]}""")) {
            f.writeText(bad)
            val store = NoticeAllowStore(f)
            assertEquals(bad, emptySet<String>(), store.current())
            assertEquals(bad, false, store.readable)
        }
        // Genuinely empty: readable
        f.writeText("""{"version":1,"allowed":[]}""")
        assertEquals(true, NoticeAllowStore(f).readable)
        // The user changed the list: it writes as usual and reads back afterwards
        f.writeText("{not json")
        val store = NoticeAllowStore(f)
        store.setAllowed("com.example", true)
        assertEquals(true, store.readable)
        assertEquals(setOf("com.example"), NoticeAllowStore(f).current())
        assertEquals(true, NoticeAllowStore(f).readable)
    }

    // ---- The list cannot be written (storage full and the like): lean towards reading less, and say honestly that it was not saved ----

    private class Revokes(override var allowRevoked: Set<String> = emptySet()) : AllowRevokes

    /** Force writes to fail: put a directory where the temp file goes (no permission changes and no need to actually fill the disk). */
    private fun breakWrites(f: File) = File(f.parentFile, f.name + ".tmp").mkdir()
    private fun mendWrites(f: File) = File(f.parentFile, f.name + ".tmp").delete()

    @Test fun failedRemovalStillStopsReadingAndIsReported() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        // Nothing is thrown; it reports that it was not saved; that App is no longer read within this run, and other Apps are unaffected
        assertFalse(store.setAllowed("com.chat", false))
        assertEquals(setOf("com.other"), store.current())
        // The file still holds the old entry: relying on memory alone, a restart would read it again
        assertEquals(setOf("com.chat", "com.other"), NoticeAllowStore(f).current())
        // Selecting it again (the list looks unchanged) also rewrites; once writes succeed it gets saved
        mendWrites(f)
        assertTrue(store.setAllowed("com.chat", false))
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
    }

    @Test fun failedGrantIsNotApplied() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        assertFalse(store.setAllowed("com.chat", true))
        assertEquals(setOf("com.other"), store.current())
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
    }

    // Setting an App that is already on the list again (only its tier changes): the list is unchanged, no file write, and a failing write does not matter
    @Test fun unchangedListNeedsNoWrite() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.none", false))
        // After one "removal" failed to save, changing another App's tier still succeeds (it was already on the list, and in the file too)
        assertFalse(store.dirty)
        assertFalse(store.setAllowed("com.other", false))
        assertTrue(store.dirty)
        assertTrue(store.setAllowed("com.chat", true))
        assertEquals(setOf("com.chat"), store.current())
        // That one "removal" still has not been written: it still reports so (the page keeps warning accordingly), and only stops once written
        assertTrue(store.dirty)
        mendWrites(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertFalse(store.dirty)
        assertEquals(setOf("com.chat"), NoticeAllowStore(f).current())
    }

    // A "don't read" that failed to save is recorded in preferences: after a restart it takes effect first (the file still holds it), and once written it is no longer recorded
    @Test fun unsavedRemovalSurvivesARestart() {
        val f = tempFile()
        val revokes = Revokes()
        val store = NoticeAllowStore(f)
        assertTrue(AllowEdits.set(store, revokes, "com.chat", true))
        assertTrue(AllowEdits.set(store, revokes, "com.other", true))
        assertTrue(revokes.allowRevoked.isEmpty())
        breakWrites(f)
        assertFalse(AllowEdits.set(store, revokes, "com.chat", false))
        assertEquals(setOf("com.chat"), revokes.allowRevoked)

        // Restart, writes still fail: it is in the file but not in the in-memory list; it is still recorded
        val second = NoticeAllowStore(f)
        assertEquals(setOf("com.chat", "com.other"), second.current())
        AllowEdits.replay(second, revokes)
        assertEquals(setOf("com.other"), second.current())
        assertEquals(setOf("com.chat"), revokes.allowRevoked)
        assertTrue(second.dirty)

        // Restart again, writes now succeed: it is written into the file and no longer recorded
        mendWrites(f)
        val third = NoticeAllowStore(f)
        AllowEdits.replay(third, revokes)
        assertEquals(setOf("com.other"), third.current())
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
        assertTrue(revokes.allowRevoked.isEmpty())
    }

    // After a "don't read" failed to save, the user selects it again: it is read only once saved, and it is no longer recorded for removal
    @Test fun grantingAgainClearsTheUnsavedRemoval() {
        val f = tempFile()
        val revokes = Revokes()
        val store = NoticeAllowStore(f)
        AllowEdits.set(store, revokes, "com.chat", true)
        breakWrites(f)
        assertFalse(AllowEdits.set(store, revokes, "com.chat", false))
        // Writes still fail: it cannot be added back, so it is still unread and still recorded
        assertFalse(AllowEdits.set(store, revokes, "com.chat", true))
        assertEquals(emptySet<String>(), store.current())
        assertEquals(setOf("com.chat"), revokes.allowRevoked)
        mendWrites(f)
        assertTrue(AllowEdits.set(store, revokes, "com.chat", true))
        assertEquals(setOf("com.chat"), store.current())
        assertTrue(revokes.allowRevoked.isEmpty())
        val next = NoticeAllowStore(f)
        AllowEdits.replay(next, revokes)
        assertEquals(setOf("com.chat"), next.current())
    }
}
