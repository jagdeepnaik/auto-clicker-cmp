package com.jagdeep.activity.ui.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.activity.domain.ActivityViewModel
import com.jagdeep.activity.ui.screens.ActivityScreen
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel

fun EntryProviderScope<NavKey>.activityEntryProvider(
    navigator: Navigator,
    history: RunHistoryStore,
    now: () -> Long,
) {
    entry<RouteTopLevel.Activity> {
        val viewModel: ActivityViewModel = viewModel(factory = viewModelFactory {
            initializer { ActivityViewModel(history, now) }
        })
        ActivityScreen(viewModel, onBack = navigator::goBack)
    }
}
