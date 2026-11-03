package com.jagdeep.common

import com.jagdeep.common.platform.Platform

class JVMPlatform : Platform {
    override val name: String = "Java Desktop (KMP)"
    override val version: String = "${System.getProperty("java.version")}"
    override val type: Platform.Type = Platform.Type.JVM
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun isJvm() = true

actual fun isAndroid() = false

actual fun isIos() = false

actual fun isWeb() = false