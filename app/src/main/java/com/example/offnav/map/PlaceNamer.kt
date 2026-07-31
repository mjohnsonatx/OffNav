package com.example.offnav.map


import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

data class PlaceLabel(val label: String, val subtitle: String)

/**
 * Derives a human label for a map point from the rendered vector tiles.
 * MUST be called on the main thread (MapLibre requirement).
 */
object PlaceNamer {

    private val LAYERS = arrayOf("poi-label", "housenumber", "road-label", "place-label")

    fun nameAt(map: MapLibreMap, target: LatLng, radiusPx: Float = 32f): PlaceLabel {
        val p = map.projection.toScreenLocation(target)
        val box = RectF(p.x - radiusPx, p.y - radiusPx, p.x + radiusPx, p.y + radiusPx)

        var poi: String? = null
        var road: String? = null
        var house: String? = null
        var place: String? = null

        runCatching { map.queryRenderedFeatures(box, *LAYERS) }
            .getOrDefault(emptyList())
            .forEach { f ->
                val name = f.getStringProperty("name:latin")
                    ?: f.getStringProperty("name")
                when {
                    f.hasProperty("housenumber") -> house = f.getStringProperty("housenumber")
                    f.hasProperty("class") && f.hasProperty("subclass") -> poi = poi ?: name
                    name != null && road == null -> road = name
                    name != null && place == null -> place = name
                }
            }

        val coords = "%.5f, %.5f".format(target.latitude, target.longitude)
        return when {
            poi != null -> PlaceLabel(poi!!, road ?: place ?: coords)
            house != null && road != null -> PlaceLabel("$road $house", place ?: coords)
            road != null -> PlaceLabel(road!!, place ?: coords)
            place != null -> PlaceLabel(place!!, coords)
            else -> PlaceLabel("Dropped pin", coords)
        }
    }
}