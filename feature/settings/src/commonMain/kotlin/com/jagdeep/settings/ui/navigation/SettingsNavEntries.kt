package com.jagdeep.settings.ui.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel

fun EntryProviderScope<NavKey>.settingsEntryProvider(
    navigator: Navigator,
    preferencesStore: PreferencesStore<AppPreferences>,
    accessGate: InputAccessGate,
    appVersion: String,
) {
    entry<RouteTopLevel.Settings> {
        val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory {
            initializer { SettingsViewModel(preferencesStore, accessGate) }
        })
        SettingsScreen(viewModel, onBack = navigator::goBack, appVersion = appVersion)
    }
}
