package com.example.offnav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offnav.map.MapScreen
import com.example.offnav.map.MapViewModel
import org.maplibre.android.MapLibre


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this) // must be called before MapView inflation

        val container = (application as App).container

        setContent {
            val vm: MapViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MapViewModel(
                            applicationContext,
                            container.tileAssetManager,
                            container.routingEngine,
                            container.navigationEngine,
                            container.locationProvider,
                            container.historyRepository,
                            container.placeSearchRepository,
                            container.region
                        ) as T
                }
            )
            MapScreen(
                viewModel = vm,
                locationController = container.locationController,
                locationProvider = container.locationProvider,
                regionImportManager = container.regionImportManager,
                activeRegion = container.region,
            )
        }
    }
}
