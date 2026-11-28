package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.IOException

/**
 * The sentences accumulated by the daily message digest (see [com.wangzan101.mika.core.proactive.DigestPool]) are
 * encrypted in their entirety into `proactive/digest.bin`
 * (AES-GCM, production uses `KeystoreKeyCipher("mika-proactive-digest")`). The sentences are written by the model based
 * on notifications and describe what other people sent:
 * as with the notification log, a genuinely corrupt file is simply deleted, not renamed aside; if it cannot be read
 * right now (keystore busy, key retrieval error), throw as usual and leave the file alone —
 * see [StoreCodec] for what counts as corrupt.
 */
class DigestFile(dir: File, private val cipher: KeyCipher) : DigestStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    @Synchronized
    override fun load(): DigestData {
        if (!file.exists()) return DigestData()
        val bytes = file.readBytes() // Failure to read is an IO fault, so throw as usual
        return StoreCodec.read(bytes, cipher, DigestData.serializer(), VERSION) ?: run {
            wipe()
            DigestData()
        }
    }

    @Synchronized
    override fun save(data: DigestData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(DigestData.serializer(), data, VERSION))
        AtomicFiles.write(file, bytes, durable = true, temp = tmp)
    }

    @Synchronized
    override fun wipe() {
        for (f in listOf(file, tmp)) if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    companion object {
        /** Which version of the on-disk format this is; if a newer one is read, leave it alone (see [StoreCodec]). Bump this when the format changes such that old code would fail or lose data when reading it. */
        const val VERSION = 1
        const val FILE = "digest.bin"
        const val KEY_ALIAS = "mika-proactive-digest"
    }
}
