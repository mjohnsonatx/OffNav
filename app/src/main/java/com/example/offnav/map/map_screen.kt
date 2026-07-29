package com.example.offnav.map


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

@Composable
fun MapScreen(viewModel: MapViewModel) {
    val state by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is MapUiState.Loading -> CircularProgressIndicator()
            is MapUiState.Error -> Text("Map error: ${s.message}")
            is MapUiState.Ready -> MapLibreMap(styleJson = s.styleJson)
        }
    }
}

@Composable
private fun MapLibreMap(styleJson: String) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    // Wire MapView into the Compose lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                map.setStyle(
                    org.maplibre.android.maps.Style.Builder().fromJson(styleJson)
                )
                // TODO: center on the extract's bounds; hardcoded for now
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(52.52, 13.405)) // Berlin — match your extract
                    .zoom(12.0)
                    .build()
            }
        }
    )
}