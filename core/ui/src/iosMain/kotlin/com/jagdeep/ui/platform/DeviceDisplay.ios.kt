package com.jagdeep.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun KeepDeviceDisplayOn(isEnabled: Boolean) {
    DisposableEffect(isEnabled) {
        UIApplication.sharedApplication.idleTimerDisabled = isEnabled

        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}
