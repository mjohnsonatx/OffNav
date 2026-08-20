package com.example.offnav.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchModelsTest {

    @Test
    fun distanceTextUsesMetersBelowOneKilometer() {
        assertEquals("999 m", result(999.9).distanceText)
    }

    @Test
    fun distanceTextUsesDecimalKilometersForLocalResults() {
        assertEquals("1.5 km", result(1_500.0).distanceText)
    }

    @Test
    fun distanceTextRoundsLongDistancesToWholeKilometers() {
        assertEquals("12 km", result(12_400.0).distanceText)
    }

    @Test
    fun placeCategoryMatchingIsCaseInsensitiveAndClosedToKnownClasses() {
        assertTrue(PlaceCategory.RESTAURANTS.matches("CAFE"))
        assertTrue(PlaceCategory.HOSPITALS.matches("pharmacy"))
        assertFalse(PlaceCategory.PARKS.matches("restaurant"))
    }

    private fun result(distanceMeters: Double) = PlaceSearchResult(
        name = "Place",
        subtitle = "Austin",
        category = "Test",
        osmClass = "test",
        latitude = 30.2672,
        longitude = -97.7431,
        distanceMeters = distanceMeters,
    )
}
