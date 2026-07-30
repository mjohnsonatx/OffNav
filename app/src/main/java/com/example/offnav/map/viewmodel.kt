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

/** Identity equality: avoids comparing a multi-MB String on every recomposition. */
class StyleHolder(val json: String)

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(val style: StyleHolder) : MapUiState
    data class Error(val message: String) : MapUiState
}

/** Primitives only – computed off the composition, cheap to diff. */
data class BannerUi(val text: String, val showSpinner: Boolean, val progress: Float?)

class MapViewModel(
    private val tileAssetManager: TileAssetManager,
    private val routingEngine: GraphHopperEngine,
    private val navigationEngine: NavigationEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _transient = MutableStateFlow<String?>(null)
    private val _route = MutableStateFlow<RouteResult?>(null)
    val hasRoute: StateFlow<Boolean> =
        _route.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val activeRoute = navigationEngine.activeRoute      // collected in a LaunchedEffect, not state
    val navState = navigationEngine.navState

    val banner: StateFlow<BannerUi?> =
        combine(routingEngine.state, _transient) { routing, transient ->
            when (routing) {
                is RoutingState.InstallingGraph -> BannerUi(
                    text = routing.fraction?.let {
                        "Installing routing graph ${(it * 100).toInt()}% " +
                                "(${routing.processedBytes / 1_000_000} / ${routing.totalBytes / 1_000_000} MB)"
                    } ?: "Installing routing graph…",
                    showSpinner = true,
                    progress = routing.fraction,
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

    private var destination: LatLng? = null
    private var routeJob: Job? = null

    fun requestRoute(from: LatLng, to: LatLng) {
        if (!routingEngine.isReady) {
            _transient.value = "Routing engine still preparing — please wait"
            return
        }
        destination = to
        routeJob?.cancel()                        // supersede any in-flight request
        routeJob = viewModelScope.launch(Dispatchers.Default) {
            routingEngine.route(from, to)
                .onSuccess {
                    _route.value = it
                    _transient.value = null
                    navigationEngine.preview(it)  // builds geometry + GeoJSON off main
                }
                .onFailure { _transient.value = "Route error: ${it.message}" }
        }
    }

    fun startNavigation() {
        val route = _route.value ?: return
        val dest = destination ?: return
        navigationEngine.start(route, dest)
    }

    fun stopNavigation() {
        navigationEngine.stop()
        _route.value = null
    }
    // NOTE: do NOT close routingEngine here – it is application-scoped.
}