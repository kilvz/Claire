package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The generated sections (built-in knowledge, provider presets) in README.md match their source; if they don't, run `./gradlew updateReadme`. */
class ReadmeSectionsTest {
    private val root = File("..").canonicalFile

    @Test fun readmeMatchesGenerated() {
        val readme = File(root, ReadmeSections.README).readText()
        val expected = ReadmeSections.render(readme, ReadmeSections.sections(root))
        assertEquals("the generated sections in README.md are stale; run ./gradlew updateReadme", expected, readme)
    }

    @Test fun renderReplacesOnlyBetweenMarkers() {
        val text = "before\n${ReadmeSections.start("x")}\nold\n${ReadmeSections.end("x")}\nafter\n"
        assertEquals(
            "before\n${ReadmeSections.start("x")}\nnew\n${ReadmeSections.end("x")}\nafter\n",
            ReadmeSections.render(text, mapOf("x" to "new")),
        )
    }

    @Test fun missingMarkerFails() {
        val e = assertThrows(IllegalStateException::class.java) { ReadmeSections.render("no markers", mapOf("x" to "new")) }
        assertTrue(e.message.orEmpty(), "missing" in e.message.orEmpty())
    }

    @Test fun readsPresetsFromSource() {
        val src = """
            ProviderPreset("Alpha", ProviderConfig.Kind.OpenAiCompat, "https://a.example/v1", caps()),
            ProviderPreset("Beta", ProviderConfig.Kind.Gemini, "https://b.example", caps(vision = true)),
            ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
        """.trimIndent()
        assertEquals(
            listOf(
                ReadmeSections.Preset("Alpha", "OpenAiCompat", "https://a.example/v1"),
                ReadmeSections.Preset("Beta", "Gemini", "https://b.example"),
                ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
            ),
            ReadmeSections.presets(src),
        )
    }

    /** The "custom" entry also mentions Responses among the endpoints it accepts, placed last (same order as the UI), not in the order the presets appear. */
    @Test fun customKindsListResponsesLast() {
        val presets = listOf(
            ReadmeSections.Preset("Alpha", "OpenAiCompat", "https://a.example/v1"),
            ReadmeSections.Preset("Beta", "OpenAiResponses", "https://b.example/v1"),
            ReadmeSections.Preset("Gamma", "Gemini", "https://c.example"),
            ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
        )
        assertTrue(ReadmeSections.providers(presets).endsWith("which takes any OpenAI-compatible, Gemini or OpenAI Responses endpoint."))
    }
}
