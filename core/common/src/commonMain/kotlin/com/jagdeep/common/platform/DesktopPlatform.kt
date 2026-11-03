package com.jagdeep.common.platform

/**
 * Desktop operating-system and CPU combinations supported bundled native tools.
 * This stays separate from [Platform.Type], which identifies the Kotlin Multiplatform target.
 */
enum class DesktopPlatform {
    MACOS_ARM64,
    MACOS_X64,
    WINDOWS_X64,
    LINUX_X64,
}

/** Returns the current supported desktop platform, or null on non-desktop KMP targets. */
expect fun currentDesktopPlatformOrNull(): DesktopPlatform?
