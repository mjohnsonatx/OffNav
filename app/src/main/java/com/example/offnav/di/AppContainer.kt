package com.example.offnav.di

import android.content.Context
import com.example.offnav.map.TileAssetManager
class AppContainer(context: Context) {
    val tileAssetManager = TileAssetManager(context.applicationContext)
    // Later: routingEngine, routeRepository, locationProvider...
}