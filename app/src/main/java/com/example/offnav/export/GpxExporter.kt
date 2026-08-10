package com.example.offnav.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.offnav.data.ActivityDao
import com.example.offnav.data.TrackPointEntity
import com.example.offnav.navigation.RouteGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxOptions(
    /** Drop points within this radius of the track's start AND end. 0 disables. */
    val privacyRadiusMeters: Double = 0.0,
    /** Omit <time> so the file can't be correlated against anything. */
    val stripTimestamps: Boolean = false,
    val includeElevation: Boolean = true,
)

class GpxExporter(
    private val context: Context,
    private val dao: ActivityDao,
) {
    suspend fun export(activityId: Long, options: GpxOptions): Uri = withContext(Dispatchers.IO) {
        val activity = dao.activity(activityId) ?: error("Activity $activityId not found")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        val dir = File(context.cacheDir, "gpx_exports").apply { mkdirs() }
        val safeName = activity.title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(40)
            .ifBlank { "Activity" }
        val file = File(dir, "$safeName.gpx")

        // Endpoints for privacy trimming; cheap because we only need the first/last row.
        val total = dao.pointCount(activityId)
        val first = dao.pointsPage(activityId, 1, 0).firstOrNull()
        val last = dao.pointsPage(activityId, 1, (total - 1).coerceAtLeast(0)).firstOrNull()

        file.bufferedWriter().use { out ->
            out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            out.write("\n<gpx version=\"1.1\" creator=\"OffNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            out.write("  <metadata>\n")
            out.write("    <name>${escape(activity.title)}</name>\n")
            if (activity.note.isNotBlank()) out.write("    <desc>${escape(activity.note)}</desc>\n")
            if (!options.stripTimestamps) out.write("    <time>${iso.format(Date(activity.startedAt))}</time>\n")
            out.write("  </metadata>\n")
            out.write("  <trk>\n    <name>${escape(activity.title)}</name>\n")
            out.write("    <type>${activity.type}</type>\n")

            var currentSegment = -1
            var offset = 0
            var wroteAnySegment = false

            while (true) {
                val page = dao.pointsPage(activityId, PAGE_SIZE, offset)
                if (page.isEmpty()) break
                offset += page.size

                for (p in page) {
                    if (options.privacyRadiusMeters > 0 && p.isPrivate(first, last, options.privacyRadiusMeters)) continue

                    if (p.segment != currentSegment) {
                        if (wroteAnySegment) out.write("    </trkseg>\n")
                        out.write("    <trkseg>\n")
                        currentSegment = p.segment
                        wroteAnySegment = true
                    }

                    out.write("      <trkpt lat=\"${"%.7f".format(Locale.ROOT, p.lat)}\" lon=\"${"%.7f".format(Locale.ROOT, p.lon)}\">\n")
                    if (options.includeElevation) p.altitudeMeters?.let {
                        out.write("        <ele>${"%.1f".format(Locale.ROOT, it)}</ele>\n")
                    }
                    if (!options.stripTimestamps) {
                        out.write("        <time>${iso.format(Date(p.timestamp))}</time>\n")
                    }
                    out.write("      </trkpt>\n")
                }
            }

            if (wroteAnySegment) out.write("    </trkseg>\n")
            out.write("  </trk>\n</gpx>\n")
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun TrackPointEntity.isPrivate(
        first: TrackPointEntity?, last: TrackPointEntity?, radius: Double,
    ): Boolean {
        first?.let { if (RouteGeometry.metersBetween(lat, lon, it.lat, it.lon) < radius) return true }
        last?.let { if (RouteGeometry.metersBetween(lat, lon, it.lat, it.lon) < radius) return true }
        return false
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private companion object { const val PAGE_SIZE = 2_000 }
}