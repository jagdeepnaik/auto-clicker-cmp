package com.jagdeep.common.platform

import java.util.Locale

actual fun currentDesktopPlatformOrNull(): DesktopPlatform? =
    detectDesktopPlatformOrNull(System.getProperty("os.name"), System.getProperty("os.arch"))

/** Resolves JVM OS properties to one of the native-tool bundles shipped by Zynk. */
fun requireCurrentDesktopPlatform(): DesktopPlatform =
    detectDesktopPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))

fun detectDesktopPlatformOrNull(osName: String, architecture: String): DesktopPlatform? =
    runCatching { detectDesktopPlatform(osName, architecture) }.getOrNull()

fun detectDesktopPlatform(osName: String, architecture: String): DesktopPlatform {
    val os = osName.lowercase(Locale.ROOT)
    val arch = architecture.lowercase(Locale.ROOT)
    val isArm64 = arch in setOf("aarch64", "arm64")
    val isX64 = arch in setOf("x86_64", "amd64", "x64", "x86-64")

    return when {
        os.contains("mac") && isArm64 -> DesktopPlatform.MACOS_ARM64
        os.contains("mac") && isX64 -> DesktopPlatform.MACOS_X64
        os.contains("win") && isX64 -> DesktopPlatform.WINDOWS_X64
        os.contains("linux") && isX64 -> DesktopPlatform.LINUX_X64
        os.contains("mac") || os.contains("win") || os.contains("linux") ->
            error("Unsupported desktop architecture: $osName ($architecture).")
        else -> error("Unsupported desktop platform: $osName ($architecture).")
    }
}
