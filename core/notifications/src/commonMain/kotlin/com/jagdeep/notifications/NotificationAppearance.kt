package com.jagdeep.notifications

/**
 * Accent colour for system notifications (ARGB), so they match the app's theme. Set by the app
 * whenever its palette changes; null keeps the platform default. Only Android uses it; desktop
 * and iOS notifications are drawn entirely by the OS.
 */
object NotificationAppearance {
    @kotlin.concurrent.Volatile
    var accentColor: Int? = null
}
