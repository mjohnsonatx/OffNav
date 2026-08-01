package com.example.offnav.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offnav.data.RouteHistoryEntry
import com.example.offnav.location.LocationController
import com.example.offnav.location.LocationProvider
import com.example.offnav.navigation.ActiveRoute
import com.example.offnav.navigation.NavBanner
import com.example.offnav.navigation.NavState
import com.example.offnav.region.RegionImportManager
import com.example.offnav.region.RegionImportSheet
import com.example.offnav.region.RegionSnapshot
import com.example.offnav.routing.TurnInstruction
import com.example.offnav.search.NearbySearchSheet
import com.example.offnav.search.PlaceSearchResult
import com.example.offnav.ui.theme.ui.StopListCard
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val ROUTE_SOURCE = "route-source"
private const val ROUTE_LAYER = "route-layer"
private const val INITIAL_LOCATION_ZOOM = 15.0
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

// ════════════════════════════════════════════════════════════════
// Root
// ════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    locationController: LocationController,
    locationProvider: LocationProvider,
    regionImportManager: RegionImportManager,
    activeRegion: RegionSnapshot,
) {

    var showRegions by rememberSaveable { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(locationProvider.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = it.values.any { g -> g } }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    var showDestinationSearch by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    Box(Modifier.fillMaxSize()) {
        MapHost(viewModel, locationController, locationProvider, hasPermission)

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            TopBar(
                regionLabel = activeRegion.displayName,
                onSearchClick = { showDestinationSearch = true },
                onRegionsClick = { showRegions = true },
            )
            BannerHost(viewModel, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }

        BottomPanel(viewModel, Modifier.align(Alignment.BottomCenter))
    }

    if (showRegions) {
        RegionImportSheet(
            manager = regionImportManager,
            activeRegion = activeRegion,
            onDismiss = { showRegions = false },
        )
    }

    if (showDestinationSearch) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.clearDestinationQuery()
                showDestinationSearch = false
            },
            sheetState = sheetState,
        ) {
            DestinationSearchSheet(
                viewModel = viewModel,
                onHistoryPick = { entry ->
                    viewModel.routeToHistory(entry)
                    showDestinationSearch = false
                },
                onPlacePick = { result ->
                    viewModel.routeToPlace(result)
                    showDestinationSearch = false
                },
            )
        }
    }
}

@Composable
private fun TopBar(regionLabel: String, onSearchClick: () -> Unit, onRegionsClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.clickable(onClick = onSearchClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "Search $regionLabel or recent destinations",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).clickable(onClick = onSearchClick),
            )
            IconButton(onClick = onRegionsClick) {
                Icon(Icons.Default.Layers, contentDescription = "Offline regions")
            }
        }
    }
}

@Composable
private fun BottomPanel(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val nav by viewModel.navState.collectAsStateWithLifecycle()

    Box(modifier.navigationBarsPadding().padding(12.dp)) {
        when (nav) {
            NavState.Idle -> RoutePreviewCard(viewModel)
            else -> NavPanel(viewModel)      // your existing composable
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutePreviewCard(viewModel: MapViewModel) {
    val summary by viewModel.routeSummary.collectAsStateWithLifecycle()
    val s = summary ?: return
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    var showSteps by remember { mutableStateOf(false) }
    var showAddStop by remember { mutableStateOf(false) }
    val addStopSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current
    val pdfExporting by viewModel.pdfExporting.collectAsStateWithLifecycle()


    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Destination header
            Text(
                s.destinationLabel,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (s.destinationSubtitle.isNotBlank()) {
                Text(
                    s.destinationSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            // Stop list (if there are waypoints)
            if (stops.size > 1) {   // destination + at least one waypoint
                StopListCard(
                    stops = stops,
                    onRemove = viewModel::removeStop,
                    onMoveUp = { i -> viewModel.moveStop(i, i - 1) },
                    onMoveDown = { i -> viewModel.moveStop(i, i + 1) },
                )
                Spacer(Modifier.height(8.dp))
            }

            // Route summary
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    s.durationText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${s.distanceText} · arrive ${s.arrivalText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.startNavigation(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start")
                }
                FilledTonalButton(onClick = { showAddStop = true }) {
                    Icon(Icons.Default.AddLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
                FilledTonalButton(onClick = { showSteps = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${s.stepCount}")
                }
                FilledTonalIconButton(onClick = viewModel::clearRoute) {
                    Icon(Icons.Default.Close, contentDescription = "Clear route")
                }
            }

            Spacer(Modifier.height(8.dp))
            // Second row: share & export
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.shareRoute(context) }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                OutlinedButton(
                    onClick = { viewModel.exportDirectionsPdf(context) },
                    enabled = !pdfExporting,
                ) {
                    if (pdfExporting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("PDF")
                }
                OutlinedButton(onClick = { viewModel.shareLocation(context) }) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share Pin")
                }
            }
        }
    }

    // Steps sheet
    if (showSteps) {
        DirectionsList(viewModel, currentIndex = -1, onDismiss = { showSteps = false })
    }

    // Add-stop nearby search sheet
    if (showAddStop) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.clearNearbySearch()
                showAddStop = false
            },
            sheetState = addStopSheetState,
        ) {
            NearbySearchSheet(
                viewModel = viewModel,
                onPick = { result ->
                    viewModel.addStop(
                        label = result.name,
                        subtitle = result.subtitle,
                        point = LatLng(result.latitude, result.longitude),
                    )
                    viewModel.clearNearbySearch()
                    showAddStop = false
                },
                onDismiss = {
                    viewModel.clearNearbySearch()
                    showAddStop = false
                },
            )
        }
    }
}

@Composable
fun DestinationSearchSheet(
    viewModel: MapViewModel,
    onHistoryPick: (RouteHistoryEntry) -> Unit,
    onPlacePick: (PlaceSearchResult) -> Unit,
) {
    val query by viewModel.destinationQuery.collectAsStateWithLifecycle()
    val historyItems by viewModel.history.collectAsStateWithLifecycle()
    val placeItems by viewModel.placeResults.collectAsStateWithLifecycle()
    val placeSearching by viewModel.placeSearching.collectAsStateWithLifecycle()
    val placeError by viewModel.placeSearchError.collectAsStateWithLifecycle()
    val historyDestinationKeys = remember(historyItems) {
        historyItems.mapTo(mutableSetOf()) { entry ->
            coordinateKey(entry.destination.latitude, entry.destination.longitude)
        }
    }
    val visiblePlaceItems = remember(placeItems, historyDestinationKeys) {
        placeItems.filterNot { result ->
            coordinateKey(result.latitude, result.longitude) in historyDestinationKeys
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onDestinationQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Destination") },
            placeholder = { Text("Address, restaurant, park, or business") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearDestinationQuery) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (query.isBlank()) "Recent destinations" else "Search results",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (query.isBlank() && historyItems.isNotEmpty()) {
                TextButton(onClick = viewModel::clearHistory) { Text("Clear") }
            }
        }

        if (query.isBlank() && historyItems.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                Text(
                    "No recent routes yet. Search above to find an Austin destination.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                if (historyItems.isNotEmpty()) {
                    if (query.isNotBlank()) {
                        item(key = "history-header") { SearchSectionHeader("Recent destinations") }
                    }
                    items(historyItems, key = { "history:${it.id}" }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onClick = { onHistoryPick(entry) },
                            onPinToggle = { viewModel.togglePin(entry.id, !entry.pinned) },
                            onDelete = { viewModel.deleteHistory(entry.id) },
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }

                if (query.isNotBlank()) {
                    when {
                        query.trim().length < 2 -> item(key = "query-hint") {
                            SearchMessage("Type at least 2 characters to search Austin places")
                        }
                        placeSearching -> item(key = "place-loading") {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Searching offline Austin data…")
                            }
                        }
                        placeError != null -> item(key = "place-error") {
                            SearchMessage(placeError ?: "Offline Austin search is unavailable", isError = true)
                        }
                        visiblePlaceItems.isNotEmpty() -> {
                            item(key = "places-header") { SearchSectionHeader("Austin places") }
                            items(
                                visiblePlaceItems,
                                key = { result ->
                                    "place:${result.latitude}:${result.longitude}:${result.name}"
                                },
                            ) { result ->
                                PlaceSearchRow(result = result, onClick = { onPlacePick(result) })
                                HorizontalDivider(Modifier.padding(start = 56.dp))
                            }
                        }
                        historyItems.isEmpty() -> item(key = "no-results") {
                            SearchMessage("No matching recent destinations or Austin places")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SearchMessage(text: String, isError: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PlaceSearchRow(result: PlaceSearchResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.subtitle.ifBlank { result.category },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun coordinateKey(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude)

@Composable
private fun HistoryRow(
    entry: RouteHistoryEntry,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.pinned) Icons.Default.Star else Icons.Default.History,
            contentDescription = null,
            tint = if (entry.pinned) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.distanceText} · ${entry.durationText} · ${entry.relativeTimeText}" +
                        if (entry.useCount > 1) " · ${entry.useCount}×" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (entry.pinned) "Unpin" else "Pin") },
                    onClick = { menuOpen = false; onPinToggle() },
                    leadingIcon = { Icon(Icons.Default.Star, null) },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                )
            }
        }
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
            viewModel = viewModel,
            onLongPress = { from, to, label, subtitle -> viewModel.requestRoute(from, to, label, subtitle) }
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
    viewModel: MapViewModel,
    // signature change
    onLongPress: (from: LatLng, to: LatLng, label: String, subtitle: String) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context).apply {
                textureMode(false)
                localIdeographFontFamily("sans-serif")
            }
        ).also { it.setMaximumFps(60) }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var hasInitialCamera by remember { mutableStateOf(false) }
    val longPress by rememberUpdatedState(onLongPress)

    // Lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); mapView.onDestroy() }
    }

    // Map init
    LaunchedEffect(Unit) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.setStyle(Style.Builder().fromJson(style.json)) { ready ->
                ready.addSource(GeoJsonSource(ROUTE_SOURCE))
                ready.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor("#3b82f6"), lineWidth(6f), lineCap("round"), lineJoin("round")
                    )
                )
                styleRef = ready
            }
            map.uiSettings.isRotateGesturesEnabled = true
            map.addOnMapLongClickListener { target ->
                locationProvider.lastFix.value?.let { fix ->
                    val named = PlaceNamer.nameAt(map, target)     // main thread — required by MapLibre
                    longPress(LatLng(fix.latitude, fix.longitude), target, named.label, named.subtitle)
                }
                true
            }

            locationProvider.lastFix.value?.let {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(it.latitude, it.longitude)).zoom(INITIAL_LOCATION_ZOOM).build()
                hasInitialCamera = true
            }
        }
    }

    // Location puck
    LaunchedEffect(hasPermission, styleRef) {
        val map = mapRef ?: return@LaunchedEffect
        val s = styleRef ?: return@LaunchedEffect
        if (!hasPermission) return@LaunchedEffect
        locationController.enable(context, map, s, followUser = true)
        locationProvider.locations.collect { loc ->
            if (!hasInitialCamera) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude)).zoom(INITIAL_LOCATION_ZOOM).build()
                hasInitialCamera = true
            }
            locationController.push(map, loc)
        }
    }

    // Route overlay
    LaunchedEffect(styleRef) {
        val s = styleRef ?: return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return@LaunchedEffect
        activeRoute.collect { route -> src.setGeoJson(route?.overlayGeoJson ?: EMPTY_FC) }
    }

    // Camera commands from ViewModel (fly-to instruction, return to tracking)
    LaunchedEffect(Unit) {
        viewModel.cameraCommands.collect { cmd ->
            val map = mapRef ?: return@collect
            when (cmd) {
                is CameraCommand.FlyTo -> {
                    // Temporarily break tracking so the camera flies to the instruction point
                    map.locationComponent.cameraMode =
                        org.maplibre.android.location.modes.CameraMode.NONE
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(cmd.target)
                                .zoom(cmd.zoom)
                                .tilt(cmd.tilt)
                                .build()
                        ),
                        1_200
                    )
                }
                CameraCommand.ReturnToTracking -> {
                    map.locationComponent.cameraMode =
                        CameraMode.TRACKING_GPS
                }

                // camera commands
                is CameraCommand.FitBounds -> {
                    if (cmd.points.size >= 2) {
                        val bounds = LatLngBounds.Builder().includes(cmd.points).build()
                        map.locationComponent.cameraMode = CameraMode.NONE
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120, 220, 120, 420), 900)
                    }
                }
            }
        }
    }

    AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
}

// ════════════════════════════════════════════════════════════════
// Status banner (routing progress / errors)
// ════════════════════════════════════════════════════════════════

@Composable
private fun BannerHost(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val b = banner ?: return
    Surface(modifier = modifier, tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (b.showSpinner) {
                if (b.progress != null) {
                    CircularProgressIndicator(progress = { b.progress }, Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(b.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Navigation panel — persistent banner + directions list
// ════════════════════════════════════════════════════════════════

// Replace the NavPanel composable entirely in MapScreen.kt

@Composable
private fun NavPanel(viewModel: MapViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val nav by viewModel.navState.collectAsStateWithLifecycle()
    val hasRoute by viewModel.hasRoute.collectAsStateWithLifecycle()
    val pdfExporting by viewModel.pdfExporting.collectAsStateWithLifecycle()
    var showDirections by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    val activeBanner: NavBanner? = when (val n = nav) {
        is NavState.Navigating -> n.banner
        is NavState.Rerouting -> n.lastBanner
        else -> null
    }
    val isRerouting = nav is NavState.Rerouting

    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (nav) {
            is NavState.Navigating, is NavState.Rerouting -> {
                activeBanner?.let { banner -> TurnBannerCard(banner, isRerouting) }
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {

                    FilledTonalButton(onClick = { showDirections = !showDirections }) {
                        Icon(
                            if (showDirections) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (showDirections) "Hide" else "Steps")
                    }

                    FilledTonalButton(onClick = viewModel::returnToTracking) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                    }

                    // Overflow menu for share/export
                    Box {
                        FilledTonalButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share route") },
                                onClick = {
                                    showOverflow = false
                                    viewModel.shareRoute(context)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share my location") },
                                onClick = {
                                    showOverflow = false
                                    viewModel.shareLocation(context)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.MyLocation, null)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (pdfExporting) "Exporting…" else "Export PDF"
                                    )
                                },
                                onClick = {
                                    if (!pdfExporting) {
                                        showOverflow = false
                                        viewModel.exportDirectionsPdf(context)
                                    }
                                },
                                enabled = !pdfExporting,
                                leadingIcon = {
                                    if (pdfExporting) {
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.PictureAsPdf, null)
                                    }
                                },
                            )
                        }
                    }

                    Button(onClick = {
                        showDirections = false
                        viewModel.stopNavigation(context)
                    }) { Text("End") }
                }
            }

            NavState.Arrived -> {
                showDirections = false
                Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "You have arrived 🎉",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.shareLocation(context) }
                            ) {
                                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share Pin")
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportDirectionsPdf(context) },
                                enabled = !pdfExporting,
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PDF")
                            }
                        }
                    }
                }
            }

            NavState.Idle -> {
                showDirections = false
                if (hasRoute) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.startNavigation(context)
                        }) { Text("Start navigation") }
                        FilledTonalButton(onClick = { showDirections = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Preview steps")
                        }
                    }
                }
            }
        }
    }

    if (showDirections) {
        DirectionsList(
            viewModel = viewModel,
            currentIndex = activeBanner?.currentInstructionIndex ?: -1,
            onDismiss = { showDirections = false },
        )
    }
}

// ════════════════════════════════════════════════════════════════
// Turn banner card (shows rerouting overlay when applicable)
// ════════════════════════════════════════════════════════════════

@Composable
private fun TurnBannerCard(banner: NavBanner, isRerouting: Boolean) {
    Surface(
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    maneuverEmoji(banner.maneuverSign),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        banner.instructionText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatDistance(banner.distanceToManeuverMeters),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${formatDistance(banner.remainingMeters)} · ${formatEta(banner.remainingSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${formatDistance(banner.remainingMeters)} · " +
                                "${formatEta(banner.remainingSeconds)} · " +
                                "arrive ${arrivalClock(banner.remainingSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                }
            }

            // Semi-transparent rerouting overlay on top of the existing banner
            if (isRerouting) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Rerouting…", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private fun arrivalClock(remainingSeconds: Int): String {
    val cal = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis() + remainingSeconds * 1000L
    }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
}
// ════════════════════════════════════════════════════════════════
// Directions list (scrollable, tappable steps)
// ════════════════════════════════════════════════════════════════

@Composable
private fun DirectionsList(
    viewModel: MapViewModel,
    currentIndex: Int,
    onDismiss: () -> Unit,
) {
    val instructions by viewModel.instructions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to the active instruction when it changes
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < instructions.size) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.45f)          // takes up to 45% of the screen
            .padding(horizontal = 8.dp),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Directions · ${instructions.size} steps",
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            HorizontalDivider()

            // Step list
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(instructions, key = { i, _ -> i }) { index, instruction ->
                    DirectionRow(
                        index = index,
                        instruction = instruction,
                        isCurrent = index == currentIndex,
                        onClick = { viewModel.flyToInstruction(instruction) },
                    )
                    if (index < instructions.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionRow(
    index: Int,
    instruction: TurnInstruction,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Step number + maneuver emoji
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp),
        ) {
            Text(
                maneuverEmoji(instruction.sign),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                instruction.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDistance(instruction.distanceMeters.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════════

private fun maneuverEmoji(sign: Int): String = when (sign) {
    -3       -> "⤺"
    -2       -> "←"
    -1       -> "↰"
    0        -> "↑"
    1        -> "↱"
    2        -> "→"
    3        -> "⤻"
    -7       -> "⇐"
    7        -> "⇒"
    6        -> "↻"
    -8, -98  -> "⤹"
    4        -> "🏁"
    5        -> "📍"
    else     -> "↑"
}

private fun formatDistance(meters: Int): String = when {
    meters >= 10_000 -> "%.0f km".format(meters / 1000.0)
    meters >= 1_000  -> "%.1f km".format(meters / 1000.0)
    meters >= 100    -> "${(meters / 10) * 10} m"
    else             -> "$meters m"
}

private fun formatEta(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}min" else "${m} min"
}
