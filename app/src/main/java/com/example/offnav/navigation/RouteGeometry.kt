package com.example.offnav.navigation

import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Immutable, flat-array route geometry with precomputed cumulative distances.
 * Built once per route on a worker thread; snapping is O(window), progress is O(1).
 */
class RouteGeometry(points: List<LatLng>) {

    val size = points.size
    private val lat = DoubleArray(size) { points[it].latitude }
    private val lon = DoubleArray(size) { points[it].longitude }
    private val cum = DoubleArray(size)          // metres from start to vertex i
    val totalMeters: Double

    init {
        require(size >= 2) { "Route needs at least 2 points" }
        var acc = 0.0
        for (i in 1 until size) {
            acc += metersBetween(lat[i - 1], lon[i - 1], lat[i], lon[i])
            cum[i] = acc
        }
        totalMeters = acc
    }

    class Snap(
        val lat: Double,
        val lon: Double,
        val segmentIndex: Int,
        /** perpendicular distance from the fix to the route, metres */
        val lateralMeters: Double,
        /** distance travelled along the route at the snapped point, metres */
        val alongMeters: Double,
    )

    /**
     * Snap to the nearest point on the polyline, searching a bounded window around
     * [fromIndex] (a little behind, to tolerate GPS noise, and well ahead).
     */
    fun snap(
        fixLat: Double,
        fixLon: Double,
        fromIndex: Int,
        behind: Int = 3,
        ahead: Int = 80,
    ): Snap {
        // Local equirectangular frame centred on the fix: one cos() for the whole search.
        val mPerDegLat = 111_132.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(fixLat))

        val first = (fromIndex - behind).coerceAtLeast(0)
        val last = (fromIndex + ahead).coerceAtMost(size - 2)

        var bestI = first
        var bestT = 0.0
        var bestD2 = Double.MAX_VALUE

        for (i in first..last) {
            val ax = (lon[i] - fixLon) * mPerDegLon
            val ay = (lat[i] - fixLat) * mPerDegLat
            val bx = (lon[i + 1] - fixLon) * mPerDegLon
            val by = (lat[i + 1] - fixLat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d2 = cx * cx + cy * cy
            if (d2 < bestD2) { bestD2 = d2; bestI = i; bestT = t }
        }

        val segMeters = cum[bestI + 1] - cum[bestI]
        return Snap(
            lat = lat[bestI] + (lat[bestI + 1] - lat[bestI]) * bestT,
            lon = lon[bestI] + (lon[bestI + 1] - lon[bestI]) * bestT,
            segmentIndex = bestI,
            lateralMeters = sqrt(bestD2),
            alongMeters = cum[bestI] + segMeters * bestT,
        )
    }

    fun remainingMeters(snap: Snap): Double = (totalMeters - snap.alongMeters).coerceAtLeast(0.0)

    companion object {
        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            // Equirectangular is accurate to <0.1% at street scale and ~8x cheaper than haversine.
            val mLat = 111_132.0
            val mLon = 111_320.0 * cos(Math.toRadians((lat1 + lat2) * 0.5))
            return hypot((lat2 - lat1) * mLat, (lon2 - lon1) * mLon)
        }
    }
}