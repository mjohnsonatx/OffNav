package com.example.offnav.ui.theme.ui


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.offnav.navigation.Stop
import com.example.offnav.navigation.StopType

@Composable
fun StopListCard(
    stops: List<Stop>,
    onRemove: (Long) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stops.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            stops.forEachIndexed { index, stop ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Stop type indicator
                    Icon(
                        when (stop.type) {
                            StopType.ORIGIN -> Icons.Default.MyLocation
                            StopType.WAYPOINT -> Icons.Default.Flag
                            StopType.DESTINATION -> Icons.Default.Place
                        },
                        contentDescription = null,
                        tint = when (stop.type) {
                            StopType.DESTINATION -> MaterialTheme.colorScheme.primary
                            StopType.WAYPOINT -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )

                    Spacer(Modifier.width(10.dp))

                    // Label
                    Column(Modifier.weight(1f)) {
                        Text(
                            stop.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (stop.subtitle.isNotBlank()) {
                            Text(
                                stop.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Reorder + remove (waypoints only)
                    if (stop.type == StopType.WAYPOINT) {
                        // Move up (not for first waypoint)
                        if (index > 0) {
                            IconButton(
                                onClick = { onMoveUp(index) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Move up", Modifier.size(18.dp))
                            }
                        } else {
                            Spacer(Modifier.size(28.dp))
                        }

                        // Move down (not if next is destination at end)
                        val nextIsLastDest = index + 1 == stops.lastIndex
                                && stops.last().type == StopType.DESTINATION
                        if (!nextIsLastDest && index < stops.lastIndex) {
                            IconButton(
                                onClick = { onMoveDown(index) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Move down", Modifier.size(18.dp))
                            }
                        } else {
                            Spacer(Modifier.size(28.dp))
                        }

                        IconButton(
                            onClick = { onRemove(stop.id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Connector line between stops
                if (index < stops.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = 22.dp)
                            .width(2.dp)
                            .height(8.dp)
                            .padding()
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.Center).height(8.dp).width(2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}