package com.example.offnav.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class TripPlannerTest {

    @Test
    fun waypointsStayBeforeDestinationWhenDestinationChanges() {
        val planner = TripPlanner()
        planner.setDestination("Capitol", "Congress Avenue", point(1.0))
        planner.addWaypoint("Coffee", "East Austin", point(2.0))
        planner.addWaypoint("Fuel", "Airport Boulevard", point(3.0))

        planner.setDestination("Airport", "ABIA", point(4.0))

        assertEquals(listOf("Coffee", "Fuel", "Airport"), planner.stops.value.map { it.label })
        assertEquals(StopType.DESTINATION, planner.stops.value.last().type)
        assertEquals(2, planner.waypoints.size)
    }

    @Test
    fun explicitWaypointIndexControlsRouteOrder() {
        val planner = TripPlanner()
        planner.setDestination("Destination", "", point(4.0))
        planner.addWaypoint("Second", "", point(2.0))
        planner.addWaypoint("First", "", point(1.0), index = 0)

        assertEquals(listOf("First", "Second", "Destination"), planner.stops.value.map { it.label })
        assertEquals(planner.stops.value.map { it.point }, planner.routePoints)
    }

    @Test
    fun invalidMoveDoesNotChangeStops() {
        val planner = TripPlanner()
        planner.setDestination("Destination", "", point(3.0))
        planner.addWaypoint("Waypoint", "", point(2.0))
        val before = planner.stops.value

        planner.moveStop(fromIndex = -1, toIndex = 1)
        planner.moveStop(fromIndex = 0, toIndex = 20)

        assertEquals(before, planner.stops.value)
    }

    @Test
    fun removeAndClearUpdatePlannerFlags() {
        val planner = TripPlanner()
        planner.setDestination("Destination", "", point(3.0))
        planner.addWaypoint("Waypoint", "", point(2.0))
        val waypointId = planner.waypoints.single().id

        planner.removeStop(waypointId)

        assertFalse(planner.hasWaypoints)
        assertEquals(1, planner.size)
        planner.clear()
        assertTrue(planner.isEmpty)
    }

    private fun point(offset: Double) = LatLng(30.26 + offset / 1_000.0, -97.74)
}
