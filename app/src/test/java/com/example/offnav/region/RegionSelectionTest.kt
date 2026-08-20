package com.example.offnav.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionSelectionTest {

    @Test
    fun builtInSelectionReportsAustinCoverage() {
        val selection = RegionSelection(listOf(RegionSnapshot.BuiltIn))

        assertEquals("Austin", selection.displayName)
        assertTrue(selection.contains(30.2672, -97.7431))
        assertFalse(selection.contains(32.7767, -96.7970))
    }

    @Test
    fun multipleRegionsUseAggregateDisplayNameAndCoverage() {
        val selection = RegionSelection(
            listOf(RegionSnapshot.BuiltIn, installed("dallas-v1", "dallas", "Dallas", 32.0, 33.0, -97.5, -96.5))
        )

        assertEquals("2 offline regions", selection.displayName)
        assertEquals(setOf("builtin", "dallas-v1"), selection.pointerValues)
        assertTrue(selection.contains(32.7767, -96.7970))
    }

    @Test
    fun selectionRejectsTwoVersionsOfSameLogicalRegion() {
        val first = installed("dallas-v1", "dallas", "Dallas", 32.0, 33.0, -97.5, -96.5)
        val second = installed("dallas-v2", "dallas", "Dallas", 32.0, 33.0, -97.5, -96.5)

        assertThrows(IllegalArgumentException::class.java) {
            RegionSelection(listOf(first, second))
        }
    }

    @Test
    fun selectionRejectsEmptySnapshotList() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionSelection(emptyList())
        }
    }

    private fun installed(
        installId: String,
        regionId: String,
        name: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ) = RegionSnapshot.Installed(
        installId = installId,
        regionId = regionId,
        displayName = name,
        version = "1",
        searchSchema = 2,
        bounds = RegionBounds(minLat, maxLat, minLon, maxLon),
        installedBytes = null,
        dir = File("build/test-regions/$installId"),
    )
}
