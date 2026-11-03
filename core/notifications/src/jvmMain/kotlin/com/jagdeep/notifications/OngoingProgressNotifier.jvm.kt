package com.jagdeep.notifications

/** Desktop shows progress in its window and stays alive while the window is open. */
actual class OngoingProgressNotifier {
    actual fun show(content: ProgressNotificationContent, onCancel: (() -> Unit)?) = Unit
    actual fun clear() = Unit
}

actual fun createOngoingProgressNotifier(): OngoingProgressNotifier = OngoingProgressNotifier()
