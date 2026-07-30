package com.example.offnav.navigation

import org.maplibre.android.geometry.LatLng
import kotlin.math.cos

object GeoMath {

    data class Snap(
        val point: LatLng,
        val segmentIndex: Int,       // index of segment start in the polyline
        val distanceMeters: Double,  // perpendicular distance from fix to route
        val fractionAlongSegment: Double
    )

    /** Snap [p] to the nearest point on [line], searching from [fromIndex]
     *  forward so we never snap backwards onto already-travelled road. */
    fun snapToPolyline(p: LatLng, line: List<LatLng>, fromIndex: Int = 0): Snap {
        var best: Snap? = null
        // Only look a bounded window ahead; avoids O(n) per fix on long routes
        val end = minOf(line.size - 1, fromIndex + 50)
        for (i in fromIndex until end) {
            val snap = snapToSegment(p, line[i], line[i + 1], i)
            if (best == null || snap.distanceMeters < best.distanceMeters) best = snap
        }
        return best ?: Snap(line.first(), 0, distanceMeters(p, line.first()), 0.0)
    }

    private fun snapToSegment(p: LatLng, a: LatLng, b: LatLng, index: Int): Snap {
        // Equirectangular projection — fine at street scale
        val lat = Math.toRadians(a.latitude)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(lat)

        val ax = 0.0; val ay = 0.0
        val bx = (b.longitude - a.longitude) * mPerDegLon
        val by = (b.latitude - a.latitude) * mPerDegLat
        val px = (p.longitude - a.longitude) * mPerDegLon
        val py = (p.latitude - a.latitude) * mPerDegLat

        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0
        else ((px - ax) * bx + (py - ay) * by / 1.0).let { ((px * bx + py * by) / len2) }
            .coerceIn(0.0, 1.0)

        val sx = bx * t; val sy = by * t
        val snapped = LatLng(
            a.latitude + sy / mPerDegLat,
            a.longitude + sx / mPerDegLon
        )
        val dx = px - sx; val dy = py - sy
        return Snap(snapped, index, Math.sqrt(dx * dx + dy * dy), t)
    }

    fun distanceMeters(a: LatLng, b: LatLng): Double = a.distanceTo(b) // MapLibre haversine

    /** Remaining distance along the polyline from a snap position to the end. */
    fun remainingDistance(line: List<LatLng>, snap: Snap): Double {
        var d = distanceMeters(snap.point, line[snap.segmentIndex + 1])
        for (i in snap.segmentIndex + 1 until line.size - 1) {
            d += distanceMeters(line[i], line[i + 1])
        }
        return d
    }
}