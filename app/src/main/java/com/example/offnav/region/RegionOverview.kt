package com.example.offnav.region

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Builds the small, metadata-only coverage overlay used by the statewide map overview. */
object RegionOverview {
    const val SOURCE_ID = "installed-region-source"
    const val FILL_LAYER_ID = "installed-region-fill"
    const val LINE_LAYER_ID = "installed-region-line"

    fun toGeoJson(regions: List<RegionInfo>): String {
        val features = regions.mapNotNull { region ->
            val bounds = region.bounds ?: return@mapNotNull null
            Feature.fromGeometry(bounds.toPolygon()).apply {
                addStringProperty("installId", region.installId)
                addStringProperty("displayName", region.displayName)
                addStringProperty(
                    "status",
                    when {
                        region.isActive -> "active"
                        region.isPendingActivation -> "pending"
                        else -> "installed"
                    },
                )
            }
        }
        return FeatureCollection.fromFeatures(features).toJson()
    }

    private fun RegionBounds.toPolygon(): Polygon {
        val southWest = Point.fromLngLat(minLongitude, minLatitude)
        val northWest = Point.fromLngLat(minLongitude, maxLatitude)
        val northEast = Point.fromLngLat(maxLongitude, maxLatitude)
        val southEast = Point.fromLngLat(maxLongitude, minLatitude)
        return Polygon.fromLngLats(
            listOf(listOf(southWest, northWest, northEast, southEast, southWest))
        )
    }
}
