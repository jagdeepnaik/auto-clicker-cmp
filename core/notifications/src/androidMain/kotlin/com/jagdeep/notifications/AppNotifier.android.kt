package com.jagdeep.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val DEFAULT_CHANNEL_ID = "app_notifications"
private const val DEFAULT_CHANNEL_NAME = "App Notifications"
private const val PROGRESS_CHANNEL_ID = "transfers"
private const val PROGRESS_CHANNEL_NAME = "Transfers"

/** True on Android 13+ until the user grants POST_NOTIFICATIONS. */
fun Context.needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Asks for notification permission (Android 13+) when it is missing; call at a moment that explains why. */
fun Activity.requestNotificationPermissionIfNeeded() {
    if (needsNotificationPermission()) {
        androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }
}

private const val NOTIFICATION_PERMISSION_REQUEST = 0x2E0F

/** Whether any of this app's activities is visible to the user. */
internal fun Context.isAppInForeground(): Boolean {
    val info = android.app.ActivityManager.RunningAppProcessInfo()
    android.app.ActivityManager.getMyMemoryState(info)
    return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}

internal object AppNotifierRuntime {
    val context: Context get() = requireNotNull(AppNotifierContext.appContext) { "AppNotifier is not initialized." }
}

private object AppNotifierContext {
    var appContext: Context? = null
    var launchActivityClass: Class<out Activity>? = null
    var smallIconResId: Int = 0
}

fun initAppNotifier(
    context: Context,
    launchActivityClass: Class<out Activity>,
    @DrawableRes smallIconResId: Int,
) {
    AppNotifierContext.appContext = context.applicationContext
    AppNotifierContext.launchActivityClass = launchActivityClass
    AppNotifierContext.smallIconResId = smallIconResId
}

actual class AppNotifier internal constructor(
    private val context: Context,
    private val launchActivityClass: Class<out Activity>,
    @DrawableRes private val smallIconResId: Int,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    @SuppressLint("MissingPermission")
    actual fun show(notificationId: Int, title: String, message: String) {
        if (context.needsNotificationPermission()) {
            return
        }
        if (!runCatching { notificationManager.areNotificationsEnabled() }.getOrDefault(false)) {
            return
        }

        if (runCatching { ensureChannel() }.isFailure) {
            return
        }

        val notification = NotificationCompat.Builder(context, DEFAULT_CHANNEL_ID)
            .setSmallIcon(smallIconResId)
            .applyAccent()
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify call.
        }
    }

    /** Ongoing, silent progress notification; [cancelIntent] adds the cancel action. */
    internal fun progressNotification(content: ProgressNotificationContent, cancelIntent: PendingIntent?): Notification {
        runCatching { ensureProgressChannel() }
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .applyAccent()
            .setSmallIcon(
                when (content.icon) {
                    ProgressIcon.Upload -> android.R.drawable.stat_sys_upload
                    ProgressIcon.Download -> android.R.drawable.stat_sys_download
                    ProgressIcon.Default -> smallIconResId
                },
            )
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setProgress(100, content.percent ?: 0, content.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent())
            .apply { cancelIntent?.let { addAction(0, content.cancelLabel, it) } }
            .build()
    }

    /** Updates a posted notification; silently skipped when posting is not permitted. */
    @SuppressLint("MissingPermission")
    internal fun update(notificationId: Int, notification: Notification) {
        if (context.needsNotificationPermission()) return
        try {
            notificationManager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify call.
        }
    }

    private fun ensureProgressChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(PROGRESS_CHANNEL_ID, PROGRESS_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of files being sent or received."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    actual fun showWhenInBackground(notificationId: Int, title: String, message: String) {
        if (!context.isAppInForeground()) show(notificationId, title, message)
    }

    actual fun isActive(notificationId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.any { notification -> notification.id == notificationId }
                ?: false
        }.getOrDefault(false)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            DEFAULT_CHANNEL_ID,
            DEFAULT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "General app alerts."
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(context, launchActivityClass)
            .setAction("com.jagdeep.notifications.OPEN_APP")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

actual fun createAppNotifier(): AppNotifier {
    val context = requireNotNull(AppNotifierContext.appContext) {
        "AppNotifier is not initialized. Call initAppNotifier(context, launchActivityClass, smallIconResId) from Android startup."
    }
    val launchActivityClass = requireNotNull(AppNotifierContext.launchActivityClass) {
        "AppNotifier launch activity is not initialized."
    }
    val smallIconResId = AppNotifierContext.smallIconResId
    require(smallIconResId != 0) {
        "AppNotifier small notification icon is not initialized."
    }
    return AppNotifier(
        context = context,
        launchActivityClass = launchActivityClass,
        smallIconResId = smallIconResId,
    )
}

/** Tints the notification's icon and actions with the app's current theme colour. */
private fun NotificationCompat.Builder.applyAccent(): NotificationCompat.Builder =
    NotificationAppearance.accentColor?.let(::setColor) ?: this
