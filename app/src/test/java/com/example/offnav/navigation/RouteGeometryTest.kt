package com.example.offnav.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class RouteGeometryTest {

    @Test
    fun routeRequiresAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteGeometry(listOf(LatLng(30.0, -97.0)))
        }
    }

    @Test
    fun totalDistanceUsesStreetScaleCoordinates() {
        val geometry = RouteGeometry(
            listOf(
                LatLng(30.0, -97.0),
                LatLng(30.001, -97.0),
                LatLng(30.001, -96.999),
            )
        )

        assertEquals(207.5, geometry.totalMeters, 2.0)
    }

    @Test
    fun snapProjectsFixOntoNearestSegment() {
        val geometry = RouteGeometry(
            listOf(
                LatLng(30.0, -97.0),
                LatLng(30.0, -96.998),
            )
        )

        val snap = geometry.snap(
            fixLat = 30.0001,
            fixLon = -96.999,
            fromIndex = 0,
        )

        assertEquals(30.0, snap.lat, 0.00001)
        assertEquals(-96.999, snap.lon, 0.00001)
        assertEquals(0, snap.segmentIndex)
        assertEquals(11.1, snap.lateralMeters, 0.8)
        assertEquals(geometry.totalMeters / 2.0, snap.alongMeters, 1.0)
    }

    @Test
    fun remainingDistanceNeverBecomesNegative() {
        val geometry = RouteGeometry(listOf(LatLng(30.0, -97.0), LatLng(30.001, -97.0)))
        val end = geometry.snap(30.002, -97.0, fromIndex = 0)

        assertTrue(end.alongMeters <= geometry.totalMeters)
        assertEquals(0.0, geometry.remainingMeters(end), 0.001)
    }
}
