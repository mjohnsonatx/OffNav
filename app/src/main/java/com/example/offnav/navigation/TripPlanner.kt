package com.example.offnav.navigation


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.atomic.AtomicLong

enum class StopType { ORIGIN, WAYPOINT, DESTINATION }

data class Stop(
    val id: Long,
    val label: String,
    val subtitle: String,
    val point: LatLng,
    val type: StopType,
)

/**
 * Manages an ordered list of stops for multi-point routing.
 * Layout is always: [origin?] + [waypoints…] + [destination?]
 *
 * Origin is ephemeral (current GPS); it's injected at route-compute time,
 * not stored here. This holds only waypoints + destination.
 */
class TripPlanner {

    private val nextId = AtomicLong(1)
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()

    val destination: Stop? get() = _stops.value.lastOrNull { it.type == StopType.DESTINATION }
    val waypoints: List<Stop> get() = _stops.value.filter { it.type == StopType.WAYPOINT }
    val hasWaypoints: Boolean get() = waypoints.isNotEmpty()
    val isEmpty: Boolean get() = _stops.value.isEmpty()

    /** The ordered points to route through (waypoints + destination). Origin prepended at route time. */
    val routePoints: List<LatLng>
        get() = _stops.value.map { it.point }

    /** Set or replace the final destination. Clears any previous destination. */
    fun setDestination(label: String, subtitle: String, point: LatLng) {
        _stops.value = waypoints + Stop(nextId.getAndIncrement(), label, subtitle, point, StopType.DESTINATION)
    }

    /**
     * Insert a waypoint. By default inserts before the destination (last waypoint position).
     * [index] is the position among all stops (waypoints + destination).
     */
    fun addWaypoint(label: String, subtitle: String, point: LatLng, index: Int? = null) {
        val list = _stops.value.toMutableList()
        val stop = Stop(nextId.getAndIncrement(), label, subtitle, point, StopType.WAYPOINT)
        val insertAt = index ?: (list.indexOfLast { it.type == StopType.DESTINATION }).coerceAtLeast(0)
        list.add(insertAt, stop)
        _stops.value = list
    }

    fun removeStop(id: Long) {
        _stops.value = _stops.value.filter { it.id != id }
    }

    fun moveStop(fromIndex: Int, toIndex: Int) {
        val list = _stops.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _stops.value = list
    }

    fun clear() {
        _stops.value = emptyList()
    }

    /** Number of stops including destination. */
    val size: Int get() = _stops.value.size
}