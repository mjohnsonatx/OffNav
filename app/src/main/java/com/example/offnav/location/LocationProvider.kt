package com.example.offnav.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class LocationProvider(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Fixes are delivered straight onto a worker thread – nothing here touches the UI. */
    private val callbackExecutor = Dispatchers.Default.asExecutor()

    /** Latest known fix, readable synchronously (replaces `locationComponent.lastKnownLocation`). */
    private val _lastFix = MutableStateFlow<Location?>(null)
    val lastFix: StateFlow<Location?> = _lastFix.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    private fun rawLocations(): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }

        // LocationListenerCompat implements the pre-API-30 abstract callbacks for us.
        val listener = LocationListenerCompat { location -> trySend(location) }

        val request = LocationRequestCompat.Builder(1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)          // navigation wants every tick
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()

        val providers = buildList {
            if (LocationManager.GPS_PROVIDER in lm.allProviders) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in lm.allProviders) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            close(IllegalStateException("No location providers available"))
            return@callbackFlow
        }

        // Seed with the freshest last-known fix so the UI/puck isn't empty for 10s.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { trySend(it) }

        providers.forEach { provider ->
            // Executor overload → no Looper required on the calling thread.
            LocationManagerCompat.requestLocationUpdates(
                lm, provider, request, callbackExecutor, listener
            )
        }

        awaitClose { LocationManagerCompat.removeUpdates(lm, listener) }
    }
        // Conflating channel: never block the GPS callback, never deliver a stale fix.
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Single hot GPS subscription shared by navigation, the map puck and anything else.
     * `WhileSubscribed(5s)` keeps GPS alive across config changes but releases it when idle.
     */
    val locations: SharedFlow<Location> = rawLocations()
        .filter { it.isUsable() }
        .onEach { _lastFix.value = it }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private fun Location.isUsable(): Boolean {
        if (latitude == 0.0 && longitude == 0.0) return false
        if (hasAccuracy() && accuracy > 150f) return false
        val prev = _lastFix.value ?: return true
        return time >= prev.time            // drop out-of-order fixes from mixed providers
    }
}