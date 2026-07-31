package com.example.offnav.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineRoadLabelAssetsTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).first { it.isDirectory }

    @Test
    fun roadLabelStyleUsesBundledGlyphsAndTransportationNames() {
        val style = File(assetsDir, "style.json").readText()

        assertTrue(style.contains("\"glyphs\": \"asset://glyphs/{fontstack}/{range}.pbf\""))
        assertTrue(style.contains("\"id\": \"road-label\""))
        assertTrue(style.contains("\"source-layer\": \"transportation_name\""))
        assertTrue(style.contains("\"text-field\": [\"get\", \"name:latin\"]"))
        assertTrue(style.contains("\"text-font\": [\"Noto Sans Regular\"]"))

        // Road names are text-only; omitting a sprite prevents an unrelated
        // missing-sprite request from breaking an otherwise offline style.
        assertFalse(style.contains("\"sprite\""))
    }

    @Test
    fun bundledRoadLabelGlyphRangesArePresentAndBinary() {
        listOf("0-255", "256-511", "8192-8447").forEach { range ->
            val glyph = File(assetsDir, "glyphs/Noto Sans Regular/$range.pbf")
            assertTrue("Missing glyph range $range", glyph.isFile)
            assertTrue("Glyph range $range is unexpectedly small", glyph.length() > 10_000L)
            assertFalse("Glyph range $range contains HTML instead of PBF data", glyph.readBytes()[0] == '<'.code.toByte())
        }
    }
}
