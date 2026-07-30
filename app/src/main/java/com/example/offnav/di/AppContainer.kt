package com.example.offnav.di

import android.content.Context
import com.example.offnav.location.LocationController
import com.example.offnav.location.LocationProvider
import com.example.offnav.map.TileAssetManager
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.GraphHopperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    // Navigation keeps running across Activity recreation; cancel only on process death.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tileAssetManager = TileAssetManager(context.applicationContext)
    val locationController = LocationController()
    val locationProvider = LocationProvider(context.applicationContext, appScope)
    val routingEngine = GraphHopperEngine(context.applicationContext)
    val navigationEngine = NavigationEngine(locationProvider, routingEngine, appScope)
}