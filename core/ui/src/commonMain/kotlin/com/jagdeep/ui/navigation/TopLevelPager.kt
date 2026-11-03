package com.jagdeep.ui.navigation

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The top-level routes as horizontal pages (phones). Swiping drags the neighbouring route in with
 * the finger and settles on it; tapping a navigation item or going back scrolls the pager instead.
 *
 * Swiping is only enabled while the current route is at the root of its stack, so a swipe never
 * leaves a flow (for example a send or pairing screen) half-way. Back at the root of a route other
 * than [NavigationState.startRoute] returns to the start route, as it does without the pager.
 *
 * [page] renders one route's own back stack. Pages beside the current one are composed while
 * swiping (and prefetched), so only the current route's page can set the top app bar; the others
 * write to a detached one.
 */
@Composable
fun TopLevelPager(
    navigationState: NavigationState,
    navigator: Navigator,
    routes: List<NavKey>,
    modifier: Modifier = Modifier,
    page: @Composable (route: NavKey) -> Unit,
) {
    val currentIndex = routes.indexOf(navigationState.topLevelRoute).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = currentIndex) { routes.size }
    val atRoot = navigationState.backStacks[navigationState.topLevelRoute]?.size == 1
    var scrollingTo by remember { mutableStateOf<Int?>(null) }
    val currentRoute by rememberUpdatedState(navigationState.topLevelRoute)
    val topAppBar = LocalTopAppBarManager.current
    val detachedTopAppBar = remember { MutableStateFlow(TopAppBarState()) }

    // Navigation from elsewhere (navigation items, back) moves the pager.
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage == currentIndex && !pagerState.isScrollInProgress) return@LaunchedEffect
        scrollingTo = currentIndex
        try {
            pagerState.animateScrollToPage(currentIndex)
        } finally {
            scrollingTo = null
        }
    }
    // A swipe that settles on another page navigates there.
    LaunchedEffect(pagerState, routes) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            swipeTarget(settled, routes, currentRoute, scrollingTo)?.let(navigator::navigate)
        }
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = atRoot && navigationState.topLevelRoute != navigationState.startRoute,
        onBackCompleted = navigator::goBack,
    )

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = atRoot,
    ) { index ->
        val route = routes[index]
        CompositionLocalProvider(
            LocalTopAppBarManager provides if (route == navigationState.topLevelRoute) topAppBar else detachedTopAppBar,
        ) {
            page(route)
        }
    }
}

/**
 * The route to navigate to when the pager settles on [settledPage], or null when it is already
 * the current route or the pager is still scrolling to a route chosen elsewhere.
 */
internal fun swipeTarget(settledPage: Int, routes: List<NavKey>, currentRoute: NavKey, scrollingTo: Int?): NavKey? {
    if (scrollingTo != null) return null
    return routes.getOrNull(settledPage)?.takeIf { it != currentRoute }
}
