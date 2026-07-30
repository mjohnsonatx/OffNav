package com.example.offnav.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.offnav.location.LocationController
import com.example.offnav.navigation.NavState
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.RoutingState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "route-source"
private const val ROUTE_LAYER = "route-layer"


@Composable
fun MapScreen(
    viewModel: MapViewModel,
    locationController: LocationController
) {
    val state by viewModel.uiState.collectAsState()
    val route by viewModel.route.collectAsState()
    val routingStatus by viewModel.routingStatus.collectAsState()
    val navState by viewModel.navState.collectAsState()
    val context = LocalContext.current

    val routingState by viewModel.routingState.collectAsState()
    val transientMessage by viewModel.transientMessage.collectAsState()
    val banner: String? = transientMessage ?: when (val r = routingState) {
        RoutingState.ImportingGraph -> "Building routing graph (first launch, may take a few minutes)…"
        RoutingState.LoadingGraph   -> "Loading routing graph…"
        RoutingState.NotReady       -> "Routing not started"
        RoutingState.Ready          -> null   // hide banner when all good
        is RoutingState.Failed      -> "Routing failed: ${r.message}"
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results.values.any { it }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is MapUiState.Loading -> CircularProgressIndicator()
            is MapUiState.Error -> Text("Map error: ${s.message}")
            is MapUiState.Ready -> MapLibreMapView(
                styleJson = s.styleJson,
                hasLocationPermission = hasLocationPermission,
                locationController = locationController,
                route = route,
                onRouteRequested = viewModel::requestRoute
            )
        }

        // Simple status banner (import progress, route errors, etc.)
        if (banner != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                tonalElevation = 4.dp
            ) { Text(banner, Modifier.padding(8.dp)) }
        }
    }

    Column(
        modifier = Modifier
            //.align(Alignment.BottomCenter)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val nav = navState) {
            is NavState.Navigating -> {
                Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            nav.currentInstruction?.text ?: "Continue",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("in ${nav.distanceToNextTurnMeters.toInt()} m")
                        Text("${(nav.remainingMeters / 1000).format1()} km remaining")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::stopNavigation) { Text("End navigation") }
            }
            is NavState.Rerouting -> {
                Surface(tonalElevation = 4.dp) { Text("Rerouting…", Modifier.padding(12.dp)) }
            }
            is NavState.Arrived -> {
                Surface(tonalElevation = 4.dp) { Text("You have arrived 🎉", Modifier.padding(12.dp)) }
            }
            is NavState.Idle -> {
                if (route != null) {
                    Button(onClick = viewModel::startNavigation) { Text("Start navigation") }
                }
            }
        }
    }
}

@Composable
private fun MapLibreMapView(
    styleJson: String,
    hasLocationPermission: Boolean,
    locationController: LocationController,
    route: RouteResult?,
    onRouteRequested: (LatLng, LatLng) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    // Compose state holders bridging the async MapLibre callbacks back
    // into the composition, so LaunchedEffects below re-run when ready.
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    // ---- 1. Lifecycle wiring ----
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

    // ---- 2. Map init: runs ONCE (not in `update`, which runs every recomposition) ----
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                // Pre-create the empty route source + layer so later
                // effects only need to setGeoJson on it.
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor("#3b82f6"),
                        lineWidth(6f),
                        lineCap("round"),
                        lineJoin("round")
                    )
                )
                styleRef = style
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(52.52, 13.405)) // match your extract
                .zoom(12.0)
                .build()

            // Long-press anywhere = route from current position to there
            map.addOnMapLongClickListener { target ->
                val loc = locationController.lastLocation(map)
                if (loc != null) {
                    onRouteRequested(LatLng(loc.latitude, loc.longitude), target)
                }
                true
            }
        }
    }

    // ---- 3. Location puck: re-runs when permission OR style becomes ready ----
    LaunchedEffect(hasLocationPermission, styleRef) {
        val map = mapRef ?: return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect
        if (hasLocationPermission) {
            locationController.enable(context, map, style, followUser = true)
        }
    }

    // ---- 4. Route drawing: re-runs when the route OR style changes ----
    LaunchedEffect(route, styleRef) {
        val style = styleRef ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        if (route == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            val line = LineString.fromLngLats(
                route.points.map { Point.fromLngLat(it.longitude, it.latitude) }
            )
            source.setGeoJson(Feature.fromGeometry(line))
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView }
        // No `update` block needed — all dynamic behavior is in the effects above.
    )
}

private fun Double.format1() = "%.1f".format(this)