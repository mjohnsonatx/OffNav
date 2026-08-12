package com.example.offnav.region

import java.io.File

/** One immutable region resource set selected at cold start. */
sealed interface RegionSnapshot {
    val pointerValue: String
    val regionId: String
    val displayName: String
    val version: String
    val searchSchema: Int
    val bounds: RegionBounds?
    val installedBytes: Long?

    data object BuiltIn : RegionSnapshot {
        override val pointerValue = "builtin"
        override val regionId = "austin"
        override val displayName = "Austin"
        override val version = "bundled"
        override val searchSchema = 2
        override val bounds = RegionBounds(30.05, 30.52, -98.05, -97.53)
        override val installedBytes: Long? = null   // measured lazily by the catalog
    }

    class Installed(
        val installId: String,
        override val regionId: String,
        override val displayName: String,
        override val version: String,
        override val searchSchema: Int,
        override val bounds: RegionBounds?,
        override val installedBytes: Long?,
        val dir: File,
    ) : RegionSnapshot {
        override val pointerValue: String get() = installId
        val tilesFile: File get() = File(dir, "tiles.mbtiles")
        val graphDir: File get() = File(dir, "routing")
        val searchDb: File get() = File(dir, "search.db")
        fun isIntact(): Boolean = tilesFile.isFile && graphDir.isDirectory && searchDb.isFile
    }
}

/**
 * The complete, immutable set of regions loaded by MapLibre, GraphHopper and search for this
 * process. Changes are staged on disk and become visible together after a cold restart.
 */
data class RegionSelection(val snapshots: List<RegionSnapshot>) {
    init {
        require(snapshots.isNotEmpty()) { "At least one offline region must be loaded" }
        require(snapshots.map { it.pointerValue }.distinct().size == snapshots.size) {
            "Duplicate region install in selection"
        }
        require(snapshots.map { it.regionId }.distinct().size == snapshots.size) {
            "Only one installed version of each region can be loaded"
        }
    }

    val pointerValues: Set<String> = snapshots.mapTo(linkedSetOf()) { it.pointerValue }
    val displayName: String = when (snapshots.size) {
        1 -> snapshots.single().displayName
        else -> "${snapshots.size} offline regions"
    }

    fun contains(latitude: Double, longitude: Double): Boolean =
        snapshots.any { it.bounds?.contains(latitude, longitude) == true }
}
