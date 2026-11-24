package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SherpaOnnxTest {
    @Test fun `the bundled English model keeps its zipformer type`() {
        assertEquals("zipformer", SherpaOnnx.bundledFiles().modelType)
    }

    @Test fun `downloaded models declare the zipformer2 type they were exported as`() {
        // The multilingual download is a zipformer2 export. Loading it with the v1 type makes the
        // native library read state tensors that do not exist; the process dies, which looks like a crash.
        assertEquals("zipformer2", SherpaOnnx.downloadedFiles(File("/tmp/sherpa-model")).modelType)
    }
}
