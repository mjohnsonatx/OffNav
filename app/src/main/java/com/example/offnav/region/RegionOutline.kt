package com.example.offnav.region

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Polygon

/**
 * Loads `assets/texas_outline.geojson` and normalises it to a FeatureCollection of
 * Polygon/MultiPolygon only. EPSG:4326 (lon, lat) is assumed — GeoJSON mandates it, and
 * MapLibre assumes it, so we only sanity-check the ordinate ranges.
 *
 * Returns null if the asset is absent or unusable: the map must still render without it.
 */
object RegionOutline {

    const val ASSET = "texas_outline.geojson"
    const val SOURCE_ID = "region-outline-source"
    const val FILL_LAYER_ID = "region-outline-fill"
    const val LINE_LAYER_ID = "region-outline-line"

    private const val TAG = "RegionOutline"
    private const val MAX_ASSET_BYTES = 16 * 1024 * 1024

    /** Call from an IO dispatcher. */
    fun load(context: Context): String? = runCatching {
        val raw = context.assets.open(ASSET).use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_ASSET_BYTES) { "outline asset is too large" }
            String(bytes, Charsets.UTF_8)
        }
        normalize(raw)
    }.onFailure { Log.w(TAG, "Outline unavailable: ${it.message}") }.getOrNull()

    /** Accepts a FeatureCollection, a bare Feature, or a bare Polygon/MultiPolygon geometry. */
    internal fun normalize(raw: String): String? {
        val type = JSONObject(raw).optString("type")
        val features: List<Feature> = when (type) {
            "FeatureCollection" -> FeatureCollection.fromJson(raw).features().orEmpty()
            "Feature" -> listOf(Feature.fromJson(raw))
            "Polygon" -> listOf(Feature.fromGeometry(Polygon.fromJson(raw)))
            "MultiPolygon" -> listOf(Feature.fromGeometry(MultiPolygon.fromJson(raw)))
            else -> {
                Log.w(TAG, "Unsupported GeoJSON root type \"$type\"")
                return null
            }
        }

        val polygons = features.filter { f ->
            val g = f.geometry()
            (g is Polygon || g is MultiPolygon) && inRange(g)
        }
        if (polygons.isEmpty()) {
            Log.w(TAG, "Outline contains no usable Polygon/MultiPolygon geometry")
            return null
        }
        if (polygons.size != features.size) {
            Log.w(TAG, "Dropped ${features.size - polygons.size} non-polygon outline feature(s)")
        }
        return FeatureCollection.fromFeatures(polygons).toJson()
    }

    /** Cheap guard against lat/lon being swapped in the source data. */
    private fun inRange(geometry: Geometry?): Boolean {
        val rings = when (geometry) {
            is Polygon -> geometry.coordinates()
            is MultiPolygon -> geometry.coordinates().flatten()
            else -> return false
        }
        return rings.all { ring ->
            ring.all { p -> p.longitude() in -180.0..180.0 && p.latitude() in -90.0..90.0 }
        }
    }
}
