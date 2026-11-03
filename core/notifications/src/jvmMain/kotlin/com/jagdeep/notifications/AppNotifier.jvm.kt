package com.jagdeep.notifications

import com.jagdeep.common.config.AppConfig
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

actual class AppNotifier {
    private val trayIcon: TrayIcon? by lazy { createTrayIcon() }

    actual fun show(notificationId: Int, title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    actual fun isActive(notificationId: Int): Boolean = false

    /** Desktop raises its window for incoming requests; a tray message would duplicate it. */
    actual fun showWhenInBackground(notificationId: Int, title: String, message: String) = Unit

    private fun createTrayIcon(): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        val tray = SystemTray.getSystemTray()
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        return runCatching {
            TrayIcon(image, AppConfig.APP_NAME).also {
                it.isImageAutoSize = true
                tray.add(it)
            }
        }.getOrNull()
    }
}

actual fun createAppNotifier(): AppNotifier = AppNotifier()
