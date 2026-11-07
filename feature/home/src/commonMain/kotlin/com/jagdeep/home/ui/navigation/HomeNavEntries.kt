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
import com.jagdeep.home.domain.NoRoutineFiles
import com.jagdeep.home.domain.RoutineFiles
import com.jagdeep.home.domain.RoutineKind
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.ui.screens.HomeScreen
import com.jagdeep.home.ui.screens.RoutineEditorScreen
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteFlow
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.serialization.Serializable

/** Create or edit a routine; holds only its opaque id, or the kind of a new one. Owned by this feature. */
@Serializable
data class RouteRoutineEditor(val routineId: String? = null, val kind: String? = null) : RouteFlow

fun EntryProviderScope<NavKey>.homeEntryProvider(
    navigator: Navigator,
    controller: ClickController,
    repository: RoutineRepository,
    newRoutineId: () -> String,
    appIcon: @Composable () -> Unit,
    routineFiles: RoutineFiles = NoRoutineFiles,
) {
    entry<RouteTopLevel.Home> {
        val viewModel: HomeViewModel = viewModel(factory = viewModelFactory {
            initializer { HomeViewModel(controller, repository, routineFiles, newRoutineId) }
        })
        HomeScreen(
            viewModel,
            appIcon = appIcon,
            onNewRoutine = { kind -> navigator.navigate(RouteRoutineEditor(kind = kind.name)) },
            onEditRoutine = { navigator.navigate(RouteRoutineEditor(it.id)) },
        )
    }
    entry<RouteRoutineEditor> { route ->
        val viewModel: RoutineEditorViewModel = viewModel(factory = viewModelFactory {
            val kind = RoutineKind.entries.firstOrNull { it.name == route.kind } ?: RoutineKind.Click
            initializer { RoutineEditorViewModel(route.routineId, controller, repository, newRoutineId, kind) }
        })
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.isSaved) { if (state.isSaved) navigator.goBack() }
        RoutineEditorScreen(viewModel, onBack = navigator::goBack)
    }
}
