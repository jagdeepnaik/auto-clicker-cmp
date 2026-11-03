package com.jagdeep.autoclicker.presentation.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.jagdeep.ui.navigation.RouteFlow
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppTransitionsTest {
    private val routes = listOf<NavKey>(RouteTopLevel.Home, RouteTopLevel.Sync, RouteTopLevel.Activity, RouteTopLevel.Settings)

    @Test
    fun `given real Navigation 3 entries when finding their tab then the default content key resolves to the right position`() {
        // Uses Navigation 3's own default content key, so a library change shows up here.
        routes.forEachIndexed { index, route ->
            assertEquals(index, routeIndexOf(NavEntry(route) {}.contentKey, routes))
        }
    }

    @Test
    fun `given a flow screen when finding its tab then it has none`() {
        assertNull(routeIndexOf(NavEntry<NavKey>(RouteFlow.Send) {}.contentKey, routes))
    }
}
