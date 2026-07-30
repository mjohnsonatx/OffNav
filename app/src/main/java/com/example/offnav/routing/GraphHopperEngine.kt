package com.example.offnav.routing

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.PointList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.Locale

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val timeMillis: Long,
    val instructions: List<TurnInstruction>
)

data class TurnInstruction(
    val text: String,
    val distanceMeters: Double,
    val sign: Int
)

sealed interface RoutingState {
    data object NotReady : RoutingState
    data object ImportingGraph : RoutingState
    data object LoadingGraph : RoutingState
    data object Ready : RoutingState
    data class Failed(val message: String) : RoutingState
}

class GraphHopperEngine(private val context: Context) {

    companion object {
        private const val TAG = "GraphHopperEngine"
        private const val PBF_ASSET = "routing/region.osm.pbf"
        private const val PBF_FILE = "region.osm.pbf"
        private const val GRAPH_DIR = "graphhopper"
        private const val VERSION_FILE = "graph.profile.version"
        private const val PROFILE = "car"
    }

    private var hopper: GraphHopper? = null
    private val initMutex = Mutex()

    private val _state = MutableStateFlow<RoutingState>(RoutingState.NotReady)
    val state = _state.asStateFlow()

    val isReady: Boolean get() = hopper != null

    /** The routing profile: a custom model over car encoded values. */
    private fun carProfile(): Profile = Profile(PROFILE).apply {
        setCustomModel(
            CustomModel().apply {
                addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
                addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
                addToPriority(
                    Statement.ElseIf("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1")
                )
                addToPriority(Statement.If("road_class == TRACK", Statement.Op.MULTIPLY, "0.5"))
                addToPriority(Statement.If("road_environment == FERRY", Statement.Op.MULTIPLY, "0.5"))
                distanceInfluence = 70.0
            }
        )
        // Optional: enable to honor turn restrictions (slower import, better routes)
        // setTurnCostsConfig(TurnCostsConfig.car())
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (hopper != null) return@withLock
            try {
                val profile = carProfile()
                val graphDir = File(context.filesDir, GRAPH_DIR)
                val versionFile = File(context.filesDir, VERSION_FILE)
                val currentVersion = profile.version.toString()

                // If the profile definition changed since the graph was built,
                // the stored graph is incompatible — discard it.
                val storedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim()
                if (graphDir.exists() && storedVersion != currentVersion) {
                    Log.w(TAG, "Profile changed ($storedVersion -> $currentVersion); rebuilding graph")
                    graphDir.deleteRecursively()
                }

                val needsImport = !graphDir.exists() || graphDir.listFiles().isNullOrEmpty()
                _state.value = if (needsImport) RoutingState.ImportingGraph
                else RoutingState.LoadingGraph

                val pbf = ensurePbfOnDisk()
                Log.i(TAG, "PBF ${pbf.length() / 1_000_000} MB, needsImport=$needsImport")

                val started = System.currentTimeMillis()
                val gh = GraphHopper().apply {
                    graphHopperLocation = graphDir.absolutePath
                    osmFile = pbf.absolutePath
                    setProfiles(profile)
                    chPreparationHandler.setCHProfiles(CHProfile(PROFILE))
                    setMinNetworkSize(200)
                    setStoreOnFlush(true)
                }
                gh.importOrLoad()
                hopper = gh
                versionFile.writeText(currentVersion)
                _state.value = RoutingState.Ready
                Log.i(TAG, "Graph ready in ${(System.currentTimeMillis() - started) / 1000}s")
            } catch (t: Throwable) {
                Log.e(TAG, "Graph init failed", t)
                _state.value = RoutingState.Failed("${t::class.simpleName}: ${t.message ?: "unknown"}")
            }
        }
    }

    suspend fun route(from: LatLng, to: LatLng): Result<RouteResult> =
        route(listOf(from, to))

    /** Supports multi-stop: points are visited in order. */
    suspend fun route(points: List<LatLng>): Result<RouteResult> =
        withContext(Dispatchers.Default) {
            val gh = hopper
                ?: return@withContext Result.failure(IllegalStateException("Engine not ready"))
            if (points.size < 2) {
                return@withContext Result.failure(IllegalArgumentException("Need >= 2 points"))
            }

            val request = GHRequest(
                points.map { com.graphhopper.util.shapes.GHPoint(it.latitude, it.longitude) }
            ).setProfile(PROFILE)

            val response = gh.route(request)
            if (response.hasErrors()) {
                return@withContext Result.failure(
                    RuntimeException(response.errors.joinToString { it.message ?: "routing error" })
                )
            }

            val best = response.best
            val tr = gh.translationMap.getWithFallBack(Locale.getDefault())
            Result.success(
                RouteResult(
                    points = best.points.toLatLngs(),
                    distanceMeters = best.distance,
                    timeMillis = best.time,
                    instructions = best.instructions.map {
                        TurnInstruction(it.getTurnDescription(tr), it.distance, it.sign)
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
