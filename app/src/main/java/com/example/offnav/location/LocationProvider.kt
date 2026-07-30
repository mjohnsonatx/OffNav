package com.example.offnav.location


import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationProvider(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // caller guarantees permission
    fun locationFlow(): Flow<Location> = callbackFlow {
        val listener = LocationListener { location -> trySend(location) }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,   // min interval ms
            2f,      // min distance meters
            listener
        )
        // Seed with last known fix if available
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.let { trySend(it) }
        awaitClose { locationManager.removeUpdates(listener) }
    }
}