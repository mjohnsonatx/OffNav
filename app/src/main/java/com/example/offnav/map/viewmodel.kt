package com.example.offnav.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.channels.Channel

class StyleHolder(val json: String)

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(val style: StyleHolder) : MapUiState
    data class Error(val message: String) : MapUiState
}

data class BannerUi(val text: String, val showSpinner: Boolean, val progress: Float?)

/** One-shot camera events consumed by the map composable. */
sealed interface CameraCommand {
    data class FlyTo(val target: LatLng, val zoom: Double = 16.0, val tilt: Double = 0.0) : CameraCommand
    data object ReturnToTracking : CameraCommand
}

class MapViewModel(
    private val tileAssetManager: TileAssetManager,
    private val routingEngine: GraphHopperEngine,
    private val navigationEngine: NavigationEngine,
) : ViewModel() {

    // ── Map style ──
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    // ── Route state ──
    private val _route = MutableStateFlow<RouteResult?>(null)
    val hasRoute: StateFlow<Boolean> =
        _route.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Full instruction list for the directions panel. */
    val instructions: StateFlow<List<TurnInstruction>> =
        _route.map { it?.instructions.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Navigation ──
    val activeRoute = navigationEngine.activeRoute
    val navState = navigationEngine.navState

    // ── Camera commands (one-shot events) ──
    private val _cameraCommands = Channel<CameraCommand>(Channel.BUFFERED)
    val cameraCommands: Flow<CameraCommand> = _cameraCommands.receiveAsFlow()

    // ── Status banner ──
    private val _transient = MutableStateFlow<String?>(null)
    val banner: StateFlow<BannerUi?> =
        combine(routingEngine.state, _transient) { routing, transient ->
            when (routing) {
                is RoutingState.InstallingGraph -> BannerUi(
                    text = routing.fraction?.let {
                        "Installing routing graph ${(it * 100).toInt()}%"
                    } ?: "Installing routing graph…",
                    showSpinner = true, progress = routing.fraction,
                )
                is RoutingState.LoadingGraph ->
                    BannerUi("Loading routing graph (${routing.elapsedSeconds}s)", true, null)
                is RoutingState.Failed ->
                    BannerUi("Routing failed: ${routing.message}", false, null)
                RoutingState.NotReady ->
                    BannerUi("Routing not started", true, null)
                RoutingState.Ready ->
                    transient?.let { BannerUi(it, false, null) }
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

    // ── Route requests ──
    private var destination: LatLng? = null
    private var routeJob: Job? = null

    fun requestRoute(from: LatLng, to: LatLng) {
        if (!routingEngine.isReady) {
            _transient.value = "Routing engine still preparing — please wait"
            return
        }
        destination = to
        routeJob?.cancel()
        routeJob = viewModelScope.launch(Dispatchers.Default) {
            routingEngine.route(from, to)
                .onSuccess {
                    _route.value = it
                    _transient.value = null
                    navigationEngine.preview(it)
                }
                .onFailure { _transient.value = "Route error: ${it.message}" }
        }
    }

    // ── Navigation control ──
    fun startNavigation() {
        val route = _route.value ?: return
        val dest = destination ?: return
        navigationEngine.start(route, dest)
    }

    fun stopNavigation() {
        navigationEngine.stop()
        _route.value = null
    }

    // ── Directions list interaction ──
    fun flyToInstruction(instruction: TurnInstruction) {
        _cameraCommands.trySend(
            CameraCommand.FlyTo(LatLng(instruction.lat, instruction.lon), zoom = 17.0)
        )
    }

    fun returnToTracking() {
        _cameraCommands.trySend(CameraCommand.ReturnToTracking)
    }
}