package com.example.offnav.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_history",
    indices = [
        Index(value = ["destKey"], unique = true),   // one row per destination
        Index(value = ["lastUsedAt"]),
    ]
)
data class RouteHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Human label, e.g. "Alexanderplatz" or "Unter den Linden 12". */
    val label: String,
    /** Secondary line, e.g. street or coordinates. */
    val subtitle: String,

    val originLat: Double,
    val originLon: Double,
    val destLat: Double,
    val destLon: Double,

    /** Rounded "lat,lon" used for dedupe (~1 m precision). */
    val destKey: String,

    val distanceMeters: Double,
    val durationMillis: Long,

    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val pinned: Boolean = false,
) {
    companion object {
        fun destKeyOf(lat: Double, lon: Double): String = "%.5f,%.5f".format(lat, lon)
    }
}