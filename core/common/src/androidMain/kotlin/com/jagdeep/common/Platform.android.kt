package com.jagdeep.common

import android.os.Build
import com.jagdeep.common.platform.Platform

class AndroidPlatform : Platform {
    override val name: String = "Android"
    override val version: String = "${Build.VERSION.SDK_INT}"
    override val type: Platform.Type = Platform.Type.ANDROID
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun isAndroid() = true

actual fun isIos() = false

actual fun isJvm() = false

actual fun isWeb() = false