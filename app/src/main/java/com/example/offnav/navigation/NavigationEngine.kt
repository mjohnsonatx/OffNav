package com.example.offnav.navigation

import com.example.offnav.location.LocationProvider
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.TurnInstruction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Regular class (NOT a data class) -> identity equals -> StateFlow/Compose comparisons are O(1)
 * even though it holds thousands of points.
 */
class ActiveRoute private constructor(
    val route: RouteResult,
    val geometry: RouteGeometry,
    /** cumulative metres at which instruction i begins */
    val instructionStart: DoubleArray,
    /** pre-serialised overlay, ready for GeoJsonSource.setGeoJson(String) on the main thread */
    val overlayGeoJson: String,
) {
    /** @return instruction to announce + metres until its manoeuvre */
    fun upcoming(travelledMeters: Double): Pair<TurnInstruction?, Double> {
        val instr = route.instructions
        if (instr.isEmpty()) return null to 0.0
        // upper bound search: first leg whose end is beyond us
        var lo = 0
        var hi = instr.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (travelledMeters < instructionStart[mid] + instr[mid].distanceMeters) hi = mid else lo = mid + 1
        }
        val legEnd = instructionStart[lo] + instr[lo].distanceMeters
        return instr.getOrNull(lo + 1) to (legEnd - travelledMeters).coerceAtLeast(0.0)
    }

    companion object {
        /** Call on Dispatchers.Default – does all the allocation-heavy work. */
        fun build(route: RouteResult): ActiveRoute {
            val starts = DoubleArray(route.instructions.size)
            var acc = 0.0
            route.instructions.forEachIndexed { i, ins -> starts[i] = acc; acc += ins.distanceMeters }
            val json = Feature.fromGeometry(
                LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })
            ).toJson()
            return ActiveRoute(route, RouteGeometry(route.points), starts, json)
        }
    }
}

/** All fields are primitives/Strings -> equals is cheap, StateFlow dedupes automatically. */
data class NavBanner(
    val instructionText: String,
    val maneuverSign: Int,
    val distanceToManeuverMeters: Int,
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val offRoute: Boolean,
)

sealed interface NavState {
    data object Idle : NavState
    data class Navigating(val banner: NavBanner) : NavState
    data object Rerouting : NavState
    data object Arrived : NavState
}

class NavigationEngine(
    private val locationProvider: LocationProvider,
    private val routingEngine: GraphHopperEngine,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val OFF_ROUTE_THRESHOLD_M = 40.0
        const val OFF_ROUTE_FIX_COUNT = 3
        const val ARRIVAL_THRESHOLD_M = 25.0
    }

    private val _navState = MutableStateFlow<NavState>(NavState.Idle)
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    /** The map layer observes this; never put it in Compose state, collect it in a LaunchedEffect. */
    private val _activeRoute = MutableStateFlow<ActiveRoute?>(null)
    val activeRoute: StateFlow<ActiveRoute?> = _activeRoute.asStateFlow()

    private var job: Job? = null

    fun start(route: RouteResult, destination: LatLng) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) { drive(ActiveRoute.build(route), destination) }
    }

    fun preview(route: RouteResult?) {
        scope.launch(Dispatchers.Default) {
            _activeRoute.value = route?.let { ActiveRoute.build(it) }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _activeRoute.value = null
        if (_navState.value !is NavState.Arrived) _navState.value = NavState.Idle
    }

    private suspend fun drive(initial: ActiveRoute, destination: LatLng) {
        var active = initial
        _activeRoute.value = active
        var lastSegment = 0
        var offRouteStreak = 0

        locationProvider.locations
            // If a reroute takes 2s we process only the newest fix afterwards, never a backlog.
            .buffer(1, BufferOverflow.DROP_OLDEST)
            .collect { fix ->
                val snap = active.geometry.snap(fix.latitude, fix.longitude, lastSegment)
                lastSegment = snap.segmentIndex
                val remaining = active.geometry.remainingMeters(snap)

                if (remaining < ARRIVAL_THRESHOLD_M) {
                    _navState.value = NavState.Arrived
                    _activeRoute.value = null
                    return@collect currentCoroutineContext().cancel()
                }

                if (snap.lateralMeters > OFF_ROUTE_THRESHOLD_M) {
                    if (++offRouteStreak >= OFF_ROUTE_FIX_COUNT) {
                        offRouteStreak = 0
                        _navState.value = NavState.Rerouting
                        routingEngine.route(LatLng(fix.latitude, fix.longitude), destination)
                            .onSuccess { newRoute ->
                                active = ActiveRoute.build(newRoute)
                                _activeRoute.value = active
                                lastSegment = 0
                            }
                        return@collect
                    }
                } else offRouteStreak = 0

                val travelled = snap.alongMeters
                val (instruction, distToTurn) = active.upcoming(travelled)
                val speed = if (fix.hasSpeed() && fix.speed > 1f) fix.speed.toDouble() else 11.0

                _navState.value = NavState.Navigating(
                    NavBanner(
                        instructionText = instruction?.text ?: "Continue",
                        maneuverSign = instruction?.sign ?: 0,
                        // rounding = the state only changes when the UI would visibly change,
                        // so StateFlow's distinct-until-changed suppresses most recompositions
                        distanceToManeuverMeters = distToTurn.roundToInt(),
                        remainingMeters = (remaining / 10.0).roundToInt() * 10,
                        remainingSeconds = (remaining / speed).roundToInt(),
                        offRoute = false,
                    )
                )
            }
    }
}