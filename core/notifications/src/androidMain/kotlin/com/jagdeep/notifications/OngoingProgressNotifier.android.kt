package com.jagdeep.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide state the foreground service renders; null means nothing is running. */
private object OngoingProgressState {
    val content = MutableStateFlow<ProgressNotificationContent?>(null)
    @Volatile var onCancel: (() -> Unit)? = null
}

/**
 * Android: a `dataSync` foreground service keeps the process alive while content is shown.
 * Start it from a user action while the app is visible (Android forbids background starts);
 * if a start is refused, the work continues for as long as Android keeps the process.
 */
actual class OngoingProgressNotifier {
    actual fun show(content: ProgressNotificationContent, onCancel: (() -> Unit)?) {
        OngoingProgressState.onCancel = onCancel
        val wasIdle = OngoingProgressState.content.value == null
        OngoingProgressState.content.value = content
        if (wasIdle) {
            val context = AppNotifierRuntime.context
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OngoingProgressService::class.java)) }
        }
    }

    actual fun clear() {
        OngoingProgressState.onCancel = null
        OngoingProgressState.content.value = null
    }
}

actual fun createOngoingProgressNotifier(): OngoingProgressNotifier = OngoingProgressNotifier()

/** Renders [OngoingProgressState]; stops itself when it becomes null. Declared in this module's manifest. */
class OngoingProgressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private val notifier by lazy { createAppNotifier() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) OngoingProgressState.onCancel?.invoke()
        // Android requires startForeground promptly after startForegroundService, even if work just ended.
        val promoted = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, render(OngoingProgressState.content.value), foregroundType())
        }.isSuccess
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (updates == null) {
            updates = scope.launch {
                OngoingProgressState.content.collect { content ->
                    if (content == null) {
                        ServiceCompat.stopForeground(this@OngoingProgressService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notifier.update(NOTIFICATION_ID, render(content))
                    }
                }
            }
        }
        // Not sticky: a restarted service would have nothing to show.
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync at about six hours a day; stop cleanly when it runs out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(content: ProgressNotificationContent?): Notification = notifier.progressNotification(
        content ?: ProgressNotificationContent(title = "Finishing…", text = null, percent = null),
        cancelIntent = if (content != null && OngoingProgressState.onCancel != null) cancelIntent() else null,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, OngoingProgressService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private companion object {
        const val NOTIFICATION_ID = 4040
        const val ACTION_CANCEL = "com.jagdeep.notifications.action.CANCEL_ONGOING"
    }
}
