package com.example.offnav.routing

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.PointList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val timeMillis: Long,
    val instructions: List<TurnInstruction>
)

data class TurnInstruction(
    val text: String,
    val distanceMeters: Double,
    val sign: Int // GraphHopper turn sign: 0 continue, -2 left, 2 right, etc.
)

sealed interface RoutingState {
    data object NotReady : RoutingState
    data object ImportingGraph : RoutingState
    data object Ready : RoutingState
    data object LoadingGraph : RoutingState
    data class Failed(val message: String) : RoutingState
}

class GraphHopperEngine(private val context: Context) {

    companion object {
        private const val PBF_ASSET = "routing/region.osm.pbf"
        private const val PBF_FILE = "region.osm.pbf"
        private const val GRAPH_DIR = "graphhopper"
        private const val PROFILE = "car"
        private const val TAG = "GraphHopperEngine"
    }

    private var hopper: GraphHopper? = null
    private val initMutex = Mutex()

    private val _state = MutableStateFlow<RoutingState>(RoutingState.NotReady)
    val state = _state.asStateFlow()
    val isReady: Boolean get() = hopper != null

    /**
     * Idempotent. Loads the graph if it exists, otherwise imports from the
     * bundled PBF (slow, first launch only). Call from a background scope.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (hopper != null) return@withLock
            try {
                val graphDir = File(context.filesDir, GRAPH_DIR)
                val needsImport = !graphDir.exists() || graphDir.listFiles().isNullOrEmpty()
                _state.value = if (needsImport) {
                    RoutingState.ImportingGraph
                } else {
                    RoutingState.LoadingGraph
                }
                val pbf = ensurePbfOnDisk()
                Log.i(TAG, "PBF at ${pbf.absolutePath} (${pbf.length() / 1_000_000} MB), " +
                        "needsImport=$needsImport, graphDir=${graphDir.absolutePath}")
                val started = System.currentTimeMillis()
                val gh = GraphHopper().apply {
                    graphHopperLocation = graphDir.absolutePath
                    osmFile = pbf.absolutePath
                    setProfiles(Profile(PROFILE).setName("car").setWeighting("fastest"))
                    chPreparationHandler.setCHProfiles(CHProfile(PROFILE))
                    setMinNetworkSize(200)
                }
                gh.importOrLoad()
                hopper = gh
                _state.value = RoutingState.Ready
                Log.i(TAG, "Graph ready in ${(System.currentTimeMillis() - started) / 1000}s")
            } catch (t: Throwable) {
                // Throwable, not Exception — OOM during import is an Error
                Log.e(TAG, "Graph init failed", t)
                _state.value = RoutingState.Failed(
                    "${t::class.simpleName}: ${t.message ?: "unknown"}"
                )
            }
        }
    }

    suspend fun route(from: LatLng, to: LatLng): Result<RouteResult> =
        withContext(Dispatchers.Default) {
            val gh = hopper
                ?: return@withContext Result.failure(IllegalStateException("Engine not ready"))

            val request = GHRequest(from.latitude, from.longitude, to.latitude, to.longitude)
                .setProfile(PROFILE)
            val response = gh.route(request)

            if (response.hasErrors()) {
                return@withContext Result.failure(
                    RuntimeException(response.errors.joinToString { it.message ?: "routing error" })
                )
            }

            val best = response.best
            Result.success(
                RouteResult(
                    points = best.points.toLatLngs(),
                    distanceMeters = best.distance,
                    timeMillis = best.time,
                    instructions = best.instructions.map {
                        TurnInstruction(
                            text = it.getTurnDescription(gh.translationMap.getWithFallBack(java.util.Locale.getDefault())),
                            distanceMeters = it.distance,
                            sign = it.sign
                        )
                    }
                )
            )
        }

    fun close() {
        hopper?.close()
        hopper = null
        _state.value = RoutingState.NotReady
    }

    private fun ensurePbfOnDisk(): File {
        val dest = File(context.filesDir, PBF_FILE)
        if (!dest.exists()) {
            context.assets.open(PBF_ASSET).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        return dest
    }

    private fun PointList.toLatLngs(): List<LatLng> =
        (0 until size()).map { LatLng(getLat(it), getLon(it)) }
}