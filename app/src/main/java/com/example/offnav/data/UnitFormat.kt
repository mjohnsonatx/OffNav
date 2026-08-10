package com.example.offnav.data

import java.util.Locale
import kotlin.math.roundToInt

/** Everything is stored SI. Display is imperial per the product spec (mph / miles / feet). */
object UnitFormat {

    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.280839895

    fun miles(meters: Double): String =
        String.format(Locale.getDefault(), "%.2f mi", meters / METERS_PER_MILE)

    fun mph(mps: Double): String =
        String.format(Locale.getDefault(), "%.1f mph", mps * 2.2369362920544)

    fun feet(meters: Double): String = "${(meters * FEET_PER_METER).roundToInt()} ft"

    /** min/mi, the only sane unit for running and hiking. */
    fun pace(mps: Double): String {
        if (mps < 0.15) return "--:-- /mi"
        val secondsPerMile = METERS_PER_MILE / mps
        val m = (secondsPerMile / 60).toInt()
        val s = (secondsPerMile % 60).roundToInt()
        // 9:60 /mi is not a thing.
        val (mm, ss) = if (s == 60) (m + 1) to 0 else m to s
        return String.format(Locale.getDefault(), "%d:%02d /mi", mm, ss)
    }

    fun speedOrPace(mps: Double, type: ActivityType): String =
        if (type.usesPace) pace(mps) else mph(mps)

    /** 1:04:09 / 4:09 — a stopwatch, not "1 hr 4 min". */
    fun clock(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%d:%02d", m, s)
    }
}