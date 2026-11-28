package com.wangzan101.mika.telegram

import com.wangzan101.mika.core.run.Delivery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class TelegramImageFilesTest {
    private val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun image(uri: String = "content://media/external/images/media/42", size: Long = bytes.size.toLong(), sha: String? = null) =
        Delivery.Image("result:1:image:0", "thread", uri, "selfie.jpg", size, sha)

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun readsAVerifiedExport() {
        assertArrayEquals(bytes, readTelegramExport(image()) { ByteArrayInputStream(bytes) })
    }

    @Test fun acceptsAMatchingHash() {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, readTelegramExport(image(sha = sha)) { ByteArrayInputStream(bytes) })
    }

    @Test fun rejectsAnExportWhoseSizeChanged() {
        expectFailure { readTelegramExport(image()) { ByteArrayInputStream(bytes + 4) } }
    }

    @Test fun rejectsAnExportThatIsShorterThanRecorded() {
        expectFailure { readTelegramExport(image(size = bytes.size + 10L)) { ByteArrayInputStream(bytes) } }
    }

    @Test fun rejectsAnExportWhoseHashChanged() {
        expectFailure { readTelegramExport(image(sha = "00")) { ByteArrayInputStream(bytes) } }
    }

    @Test fun rejectsANonMediaUri() {
        expectFailure { readTelegramExport(image(uri = "file:///sdcard/DCIM/Camera/selfie.jpg")) { ByteArrayInputStream(bytes) } }
    }

    @Test fun rejectsAnExportOverTheUploadLimit() {
        expectFailure { readTelegramExport(image(size = MAX_BYTES + 1)) { ByteArrayInputStream(bytes) } }
    }

    @Test fun rejectsAMissingExport() {
        expectFailure { readTelegramExport(image()) { null } }
    }
}
