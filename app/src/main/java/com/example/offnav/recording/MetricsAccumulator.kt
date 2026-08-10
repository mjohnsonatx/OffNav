package com.example.offnav.recording

import com.example.offnav.data.ActivityType
import com.example.offnav.navigation.RouteGeometry
import kotlin.math.max

/** One accepted GPS sample. */
data class TrackSample(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
)

/**
 * Incremental distance / moving-time / max-speed. Deterministic: feeding the same
 * sample sequence twice (live, then replayed from Room) produces identical output.
 */
class MetricsAccumulator(private val type: ActivityType) {

    var distanceMeters: Double = 0.0; private set
    var movingMillis: Long = 0L; private set
    var maxSpeedMps: Double = 0.0; private set
    var acceptedCount: Int = 0; private set

    var minLat = Double.MAX_VALUE; private set
    var minLon = Double.MAX_VALUE; private set
    var maxLat = -Double.MAX_VALUE; private set
    var maxLon = -Double.MAX_VALUE; private set

    private var last: TrackSample? = null

    /** Call on resume so we never bridge a pause with a straight line. */
    fun breakSegment() { last = null }

    /**
     * @return true if the sample was accepted into the track (and should be persisted).
     *
     * Rejection reasons, in order:
     *  - accuracy worse than [MAX_ACCURACY_M]          → the fix is noise
     *  - implied speed above the type's plausible max  → GPS teleport / tunnel exit
     *  - displacement below the jitter floor AND the
     *    sample is recent                              → standing still, don't inflate distance
     */
    fun accept(sample: TrackSample): Boolean {
        if (sample.accuracyMeters > MAX_ACCURACY_M) return false

        val previous = last
        if (previous == null) {
            commit(sample, 0.0, 0L)
            return true
        }

        val dtMillis = sample.timestamp - previous.timestamp
        if (dtMillis <= 0) return false   // duplicate or out-of-order

        val meters = RouteGeometry.metersBetween(
            previous.lat, previous.lon, sample.lat, sample.lon,
        )

        val impliedSpeed = meters / (dtMillis / 1000.0)
        if (impliedSpeed > type.maxPlausibleSpeedMps) return false

        // Jitter floor scales with reported accuracy: a 20 m fix must move further
        // than a 4 m fix before we believe it. Long gaps always commit, to keep time honest.
        val jitterFloor = max(MIN_DISPLACEMENT_M, sample.accuracyMeters * 0.5)
        if (meters < jitterFloor && dtMillis < FORCE_COMMIT_MS) return false

        // Prefer the Doppler speed from the chipset; fall back to derived.
        val speed = sample.speedMps?.toDouble()?.takeIf { it.isFinite() } ?: impliedSpeed
        val movingDelta = if (speed >= type.movingThresholdMps) dtMillis else 0L

        commit(sample, meters, movingDelta)
        if (speed <= type.maxPlausibleSpeedMps) maxSpeedMps = max(maxSpeedMps, speed)
        return true
    }

    private fun commit(sample: TrackSample, meters: Double, movingDelta: Long) {
        distanceMeters += meters
        movingMillis += movingDelta
        acceptedCount++
        minLat = minOf(minLat, sample.lat); maxLat = maxOf(maxLat, sample.lat)
        minLon = minOf(minLon, sample.lon); maxLon = maxOf(maxLon, sample.lon)
        last = sample
    }

    val hasBounds: Boolean get() = acceptedCount > 0

    private companion object {
        const val MAX_ACCURACY_M = 25f
        const val MIN_DISPLACEMENT_M = 2.5
        const val FORCE_COMMIT_MS = 10_000L
    }
}