package com.example.offnav.data


import com.example.offnav.routing.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

/** Immutable, primitives-only view model for the history list. */
data class RouteHistoryEntry(
    val id: Long,
    val label: String,
    val subtitle: String,
    val destination: LatLng,
    val distanceText: String,
    val durationText: String,
    val relativeTimeText: String,
    val useCount: Int,
    val pinned: Boolean,
)

class RouteHistoryRepository(private val dao: RouteHistoryDao) {

    fun observe(query: String): Flow<List<RouteHistoryEntry>> {
        val source = if (query.isBlank()) dao.recent() else dao.search(query.trim())
        return source
            .map { rows -> rows.map { it.toEntry() } }
            .flowOn(Dispatchers.Default)      // mapping + formatting off the main thread
    }

    suspend fun record(
        origin: LatLng,
        destination: LatLng,
        label: String,
        subtitle: String,
        route: RouteResult,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.recordVisit(
            RouteHistoryEntity(
                label = label,
                subtitle = subtitle,
                originLat = origin.latitude,
                originLon = origin.longitude,
                destLat = destination.latitude,
                destLon = destination.longitude,
                destKey = RouteHistoryEntity.destKeyOf(destination.latitude, destination.longitude),
                distanceMeters = route.distanceMeters,
                durationMillis = route.timeMillis,
                createdAt = now,
                lastUsedAt = now,
                useCount = 1,
            )
        )
        dao.trimTo(200)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }
    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) { dao.setPinned(id, pinned) }
    suspend fun clearUnpinned() = withContext(Dispatchers.IO) { dao.clearUnpinned() }
}

// ── formatting helpers (run off the main thread via flowOn) ──

private fun RouteHistoryEntity.toEntry() = RouteHistoryEntry(
    id = id,
    label = label,
    subtitle = subtitle,
    destination = LatLng(destLat, destLon),
    distanceText = formatMeters(distanceMeters),
    durationText = formatDuration(durationMillis / 1000),
    relativeTimeText = formatRelative(lastUsedAt),
    useCount = useCount,
    pinned = pinned,
)

internal fun formatMeters(meters: Double): String = when {
    meters >= 10_000 -> "%.0f km".format(meters / 1000.0)
    meters >= 1_000 -> "%.1f km".format(meters / 1000.0)
    else -> "${meters.toInt()} m"
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h} hr ${m} min"
        m > 0 -> "${m} min"
        else -> "<1 min"
    }
}

private fun formatRelative(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}