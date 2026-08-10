package com.example.offnav.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.offnav.data.ActivitySummary
import com.example.offnav.map.TileAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders a 1080x1350 share card: offline basemap + the recorded track + a stats footer.
 * Nothing leaves the device; the bitmap is written to cacheDir and handed to the chooser.
 */
class ActivityCardRenderer(
    private val context: Context,
    private val tiles: TileAssetManager,
) {
    suspend fun render(summary: ActivitySummary, segments: List<List<LatLng>>): Uri {
        require(segments.any { it.size >= 2 }) { "Track is too short to render" }

        val styleJson = withContext(Dispatchers.IO) { tiles.buildStyleJson() }
        val bitmap = snapshot(styleJson, segments)
        val card = withContext(Dispatchers.Default) { decorate(bitmap, summary) }

        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "activity_cards").apply { mkdirs() }
            val file = File(dir, "activity_${summary.uuid}.png")
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    /** MapSnapshotter must be created and started on the main thread. */
    private suspend fun snapshot(styleJson: String, segments: List<List<LatLng>>): Bitmap =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val geometry = MultiLineString.fromLngLats(
                    segments.filter { it.size >= 2 }.map { seg ->
                        seg.map { Point.fromLngLat(it.longitude, it.latitude) }
                    }
                )
                val source = GeoJsonSource(
                    TRACK_SOURCE,
                    FeatureCollection.fromFeature(Feature.fromGeometry(geometry)),
                )

                // Casing underneath, bright line on top — reads at thumbnail size.
                val casing = LineLayer("$TRACK_LAYER-casing", TRACK_SOURCE).withProperties(
                    lineColor("#ffffff"), lineWidth(11f), lineCap("round"), lineJoin("round"),
                )
                val line = LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                    lineColor("#fc4c02"), lineWidth(6f), lineCap("round"), lineJoin("round"),
                )

                val bounds = padded(segments.flatten())

                val options = MapSnapshotter.Options(CARD_WIDTH, MAP_HEIGHT)
                    .withStyleBuilder(
                        Style.Builder().fromJson(styleJson)
                            .withSource(source)
                            .withLayer(casing)
                            .withLayer(line)
                    )
                    .withRegion(bounds)
                    .withLogo(false)

                val snapshotter = MapSnapshotter(context, options)
                cont.invokeOnCancellation { snapshotter.cancel() }
                snapshotter.start(
                    { snapshot -> cont.resume(snapshot.bitmap) },
                    { error -> cont.resumeWithException(IllegalStateException("Snapshot failed: $error")) },
                )
            }
        }

    private fun padded(points: List<LatLng>): LatLngBounds {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        // A degenerate bbox (out-and-back on one street) makes the snapshotter throw.
        val padLat = ((maxLat - minLat) * 0.15).coerceAtLeast(0.0012)
        val padLon = ((maxLon - minLon) * 0.15).coerceAtLeast(0.0012)
        return LatLngBounds.from(maxLat + padLat, maxLon + padLon, minLat - padLat, minLon - padLon)
    }

    private fun decorate(map: Bitmap, s: ActivitySummary): Bitmap {
        val card = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(Color.parseColor("#111111"))
        canvas.drawBitmap(map, 0f, 0f, null)

        // Scrim so the title is legible over any basemap.
        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, MAP_HEIGHT - 260f, 0f, MAP_HEIGHT.toFloat(),
                Color.TRANSPARENT, Color.parseColor("#CC111111"), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, MAP_HEIGHT - 260f, CARD_WIDTH.toFloat(), MAP_HEIGHT.toFloat(), scrim)

        val title = Paint().apply {
            color = Color.WHITE; textSize = 64f; isFakeBoldText = true; isAntiAlias = true
        }
        val subtitle = Paint().apply {
            @Suppress("UNUSED") // placeholder removed below
            canvas.drawText("${s.type.emoji}  ${s.title}", MARGIN, MAP_HEIGHT - 130f, title)
        }

        val meta = Paint().apply {
            color = Color.parseColor("#BBBBBB"); textSize = 34f; isAntiAlias = true
        }
        val stamp = SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault()).format(Date(s.startedAt))
        canvas.drawText(stamp, MARGIN, MAP_HEIGHT - 70f, meta)

        val stats = buildList {
            add("DISTANCE" to s.distanceText)
            add("TIME" to s.durationText)
            add(if (s.type.usesPace) "PACE" to s.avgSpeedText else "AVG" to s.avgSpeedText)
            s.elevationGainText?.let { add("ELEV GAIN" to it) }
        }.take(4)

        val labelPaint = Paint().apply {
            color = Color.parseColor("#888888"); textSize = 28f; isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.WHITE; textSize = 58f; isFakeBoldText = true; isAntiAlias = true
        }

        val columnWidth = (CARD_WIDTH - 2 * MARGIN) / stats.size
        stats.forEachIndexed { i, (label, value) ->
            val x = MARGIN + i * columnWidth
            canvas.drawText(label, x, MAP_HEIGHT + 80f, labelPaint)
            canvas.drawText(value, x, MAP_HEIGHT + 150f, valuePaint)
        }

        val brand = Paint().apply {
            color = Color.parseColor("#555555"); textSize = 26f; isAntiAlias = true
        }
        canvas.drawText("Recorded offline with OffNav", MARGIN, CARD_HEIGHT - 40f, brand)
        return card
    }

    private companion object {
        const val TRACK_SOURCE = "activity-track-source"
        const val TRACK_LAYER = "activity-track-layer"
        const val CARD_WIDTH = 1080
        const val MAP_HEIGHT = 1080
        const val CARD_HEIGHT = 1350
        const val MARGIN = 56f
    }
}