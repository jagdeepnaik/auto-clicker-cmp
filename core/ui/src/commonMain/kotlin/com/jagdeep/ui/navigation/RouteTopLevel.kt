package com.jagdeep.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

interface RouteTopLevel : NavKey {
    @Serializable
    data object Home : RouteTopLevel

    /** Transfers in progress and history. */
    @Serializable
    data object Activity : RouteTopLevel

    /** Folders synced with paired devices. */
    @Serializable
    data object Sync : RouteTopLevel

    @Serializable
    data object Settings : RouteTopLevel
}
