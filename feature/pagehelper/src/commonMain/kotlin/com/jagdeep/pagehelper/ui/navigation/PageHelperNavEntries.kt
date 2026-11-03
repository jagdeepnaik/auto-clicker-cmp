package com.jagdeep.pagehelper.ui.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.pagehelper.domain.PageHelperController
import com.jagdeep.pagehelper.domain.PageHelperViewModel
import com.jagdeep.pagehelper.ui.screens.PageHelperScreen
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel

fun EntryProviderScope<NavKey>.pageHelperEntryProvider(
    navigator: Navigator,
    controller: PageHelperController,
    onInstallExtension: suspend () -> String?,
) {
    // core:ui stays an exact template copy, so this tab reuses its spare Sync route.
    entry<RouteTopLevel.Sync> {
        val viewModel: PageHelperViewModel = viewModel(factory = viewModelFactory {
            initializer { PageHelperViewModel(controller, onInstallExtension) }
        })
        PageHelperScreen(viewModel, onBack = navigator::goBack)
    }
}
