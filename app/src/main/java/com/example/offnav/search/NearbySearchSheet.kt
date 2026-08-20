package com.example.offnav.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offnav.map.MapViewModel

@Composable
fun NearbySearchSheet(
    viewModel: MapViewModel,
    onPick: (PlaceSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by viewModel.nearbyQuery.collectAsStateWithLifecycle()
    val selectedCats by viewModel.selectedCategories.collectAsStateWithLifecycle()
    val results by viewModel.nearbyResults.collectAsStateWithLifecycle()
    val searching by viewModel.nearbySearching.collectAsStateWithLifecycle()

    NearbySearchContent(
        query = query,
        selectedCategories = selectedCats,
        results = results,
        searching = searching,
        onQueryChange = viewModel::onNearbyQueryChange,
        onCategoryToggle = viewModel::toggleCategory,
        onPick = onPick,
    )
}

/** State-hoisted nearby search surface used by the production sheet and UI tests. */
@Composable
internal fun NearbySearchContent(
    query: String,
    selectedCategories: Set<PlaceCategory>,
    results: List<PlaceSearchResult>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onCategoryToggle: (PlaceCategory) -> Unit,
    onPick: (PlaceSearchResult) -> Unit,
) {

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
    ) {
        // ── Search field ──
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search nearby places") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )

        Spacer(Modifier.height(12.dp))

        // ── Category chips ──
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaceCategory.entries.forEach { cat ->
                val selected = cat in selectedCategories
                FilterChip(
                    selected = selected,
                    onClick = { onCategoryToggle(cat) },
                    label = { Text(cat.label) },
                    leadingIcon = {
                        Icon(cat.icon, null, Modifier.size(18.dp))
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Results ──
        when {
            searching -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching nearby…")
                }
            }

            results.isEmpty() && (query.isNotBlank() || selectedCategories.isNotEmpty()) -> {
                Text(
                    "No nearby places found. Try a different search or category.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            results.isEmpty() -> {
                Text(
                    "Tap a category or type to search nearby places",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                Text(
                    "${results.size} nearby places",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    items(results, key = { "${it.latitude}:${it.longitude}:${it.name}" }) { result ->
                        NearbyResultRow(result = result, onClick = { onPick(result) })
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyResultRow(result: PlaceSearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.subtitle.ifBlank { result.category },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Distance badge
        if (result.distanceMeters > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
            ) {
                Text(
                    result.distanceText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
