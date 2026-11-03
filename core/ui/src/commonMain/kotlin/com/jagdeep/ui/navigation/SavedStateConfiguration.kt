package com.jagdeep.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.modules.PolymorphicModuleBuilder

fun PolymorphicModuleBuilder<NavKey>.topLevelSavedStateSerializer() {
    subclass(
        subclass = RouteTopLevel.Home::class,
        serializer = RouteTopLevel.Home.serializer()
    )
    subclass(
        subclass = RouteTopLevel.Activity::class,
        serializer = RouteTopLevel.Activity.serializer()
    )
    subclass(
        subclass = RouteTopLevel.Sync::class,
        serializer = RouteTopLevel.Sync.serializer()
    )
    subclass(
        subclass = RouteTopLevel.Settings::class,
        serializer = RouteTopLevel.Settings.serializer()
    )
    subclass(
        subclass = RouteFlow.PairDevice::class,
        serializer = RouteFlow.PairDevice.serializer()
    )
    subclass(
        subclass = RouteFlow.Send::class,
        serializer = RouteFlow.Send.serializer()
    )
}
