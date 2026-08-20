package com.example.offnav.region

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offnav.ui.theme.OffNavTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegionRowInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun regionsSheetShowsOfflineManagementSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RegionStore(context)
        val active = RegionSelection(listOf(RegionSnapshot.BuiltIn))
        val manager = RegionImportManager(context, store, scope)
        val catalog = RegionCatalog(context, store, active, scope)

        compose.setContent {
            OffNavTheme(dynamicColor = false) {
                RegionsSheet(manager = manager, catalog = catalog, onDismiss = {})
            }
        }

        compose.onNodeWithText("Offline regions").assertIsDisplayed()
        compose.onNodeWithText("Import .offnav bundle…").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Austin").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun regionRowsDistinguishLoadedAndManageableOfflineRegions() {
        var loads = 0
        var deletes = 0
        compose.setContent {
            OffNavTheme(dynamicColor = false) {
                Column {
                    RegionRow(
                        region = region("builtin", "Austin", active = true, selected = true),
                        onLoadToggle = {},
                        onDelete = {},
                    )
                    RegionRow(
                        region = region("dallas-1", "Dallas", active = false, selected = false),
                        onLoadToggle = { loads++ },
                        onDelete = { deletes++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Loaded").assertIsDisplayed()
        compose.onNodeWithText("Dallas").assertIsDisplayed()
        compose.onNodeWithText("Load").performClick()
        compose.onNodeWithContentDescription("Delete Dallas").performClick()

        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(1, deletes)
        }
    }

    private fun region(
        installId: String,
        name: String,
        active: Boolean,
        selected: Boolean,
    ) = RegionInfo(
        installId = installId,
        regionId = name.lowercase(),
        displayName = name,
        version = "1",
        bounds = RegionBounds(30.0, 31.0, -98.0, -97.0),
        installedBytes = 10L * 1024L * 1024L,
        isActive = active,
        isSelectedForNextLaunch = selected,
    )
}
