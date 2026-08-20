package com.example.offnav.navigation

import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.TurnInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class ActiveRouteTest {

    @Test
    fun upcomingAdvancesToTheInstructionAfterCurrentLeg() {
        val active = ActiveRoute.build(route(instructions()))

        val first = active.upcoming(40.0)
        assertEquals("Turn right", first.first?.text)
        assertEquals(60.0, first.second, 0.001)
        assertEquals(0, first.third)

        val second = active.upcoming(150.0)
        assertEquals("Arrive", second.first?.text)
        assertEquals(150.0, second.second, 0.001)
        assertEquals(1, second.third)
    }

    @Test
    fun upcomingAtRouteEndHasNoFollowingInstruction() {
        val active = ActiveRoute.build(route(instructions()))

        val end = active.upcoming(350.0)

        assertNull(end.first)
        assertEquals(0.0, end.second, 0.001)
        assertEquals(2, end.third)
    }

    @Test
    fun buildCreatesReusableRouteOverlay() {
        val active = ActiveRoute.build(route(emptyList()))

        assertEquals(3, active.geometry.size)
        assertTrue(active.overlayGeoJson.contains("LineString"))
        assertNull(active.upcoming(0.0).first)
    }

    private fun route(instructions: List<TurnInstruction>) = RouteResult(
        points = listOf(
            LatLng(30.2670, -97.7430),
            LatLng(30.2680, -97.7430),
            LatLng(30.2690, -97.7420),
        ),
        distanceMeters = 300.0,
        timeMillis = 180_000,
        instructions = instructions,
    )

    private fun instructions() = listOf(
        instruction("Continue", 100.0, 0),
        instruction("Turn right", 200.0, 2),
        instruction("Arrive", 0.0, 4),
    )

    private fun instruction(text: String, meters: Double, sign: Int) = TurnInstruction(
        text = text,
        distanceMeters = meters,
        sign = sign,
        lat = 30.267,
        lon = -97.743,
    )
}
