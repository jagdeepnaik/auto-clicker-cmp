package com.jagdeep.ui.navigation

import androidx.navigation3.runtime.NavKey

class Navigator(val navigationState: NavigationState) {

    fun navigate(route: NavKey) {
        if (route in navigationState.backStacks.keys) {
            navigationState.topLevelRoute = route
        } else {
            navigationState.backStacks[navigationState.topLevelRoute]?.add(route)
        }
    }

    fun goBack() {
        val currentStack = navigationState.backStacks[navigationState.topLevelRoute]
            ?: error("Back stack for ${navigationState.topLevelRoute} doesn't exist")

        val currentRoute = currentStack.last()

        if (currentRoute == navigationState.topLevelRoute) {
            navigationState.topLevelRoute = navigationState.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}