package com.jagdeep.notifications

expect class AppNotifier {
    fun show(notificationId: Int, title: String, message: String)
    fun isActive(notificationId: Int): Boolean

    /**
     * Alerts only while the app is not on screen (in-app UI covers the visible case). Desktop
     * does nothing here: it raises its own window instead of a tray message.
     */
    fun showWhenInBackground(notificationId: Int, title: String, message: String)
}

expect fun createAppNotifier(): AppNotifier
