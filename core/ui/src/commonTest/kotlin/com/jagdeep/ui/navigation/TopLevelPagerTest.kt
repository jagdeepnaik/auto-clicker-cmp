package com.jagdeep.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopLevelPagerTest {
    private data class Route(val name: String) : NavKey

    private val home = Route("home")
    private val sync = Route("sync")
    private val settings = Route("settings")
    private val routes = listOf(home, sync, settings)

    @Test
    fun `given a swipe that settles on another page when it settles then it navigates there`() {
        assertEquals(sync, swipeTarget(settledPage = 1, routes, currentRoute = home, scrollingTo = null))
    }

    @Test
    fun `given the pager settles on the current route when it settles then nothing navigates`() {
        assertNull(swipeTarget(settledPage = 0, routes, currentRoute = home, scrollingTo = null))
    }

    @Test
    fun `given a navigation item scroll passing through a page when it settles there then nothing navigates`() {
        assertNull(swipeTarget(settledPage = 1, routes, currentRoute = settings, scrollingTo = 2))
    }

    @Test
    fun `given a page outside the routes when it settles then nothing navigates`() {
        assertNull(swipeTarget(settledPage = 5, routes, currentRoute = home, scrollingTo = null))
    }
}
