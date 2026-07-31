package com.example.offnav.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RouteHistoryEntityTest {
    @Test
    fun destinationKeyIsStableAcrossDeviceLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)

            assertEquals(
                "30.26720,-97.74310",
                RouteHistoryEntity.destKeyOf(30.2672, -97.7431),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun destinationKeyDeduplicatesSubMeterCoordinateNoise() {
        assertEquals(
            RouteHistoryEntity.destKeyOf(30.2672001, -97.7431001),
            RouteHistoryEntity.destKeyOf(30.2672002, -97.7431002),
        )
    }
}
