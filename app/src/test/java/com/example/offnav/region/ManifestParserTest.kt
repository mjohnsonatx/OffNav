package com.example.offnav.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestParserTest {

    @Test
    fun validManifestProducesCompleteRegionContract() {
        val manifest = ManifestParser.parse(validManifest().toByteArray(Charsets.US_ASCII))

        assertEquals("austin", manifest.regionId)
        assertEquals("Austin", manifest.displayName)
        assertEquals(2, manifest.searchSchema)
        assertEquals(60L, manifest.declaredPayloadBytes)
        assertEquals(30.05, manifest.bounds.minLatitude, 0.0)
        assertEquals(-97.53, manifest.bounds.maxLongitude, 0.0)
    }

    @Test
    fun unknownManifestKeyIsRejected() {
        val error = assertThrows(RegionImportException::class.java) {
            ManifestParser.parse((validManifest() + "unexpected=value\n").toByteArray())
        }

        assertEquals("Unknown manifest key: \"unexpected\"", error.userMessage)
    }

    @Test
    fun duplicateManifestKeyIsRejected() {
        val error = assertThrows(RegionImportException::class.java) {
            ManifestParser.parse((validManifest() + "id=other\n").toByteArray())
        }

        assertEquals("Duplicate manifest key: \"id\"", error.userMessage)
    }

    @Test
    fun invertedBoundsAreRejected() {
        val raw = validManifest().replace("maxLatitude=30.52", "maxLatitude=30.01")

        val error = assertThrows(RegionImportException::class.java) {
            ManifestParser.parse(raw.toByteArray())
        }

        assertEquals(
            "\"minLatitude\" must be less than \"maxLatitude\"",
            error.userMessage,
        )
    }

    @Test
    fun nonCanonicalDigestIsRejected() {
        val raw = validManifest().replace("a".repeat(64), "A".repeat(64))

        val error = assertThrows(RegionImportException::class.java) {
            ManifestParser.parse(raw.toByteArray())
        }

        assertEquals(
            "\"tiles.sha256\" must be 64 lowercase hex characters",
            error.userMessage,
        )
    }

    @Test
    fun aggregatePayloadAboveBundleLimitIsRejected() {
        val raw = validManifest()
            .replace("tiles.bytes=10", "tiles.bytes=${BundleSpec.MAX_BUNDLE_BYTES}")

        val error = assertThrows(RegionImportException::class.java) {
            ManifestParser.parse(raw.toByteArray())
        }

        assertEquals("Bundle declares more than 4 GB of content", error.userMessage)
    }

    @Test
    fun byteBudgetAllowsExactLimitAndRejectsNextByte() {
        val budget = ByteBudget(10)
        budget.consume(4)
        budget.consume(6)
        assertEquals(10L, budget.used)

        val error = assertThrows(RegionImportException::class.java) { budget.consume(1) }
        assertEquals("Bundle exceeds the 0 GB limit", error.userMessage)
    }

    private fun validManifest() = """
        format=1
        id=austin
        displayName=Austin
        version=2026-08-20
        searchSchema=2
        minLatitude=30.05
        maxLatitude=30.52
        minLongitude=-98.05
        maxLongitude=-97.53
        tiles.bytes=10
        tiles.sha256=${"a".repeat(64)}
        routing.bytes=20
        routing.sha256=${"b".repeat(64)}
        search.bytes=30
        search.sha256=${"c".repeat(64)}
    """.trimIndent() + "\n"
}
