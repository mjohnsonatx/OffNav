// com/example/offnav/service/NavigationForegroundService.kt
package com.example.offnav.service

import android.app.*
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
import com.example.offnav.navigation.NavBanner
import com.example.offnav.navigation.NavState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class NavigationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nav_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.offnav.START_NAV"
        const val ACTION_STOP = "com.example.offnav.STOP_NAV"

        fun start(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification("Navigation active", "Calculating route…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope?.launch {
            observeNavState()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "offnav::NavigationWakeLock"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hour max as safety net
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private suspend fun observeNavState() {
        val container = (application as App).container
        container.navigationEngine.navState.collectLatest { state ->
            when (state) {
                is NavState.Navigating -> {
                    updateNotification(state.banner)
                    updateWidgets(state.banner)
                }
                is NavState.Rerouting -> {
                    val notif = buildNotification(
                        "Rerouting…",
                        state.lastBanner?.let {
                            "${formatDistance(it.remainingMeters)} remaining"
                        } ?: "Finding new route"
                    )
                    getNotificationManager().notify(NOTIFICATION_ID, notif)
                }
                NavState.Arrived -> {
                    val notif = buildNotification("You have arrived! 🎉", "Navigation complete")
                    getNotificationManager().notify(NOTIFICATION_ID, notif)
                    delay(5_000)
                    stopSelf()
                }
                NavState.Idle -> stopSelf()
            }
        }
    }

    private fun updateNotification(banner: NavBanner) {
        val title = "${maneuverArrow(banner.maneuverSign)} ${banner.instructionText}"
        val text = "${formatDistance(banner.distanceToManeuverMeters)} · " +
                "${formatDistance(banner.remainingMeters)} remaining · " +
                "${formatEta(banner.remainingSeconds)}"
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun updateWidgets(banner: NavBanner) {
        // Broadcast to lock screen and home widgets
        val intent = Intent(NavWidgetProvider.ACTION_NAV_UPDATE).apply {
            putExtra("instruction", banner.instructionText)
            putExtra("maneuver", banner.maneuverSign)
            putExtra("distToTurn", banner.distanceToManeuverMeters)
            putExtra("remaining", banner.remainingMeters)
            putExtra("remainingSec", banner.remainingSeconds)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation) // create this drawable
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // shows on lock screen
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Turn-by-turn navigation updates"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    private fun getNotificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun maneuverArrow(sign: Int): String = when (sign) {
        -3 -> "⤺"; -2 -> "←"; -1 -> "↰"; 0 -> "↑"
        1 -> "↱"; 2 -> "→"; 3 -> "⤻"; 4 -> "🏁"
        else -> "↑"
    }

    private fun formatDistance(meters: Int): String = when {
        meters >= 1_000 -> "%.1f km".format(meters / 1000.0)
        else -> "$meters m"
    }

    private fun formatEta(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}min" else "${m} min"
    }
}