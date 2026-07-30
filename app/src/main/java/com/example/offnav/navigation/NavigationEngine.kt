package com.example.offnav.navigation

import android.location.Location
import com.example.offnav.location.LocationProvider
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.TurnInstruction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

sealed interface NavState {
    data object Idle : NavState
    data class Navigating(
        val route: RouteResult,
        val snappedPosition: LatLng,
        val remainingMeters: Double,
        val currentInstruction: TurnInstruction?,
        val distanceToNextTurnMeters: Double
    ) : NavState
    data class Rerouting(val route: RouteResult) : NavState
    data object Arrived : NavState
}

class NavigationEngine(
    private val locationProvider: LocationProvider,
    private val routingEngine: GraphHopperEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val OFF_ROUTE_THRESHOLD_M = 40.0
        private const val OFF_ROUTE_FIX_COUNT = 3   // consecutive bad fixes before reroute
        private const val ARRIVAL_THRESHOLD_M = 25.0
    }

    private val _navState = MutableStateFlow<NavState>(NavState.Idle)
    val navState = _navState.asStateFlow()

    private var job: Job? = null
    private var destination: LatLng? = null

    fun start(route: RouteResult, destination: LatLng) {
        stop()
        this.destination = destination
        var activeRoute = route
        // Cumulative distance at which each instruction begins
        var instructionOffsets = buildInstructionOffsets(activeRoute)
        var lastSegmentIndex = 0
        var offRouteStreak = 0

        job = scope.launch {
            locationProvider.locationFlow().collect { fix ->
                val pos = LatLng(fix.latitude, fix.longitude)
                val line = activeRoute.points

                val snap = GeoMath.snapToPolyline(pos, line, fromIndex = lastSegmentIndex)
                lastSegmentIndex = snap.segmentIndex

                // --- Arrival check ---
                val remaining = GeoMath.remainingDistance(line, snap)
                if (remaining < ARRIVAL_THRESHOLD_M) {
                    _navState.value = NavState.Arrived
                    stop()
                    return@collect
                }

                // --- Off-route check ---
                if (snap.distanceMeters > OFF_ROUTE_THRESHOLD_M) {
                    offRouteStreak++
                    if (offRouteStreak >= OFF_ROUTE_FIX_COUNT) {
                        offRouteStreak = 0
                        _navState.value = NavState.Rerouting(activeRoute)
                        routingEngine.route(pos, destination!!).onSuccess { newRoute ->
                            activeRoute = newRoute
                            instructionOffsets = buildInstructionOffsets(newRoute)
                            lastSegmentIndex = 0
                        }
                        // on failure: keep old route, will retry on next off-route streak
                        return@collect
                    }
                } else {
                    offRouteStreak = 0
                }

                // --- Current instruction ---
                val travelled = activeRoute.distanceMeters - remaining
                val (instruction, distToTurn) =
                    currentInstruction(activeRoute.instructions, instructionOffsets, travelled)

                _navState.value = NavState.Navigating(
                    route = activeRoute,
                    snappedPosition = snap.point,
                    remainingMeters = remaining,
                    currentInstruction = instruction,
                    distanceToNextTurnMeters = distToTurn
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_navState.value !is NavState.Arrived) _navState.value = NavState.Idle
    }

    /** Cumulative start-distance of each instruction along the route. */
    private fun buildInstructionOffsets(route: RouteResult): DoubleArray {
        val offsets = DoubleArray(route.instructions.size)
        var acc = 0.0
        route.instructions.forEachIndexed { i, instr ->
            offsets[i] = acc
            acc += instr.distanceMeters
        }
        return offsets
    }

    /** Find the instruction whose leg we're currently on; report distance to its *end*
     *  (i.e., the upcoming maneuver). */
    private fun currentInstruction(
        instructions: List<TurnInstruction>,
        offsets: DoubleArray,
        travelled: Double
    ): Pair<TurnInstruction?, Double> {
        for (i in instructions.indices) {
            val legEnd = offsets[i] + instructions[i].distanceMeters
            if (travelled < legEnd) {
                // The maneuver to announce is the NEXT instruction's turn,
                // happening at the end of this leg.
                val next = instructions.getOrNull(i + 1)
                return next to (legEnd - travelled)
            }
        }
        return null to 0.0
    }
}