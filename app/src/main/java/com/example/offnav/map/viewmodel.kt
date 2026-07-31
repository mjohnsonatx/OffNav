package com.example.offnav.map

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.data.RouteHistoryEntry
import com.example.offnav.data.RouteHistoryRepository
import com.example.offnav.data.formatDuration
import com.example.offnav.data.formatMeters
import com.example.offnav.export.DirectionsPdfExporter
import com.example.offnav.location.LocationProvider
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.navigation.TripPlanner
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.RoutingState
import com.example.offnav.routing.TurnInstruction
import com.example.offnav.search.PlaceCategory
import com.example.offnav.search.PlaceSearchRepository
import com.example.offnav.search.PlaceSearchResult
import com.example.offnav.service.NavigationForegroundService
import com.example.offnav.sharing.LocationSharer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class StyleHolder(val json: String)

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(val style: StyleHolder) : MapUiState
    data class Error(val message: String) : MapUiState
}

data class BannerUi(val text: String, val showSpinner: Boolean, val progress: Float?)

/** Preview card shown before navigation starts. Primitives only. */
data class RouteSummary(
    val destinationLabel: String,
    val destinationSubtitle: String,
    val distanceText: String,
    val durationText: String,
    val arrivalText: String,
    val stepCount: Int,
)

sealed interface CameraCommand {
    data class FlyTo(val target: LatLng, val zoom: Double = 16.0, val tilt: Double = 0.0) : CameraCommand
    data class FitBounds(val points: List<LatLng>) : CameraCommand
    data object ReturnToTracking : CameraCommand
}

@OptIn(FlowPreview::class)
class MapViewModel(
    private val appContext: Context,
    private val tileAssetManager: TileAssetManager,
    private val routingEngine: GraphHopperEngine,
    private val navigationEngine: NavigationEngine,
    private val locationProvider: LocationProvider,
    private val historyRepository: RouteHistoryRepository,
    private val placeSearchRepository: PlaceSearchRepository,
) : ViewModel() {

    // ── Map style ──
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    // ── Current route ──
    private val _route = MutableStateFlow<RouteResult?>(null)
    private val _destinationLabel = MutableStateFlow(PlaceLabelUi("", ""))

    val tripPlanner = TripPlanner()
    val stops = tripPlanner.stops
    /** Debounced route recomputation when stops change. */
    private var recomputeJob: Job? = null

    val hasRoute: StateFlow<Boolean> =
        _route.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val instructions: StateFlow<List<TurnInstruction>> =
        _route.map { it?.instructions.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Distance / duration / ETA card for the previewed route. */
    val routeSummary: StateFlow<RouteSummary?> =
        combine(_route, _destinationLabel) { route, label ->
            route?.let {
                RouteSummary(
                    destinationLabel = label.label.ifBlank { "Destination" },
                    destinationSubtitle = label.subtitle,
                    distanceText = formatMeters(it.distanceMeters),
                    durationText = formatDuration(it.timeMillis / 1000),
                    arrivalText = clockAfter(it.timeMillis),
                    stepCount = it.instructions.size,
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Navigation ──
    val activeRoute = navigationEngine.activeRoute
    val navState = navigationEngine.navState

    // ── Unified destination search: Room history + offline Austin place index ──
    private val _destinationQuery = MutableStateFlow("")
    val destinationQuery = _destinationQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<RouteHistoryEntry>> =
        _destinationQuery
            .debounce(180.milliseconds)                       // don't hit the DB on every keystroke
            .distinctUntilChanged()
            .flatMapLatest { historyRepository.observe(it) }   // cancels superseded queries
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _placeResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val placeResults = _placeResults.asStateFlow()
    private val _placeSearching = MutableStateFlow(false)
    val placeSearching = _placeSearching.asStateFlow()
    private val _placeSearchError = MutableStateFlow<String?>(null)
    val placeSearchError = _placeSearchError.asStateFlow()
    private var placeSearchJob: Job? = null

    // Nearby-search state must be initialized before init launches collectors.
    // Kotlin executes property initializers and init blocks in source order.
    private val _nearbyQuery = MutableStateFlow("")
    val nearbyQuery = _nearbyQuery.asStateFlow()
    private val _selectedCategories = MutableStateFlow<Set<PlaceCategory>>(emptySet())
    val selectedCategories = _selectedCategories.asStateFlow()
    private val _nearbyResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val nearbyResults = _nearbyResults.asStateFlow()
    private val _nearbySearching = MutableStateFlow(false)
    val nearbySearching = _nearbySearching.asStateFlow()
    private var nearbyJob: Job? = null

    fun onDestinationQueryChange(query: String) {
        _destinationQuery.value = query
        _placeSearchError.value = null
        _placeResults.value = emptyList()
        placeSearchJob?.cancel()

        val requestedText = query
        val requestedQuery = requestedText.trim()
        if (requestedQuery.length < 2) {
            _placeSearching.value = false
            return
        }

        _placeSearching.value = true
        placeSearchJob = viewModelScope.launch {
            delay(180.milliseconds)
            try {
                _placeResults.value = withContext(Dispatchers.IO) {
                    placeSearchRepository.search(requestedQuery)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e("MapViewModel", "Offline Austin place search failed", failure)
                _placeResults.value = emptyList()
                _placeSearchError.value = "Offline Austin search is unavailable"
            } finally {
                if (_destinationQuery.value == requestedText) {
                    _placeSearching.value = false
                }
            }
        }
    }

    fun clearDestinationQuery() = onDestinationQueryChange("")

    fun deleteHistory(id: Long) = viewModelScope.launch { historyRepository.delete(id) }
    fun togglePin(id: Long, pinned: Boolean) = viewModelScope.launch { historyRepository.setPinned(id, pinned) }
    fun clearHistory() = viewModelScope.launch { historyRepository.clearUnpinned() }

    // ── Camera ──
    private val _cameraCommands = Channel<CameraCommand>(Channel.BUFFERED)
    val cameraCommands: Flow<CameraCommand> = _cameraCommands.receiveAsFlow()

    // ── Status banner ──
    private val _transient = MutableStateFlow<String?>(null)
    val banner: StateFlow<BannerUi?> =
        combine(routingEngine.state, _transient) { routing, transient ->
            when (routing) {
                is RoutingState.InstallingGraph -> BannerUi(
                    routing.fraction?.let { "Installing routing graph ${(it * 100).toInt()}%" }
                        ?: "Installing routing graph…", true, routing.fraction
                )
                is RoutingState.LoadingGraph ->
                    BannerUi("Loading routing graph (${routing.elapsedSeconds}s)", true, null)
                is RoutingState.Failed -> BannerUi("Routing failed: ${routing.message}", false, null)
                RoutingState.NotReady -> BannerUi("Routing not started", true, null)
                RoutingState.Ready -> transient?.let { BannerUi(it, false, null) }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = try {
                MapUiState.Ready(StyleHolder(tileAssetManager.buildStyleJson()))
            } catch (e: Exception) {
                MapUiState.Error(e.message ?: "Failed to load map data")
            }
        }
        viewModelScope.launch { routingEngine.initialize() }

        viewModelScope.launch {
            tripPlanner.stops
                .drop(1)  // skip initial empty
                .debounce(400.milliseconds)
                .distinctUntilChanged()
                .collect { recomputeRoute() }
        }

        viewModelScope.launch {
            _selectedCategories.collect { runNearbySearch() }
        }
    }

    /** Recompute the full multi-point route from current GPS through all stops. */
    private fun recomputeRoute() {
        if (!routingEngine.isReady) return
        val fix = locationProvider.lastFix.value ?: return
        val stopPoints = tripPlanner.routePoints
        if (stopPoints.isEmpty()) {
            clearRoute()
            return
        }
        val allPoints = listOf(LatLng(fix.latitude, fix.longitude)) + stopPoints
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch(Dispatchers.Default) {
            routingEngine.route(allPoints)
                .onSuccess { result ->
                    _route.value = result
                    _transient.value = null
                    navigationEngine.preview(result)
                    _cameraCommands.trySend(CameraCommand.FitBounds(result.points))
                    // Record destination in history
                    tripPlanner.destination?.let { dest ->
                        historyRepository.record(
                            allPoints.first(), dest.point,
                            dest.label, dest.subtitle, result
                        )
                    }
                }
                .onFailure { _transient.value = "Route error: ${it.message}" }
        }
    }

    /** Add a stop to the current trip. */
    fun addStop(label: String, subtitle: String, point: LatLng) {
        if (tripPlanner.isEmpty) {
            // No destination yet — treat as destination
            requestRoute(
                LatLng(locationProvider.lastFix.value?.latitude ?: return,
                    locationProvider.lastFix.value?.longitude ?: return),
                point, label, subtitle
            )
            return
        }
        tripPlanner.addWaypoint(label, subtitle, point)
        // recompute triggered by flow
    }

    fun removeStop(id: Long) {
        tripPlanner.removeStop(id)
        if (tripPlanner.isEmpty) clearRoute()
    }
    fun moveStop(from: Int, to: Int) {
        tripPlanner.moveStop(from, to)
    }

    // ── Update requestRoute to go through TripPlanner ──
    fun requestRoute(from: LatLng, to: LatLng, label: String = "", subtitle: String = "") {
        if (!routingEngine.isReady) {
            _transient.value = "Routing engine still preparing — please wait"
            return
        }
        destination = to
        _destinationLabel.value = PlaceLabelUi(label, subtitle)
        // Set destination in the planner (clears previous waypoints)
        tripPlanner.clear()
        tripPlanner.setDestination(label, subtitle, to)
        // Route recomputation is triggered by the stops flow observer
    }

    // ── Routing ──
    private var destination: LatLng? = null
    private var routeJob: Job? = null

    fun routeToHistory(entry: RouteHistoryEntry) {
        clearDestinationQuery()
        tripPlanner.clear()
        tripPlanner.setDestination(entry.label, entry.subtitle, entry.destination)
        _destinationLabel.value = PlaceLabelUi(entry.label, entry.subtitle)
        destination = entry.destination
    }
    fun routeToPlace(result: PlaceSearchResult) {
        clearDestinationQuery()
        val target = LatLng(result.latitude, result.longitude)
        tripPlanner.clear()
        tripPlanner.setDestination(result.name, result.subtitle, target)
        _destinationLabel.value = PlaceLabelUi(result.name, result.subtitle)
        destination = target
    }

    private fun routeFromCurrent(target: LatLng, label: String, subtitle: String) {
        val fix = locationProvider.lastFix.value
        if (fix == null) {
            _transient.value = "Waiting for a GPS fix…"
            return
        }
        requestRoute(
            from = LatLng(fix.latitude, fix.longitude),
            to = target,
            label = label,
            subtitle = subtitle,
        )
    }

    fun clearRoute() {
        routeJob?.cancel()
        _route.value = null
        _destinationLabel.value = PlaceLabelUi("", "")
        navigationEngine.preview(null)
    }

    // ── Navigation control ──
    fun startNavigation() {
        val route = _route.value ?: return
        val dest = destination ?: return
        navigationEngine.start(route, dest)
        _cameraCommands.trySend(CameraCommand.ReturnToTracking)
    }

    fun stopNavigation() {
        navigationEngine.stop()
        clearRoute()
    }

    fun flyToInstruction(instruction: TurnInstruction) {
        _cameraCommands.trySend(CameraCommand.FlyTo(LatLng(instruction.lat, instruction.lon), 17.0))
    }

    fun returnToTracking() = _cameraCommands.trySend(CameraCommand.ReturnToTracking)

    data class PlaceLabelUi(val label: String, val subtitle: String)

    private fun clockAfter(millisFromNow: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + millisFromNow }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    // ═══════════════════════════════════════════════════════════
    // NEW: Nearby search with categories
    // ═══════════════════════════════════════════════════════════
    fun onNearbyQueryChange(query: String) {
        _nearbyQuery.value = query
        runNearbySearch()
    }
    fun toggleCategory(category: PlaceCategory) {
        _selectedCategories.value = _selectedCategories.value.let { current ->
            if (category in current) current - category else current + category
        }
        runNearbySearch()
    }
    fun clearNearbySearch() {
        _nearbyQuery.value = ""
        _selectedCategories.value = emptySet()
        _nearbyResults.value = emptyList()
    }
    private fun runNearbySearch() {
        nearbyJob?.cancel()
        val query = _nearbyQuery.value.trim()
        val cats = _selectedCategories.value
        val fix = locationProvider.lastFix.value
        if (fix == null) {
            _nearbyResults.value = emptyList()
            return
        }
        // If no query and no categories, show nothing (or could show popular nearby)
        if (query.isEmpty() && cats.isEmpty()) {
            _nearbyResults.value = emptyList()
            _nearbySearching.value = false
            return
        }
        _nearbySearching.value = true
        nearbyJob = viewModelScope.launch {
            delay(200.milliseconds)   // debounce
            try {
                val center = LatLng(fix.latitude, fix.longitude)
                val results = withContext(Dispatchers.IO) {
                    placeSearchRepository.searchNearby(
                        center = center,
                        radiusMeters = 10_000.0,
                        query = query,
                        categories = cats,
                        limit = 50,
                    )
                }
                _nearbyResults.value = results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MapViewModel", "Nearby search failed", e)
                _nearbyResults.value = emptyList()
            } finally {
                _nearbySearching.value = false
            }
        }
    }

    private val pdfExporter = DirectionsPdfExporter(appContext)

    fun shareLocation(context: Context) {
        val fix = locationProvider.lastFix.value ?: return
        LocationSharer.shareCurrentLocation(context, fix)
    }

    fun shareRoute(context: Context) {
        val fix = locationProvider.lastFix.value ?: return
        val summary = routeSummary.value ?: return
        val dest = destination ?: return
        LocationSharer.shareRoute(
            context, fix,
            summary.destinationLabel,
            dest.latitude, dest.longitude,
            summary.distanceText, summary.durationText,
        )
    }

    private val _pdfExporting = MutableStateFlow(false)
    val pdfExporting = _pdfExporting.asStateFlow()

    fun exportDirectionsPdf(context: Context) {
        val route = _route.value ?: return
        val label = _destinationLabel.value
        _pdfExporting.value = true
        viewModelScope.launch {
            try {
                val uri = pdfExporter.export(route, label.label, label.subtitle)
                // Open share/view chooser
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Directions PDF"))
                }
            } catch (e: Exception) {
                _transient.value = "PDF export failed: ${e.message}"
            } finally {
                _pdfExporting.value = false
            }
        }
    }

    // Modify startNavigation to also launch foreground service:
    fun startNavigation(context: Context) {
        val route = _route.value ?: return
        val dest = destination ?: return
        navigationEngine.start(route, dest)
        _cameraCommands.trySend(CameraCommand.ReturnToTracking)
        NavigationForegroundService.start(context)
    }

    // Modify stopNavigation to also stop the service:
    fun stopNavigation(context: Context) {
        navigationEngine.stop()
        clearRoute()
        NavigationForegroundService.stop(context)
    }

}
