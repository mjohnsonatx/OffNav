package com.example.offnav.location

import android.annotation.SuppressLint
import android.content.Context
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

class LocationController {

    @SuppressLint("MissingPermission") // caller guarantees permission
    fun enable(context: Context, map: MapLibreMap, style: Style, followUser: Boolean) {
        val options = LocationComponentOptions.builder(context)
            .pulseEnabled(true)
            .build()

        val request = LocationEngineRequest.Builder(1000L)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setFastestInterval(500L)
            .build()

        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(options)
                    .locationEngineRequest(request)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            isLocationComponentEnabled = true
            renderMode = RenderMode.COMPASS
            cameraMode = if (followUser) CameraMode.TRACKING else CameraMode.NONE
        }
    }

    fun lastLocation(map: MapLibreMap) =
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
}