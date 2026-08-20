package com.example.offnav.region

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class BundledRegionAssetsTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).first { it.isDirectory }

    @Test
    fun bundledAustinPayloadsArePresentAndMatchThePublishedBuild() {
        verify(
            relativePath = "tiles/region.mbtiles",
            expectedBytes = 39_112_704L,
            expectedSha256 = "c99878c4ca4efbc42ee27331a33770a8f9f46379485c83d5b4fc3822608d9408",
        )
        verify(
            relativePath = "routing/region.ghz",
            expectedBytes = 30_886_030L,
            expectedSha256 = "e8a7d58a7187a908553377472241a31fab6d101678519f41628ddca54ce8da5a",
        )
        verify(
            relativePath = "search/austin_places.db",
            expectedBytes = 102_129_664L,
            expectedSha256 = "29e8f27b2c86dc9d9e245b5eaab7ffb962fa6ef1c7c4d192ffca409468db207d",
        )
    }

    @Test
    fun bundledDatabasesAndRoutingArchiveHaveTheExpectedFormats() {
        val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        listOf("tiles/region.mbtiles", "search/austin_places.db").forEach { relativePath ->
            val file = File(assetsDir, relativePath)
            FileInputStream(file).use { input ->
                assertArrayEquals("Invalid SQLite header for $relativePath", sqliteHeader, input.readNBytes(16))
            }
        }

        ZipFile(File(assetsDir, "routing/region.ghz")).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            assertTrue("Routing archive is missing its compatibility marker", "offnav.graph.version" in entries)
            assertTrue("Routing archive is missing its road graph", "edges" in entries)
            assertTrue("Routing archive is missing its location index", "location_index" in entries)
        }
    }

    private fun verify(relativePath: String, expectedBytes: Long, expectedSha256: String) {
        val file = File(assetsDir, relativePath)
        assertTrue("Missing bundled asset $relativePath", file.isFile)
        assertEquals("Unexpected size for $relativePath", expectedBytes, file.length())

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        assertEquals("Unexpected SHA-256 for $relativePath", expectedSha256, actual)
    }
}
