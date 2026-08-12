package com.example.offnav.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Polygon
import java.io.File

class RegionOverviewTest {

    @Test
    fun outlineLoaderPointsAtTheBundledAsset() {
        val assetsDir = sequenceOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        ).first { it.isDirectory }

        val outline = File(assetsDir, RegionOutline.ASSET)
        assertTrue("Missing ${RegionOutline.ASSET}", outline.isFile)
        assertTrue("Texas outline is unexpectedly small", outline.length() > 10_000L)
    }

    @Test
    fun overviewContainsOneClosedRectanglePerBoundedRegion() {
        val regions = listOf(
            region("austin", RegionBounds(30.0, 31.0, -98.0, -97.0), active = true),
            region("dfw", RegionBounds(32.0, 33.0, -97.5, -96.5), active = true),
            region("legacy", null),
        )

        val features = FeatureCollection.fromJson(RegionOverview.toGeoJson(regions)).features().orEmpty()

        assertEquals(2, features.size)
        assertEquals(setOf("austin", "dfw"), features.map { it.getStringProperty("installId") }.toSet())
        features.forEach { feature ->
            val ring = (feature.geometry() as Polygon).coordinates().single()
            assertEquals(5, ring.size)
            assertEquals(ring.first(), ring.last())
        }
    }

    @Test
    fun pendingSelectionStatesReflectLoadsAndUnloads() {
        val activeButNotSelected = region(
            "austin",
            RegionBounds(30.0, 31.0, -98.0, -97.0),
            active = true,
        )
        val pending = region(
            "dfw",
            RegionBounds(32.0, 33.0, -97.5, -96.5),
            selectedForNextLaunch = true,
        )

        assertTrue(activeButNotSelected.isPendingRemoval)
        assertFalse(activeButNotSelected.canDelete)
        assertTrue(pending.isPendingActivation)
        assertFalse(pending.canDelete)
    }

    @Test
    fun overviewOmitsInactiveAndDuplicateLogicalRegions() {
        val regions = listOf(
            region("austin-old", RegionBounds(30.0, 31.0, -98.0, -97.0), active = true, regionId = "austin"),
            region("austin-new", RegionBounds(29.0, 31.0, -99.0, -97.0), active = true, regionId = "austin"),
            region("houston", RegionBounds(29.0, 30.0, -96.0, -95.0)),
        )

        val features = FeatureCollection.fromJson(RegionOverview.toGeoJson(regions)).features().orEmpty()

        assertEquals(1, features.size)
        assertEquals("austin-old", features.single().getStringProperty("installId"))
    }

    private fun region(
        id: String,
        bounds: RegionBounds?,
        active: Boolean = false,
        selectedForNextLaunch: Boolean = false,
        regionId: String = id,
    ) = RegionInfo(
        installId = id,
        regionId = regionId,
        displayName = id,
        version = "1",
        bounds = bounds,
        installedBytes = null,
        isActive = active,
        isSelectedForNextLaunch = selectedForNextLaunch,
    )
}
