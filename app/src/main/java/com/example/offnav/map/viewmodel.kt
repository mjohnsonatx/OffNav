package com.example.offnav.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.navigation.NavState
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.RoutingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(val styleJson: String) : MapUiState
    data class Error(val message: String) : MapUiState
}

class MapViewModel(
    private val tileAssetManager: TileAssetManager,
    private val routingEngine: GraphHopperEngine,
    private val navigationEngine: NavigationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _route = MutableStateFlow<RouteResult?>(null)
    val route = _route.asStateFlow()

    private val _routingStatus = MutableStateFlow("Preparing routing…")
    val routingStatus = _routingStatus.asStateFlow()
    val navState = navigationEngine.navState

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = try {
                MapUiState.Ready(tileAssetManager.buildStyleJson())
            } catch (e: Exception) {
                MapUiState.Error(e.message ?: "Failed to load map data")
            }
        }
        viewModelScope.launch {
            routingEngine.initialize()
            _routingStatus.value = when (val s = routingEngine.state) {
                is RoutingState.Ready -> "Routing ready"
                is RoutingState.Failed -> "Routing failed: ${s.message}"
                else -> "Routing unavailable"
            }
        }
        viewModelScope.launch {
            navigationEngine.navState.collect { nav ->
                when (nav) {
                    is NavState.Navigating -> _route.value = nav.route
                    is NavState.Arrived -> _route.value = null
                    else -> Unit
                }
            }
        }
    }

    private var lastDestination: LatLng? = null
    fun requestRoute(from: LatLng, to: LatLng) {
        lastDestination = to
        viewModelScope.launch {
            routingEngine.route(from, to)
                .onSuccess { _route.value = it }
                .onFailure { _routingStatus.value = "Route error: ${it.message}" }
        }
    }
    fun startNavigation() {
        val route = _route.value ?: return
        val dest = lastDestination ?: return
        navigationEngine.start(route, dest)
    }
    fun stopNavigation() {
        navigationEngine.stop()
        clearRoute()
    }

    fun clearRoute() { _route.value = null }

    override fun onCleared() = routingEngine.close()
}