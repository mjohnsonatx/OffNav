package com.example.offnav.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.offnav.ui.theme.OffNavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class NearbySearchSheetInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nearbySearchDisplaysOfflineResultAndRoutesCategoryAndPlaceClicks() {
        val result = PlaceSearchResult(
            name = "Austin Central Library",
            subtitle = "West Cesar Chavez Street",
            category = "Library",
            osmClass = "library",
            latitude = 30.2658,
            longitude = -97.7519,
            distanceMeters = 1_500.0,
        )
        var toggled: PlaceCategory? = null
        var picked: PlaceSearchResult? = null

        compose.setContent {
            OffNavTheme(dynamicColor = false) {
                NearbySearchContent(
                    query = "library",
                    selectedCategories = setOf(PlaceCategory.PARKS),
                    results = listOf(result),
                    searching = false,
                    onQueryChange = {},
                    onCategoryToggle = { toggled = it },
                    onPick = { picked = it },
                )
            }
        }

        compose.onNodeWithText("1 nearby places").assertIsDisplayed()
        compose.onNodeWithText("1.5 km").assertIsDisplayed()
        compose.onNodeWithText("Fuel").performClick()
        compose.onNodeWithText("Austin Central Library").performClick()

        compose.runOnIdle {
            assertEquals(PlaceCategory.FUEL, toggled)
            assertSame(result, picked)
        }
    }
}
