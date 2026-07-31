package com.example.offnav.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.data.RouteHistoryEntry
import com.example.offnav.data.RouteHistoryRepository
import com.example.offnav.data.formatDuration
import com.example.offnav.data.formatMeters
import com.example.offnav.location.LocationProvider
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.RoutingState
import com.example.offnav.routing.TurnInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

class MapViewModel(
    private val tileAssetManager: TileAssetManager,
    private val routingEngine: GraphHopperEngine,
    private val navigationEngine: NavigationEngine,
    private val locationProvider: LocationProvider,
    private val historyRepository: RouteHistoryRepository,
) : ViewModel() {

    // ── Map style ──
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    // ── Current route ──
    private val _route = MutableStateFlow<RouteResult?>(null)
    private val _destinationLabel = MutableStateFlow(PlaceLabelUi("", ""))

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

    // ── History ──
    private val _historyQuery = MutableStateFlow("")
    val historyQuery = _historyQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<RouteHistoryEntry>> =
        _historyQuery
            .debounce(180.milliseconds)                       // don't hit the DB on every keystroke
            .distinctUntilChanged()
            .flatMapLatest { historyRepository.observe(it) }   // cancels superseded queries
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onHistoryQueryChange(q: String) { _historyQuery.value = q }

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
    }

    // ── Routing ──
    private var destination: LatLng? = null
    private var routeJob: Job? = null

    fun requestRoute(from: LatLng, to: LatLng, label: String = "", subtitle: String = "") {
        if (!routingEngine.isReady) {
            _transient.value = "Routing engine still preparing — please wait"
            return
        }
        destination = to
        _destinationLabel.value = PlaceLabelUi(label, subtitle)
        routeJob?.cancel()
        routeJob = viewModelScope.launch(Dispatchers.Default) {
            routingEngine.route(from, to)
                .onSuccess { result ->
                    _route.value = result
                    _transient.value = null
                    navigationEngine.preview(result)
                    _cameraCommands.trySend(CameraCommand.FitBounds(result.points))
                    historyRepository.record(from, to, label, subtitle, result)
                }
                .onFailure { _transient.value = "Route error: ${it.message}" }
        }
    }

    /** Re-route to a saved destination from the current position. */
    fun routeToHistory(entry: RouteHistoryEntry) {
        val fix = locationProvider.lastFix.value
        if (fix == null) {
            _transient.value = "Waiting for a GPS fix…"
            return
        }
        requestRoute(
            from = LatLng(fix.latitude, fix.longitude),
            to = entry.destination,
            label = entry.label,
            subtitle = entry.subtitle,
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
}