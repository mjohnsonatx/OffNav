package com.example.offnav.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import com.example.offnav.data.ActivitySummary

object ActivitySharer {

    fun shareCard(context: Context, uri: Uri, summary: ActivitySummary) {
        val caption = buildString {
            appendLine("${summary.type.emoji} ${summary.title}")
            append(summary.distanceText).append(" · ").append(summary.durationText)
            append(" · ").append(summary.avgSpeedText)
            summary.elevationGainText?.let { append(" · ").append(it).append(" gain") }
        }
        launch(context, uri, "image/png", summary.title, caption, "Share activity")
    }

    fun shareGpx(context: Context, uri: Uri, summary: ActivitySummary) {
        launch(context, uri, "application/gpx+xml", summary.title, "", "Export GPX")
    }

    private fun launch(
        context: Context, uri: Uri, mime: String,
        subject: String, text: String, chooserTitle: String,
    ) {
        val intent = ShareCompat.IntentBuilder(context)
            .setType(mime)
            .setStream(uri)
            .setSubject(subject)
            .apply { if (text.isNotBlank()) setText(text) }
            .intent
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}