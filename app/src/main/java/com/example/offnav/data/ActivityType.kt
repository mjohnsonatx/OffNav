package com.example.offnav.data

/**
 * Per-type physics. Used for jitter rejection, speed sanity checks, and moving-time.
 * Stored as the enum *name* in Room — never the ordinal.
 */
enum class ActivityType(
    val displayName: String,
    val emoji: String,
    /** Fixes implying a faster speed than this are rejected as GPS teleports. */
    val maxPlausibleSpeedMps: Double,
    /** Below this, we consider the user stopped (for moving-time / auto-pause). */
    val movingThresholdMps: Double,
    /** Runners/hikers think in min/mi; cyclists and drivers think in mph. */
    val usesPace: Boolean,
) {
    WALK("Walk", "🚶", 4.5, 0.4, true),
    RUN("Run", "🏃", 9.0, 0.7, true),
    HIKE("Hike", "🥾", 4.5, 0.3, true),
    BIKE("Bike", "🚴", 25.0, 1.0, false),
    DRIVE("Drive", "🚗", 60.0, 1.5, false),
    OTHER("Activity", "📍", 60.0, 0.5, false);

    companion object {
        fun fromName(value: String): ActivityType =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class RecordingStatus { IDLE, RECORDING, PAUSED }

/** Persisted lifecycle of an activity row. */
enum class ActivityStatus { RECORDING, PAUSED, COMPLETED }

enum class ElevationSource { BAROMETER, GPS, NONE }