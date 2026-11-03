package com.jagdeep.autoclicker.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.jagdeep.activity.ui.navigation.activityEntryProvider
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.ui.navigation.homeEntryProvider
import com.jagdeep.autoclicker.installBrowserExtension
import com.jagdeep.pagehelper.domain.PageHelperController
import com.jagdeep.pagehelper.ui.navigation.pageHelperEntryProvider
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.settings.ui.navigation.settingsEntryProvider
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.navigation.NavigationState
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.TopLevelPager
import com.jagdeep.ui.platform.isDesktopPlatform

@Composable
fun AppNavHost(
    navigationState: NavigationState,
    navigator: Navigator,
    topLevelRoutes: List<NavKey>,
    preferencesStore: PreferencesStore<AppPreferences>,
    controller: ClickController,
    routineRepository: RoutineRepository,
    pageHelper: PageHelperController,
    runHistory: RunHistoryStore,
    accessGate: InputAccessGate,
    appVersion: String,
    now: () -> Long,
    newId: () -> String,
    appIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacks = navigationState.rememberDecoratedStacks(entryProvider {
        homeEntryProvider(navigator, controller, routineRepository, newId, appIcon)
        pageHelperEntryProvider(navigator, pageHelper, onInstallExtension = ::installBrowserExtension)
        activityEntryProvider(navigator, runHistory, now)
        settingsEntryProvider(navigator, preferencesStore, accessGate, appVersion)
    })
    if (isDesktopPlatform()) {
        // Desktop navigates with the sidebar.
        NavDisplay(
            modifier = modifier,
            transitionSpec = { topLevelFadeTransition(topLevelRoutes) },
            popTransitionSpec = { topLevelFadeTransition(topLevelRoutes) },
            onBack = navigator::goBack,
            entries = navigationState.entriesInUse(stacks),
        )
    } else {
        // Phones: each top-level route is a page that swipes in with the finger.
        TopLevelPager(navigationState, navigator, topLevelRoutes, modifier) { route ->
            NavDisplay(
                transitionSpec = { slideForwardTransition() },
                popTransitionSpec = { slideBackTransition() },
                onBack = navigator::goBack,
                entries = stacks.getValue(route),
            )
        }
    }
}
