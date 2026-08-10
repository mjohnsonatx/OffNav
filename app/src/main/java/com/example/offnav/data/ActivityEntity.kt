package com.example.offnav.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "recorded_activity",
    indices = [Index(value = ["startedAt"]), Index(value = ["status"])],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Stable id for exports; survives re-import, never reuses a rowid. */
    val uuid: String = UUID.randomUUID().toString(),

    val type: String = ActivityType.OTHER.name,
    val status: String = ActivityStatus.RECORDING.name,

    val title: String = "",
    val note: String = "",

    /** Wall-clock start, for display only. Durations come from elapsedRealtime. */
    val startedAt: Long,
    val endedAt: Long? = null,

    /** Wall-clock span start→end, including pauses. */
    val elapsedMillis: Long = 0,
    /** Time spent recording, excluding pauses. */
    val activeMillis: Long = 0,
    /** Time spent above the type's moving threshold. */
    val movingMillis: Long = 0,

    val distanceMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,

    val elevationGainMeters: Double = 0.0,
    val elevationLossMeters: Double = 0.0,
    val elevationSource: String = ElevationSource.NONE.name,

    // Bounding box — lets the list/detail screens fit the camera without loading points.
    @ColumnInfo(defaultValue = "0") val minLat: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val minLon: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val maxLat: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val maxLon: Double = 0.0,

    val pointCount: Int = 0,
) {
    /** Strava's "average speed" is distance / moving time, not distance / elapsed. */
    val avgMovingSpeedMps: Double
        get() = if (movingMillis > 0) distanceMeters / (movingMillis / 1000.0) else 0.0
}

@Entity(
    tableName = "track_point",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["activityId", "timestamp"])],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    /** Increments on every resume → renders as a separate <trkseg> / polyline. */
    val segment: Int,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    /** Fused/corrected altitude in metres, or null when no source is available. */
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?,
)