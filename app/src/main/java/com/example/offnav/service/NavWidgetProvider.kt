package com.example.offnav.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.offnav.MainActivity
import com.example.offnav.R


class NavWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NAV_UPDATE = "com.example.offnav.NAV_UPDATE"

        /** Called directly from the foreground service — no broadcast needed. */
        fun updateAll(
            context: Context,
            instruction: String,
            maneuver: Int,
            distToTurn: Int,
            remaining: Int,
            remainingSec: Int,
        ) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, NavWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildNavViews(
                context, instruction, maneuver, distToTurn, remaining, remainingSec
            )
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        /** Reset all widgets to idle state. */
        fun resetAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, NavWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildIdleViews(context)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun buildNavViews(
            context: Context,
            instruction: String,
            maneuver: Int,
            distToTurn: Int,
            remaining: Int,
            remainingSec: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nav)

            views.setTextViewText(R.id.widget_maneuver, maneuverArrow(maneuver))
            views.setTextViewText(R.id.widget_instruction, instruction)
            views.setTextViewText(R.id.widget_dist_to_turn, formatDist(distToTurn))
            views.setTextViewText(
                R.id.widget_remaining,
                "${formatDist(remaining)} · ${formatEta(remainingSec)}"
            )

            views.setOnClickPendingIntent(R.id.widget_root, openAppPending(context))
            return views
        }

        private fun buildIdleViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_nav)
            views.setTextViewText(R.id.widget_maneuver, "🗺️")
            views.setTextViewText(R.id.widget_instruction, "OffNav")
            views.setTextViewText(R.id.widget_dist_to_turn, "Tap to open")
            views.setTextViewText(R.id.widget_remaining, "No active navigation")
            views.setOnClickPendingIntent(R.id.widget_root, openAppPending(context))
            return views
        }

        private fun openAppPending(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun maneuverArrow(sign: Int) = when (sign) {
            -3 -> "⤺"; -2 -> "←"; -1 -> "↰"; 0 -> "↑"
            1 -> "↱"; 2 -> "→"; 3 -> "⤻"; 4 -> "🏁"; else -> "↑"
        }

        private fun formatDist(m: Int) =
            if (m >= 1000) "%.1f km".format(m / 1000.0) else "$m m"

        private fun formatEta(s: Int): String {
            val h = s / 3600; val m = (s % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m} min"
        }
    }

    /** Called when the widget is first placed or the system refresh fires. */
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, buildIdleViews(context)) }
    }
}