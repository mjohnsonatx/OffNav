package com.example.offnav.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

/** Primitives + formatted strings. Safe to hand straight to Compose. */
data class ActivitySummary(
    val id: Long,
    val uuid: String,
    val title: String,
    val note: String,
    val type: ActivityType,
    val startedAt: Long,
    val durationText: String,
    val distanceText: String,
    val avgSpeedText: String,
    val maxSpeedText: String,
    val elevationGainText: String?,
    val elevationLossText: String?,
    val southwest: LatLng,
    val northeast: LatLng,
    val hasBounds: Boolean,
    val pointCount: Int,
)

class ActivityRepository(private val dao: ActivityDao) {

    fun observeCompleted(): Flow<List<ActivitySummary>> =
        dao.observeCompleted()
            .map { rows -> rows.map { it.toSummary() } }
            .flowOn(Dispatchers.Default)

    fun observe(id: Long): Flow<ActivitySummary?> =
        dao.observeActivity(id).map { it?.toSummary() }.flowOn(Dispatchers.Default)

    suspend fun summary(id: Long): ActivitySummary? =
        withContext(Dispatchers.IO) { dao.activity(id)?.toSummary() }

    /** Segments, in order, for polyline rendering. */
    suspend fun trackSegments(id: Long): List<List<LatLng>> = withContext(Dispatchers.IO) {
        dao.points(id)
            .groupBy { it.segment }
            .toSortedMap()
            .values
            .map { seg -> seg.map { LatLng(it.lat, it.lon) } }
    }

    suspend fun rawPoints(id: Long): List<TrackPointEntity> =
        withContext(Dispatchers.IO) { dao.points(id) }

    suspend fun rename(id: Long, title: String, note: String) = withContext(Dispatchers.IO) {
        dao.activity(id)?.let { dao.updateActivity(it.copy(title = title, note = note)) }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteActivity(id) }
}

private fun ActivityEntity.toSummary(): ActivitySummary {
    val activityType = ActivityType.fromName(type)
    val source = ElevationSource.valueOf(elevationSource)
    val bounds = pointCount > 0 && (minLat != 0.0 || maxLat != 0.0)
    return ActivitySummary(
        id = id,
        uuid = uuid,
        title = title,
        note = note,
        type = activityType,
        startedAt = startedAt,
        durationText = UnitFormat.clock(movingMillis.takeIf { it > 0 } ?: activeMillis),
        distanceText = UnitFormat.miles(distanceMeters),
        avgSpeedText = UnitFormat.speedOrPace(avgMovingSpeedMps, activityType),
        maxSpeedText = UnitFormat.mph(maxSpeedMps),
        // Don't invent elevation we never measured.
        elevationGainText = if (source == ElevationSource.NONE) null
        else UnitFormat.feet(elevationGainMeters),
        elevationLossText = if (source == ElevationSource.NONE) null
        else UnitFormat.feet(elevationLossMeters),
        southwest = LatLng(minLat, minLon),
        northeast = LatLng(maxLat, maxLon),
        hasBounds = bounds,
        pointCount = pointCount,
    )
}