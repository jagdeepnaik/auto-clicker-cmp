package com.jagdeep.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.HomeViewModel
import com.jagdeep.home.domain.RoutineEditorViewModel
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.ui.screens.HomeScreen
import com.jagdeep.home.ui.screens.RoutineEditorScreen
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteFlow
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.serialization.Serializable

/** Create or edit a routine; holds only its opaque id. Owned by this feature. */
@Serializable
data class RouteRoutineEditor(val routineId: String? = null) : RouteFlow

fun EntryProviderScope<NavKey>.homeEntryProvider(
    navigator: Navigator,
    controller: ClickController,
    repository: RoutineRepository,
    newRoutineId: () -> String,
    appIcon: @Composable () -> Unit,
) {
    entry<RouteTopLevel.Home> {
        val viewModel: HomeViewModel = viewModel(factory = viewModelFactory {
            initializer { HomeViewModel(controller, repository) }
        })
        HomeScreen(
            viewModel,
            appIcon = appIcon,
            onNewRoutine = { navigator.navigate(RouteRoutineEditor()) },
            onEditRoutine = { navigator.navigate(RouteRoutineEditor(it.id)) },
        )
    }
    entry<RouteRoutineEditor> { route ->
        val viewModel: RoutineEditorViewModel = viewModel(factory = viewModelFactory {
            initializer { RoutineEditorViewModel(route.routineId, controller, repository, newRoutineId) }
        })
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.isSaved) { if (state.isSaved) navigator.goBack() }
        RoutineEditorScreen(viewModel, onBack = navigator::goBack)
    }
}
