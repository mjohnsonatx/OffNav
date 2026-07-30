package com.example.offnav.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.location.LocationProvider
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.*
import com.example.offnav.search.PlaceSearchRepository
import com.example.offnav.search.PlaceSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val locationProvider: LocationProvider,
    private val placeSearchRepository: PlaceSearchRepository,
) : ViewModel() {

    // ── Map style ──
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    // ── Route state ──
    private val _route = MutableStateFlow<RouteResult?>(null)
    val hasRoute: StateFlow<Boolean> =
        _route.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Offline Austin destination search.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    private var searchJob: Job? = null

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

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchError.value = null
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }

        val requestedQuery = query
        _searching.value = true
        searchJob = viewModelScope.launch {
            delay(180)
            try {
                _searchResults.value = withContext(Dispatchers.IO) {
                    placeSearchRepository.search(requestedQuery)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e("MapViewModel", "Offline Austin search failed", failure)
                _searchResults.value = emptyList()
                _searchError.value = "Offline Austin search is unavailable"
            } finally {
                if (_searchQuery.value == requestedQuery) _searching.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchError.value = null
        _searching.value = false
    }

    fun selectSearchResult(result: PlaceSearchResult) {
        searchJob?.cancel()
        _searchQuery.value = result.name
        _searchResults.value = emptyList()
        _searchError.value = null

        val target = LatLng(result.latitude, result.longitude)
        destination = target
        _cameraCommands.trySend(CameraCommand.FlyTo(target, zoom = 16.5))

        val fix = locationProvider.lastFix.value
        if (fix == null) {
            _transient.value = "Waiting for a GPS fix before routing to ${result.name}"
            return
        }
        requestRoute(LatLng(fix.latitude, fix.longitude), target)
    }

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
