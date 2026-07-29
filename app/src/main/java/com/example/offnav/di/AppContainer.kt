package com.example.offnav.di

import android.content.Context
import com.example.offnav.location.LocationController
import com.example.offnav.map.TileAssetManager
class AppContainer(context: Context) {
    val tileAssetManager = TileAssetManager(context.applicationContext)
    val locationController = LocationController()
    val routingEngine = GraphHopperEngine(context.applicationContext) // Part 3
}