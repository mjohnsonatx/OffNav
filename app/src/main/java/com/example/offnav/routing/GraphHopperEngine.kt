package com.example.offnav.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.offnav.region.GraphProfile
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
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
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import com.example.offnav.region.RegionSnapshot

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val timeMillis: Long,
    val instructions: List<TurnInstruction>
)

data class TurnInstruction(
    val text: String,
    val distanceMeters: Double,
    val sign: Int,
    val lat: Double,   // maneuver point
    val lon: Double,
)

sealed interface RoutingState {
    data object NotReady : RoutingState

    data class InstallingGraph(
        val processedBytes: Long,
        val totalBytes: Long
    ) : RoutingState {
        val fraction: Float?
            get() = totalBytes.takeIf { it > 0 }
                ?.let { (processedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    }

    data class LoadingGraph(val elapsedSeconds: Long) : RoutingState
    data object Ready : RoutingState
    data class Failed(val message: String) : RoutingState
}

class GraphHopperEngine(
    private val context: Context,
    private val region: RegionSnapshot,
) {

    companion object {
        private const val TAG = "GraphHopperEngine"
        private const val GRAPH_ASSET = "routing/region.ghz"
        private const val BUILTIN_GRAPH_DIR = "graphhopper"
        private const val GRAPH_PARTIAL_DIR = "graphhopper.partial"
        private const val LEGACY_PBF_FILE = "region.osm.pbf"
        private const val LEGACY_VERSION_FILE = "graph.profile.version"
        // delegate to the shared contract so the importer and the engine can never disagree
        internal const val PROFILE = GraphProfile.PROFILE
        private const val GRAPH_VERSION_ENTRY = GraphProfile.VERSION_ENTRY
        private const val ENCODED_VALUES = GraphProfile.ENCODED_VALUES
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val COPY_PROGRESS_STEP = 8L * 1024L * 1024L
    }

    private fun carProfile(): Profile = GraphProfile.carProfile()

    private var hopper: GraphHopper? = null
    private val initMutex = Mutex()

    private val _state = MutableStateFlow<RoutingState>(RoutingState.NotReady)
    val state = _state.asStateFlow()

    /** Low-priority single thread: extraction + MMAP load never outrank the render thread. */
    private val initDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gh-init").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    /** Serialised routing: one request at a time, leaves cores for the UI. */
    private val routeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gh-route").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    val isReady: Boolean get() = hopper != null

    /** The routing profile: a custom model over car encoded values. */
//    private fun carProfile(): Profile = Profile(PROFILE).apply {
//        setCustomModel(
//            CustomModel().apply {
//                addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
//                addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
//                addToPriority(
//                    Statement.ElseIf("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1")
//                )
//                addToPriority(Statement.If("road_class == TRACK", Statement.Op.MULTIPLY, "0.5"))
//                addToPriority(Statement.If("road_environment == FERRY", Statement.Op.MULTIPLY, "0.5"))
//                distanceInfluence = 70.0
//            }
//        )
//        // Optional: enable to honor turn restrictions (slower import, better routes)
//        // setTurnCostsConfig(TurnCostsConfig.car())
//    }

    suspend fun initialize() = withContext(initDispatcher) {
        initMutex.withLock {
            if (hopper != null) return@withLock
            var graphHopper: GraphHopper? = null
            try {
                val profile = carProfile()
                val currentVersion = GraphProfile.expectedVersion()
                val started = SystemClock.elapsedRealtime()
                deleteLegacyRoutingFiles()
                // The active snapshot decides where the graph lives. It is never rewritten.
                val graphDir = when (region) {
                    is RegionSnapshot.Installed -> region.graphDir.also {
                        check(it.isDirectory) { "Active region is missing its routing graph" }
                        check(GraphProfile.installedVersion(it) == currentVersion) {
                            "Active region's routing graph is incompatible with this app version"
                        }
                    }
                    RegionSnapshot.BuiltIn -> File(context.filesDir, BUILTIN_GRAPH_DIR).also {
                        ensureGraphInstalled(it, currentVersion)
                    }
                }

                publishLoadStage("Loading memory-mapped routing graph", started)

                val config = GraphHopperConfig().apply {
                    putObject("graph.location", graphDir.absolutePath)
                    putObject("graph.dataaccess.default_type", "MMAP")
                    putObject("graph.encoded_values", ENCODED_VALUES)
                    putObject("prepare.min_network_size", 200)
                    putObject("import.osm.ignored_highways", "")
                    setProfiles(listOf(profile))
                    setCHProfiles(listOf(CHProfile(PROFILE)))
                }
                val gh = AndroidGraphHopper { stage -> publishLoadStage(stage, started) }.apply {
                    init(config)
                    setAllowWrites(false)
                }
                graphHopper = gh
                loadWithTimer(gh, started)
                hopper = gh
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
        withContext(routeDispatcher) {
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
                        TurnInstruction(
                            text = it.getTurnDescription(tr),
                            distanceMeters = it.distance,
                            sign = it.sign,
                            lat = it.points.getLat(0),
                            lon = it.points.getLon(0),
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

    private suspend fun loadWithTimer(
        graphHopper: GraphHopper,
        started: Long
    ) = coroutineScope {
        val timer = launch {
            var lastLoggedElapsed = 0L
            while (isActive) {
                delay(1_000)
                val elapsed = elapsedSeconds(started)
                _state.value = when (val current = _state.value) {
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
            check(graphHopper.load()) { "Prebuilt routing graph could not be loaded" }
        } finally {
            timer.cancelAndJoin()
        }
    }

    private fun publishLoadStage(stage: String, started: Long) {
        _state.value = RoutingState.LoadingGraph(elapsedSeconds(started))
        Log.i(TAG, stage)
    }

    private fun elapsedSeconds(started: Long): Long =
        (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1_000L

    private fun progressDescription(state: RoutingState): String = when (state) {
        is RoutingState.InstallingGraph -> "Installing prebuilt routing graph"
        is RoutingState.LoadingGraph -> "Loading memory-mapped routing graph"
        RoutingState.NotReady -> "Routing not started"
        RoutingState.Ready -> "Routing ready"
        is RoutingState.Failed -> "Routing failed"
    }

    private fun deleteLegacyRoutingFiles() {
        for (name in listOf(LEGACY_PBF_FILE, "$LEGACY_PBF_FILE.partial", LEGACY_VERSION_FILE)) {
            val file = File(context.filesDir, name)
            if (file.exists() && file.delete()) {
                Log.i(TAG, "Removed legacy routing file: $name")
            }
        }
    }

    private fun ensureGraphInstalled(graphDir: File, currentVersion: String) {
        val installedVersion = File(graphDir, GRAPH_VERSION_ENTRY)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
        if (installedVersion == currentVersion) return

        if (graphDir.exists()) {
            Log.w(TAG, "Replacing incompatible or incomplete routing graph ($installedVersion)")
            check(graphDir.deleteRecursively()) { "Could not remove incompatible routing graph" }
        }

        val partialDir = File(context.filesDir, GRAPH_PARTIAL_DIR)
        if (partialDir.exists()) {
            check(partialDir.deleteRecursively()) { "Could not clear partial routing graph" }
        }
        check(partialDir.mkdirs()) { "Could not create routing graph staging directory" }

        val assetLength = runCatching {
            context.assets.openFd(GRAPH_ASSET).use { it.length }
        }.getOrDefault(-1L)
        Log.i(TAG, "Installing prebuilt routing graph ($assetLength bytes)")
        var nextProgressUpdate = 0L
        try {
            val rootPath = partialDir.canonicalPath + File.separator
            context.assets.open(GRAPH_ASSET).use { assetInput ->
                val countingInput = CountingInputStream(assetInput)
                ZipInputStream(countingInput.buffered(COPY_BUFFER_SIZE)).use { zip ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val outputFile = File(partialDir, entry.name)
                        check(outputFile.canonicalPath.startsWith(rootPath)) {
                            "Unsafe routing graph archive entry: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            check(outputFile.isDirectory || outputFile.mkdirs()) {
                                "Could not create routing graph directory: ${entry.name}"
                            }
                        } else {
                            val parent = outputFile.parentFile
                            check(parent == null || parent.isDirectory || parent.mkdirs()) {
                                "Could not create routing graph parent directory: ${entry.name}"
                            }
                            outputFile.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    if (countingInput.bytesRead >= nextProgressUpdate) {
                                        _state.value = RoutingState.InstallingGraph(
                                            countingInput.bytesRead,
                                            assetLength
                                        )
                                        nextProgressUpdate = countingInput.bytesRead + COPY_PROGRESS_STEP
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            _state.value = RoutingState.InstallingGraph(assetLength, assetLength)
            val extractedVersion = File(partialDir, GRAPH_VERSION_ENTRY)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
            check(extractedVersion == currentVersion) {
                "Routing graph version mismatch: expected $currentVersion, found $extractedVersion"
            }
            check(partialDir.renameTo(graphDir)) { "Could not finalize routing graph installation" }
        } catch (t: Throwable) {
            partialDir.deleteRecursively()
            throw t
        }
        Log.i(TAG, "Prebuilt routing graph installed")
    }

    private fun PointList.toLatLngs(): List<LatLng> =
        (0 until size()).map { LatLng(getLat(it), getLon(it)) }

    private class AndroidGraphHopper(
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

        override fun postProcessing(closeEarly: Boolean) {
            onStage("Loading routing indexes and shortcuts")
            super.postProcessing(closeEarly)
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
}
