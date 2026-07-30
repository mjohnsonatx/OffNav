package com.example.offnav.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.navigation.NavState
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.routing.RouteResult
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

    val routingState = routingEngine.state
    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage = _transientMessage.asStateFlow()

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _route = MutableStateFlow<RouteResult?>(null)
    val route = _route.asStateFlow()

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
        if (!routingEngine.isReady) {
            _transientMessage.value = "Routing engine still preparing — please wait"
            return
        }
        lastDestination = to
        viewModelScope.launch {
            routingEngine.route(from, to)
                .onSuccess { _route.value = it; _transientMessage.value = null }
                .onFailure { _transientMessage.value = "Route error: ${it.message}" }
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
