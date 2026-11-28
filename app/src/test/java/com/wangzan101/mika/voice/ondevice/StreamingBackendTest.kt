package com.wangzan101.mika.voice.ondevice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the config mapping (plain Kotlin data classes; no native library is loaded). */
class StreamingBackendTest {

    private val catalog = VoicePackCatalog.parse(
        File("src/main/assets/voice-models/catalog.json").readText(),
    ) { _, _ -> true }

    private fun installed(id: String, bundled: Boolean = false) = InstalledVoicePack(
        entry = catalog.entry(id)!!,
        dir = File("/packs/$id/1"),
        bundled = bundled,
    )

    @Test
    fun everyLivePackUsesThisBackendAndItsFamily() {
        val backend = StreamingBackend()
        catalog.packs.filter { it.streaming }.forEach {
            assertEquals(it.id, StreamingBackend.ID, it.backend)
            assertTrue(it.id, backend.supports(it.family))
            assertFalse("a live pack must not also be an offline pack", SherpaOnnxBackend().supports(it.family))
        }
        assertFalse(backend.supports("whisper"))
        assertTrue(backend.supports("zipformer"))
        assertTrue(backend.supports("zipformer2"))
    }

    @Test
    fun bundledPackReadsItsAssetsAndKeepsTheFamilyAsTheModelType() {
        val config = StreamingBackend.config(installed("zipformer-en-20m", bundled = true), EngineOptions(2))
        val model = config.modelConfig
        assertEquals("voice-models/zipformer-en-20m/encoder-epoch-99-avg-1.int8.onnx", model.transducer.encoder)
        assertEquals("voice-models/zipformer-en-20m/decoder-epoch-99-avg-1.onnx", model.transducer.decoder)
        assertEquals("voice-models/zipformer-en-20m/joiner-epoch-99-avg-1.int8.onnx", model.transducer.joiner)
        assertEquals("voice-models/zipformer-en-20m/tokens.txt", model.tokens)
        assertEquals("zipformer", model.modelType)
        assertEquals(2, model.numThreads)
        assertEquals("cpu", model.provider)
        assertTrue(config.enableEndpoint)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals("greedy_search", config.decodingMethod)
    }

    @Test
    fun downloadedPackUsesAbsolutePathsAndTheZipformer2Type() {
        val config = StreamingBackend.config(installed("zipformer-multilingual-int8"), EngineOptions(4))
        val model = config.modelConfig
        assertEquals("/packs/zipformer-multilingual-int8/1/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", model.transducer.encoder)
        assertEquals("/packs/zipformer-multilingual-int8/1/decoder-epoch-75-avg-11-chunk-16-left-128.onnx", model.transducer.decoder)
        assertEquals("/packs/zipformer-multilingual-int8/1/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", model.transducer.joiner)
        assertEquals("/packs/zipformer-multilingual-int8/1/tokens.txt", model.tokens)
        assertEquals("zipformer2", model.modelType)
        assertEquals(4, model.numThreads)
    }
}
