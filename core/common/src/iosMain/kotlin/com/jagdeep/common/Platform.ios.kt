package com.jagdeep.common

import com.jagdeep.common.platform.Platform
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName()
    override val version: String = UIDevice.currentDevice.systemVersion
    override val type: Platform.Type = Platform.Type.IOS
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun isIos() = true

actual fun isAndroid() = false

actual fun isJvm() = false

actual fun isWeb() = false