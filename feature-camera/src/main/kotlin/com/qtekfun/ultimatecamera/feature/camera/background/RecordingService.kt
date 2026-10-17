package com.qtekfun.ultimatecamera.feature.camera.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.qtekfun.ultimatecamera.feature.camera.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the ongoing notification shows (D66). */
data class BackgroundStatus(
    val recording: Boolean = false,
    val startedAtElapsedMs: Long = 0,
    val fileName: String? = null,
    val mode: String? = null,
    val fps: Int = 0,
    val storageMinutes: Long? = null,
    val roleActive: Boolean = false
)

/** Between the camera screen (which owns the camera) and [RecordingService]; one per process. */
object RecordingServiceBridge {
    private val _status = MutableStateFlow(BackgroundStatus())

    /** The notification's content; also read by the instrumented background test. */
    val status: StateFlow<BackgroundStatus> = _status.asStateFlow()

    /** Called on the main thread when the notification's Stop is pressed; null when no camera screen exists. */
    @Volatile var onStop: (() -> Unit)? = null

    /** Called when the service could not go to the foreground (the camera can then not be kept off screen). */
    @Volatile var onLost: (() -> Unit)? = null

    internal fun publish(status: BackgroundStatus) {
        _status.value = status
    }
}

/**
 * Keeps the take and the camera role alive off screen (D66): a camera (and microphone, when granted) foreground service
 * with an ongoing notification (elapsed time, mode, storage minutes, Stop) and a partial wake lock. The camera pipeline
 * itself stays with the camera screen's view model, in this process; the service only keeps the process in the
 * foreground state Android requires to use the camera and the microphone off screen.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val stop = RecordingServiceBridge.onStop
            if (stop == null) stopSelf() else stop()
            // A stale Stop (the service was already gone) must not leave a started background service behind.
            if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground && !goForeground()) {
            RecordingServiceBridge.onLost?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    @Suppress("TooGenericExceptionCaught") // startForeground throws several platform-specific exceptions.
    private fun goForeground(): Boolean {
        ensureChannels(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        try {
            startForeground(NOTIFICATION_ID, build(RecordingServiceBridge.status.value), types)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot go to the foreground: ${e.message}")
            return false
        }
        foreground = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltimateCamera:take")
            .apply { acquire(WAKE_LOCK_MAX_MS) }
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch { RecordingServiceBridge.status.collect { manager.notify(NOTIFICATION_ID, build(it)) } }
        return true
    }

    private fun build(status: BackgroundStatus): Notification {
        val title = when {
            status.recording -> getString(R.string.background_recording_title, status.mode ?: "")
            else -> getString(R.string.background_role_title)
        }
        val text = status.storageMinutes?.let { resources.getQuantityString(R.plurals.background_storage_minutes, it.toInt(), it) }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_cam_record)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.background_stop), stop).build())
            .apply {
                if (status.recording) {
                    // Wall-clock start of the take: the system chronometer counts the elapsed time without updates.
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - status.startedAtElapsedMs))
                    setUsesChronometer(true)
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.qtekfun.ultimatecamera.action.STOP_TAKE"
        private const val TAG = "RecordingService"
        private const val CHANNEL_ONGOING = "recording"
        private const val CHANNEL_ALERTS = "recording_alerts"
        private const val NOTIFICATION_ID = 0x0C41
        private const val ALERT_ID = 0x0C42

        /** Safety net only: the lock is released when the service stops; 12 h covers the longest take. */
        private const val WAKE_LOCK_MAX_MS = 12L * 60 * 60 * 1000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        /** A take that ended off screen (guard, D47): tells the user why, as a normal (dismissable) notification. */
        fun notifyStopped(context: Context, text: String) {
            ensureChannels(context)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val notification = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_cam_stop)
                .setContentTitle(context.getString(R.string.background_stopped_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            // Without POST_NOTIFICATIONS the system drops it; the reason is shown again on screen anyway.
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
            }
        }

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.background_channel_ongoing), NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.background_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
