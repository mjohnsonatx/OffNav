package com.example.offnav.di

import android.content.Context
import com.example.offnav.data.OffNavDatabase
import com.example.offnav.data.RouteHistoryRepository
import com.example.offnav.location.LocationController
import com.example.offnav.location.LocationProvider
import com.example.offnav.map.TileAssetManager
import com.example.offnav.navigation.NavigationEngine
import com.example.offnav.region.RegionBootstrap
import com.example.offnav.region.RegionCatalog
import com.example.offnav.region.RegionImportManager
import com.example.offnav.region.RegionSnapshot
import com.example.offnav.region.RegionStore
import com.example.offnav.routing.GraphHopperEngine
import com.example.offnav.search.PlaceSearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val regionStore = RegionStore(context)

    /**
     * ONE immutable region identity, resolved once from the cold-start pointer value.
     * Every region-bound component below is constructed against this snapshot and nothing else.
     */
    val region: RegionSnapshot = RegionBootstrap(regionStore).resolve()

    val regionCatalog = RegionCatalog(context.applicationContext, regionStore, region, appScope)
    val regionImportManager = RegionImportManager(context.applicationContext, regionStore, appScope)

    private val database = OffNavDatabase.build(context)
    val historyRepository = RouteHistoryRepository(database.routeHistoryDao())

    val tileAssetManager = TileAssetManager(context.applicationContext, region)
    val locationController = LocationController()
    val locationProvider = LocationProvider(context.applicationContext, appScope)
    val routingEngine = GraphHopperEngine(context.applicationContext, region)
    val placeSearchRepository = PlaceSearchRepository(context.applicationContext, region)
    val navigationEngine = NavigationEngine(locationProvider, routingEngine, appScope)
}
