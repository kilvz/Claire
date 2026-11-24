package com.wangzan101.mika.settings

import com.wangzan101.mika.core.docs.ReadmeSections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The README's provider preset section is parsed line by line from the source; this compares it against the real objects and fails when the source syntax changes or the parsing falls behind. */
class ReadmePresetsTest {
    @Test fun sourceReadingMatchesProviderPresets() {
        val source = File("../${ReadmeSections.PRESETS_SOURCE}").readText()
        assertEquals(
            ProviderPresets.all.map { ReadmeSections.Preset(it.label, it.kind.name, it.baseUrl) },
            ReadmeSections.presets(source),
        )
    }
}
