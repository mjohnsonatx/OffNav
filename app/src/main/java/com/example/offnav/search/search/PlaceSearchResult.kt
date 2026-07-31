package com.example.offnav.search.search

data class PlaceSearchResult(
    val name: String,
    val subtitle: String,
    val category: String,       // human-readable, e.g. "Restaurant"
    val osmClass: String,       // raw class from your index, e.g. "restaurant"
    val latitude: Double,
    val longitude: Double,
    /** Filled in after search when sorted by distance. */
    val distanceMeters: Double = 0.0,
) {
    val distanceText: String
        get() = when {
            distanceMeters >= 10_000 -> "%.0f km".format(distanceMeters / 1000.0)
            distanceMeters >= 1_000  -> "%.1f km".format(distanceMeters / 1000.0)
            else                     -> "${distanceMeters.toInt()} m"
        }
}