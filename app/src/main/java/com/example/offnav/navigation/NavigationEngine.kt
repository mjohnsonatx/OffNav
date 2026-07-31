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
    val instructionStart: DoubleArray,
    val overlayGeoJson: String,
) {
    /**
     * @return Triple of:
     *   - the next instruction to announce (or null)
     *   - metres until that manoeuvre
     *   - index of the current leg (the one we're travelling along)
     */
    fun upcoming(travelledMeters: Double): Triple<TurnInstruction?, Double, Int> {
        val instr = route.instructions
        if (instr.isEmpty()) return Triple(null, 0.0, 0)

        var lo = 0
        var hi = instr.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (travelledMeters < instructionStart[mid] + instr[mid].distanceMeters) hi = mid
            else lo = mid + 1
        }
        val legEnd = instructionStart[lo] + instr[lo].distanceMeters
        return Triple(
            instr.getOrNull(lo + 1),
            (legEnd - travelledMeters).coerceAtLeast(0.0),
            lo
        )
    }

    companion object {
        fun build(route: RouteResult): ActiveRoute {
            val starts = DoubleArray(route.instructions.size)
            var acc = 0.0
            route.instructions.forEachIndexed { i, ins ->
                starts[i] = acc; acc += ins.distanceMeters
            }
            val json = Feature.fromGeometry(
                LineString.fromLngLats(
                    route.points.map { Point.fromLngLat(it.longitude, it.latitude) }
                )
            ).toJson()
            return ActiveRoute(route, RouteGeometry(route.points), starts, json)
        }
    }
}

/** All fields are primitives/Strings → cheap equals, StateFlow dedupes automatically. */
data class NavBanner(
    val instructionText: String,
    val maneuverSign: Int,
    val distanceToManeuverMeters: Int,
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val offRoute: Boolean,
    val currentInstructionIndex: Int,   // highlights the active step in the list
)

sealed interface NavState {
    data object Idle : NavState

    data class Navigating(val banner: NavBanner) : NavState

    /** Carries the last known banner so the UI can keep showing turn info. */
    data class Rerouting(val lastBanner: NavBanner) : NavState

    data object Arrived : NavState
}

class NavigationEngine(
    private val locationProvider: LocationProvider,
    private val routingEngine: GraphHopperEngine,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val OFF_ROUTE_THRESHOLD_M = 50.0      // was 40 — a little more forgiving
        const val OFF_ROUTE_FIX_COUNT = 4            // was 3 — need 4 consecutive bad fixes
        const val ARRIVAL_THRESHOLD_M = 25.0
        const val REROUTE_COOLDOWN_MS = 10_000L      // don't reroute again within 10s
    }
    private val _navState = MutableStateFlow<NavState>(NavState.Idle)
    val navState: StateFlow<NavState> = _navState.asStateFlow()
    private val _activeRoute = MutableStateFlow<ActiveRoute?>(null)
    val activeRoute: StateFlow<ActiveRoute?> = _activeRoute.asStateFlow()
    private var job: Job? = null
    fun start(route: RouteResult, destination: LatLng) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            drive(ActiveRoute.build(route), destination)
        }
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
        var lastRerouteTime = 0L
        var lastBanner: NavBanner? = null          // kept alive across reroutes
        locationProvider.locations
            .buffer(1, BufferOverflow.DROP_OLDEST)
            .collect { fix ->
                val now = System.currentTimeMillis()
                val snap = active.geometry.snap(fix.latitude, fix.longitude, lastSegment)
                lastSegment = snap.segmentIndex
                val remaining = active.geometry.remainingMeters(snap)
                // ── Arrival ──
                if (remaining < ARRIVAL_THRESHOLD_M) {
                    _navState.value = NavState.Arrived
                    _activeRoute.value = null
                    return@collect currentCoroutineContext().cancel()
                }
                // ── Off-route detection with cooldown ──
                val cooledDown = (now - lastRerouteTime) > REROUTE_COOLDOWN_MS
                if (snap.lateralMeters > OFF_ROUTE_THRESHOLD_M && cooledDown) {
                    if (++offRouteStreak >= OFF_ROUTE_FIX_COUNT) {
                        offRouteStreak = 0
                        lastRerouteTime = now
                        // Emit Rerouting but keep the last banner so the UI stays populated
                        _navState.value = NavState.Rerouting(
                            lastBanner ?: NavBanner("Rerouting", 0, 0, remaining.roundToInt(), 0, true, 0)
                        )
                        routingEngine.route(LatLng(fix.latitude, fix.longitude), destination)
                            .onSuccess { newRoute ->
                                active = ActiveRoute.build(newRoute)
                                _activeRoute.value = active
                                lastSegment = 0
                            }
                        // on failure: keep old route, will retry on next streak
                        return@collect
                    }
                } else if (snap.lateralMeters <= OFF_ROUTE_THRESHOLD_M) {
                    offRouteStreak = 0
                }
                // ── Build banner ──
                val travelled = snap.alongMeters
                val (instruction, distToTurn, legIndex) = active.upcoming(travelled)
                val speed = if (fix.hasSpeed() && fix.speed > 1f) fix.speed.toDouble() else 11.0
                val banner = NavBanner(
                    instructionText = instruction?.text ?: "Continue",
                    maneuverSign = instruction?.sign ?: 0,
                    distanceToManeuverMeters = distToTurn.roundToInt(),
                    remainingMeters = (remaining / 10.0).roundToInt() * 10,
                    remainingSeconds = (remaining / speed).roundToInt(),
                    offRoute = snap.lateralMeters > OFF_ROUTE_THRESHOLD_M,
                    currentInstructionIndex = legIndex,
                )
                lastBanner = banner
                _navState.value = NavState.Navigating(banner)
            }
    }
}