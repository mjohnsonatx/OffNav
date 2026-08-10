package com.example.offnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.offnav.App
import com.example.offnav.MainActivity
import com.example.offnav.R
import com.example.offnav.data.RecordingStatus
import com.example.offnav.data.UnitFormat
import com.example.offnav.recording.LiveStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and the while-in-use location grant valid) while
 * [com.example.offnav.recording.ActivityRecorder] runs in the application scope.
 * This service never drives recording — it only mirrors it.
 */
class RecordingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.example.offnav.START_RECORDING"
        const val ACTION_PAUSE = "com.example.offnav.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.offnav.RESUME_RECORDING"
        const val ACTION_STOP = "com.example.offnav.STOP_RECORDING_SERVICE"

        fun start(context: Context) = send(context, ACTION_START, foreground = true)
        fun stop(context: Context) = send(context, ACTION_STOP, foreground = false)

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                this.action = action
            }
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offnav::RecordingWakeLock")
            .apply { acquire(12 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = (application as App).container.activityRecorder

        when (intent?.action) {
            ACTION_PAUSE -> recorder.pause()
            ACTION_RESUME -> recorder.resume()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }

        startInForeground(buildNotification("Recording", "Waiting for GPS…", RecordingStatus.RECORDING))

        scope?.launch {
            recorder.stats.collectLatest { stats ->
                if (stats.status == RecordingStatus.IDLE) { stopSelf(); return@collectLatest }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(
                    title = "${stats.type.emoji} ${stats.type.displayName} · ${UnitFormat.clock(stats.activeMillis)}",
                    text = "${UnitFormat.miles(stats.distanceMeters)} · " +
                            UnitFormat.speedOrPace(stats.avgMovingSpeedMps, stats.type),
                    status = stats.status,
                ))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel(); scope = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String, status: RecordingStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (status == RecordingStatus.PAUSED) ACTION_RESUME else ACTION_PAUSE
        val togglePending = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingForegroundService::class.java).apply { action = toggleAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(
                R.drawable.ic_stop,
                if (status == RecordingStatus.PAUSED) "Resume" else "Pause",
                togglePending,
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Activity recording", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Shows your in-progress activity"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}