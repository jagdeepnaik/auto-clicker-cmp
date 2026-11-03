package com.jagdeep.autoclicker.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import androidx.savedstate.serialization.SavedStateConfiguration
import com.jagdeep.home.ui.navigation.RouteRoutineEditor
import com.jagdeep.ui.navigation.NavigationState
import com.jagdeep.ui.navigation.topLevelSavedStateSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

private val savedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(baseClass = NavKey::class) {
            topLevelSavedStateSerializer()
            subclass(RouteRoutineEditor::class, RouteRoutineEditor.serializer())
        }
    }
}

@Composable
fun rememberNavigationState(startRoute: NavKey, topLevelRoutes: Set<NavKey>): NavigationState {
    val topLevelRoute = rememberSerializable(
        startRoute,
        topLevelRoutes,
        configuration = savedStateConfiguration,
        serializer = MutableStateSerializer(PolymorphicSerializer(NavKey::class))
    ) {
        mutableStateOf(startRoute)
    }
    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(
            configuration = savedStateConfiguration,
            key
        )
    }
    return remember(startRoute, topLevelRoutes) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks
        )
    }
}

/** Every top-level route's back stack as decorated entries, kept for as long as the app runs. */
@Composable
fun NavigationState.rememberDecoratedStacks(
    entryProvider: (NavKey) -> NavEntry<NavKey>
): Map<NavKey, List<NavEntry<NavKey>>> = backStacks.mapValues { (key, backStack) ->
    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        rememberViewModelStoreNavEntryDecorator()
    )
    rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = decorators,
        entryProvider = entryProvider
    )
}

/** The entries shown in one display: the start route's stack, then the current route's. */
fun NavigationState.entriesInUse(
    stacks: Map<NavKey, List<NavEntry<NavKey>>>
): SnapshotStateList<NavEntry<NavKey>> = stacksInUse
    .flatMap { key -> stacks[key] ?: emptyList() }
    .toMutableStateList()
