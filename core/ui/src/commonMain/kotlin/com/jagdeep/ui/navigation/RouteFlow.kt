package com.jagdeep.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Non-top-level destinations that more than one feature may open. The owning feature provides
 * the entry; other features navigate by key without importing it.
 */
interface RouteFlow : NavKey {
    /** Guided device pairing, owned by `feature:sharing`. */
    @Serializable
    data object PairDevice : RouteFlow

    /** Choose files and a recipient, then send; owned by `feature:sharing`. */
    @Serializable
    data object Send : RouteFlow
}
