package com.example.offnav.recording

import com.example.offnav.data.ElevationSource
import kotlin.math.abs
import kotlin.math.pow

/**
 * Relative elevation gain/loss.
 *
 * Barometer: ±0.2 m resolution. The absolute altitude it reports is wrong (it assumes
 * standard sea-level pressure) but *deltas* are correct, which is all gain/loss needs.
 * Slow weather drift (a front moving through over an hour) is absorbed by the hysteresis
 * threshold, not by the smoother.
 *
 * GPS: vertical error is ~1.5x horizontal and is heavily autocorrelated, so a naive
 * sum-of-positive-deltas reports hundreds of feet of climb while sitting still.
 * We use a much larger threshold and gate on vertical accuracy.
 */
class ElevationTracker(hasBarometer: Boolean) {

    val source: ElevationSource =
        if (hasBarometer) ElevationSource.BAROMETER else ElevationSource.GPS

    var gainMeters: Double = 0.0; private set
    var lossMeters: Double = 0.0; private set

    /** Best current altitude estimate in metres MSL, or null until calibrated. */
    var altitudeMeters: Double? = null; private set

    private var smoothedBaroAltitude: Double? = null
    private var baroOffset: Double? = null       // MSL correction from the first good GPS fix
    private var reference: Double? = null        // hysteresis anchor
    private var lastAcceptTime: Long = 0L

    private val threshold: Double =
        if (source == ElevationSource.BAROMETER) BARO_THRESHOLD_M else GPS_THRESHOLD_M

    fun onPressure(hPa: Float) {
        if (source != ElevationSource.BAROMETER) return
        val raw = pressureToAltitude(hPa)
        val previous = smoothedBaroAltitude
        smoothedBaroAltitude =
            if (previous == null) raw else previous + EMA_ALPHA * (raw - previous)
    }

    /**
     * @param verticalAccuracyMeters null on devices/API levels that don't report it.
     */
    fun onGpsAltitude(
        timestamp: Long,
        gpsAltitude: Double?,
        verticalAccuracyMeters: Float?,
    ) {
        when (source) {
            ElevationSource.BAROMETER -> {
                val baro = smoothedBaroAltitude ?: return
                // One-time MSL calibration so exported <ele> isn't nonsense.
                if (baroOffset == null && gpsAltitude != null &&
                    (verticalAccuracyMeters == null || verticalAccuracyMeters < 15f)
                ) {
                    baroOffset = gpsAltitude - baro
                }
                integrate(timestamp, baro + (baroOffset ?: 0.0))
            }

            ElevationSource.GPS -> {
                if (gpsAltitude == null) return
                if (verticalAccuracyMeters != null && verticalAccuracyMeters > GPS_MAX_VACC_M) return
                integrate(timestamp, gpsAltitude)
            }

            ElevationSource.NONE -> Unit
        }
    }

    private fun integrate(timestamp: Long, altitude: Double) {
        altitudeMeters = altitude

        val anchor = reference
        if (anchor == null) {
            reference = altitude
            lastAcceptTime = timestamp
            return
        }

        val delta = altitude - anchor
        if (abs(delta) < threshold) return

        // Reject physically impossible vertical rates (elevator, tunnel, pressure spike
        // from slamming a car door — yes, really).
        val dtSeconds = ((timestamp - lastAcceptTime).coerceAtLeast(1L)) / 1000.0
        if (abs(delta) / dtSeconds > MAX_VERTICAL_MPS) {
            reference = altitude
            lastAcceptTime = timestamp
            return
        }

        if (delta > 0) gainMeters += delta else lossMeters += -delta
        reference = altitude
        lastAcceptTime = timestamp
    }

    private fun pressureToAltitude(hPa: Float): Double =
        44_330.0 * (1.0 - (hPa / SEA_LEVEL_HPA).toDouble().pow(1.0 / 5.255))

    private companion object {
        const val SEA_LEVEL_HPA = 1013.25f
        const val EMA_ALPHA = 0.12           // ~1.5 s time constant at the UI sensor rate
        const val BARO_THRESHOLD_M = 2.0
        const val GPS_THRESHOLD_M = 6.0
        const val GPS_MAX_VACC_M = 10f
        const val MAX_VERTICAL_MPS = 4.0
    }
}