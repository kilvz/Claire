package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.IOException

/**
 * The notification journal (the raw notifications used by the daily notification digest, see
 * [com.wangzan101.mika.core.proactive.NoticeJournal]) is encrypted as a whole into `proactive/journal.bin` (AES-GCM, using
 * `KeystoreKeyCipher("mika-proactive-journal")` in production), with its own key separate from the topic pool and contacts.
 * It holds notification titles and bodies, kept for at most two days. It writes a temp file and then renames atomically.
 *
 * One difference from the topic pool: a file that is genuinely corrupt is **deleted outright**, not renamed aside — this is text sent by other
 * people, and no invisible, undeletable copy is left behind. When it merely cannot be read right now (keystore busy, key retrieval failed) it
 * throws as usual and the file is left alone (what counts as corrupt is in [StoreCodec]); if it stays unreadable and the file has not been
 * written for two days, it is deleted.
 */
class NoticeJournalFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : NoticeJournalStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** Load it; null when there is no file, or the file was corrupt (and has been deleted). */
    @Synchronized
    override fun load(): NoticeJournalData? {
        if (!file.exists()) return null
        val data = try {
            StoreCodec.read(file.readBytes(), cipher, NoticeJournalData.serializer(), VERSION)
        } catch (e: Exception) {
            // Cannot be read right now: leave the file alone and throw as usual. But raw notifications should only be kept for two days — if
            // this file has not been written for two days, everything in it is expired, and an unreadable file must not just sit there: delete it
            if (clock() - file.lastModified() > NoticeJournal.KEEP_MS) wipe()
            throw e
        }
        if (data == null) wipe()
        return data
    }

    @Synchronized
    override fun save(data: NoticeJournalData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(NoticeJournalData.serializer(), data, VERSION))
        AtomicFiles.write(file, bytes, durable = true, temp = tmp)
    }

    /** Delete everything (the file and the temp file); also works when it cannot be read. */
    @Synchronized
    override fun wipe() {
        for (f in listOf(file, tmp)) if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    companion object {
        /** Which version of the on-disk format this is; anything newer is left untouched (see [StoreCodec]). Bump it when the format changes such that old code would fail or lose data reading it. */
        const val VERSION = 1
        const val FILE = "journal.bin"
        const val KEY_ALIAS = "mika-proactive-journal"
    }
}
