package com.example.offnav.ui.theme.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.offnav.navigation.Stop
import com.example.offnav.navigation.StopType
import com.example.offnav.ui.theme.OffNavTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class StopListCardInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stopListShowsOrderedStopsAndOnlyEditsWaypoint() {
        val stops = listOf(
            stop(1, "Current location", StopType.ORIGIN),
            stop(2, "Coffee stop", StopType.WAYPOINT),
            stop(3, "Capitol", StopType.DESTINATION),
        )
        var removedId: Long? = null
        var movedUpIndex: Int? = null

        compose.setContent {
            OffNavTheme(dynamicColor = false) {
                StopListCard(
                    stops = stops,
                    onRemove = { removedId = it },
                    onMoveUp = { movedUpIndex = it },
                    onMoveDown = {},
                )
            }
        }

        compose.onNodeWithText("Current location").assertIsDisplayed()
        compose.onNodeWithText("Coffee stop").assertIsDisplayed()
        compose.onNodeWithText("Capitol").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Remove").assertCountEquals(1)
        compose.onNodeWithContentDescription("Move up").performClick()
        compose.onNodeWithContentDescription("Remove").performClick()

        compose.runOnIdle {
            assertEquals(1, movedUpIndex)
            assertEquals(2L, removedId)
        }
    }

    private fun stop(id: Long, label: String, type: StopType) = Stop(
        id = id,
        label = label,
        subtitle = "Austin",
        point = LatLng(30.267 + id / 10_000.0, -97.743),
        type = type,
    )
}
