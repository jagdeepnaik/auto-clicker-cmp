package com.jagdeep.notifications

import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionAlert
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

private val foregroundNotificationDelegate = AppForegroundNotificationDelegate()

actual class AppNotifier {
    init {
        UNUserNotificationCenter.currentNotificationCenter().delegate =
            foregroundNotificationDelegate
    }

    actual fun show(
        notificationId: Int,
        title: String,
        message: String,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 0.1,
            repeats = false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "app-$notificationId-${NSUUID.UUID().UUIDString}",
            content = content,
            trigger = trigger,
        )
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request, withCompletionHandler = null)
    }

    actual fun isActive(notificationId: Int): Boolean = false

    actual fun showWhenInBackground(notificationId: Int, title: String, message: String) {
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
            show(notificationId, title, message)
        }
    }
}

actual fun createAppNotifier(): AppNotifier = AppNotifier()

private class AppForegroundNotificationDelegate :
    NSObject(), UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionAlert or
                    UNNotificationPresentationOptionSound,
        )
    }
}
