package com.example.offnav.di

import android.content.Context
import com.example.offnav.data.OffNavDatabase
import com.example.offnav.data.RouteHistoryRepository
import com.example.offnav.location.LocationController
import com.example.offnav.location.LocationProvider
import com.example.offnav.map.TileAssetManager
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.search.PlaceSearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = OffNavDatabase.build(context)
    val historyRepository = RouteHistoryRepository(database.routeHistoryDao())
    val tileAssetManager = TileAssetManager(context.applicationContext)
    val locationController = LocationController()
    val locationProvider = LocationProvider(context.applicationContext, appScope)
    val routingEngine = GraphHopperEngine(context.applicationContext)
    val navigationEngine = NavigationEngine(locationProvider, routingEngine, appScope)
}