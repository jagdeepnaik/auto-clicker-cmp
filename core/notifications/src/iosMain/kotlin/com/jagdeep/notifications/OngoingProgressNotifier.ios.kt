package com.jagdeep.notifications

/** iOS gives no way to keep an arbitrary transfer running in the background; nothing to show. */
actual class OngoingProgressNotifier {
    actual fun show(content: ProgressNotificationContent, onCancel: (() -> Unit)?) = Unit
    actual fun clear() = Unit
}

actual fun createOngoingProgressNotifier(): OngoingProgressNotifier = OngoingProgressNotifier()
