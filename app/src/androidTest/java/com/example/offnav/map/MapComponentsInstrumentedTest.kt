package com.example.offnav.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.offnav.navigation.NavBanner
import com.example.offnav.routing.TurnInstruction
import com.example.offnav.search.PlaceSearchResult
import com.example.offnav.ui.theme.OffNavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class MapComponentsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topBarRoutesSearchAndRegionActions() {
        var searchClicks = 0
        var regionClicks = 0
        compose.setContent {
            TestTheme {
                TopBar(
                    regionLabel = "Austin",
                    onSearchClick = { searchClicks++ },
                    onRegionsClick = { regionClicks++ },
                )
            }
        }

        compose.onNodeWithText("Search Austin or recent destinations").performClick()
        compose.onNodeWithContentDescription("Offline regions").performClick()

        compose.runOnIdle {
            assertEquals(1, searchClicks)
            assertEquals(1, regionClicks)
        }
    }

    @Test
    fun routePreviewShowsSummaryAndRoutesPrimaryActions() {
        var starts = 0
        var clears = 0
        compose.setContent {
            TestTheme {
                RoutePreviewContent(
                    summary = RouteSummary(
                        destinationLabel = "Zilker Park",
                        destinationSubtitle = "Barton Springs Road",
                        distanceText = "4.2 km",
                        durationText = "12 min",
                        arrivalText = "2:20 PM",
                        stepCount = 7,
                    ),
                    stops = emptyList(),
                    pdfExporting = true,
                    onStart = { starts++ },
                    onAddStop = {},
                    onShowSteps = {},
                    onClear = { clears++ },
                    onShareRoute = {},
                    onExportPdf = {},
                    onShareLocation = {},
                    onRemoveStop = {},
                    onMoveStopUp = {},
                    onMoveStopDown = {},
                )
            }
        }

        compose.onNodeWithText("Zilker Park").assertIsDisplayed()
        compose.onNodeWithText("4.2 km · arrive 2:20 PM").assertIsDisplayed()
        compose.onNodeWithText("PDF").assertIsNotEnabled()
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithContentDescription("Clear route").performClick()

        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, clears)
        }
    }

    @Test
    fun destinationSearchSelectsOfflinePlaceResult() {
        val visible = place("Zilker Botanical Garden", 30.26955, -97.77295)
        var picked: PlaceSearchResult? = null

        compose.setContent {
            TestTheme {
                DestinationSearchContent(
                    query = "garden",
                    historyItems = emptyList(),
                    placeItems = listOf(visible),
                    placeSearching = false,
                    placeError = null,
                    onQueryChange = {},
                    onClearQuery = {},
                    onClearHistory = {},
                    onHistoryPick = {},
                    onPlacePick = { picked = it },
                    onPinToggle = {},
                    onDeleteHistory = {},
                )
            }
        }

        compose.onNodeWithText("Zilker Botanical Garden").performClick()
        compose.runOnIdle { assertSame(visible, picked) }
    }

    @Test
    fun turnBannerPreservesGuidanceWhileShowingReroutingState() {
        compose.setContent {
            TestTheme {
                TurnBannerCard(
                    banner = NavBanner(
                        instructionText = "Turn right onto Congress Avenue",
                        maneuverSign = 2,
                        distanceToManeuverMeters = 240,
                        remainingMeters = 2_400,
                        remainingSeconds = 420,
                        offRoute = true,
                        currentInstructionIndex = 3,
                    ),
                    isRerouting = true,
                )
            }
        }

        compose.onNodeWithText("Turn right onto Congress Avenue").assertIsDisplayed()
        compose.onNodeWithText("Rerouting…").assertIsDisplayed()
    }

    @Test
    fun directionRowDisplaysStepAndForwardsSelection() {
        var clicks = 0
        compose.setContent {
            TestTheme {
                DirectionRow(
                    index = 1,
                    instruction = TurnInstruction(
                        text = "Continue on East 12th Street",
                        distanceMeters = 850.0,
                        sign = 0,
                        lat = 30.275,
                        lon = -97.720,
                    ),
                    isCurrent = true,
                    onClick = { clicks++ },
                )
            }
        }

        compose.onNodeWithText("Continue on East 12th Street").performClick()
        compose.onNodeWithText("850 m").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun place(name: String, latitude: Double, longitude: Double) = PlaceSearchResult(
        name = name,
        subtitle = "Austin",
        category = "Park",
        osmClass = "park",
        latitude = latitude,
        longitude = longitude,
    )
}

@androidx.compose.runtime.Composable
private fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    OffNavTheme(dynamicColor = false, content = content)
}
