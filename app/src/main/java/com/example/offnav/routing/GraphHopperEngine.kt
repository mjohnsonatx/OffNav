package com.example.offnav.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadEnvironment
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Parameters
import com.graphhopper.util.PointList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    data class CopyingPbf(
        val copiedBytes: Long,
        val totalBytes: Long
    ) : RoutingState {
        val fraction: Float?
            get() = totalBytes.takeIf { it > 0 }
                ?.let { (copiedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    }
    data class ImportingGraph(
        val stage: String,
        val elapsedSeconds: Long
    ) : RoutingState
    data class LoadingGraph(val elapsedSeconds: Long) : RoutingState
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
        private const val GRAPH_CONFIG_VERSION = 3
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val COPY_PROGRESS_STEP = 8L * 1024L * 1024L
        private const val ENCODED_VALUES =
            "car_access,road_access,road_class,road_environment,car_average_speed"
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
            var graphHopper: GraphHopper? = null
            try {
                val profile = carProfile()
                val graphDir = File(context.filesDir, GRAPH_DIR)
                val versionFile = File(context.filesDir, VERSION_FILE)
                val currentVersion =
                    "$GRAPH_CONFIG_VERSION:${profile.version}:$ENCODED_VALUES"

                // If the profile definition changed since the graph was built,
                // the stored graph is incompatible — discard it.
                val storedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim()
                if (graphDir.exists() && storedVersion != currentVersion) {
                    Log.w(TAG, "Profile changed ($storedVersion -> $currentVersion); rebuilding graph")
                    graphDir.deleteRecursively()
                }

                val needsImport = !graphDir.exists() || graphDir.listFiles().isNullOrEmpty()
                val started = SystemClock.elapsedRealtime()
                if (needsImport) {
                    publishImportStage("Preparing routing graph", started)
                } else {
                    _state.value = RoutingState.LoadingGraph(elapsedSeconds = 0)
                    Log.i(TAG, "Loading saved routing graph")
                }

                val pbf = ensurePbfOnDisk()
                if (_state.value is RoutingState.CopyingPbf) {
                    if (needsImport) {
                        publishImportStage("Preparing routing graph", started)
                    } else {
                        _state.value = RoutingState.LoadingGraph(elapsedSeconds = elapsedSeconds(started))
                    }
                }
                Log.i(TAG, "PBF ${pbf.length() / 1_000_000} MB, needsImport=$needsImport")

                val gh = ProgressGraphHopper { stage ->
                    publishImportStage(stage, started)
                }.apply {
                    graphHopperLocation = graphDir.absolutePath
                    osmFile = pbf.absolutePath
                    setEncodedValuesString(ENCODED_VALUES)
                    setProfiles(profile)
                    chPreparationHandler.setCHProfiles(CHProfile(PROFILE))
                    setMinNetworkSize(200)
                    setStoreOnFlush(true)
                }
                graphHopper = gh
                importOrLoadWithTimer(gh, started)
                hopper = gh
                versionFile.writeText(currentVersion)
                _state.value = RoutingState.Ready
                Log.i(TAG, "Graph ready in ${elapsedSeconds(started)}s")
            } catch (t: Throwable) {
                runCatching { graphHopper?.close() }
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

    private suspend fun importOrLoadWithTimer(
        graphHopper: GraphHopper,
        started: Long
    ) = coroutineScope {
        val timer = launch {
            var lastLoggedElapsed = 0L
            while (isActive) {
                delay(1_000)
                val elapsed = elapsedSeconds(started)
                _state.value = when (val current = _state.value) {
                    is RoutingState.ImportingGraph -> current.copy(elapsedSeconds = elapsed)
                    is RoutingState.LoadingGraph -> current.copy(elapsedSeconds = elapsed)
                    else -> current
                }
                if (elapsed >= lastLoggedElapsed + 30) {
                    lastLoggedElapsed = elapsed
                    Log.i(TAG, "${progressDescription(_state.value)} (${elapsed}s elapsed)")
                }
            }
        }
        try {
            graphHopper.importOrLoad()
        } finally {
            timer.cancelAndJoin()
        }
    }

    private fun publishImportStage(stage: String, started: Long) {
        _state.value = RoutingState.ImportingGraph(stage, elapsedSeconds(started))
        Log.i(TAG, stage)
    }

    private fun elapsedSeconds(started: Long): Long =
        (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1_000L

    private fun progressDescription(state: RoutingState): String = when (state) {
        is RoutingState.CopyingPbf -> "Copying routing data"
        is RoutingState.ImportingGraph -> state.stage
        is RoutingState.LoadingGraph -> "Loading saved routing graph"
        RoutingState.NotReady -> "Routing not started"
        RoutingState.Ready -> "Routing ready"
        is RoutingState.Failed -> "Routing failed"
    }

    private fun ensurePbfOnDisk(): File {
        val dest = File(context.filesDir, PBF_FILE)
        val assetLength = runCatching {
            context.assets.openFd(PBF_ASSET).use { it.length }
        }.getOrDefault(-1L)

        if (dest.exists() && (assetLength <= 0L || dest.length() == assetLength)) {
            return dest
        }
        if (dest.exists()) {
            Log.w(TAG, "Discarding incomplete PBF copy (${dest.length()} of $assetLength bytes)")
            check(dest.delete()) { "Could not replace incomplete routing PBF" }
        }

        val partial = File(context.filesDir, "$PBF_FILE.partial")
        if (partial.exists()) check(partial.delete()) { "Could not clear partial routing PBF" }

        Log.i(TAG, "Copying routing PBF into app storage ($assetLength bytes)")
        var copiedBytes = 0L
        var nextProgressUpdate = 0L
        try {
            context.assets.open(PBF_ASSET).use { input ->
                partial.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        if (copiedBytes >= nextProgressUpdate) {
                            _state.value = RoutingState.CopyingPbf(copiedBytes, assetLength)
                            nextProgressUpdate = copiedBytes + COPY_PROGRESS_STEP
                        }
                    }
                }
            }
            _state.value = RoutingState.CopyingPbf(copiedBytes, assetLength)
            check(assetLength <= 0L || copiedBytes == assetLength) {
                "Routing PBF copy was incomplete: $copiedBytes of $assetLength bytes"
            }
            check(partial.renameTo(dest)) { "Could not finalize routing PBF copy" }
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
        Log.i(TAG, "Routing PBF copy complete (${dest.length()} bytes)")
        return dest
    }

    private fun PointList.toLatLngs(): List<LatLng> =
        (0 until size()).map { LatLng(getLat(it), getLon(it)) }

    private class ProgressGraphHopper(
        private val onStage: (String) -> Unit
    ) : GraphHopper() {
        /**
         * GraphHopper normally compiles custom-model expressions with Janino at runtime.
         * Android cannot load the generated JVM class files, so this factory implements
         * the car profile's small fixed rule set directly using the same CustomWeighting.
         */
        override fun createWeightingFactory(): WeightingFactory {
            val carAccess = encodingManager.getBooleanEncodedValue("car_access")
            val carAverageSpeed = encodingManager.getDecimalEncodedValue("car_average_speed")
            val roadAccess = encodingManager.getEnumEncodedValue("road_access", RoadAccess::class.java)
            val roadClass = encodingManager.getEnumEncodedValue("road_class", RoadClass::class.java)
            val roadEnvironment =
                encodingManager.getEnumEncodedValue("road_environment", RoadEnvironment::class.java)

            val parameters = CustomWeighting.Parameters(
                { edge, reverse ->
                    val accessible = if (reverse) {
                        edge.getReverse(carAccess)
                    } else {
                        edge.get(carAccess)
                    }
                    if (!accessible) {
                        0.0
                    } else if (reverse) {
                        edge.getReverse(carAverageSpeed)
                    } else {
                        edge.get(carAverageSpeed)
                    }
                },
                { carAverageSpeed.maxStorableDecimal },
                { edge, reverse ->
                    val accessible = if (reverse) {
                        edge.getReverse(carAccess)
                    } else {
                        edge.get(carAccess)
                    }
                    if (!accessible) {
                        0.0
                    } else {
                        var priority = 1.0
                        if (edge.get(roadAccess) == RoadAccess.DESTINATION) priority *= 0.1
                        if (edge.get(roadClass) == RoadClass.TRACK) priority *= 0.5
                        if (edge.get(roadEnvironment) == RoadEnvironment.FERRY) priority *= 0.5
                        priority
                    }
                },
                { 1.0 },
                70.0,
                Parameters.Routing.DEFAULT_HEADING_PENALTY
            )

            return WeightingFactory { profile, _, _ ->
                require(profile.name == PROFILE) { "Unsupported routing profile: ${profile.name}" }
                CustomWeighting(TurnCostProvider.NO_TURN_COST_PROVIDER, parameters)
            }
        }

        override fun importOSM() {
            onStage("Reading OpenStreetMap roads")
            super.importOSM()
        }

        override fun postImportOSM() {
            onStage("Finalizing imported roads")
            super.postImportOSM()
        }

        override fun cleanUp() {
            onStage("Removing disconnected road networks")
            super.cleanUp()
        }

        override fun postProcessing(closeEarly: Boolean) {
            onStage("Building routing indexes and shortcuts")
            super.postProcessing(closeEarly)
        }

        override fun flush() {
            onStage("Saving routing graph")
            super.flush()
        }
    }
}
