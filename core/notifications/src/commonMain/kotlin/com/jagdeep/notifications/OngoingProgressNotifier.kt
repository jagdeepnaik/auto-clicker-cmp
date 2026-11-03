package com.jagdeep.notifications

/** Small icon for an ongoing progress notification; mapped to a platform icon. */
enum class ProgressIcon { Upload, Download, Default }

/** Content of an ongoing, silent progress notification. A null [percent] shows an indeterminate bar. */
data class ProgressNotificationContent(
    val title: String,
    val text: String?,
    val percent: Int?,
    val icon: ProgressIcon = ProgressIcon.Default,
    /** Label for a cancel action; shown only when an onCancel callback is supplied. */
    val cancelLabel: String = "Cancel",
)

/**
 * Keeps a user-started, long-running activity (such as a file transfer) visible and alive while
 * the app is in the background. Android backs it with a foreground service; desktop processes
 * stay alive with their window and iOS cannot extend background work, so both do nothing.
 */
expect class OngoingProgressNotifier {
    /** Shows or updates the notification; [onCancel] adds a cancel action when non-null. */
    fun show(content: ProgressNotificationContent, onCancel: (() -> Unit)? = null)

    /** Removes the notification and releases anything keeping the app alive. */
    fun clear()
}

expect fun createOngoingProgressNotifier(): OngoingProgressNotifier
