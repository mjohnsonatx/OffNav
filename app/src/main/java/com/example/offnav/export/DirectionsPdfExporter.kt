// com/example/offnav/export/DirectionsPdfExporter.kt
package com.example.offnav.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.offnav.routing.RouteResult
import com.example.offnav.routing.TurnInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DirectionsPdfExporter(private val context: Context) {

    companion object {
        private const val PAGE_WIDTH = 595   // A4 in points
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 48f
        private const val LINE_HEIGHT = 18f
        private const val HEADER_HEIGHT = 32f
    }

    suspend fun export(
        route: RouteResult,
        destinationLabel: String,
        destinationSubtitle: String,
    ): Uri = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        try {
            val titlePaint = Paint().apply {
                textSize = 22f; isFakeBoldText = true; isAntiAlias = true
            }
            val subtitlePaint = Paint().apply {
                textSize = 13f; color = 0xFF666666.toInt(); isAntiAlias = true
            }
            val headerPaint = Paint().apply {
                textSize = 16f; isFakeBoldText = true; isAntiAlias = true
            }
            val bodyPaint = Paint().apply {
                textSize = 12f; isAntiAlias = true
            }
            val distPaint = Paint().apply {
                textSize = 11f; color = 0xFF3B82F6.toInt(); isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = 0xFFDDDDDD.toInt(); strokeWidth = 0.5f
            }

            var pageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            var page = doc.startPage(pageInfo)
            var canvas = page.canvas
            var y = MARGIN

            // Title
            val timestamp = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                .format(Date())
            canvas.drawText("Directions to $destinationLabel", MARGIN, y + 22f, titlePaint)
            y += 30f
            if (destinationSubtitle.isNotBlank()) {
                canvas.drawText(destinationSubtitle, MARGIN, y + 13f, subtitlePaint)
                y += 20f
            }

            // Summary
            y += 10f
            val distText = formatMeters(route.distanceMeters)
            val durText = formatDuration(route.timeMillis / 1000)
            canvas.drawText(
                "$distText · $durText · ${route.instructions.size} steps · Generated $timestamp",
                MARGIN, y + 13f, subtitlePaint
            )
            y += 30f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
            y += 15f

            // Instructions
            fun ensureSpace(needed: Float): Canvas {
                if (y + needed > PAGE_HEIGHT - MARGIN) {
                    doc.finishPage(page)
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                    page = doc.startPage(pageInfo)
                    y = MARGIN
                    return page.canvas
                }
                return canvas
            }

            route.instructions.forEachIndexed { i, instr ->
                canvas = ensureSpace(50f)

                val arrow = maneuverArrow(instr.sign)
                val stepLabel = "${i + 1}."

                canvas.drawText("$stepLabel $arrow", MARGIN, y + 14f, headerPaint)
                y += LINE_HEIGHT

                // Word-wrap instruction text
                val maxWidth = PAGE_WIDTH - 2 * MARGIN - 10f
                val words = instr.text.split(" ")
                var line = ""
                for (word in words) {
                    val test = if (line.isEmpty()) word else "$line $word"
                    if (bodyPaint.measureText(test) > maxWidth && line.isNotEmpty()) {
                        canvas = ensureSpace(LINE_HEIGHT)
                        canvas.drawText(line, MARGIN + 10f, y + 12f, bodyPaint)
                        y += LINE_HEIGHT
                        line = word
                    } else {
                        line = test
                    }
                }
                if (line.isNotEmpty()) {
                    canvas = ensureSpace(LINE_HEIGHT)
                    canvas.drawText(line, MARGIN + 10f, y + 12f, bodyPaint)
                    y += LINE_HEIGHT
                }

                canvas = ensureSpace(LINE_HEIGHT)
                canvas.drawText(formatMeters(instr.distanceMeters), MARGIN + 10f, y + 11f, distPaint)
                y += LINE_HEIGHT

                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
                y += 10f
            }

            doc.finishPage(page)

            // Write to file
            val dir = File(context.cacheDir, "pdf_exports").apply { mkdirs() }
            val sanitized = destinationLabel.replace(Regex("[^a-zA-Z0-9 ]"), "").take(40).trim()
            val file = File(dir, "Directions_$sanitized.pdf")
            file.outputStream().use { doc.writeTo(it) }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } finally {
            doc.close()
        }
    }

    private fun maneuverArrow(sign: Int) = when (sign) {
        -3 -> "Sharp left"; -2 -> "Left"; -1 -> "Slight left"; 0 -> "Straight"
        1 -> "Slight right"; 2 -> "Right"; 3 -> "Sharp right"
        4 -> "Finish"; 5 -> "Via point"; 6 -> "Roundabout"
        else -> "Continue"
    }

    private fun formatMeters(m: Double): String = when {
        m >= 1000 -> "%.1f km".format(m / 1000.0)
        else -> "${m.toInt()} m"
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}min" else "${m} min"
    }
}