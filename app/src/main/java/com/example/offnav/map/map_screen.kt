package com.example.offnav.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offnav.location.LocationController
import com.example.offnav.location.LocationProvider
import com.example.offnav.navigation.ActiveRoute
import com.example.offnav.navigation.NavBanner
import com.example.offnav.navigation.NavState
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.RoutingState
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
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
    locationController: LocationController,
    locationProvider: LocationProvider,
) {
    var hasPermission by remember { mutableStateOf(locationProvider.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = it.values.any { granted -> granted } }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    Box(Modifier.fillMaxSize()) {
        MapHost(viewModel, locationController, locationProvider, hasPermission)
        BannerHost(viewModel, Modifier.align(Alignment.TopCenter).padding(8.dp))
        NavPanel(viewModel, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun MapHost(
    viewModel: MapViewModel,
    locationController: LocationController,
    locationProvider: LocationProvider,
    hasPermission: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val s = state) {
        MapUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is MapUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Map error: ${s.message}") }
        is MapUiState.Ready -> MapLibreCanvas(
            style = s.style,
            hasPermission = hasPermission,
            locationController = locationController,
            locationProvider = locationProvider,
            activeRoute = viewModel.activeRoute,
            onLongPress = viewModel::requestRoute,
        )
    }
}

@Composable
private fun MapLibreCanvas(
    style: StyleHolder,
    hasPermission: Boolean,
    locationController: LocationController,
    locationProvider: LocationProvider,
    activeRoute: StateFlow<ActiveRoute?>,
    onLongPress: (LatLng, LatLng) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context).apply {
                textureMode(false)                       // GLSurfaceView: lowest-latency path
                localIdeographFontFamily("sans-serif")   // no CJK glyph bundle needed
            }
        ).also { it.setMaximumFps(60) }                   // don't burn GPU at 120Hz
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.setStyle(Style.Builder().fromJson(style.json)) { ready ->
                ready.addSource(GeoJsonSource(ROUTE_SOURCE))
                ready.addLayerBelow(                       // keep labels above the route line
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor("#3b82f6"), lineWidth(6f), lineCap("round"), lineJoin("round")
                    ),
                    "road-label"
                )
                styleRef = ready
            }
            map.uiSettings.isRotateGesturesEnabled = true
            map.addOnMapLongClickListener { target ->
                locationProvider.lastFix.value?.let {
                    longPress(LatLng(it.latitude, it.longitude), target)
                }
                true
            }
            locationProvider.lastFix.value?.let {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(it.latitude, it.longitude)).zoom(15.0).build()
            }
        }
    }

    // Location puck: single GPS subscription, fed from our provider.
    LaunchedEffect(hasPermission, styleRef) {
        val map = mapRef ?: return@LaunchedEffect
        val s = styleRef ?: return@LaunchedEffect
        if (!hasPermission) return@LaunchedEffect
        locationController.enable(context, map, s, followUser = true)
        locationProvider.locations.collect { locationController.push(map, it) }
    }

    // Route overlay: flow collected here, so route changes cause ZERO recomposition.
    LaunchedEffect(styleRef) {
        val s = styleRef ?: return@LaunchedEffect
        val source = s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        activeRoute.collect { route ->
            source.setGeoJson(route?.overlayGeoJson ?: EMPTY_FEATURE_COLLECTION)
        }
    }

    AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
}

@Composable
private fun NavPanel(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val nav by viewModel.navState.collectAsStateWithLifecycle()
    val hasRoute by viewModel.hasRoute.collectAsStateWithLifecycle()

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (val n = nav) {
            is NavState.Navigating -> {
                TurnBanner(n.banner)
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::stopNavigation) { Text("End navigation") }
            }
            NavState.Rerouting -> Surface(tonalElevation = 4.dp) { Text("Rerouting…", Modifier.padding(12.dp)) }
            NavState.Arrived -> Surface(tonalElevation = 4.dp) { Text("You have arrived 🎉", Modifier.padding(12.dp)) }
            NavState.Idle -> if (hasRoute) {
                Button(onClick = viewModel::startNavigation) { Text("Start navigation") }
            }
        }
    }
}

@Composable
private fun BannerHost(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val b = banner ?: return

    Surface(modifier = modifier, tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (b.showSpinner) {
                if (b.progress != null) {
                    CircularProgressIndicator(
                        progress = { b.progress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(b.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

//@Composable
//private fun TurnBanner(banner: NavBanner) {
//    Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
//        Row(
//            modifier = Modifier.padding(12.dp),
//            verticalAlignment = Alignment.CenterVertically,
//        ) {
//            Icon(
//                painter = painterResource(Maneuvers.icon(banner.maneuverSign)),
//                contentDescription = banner.instructionText,
//                modifier = Modifier.size(48.dp),
//                tint = MaterialTheme.colorScheme.primary,
//            )
//            Spacer(Modifier.width(12.dp))
//            Column {
//                Text(
//                    banner.instructionText,
//                    style = MaterialTheme.typography.titleMedium,
//                    maxLines = 2,
//                    overflow = TextOverflow.Ellipsis,
//                )
//                Spacer(Modifier.height(2.dp))
//                Text(
//                    formatDistance(banner.distanceToManeuverMeters),
//                    style = MaterialTheme.typography.headlineSmall,
//                    color = MaterialTheme.colorScheme.primary,
//                )
//                Text(
//                    "${formatDistance(banner.remainingMeters)} · ${formatEta(banner.remainingSeconds)}",
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant,
//                )
//            }
//        }
//    }
//}

// temporary until you add the drawable set
@Composable
private fun TurnBanner(banner: NavBanner) {
    Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                maneuverEmoji(banner.maneuverSign),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(banner.instructionText, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Text(formatDistance(banner.distanceToManeuverMeters),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("${formatDistance(banner.remainingMeters)} · ${formatEta(banner.remainingSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun maneuverEmoji(sign: Int): String = when (sign) {
    -3       -> "⤺"    // sharp left
    -2       -> "←"    // left
    -1       -> "↰"    // slight left
    0        -> "↑"    // straight
    1        -> "↱"    // slight right
    2        -> "→"    // right
    3        -> "⤻"    // sharp right
    -7       -> "⇐"   // keep left
    7        -> "⇒"   // keep right
    6        -> "↻"    // roundabout
    -8, -98  -> "⤹"   // u-turn
    4        -> "🏁"   // finish
    5        -> "📍"   // via
    else     -> "↑"
}

private fun formatDistance(meters: Int): String = when {
    meters >= 10_000 -> "%.0f km".format(meters / 1000.0)
    meters >= 1_000  -> "%.1f km".format(meters / 1000.0)
    meters >= 100    -> "${(meters / 10) * 10} m"      // round to 10 m
    else             -> "$meters m"
}

private fun formatEta(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}min" else "${m} min"
}

private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""