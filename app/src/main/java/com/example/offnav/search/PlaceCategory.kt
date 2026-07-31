package com.example.offnav.search


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class PlaceCategory(
    val label: String,
    val icon: ImageVector,
    /** OSM `class` values (OpenMapTiles / your place index schema). */
    val osmClasses: Set<String>,
) {
    RESTAURANTS(
        "Restaurants", Icons.Default.Restaurant,
        setOf("restaurant", "fast_food", "cafe", "bar", "pub", "food_court", "bakery", "ice_cream"),
    ),
    FUEL(
        "Fuel", Icons.Default.LocalGasStation,
        setOf("fuel", "charging_station"),
    ),
    HOSPITALS(
        "Hospitals", Icons.Default.LocalHospital,
        setOf("hospital", "clinic", "doctors", "pharmacy", "dentist", "veterinary"),
    ),
    PARKS(
        "Parks", Icons.Default.Park,
        setOf("park", "garden", "playground", "nature_reserve", "dog_park"),
    ),
    BUSINESSES(
        "Businesses", Icons.Default.Store,
        setOf(
            "shop", "supermarket", "convenience", "department_store", "mall",
            "bank", "post_office", "car_repair", "hairdresser", "laundry",
        ),
    );

    fun matches(osmClass: String): Boolean = osmClass.lowercase() in osmClasses
}