package com.example.offnav.region

import java.io.File

/**
 * The single immutable region identity chosen once at cold start by [com.example.offnav.di.AppContainer]
 * and handed to the tile, routing and search components. Nothing mutates it for the life of the process.
 */
sealed interface RegionSnapshot {
    val pointerValue: String
    val regionId: String
    val version: String
    val searchSchema: Int

    /** The region that ships inside the APK. Materialised lazily from assets, exactly as before. */
    data object BuiltIn : RegionSnapshot {
        override val pointerValue = "builtin"
        override val regionId = "austin"
        override val version = "bundled"
        override val searchSchema = 2
    }

    /** A region imported from a `.offnav` bundle and published under `filesDir/regions/<installId>`. */
    class Installed(
        val installId: String,
        override val regionId: String,
        override val version: String,
        override val searchSchema: Int,
        val dir: File,
    ) : RegionSnapshot {
        override val pointerValue: String get() = installId
        val tilesFile: File get() = File(dir, "tiles.mbtiles")
        val graphDir: File get() = File(dir, "routing")
        val searchDb: File get() = File(dir, "search.db")

        fun isIntact(): Boolean =
            tilesFile.isFile && graphDir.isDirectory && searchDb.isFile
    }
}