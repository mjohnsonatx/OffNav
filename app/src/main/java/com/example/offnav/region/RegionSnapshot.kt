package com.example.offnav.region

import java.io.File

/**
 * The single immutable region identity chosen once at cold start by [com.example.offnav.di.AppContainer]
 * and handed to the tile, routing and search components. Nothing mutates it for the life of the process.
 */
sealed interface RegionSnapshot {
    val pointerValue: String
    val regionId: String
    val displayName: String
    val version: String
    val searchSchema: Int
    /** Null only for legacy descriptors written before bounds were part of the manifest. */
    val bounds: RegionBounds?

    data object BuiltIn : RegionSnapshot {
        override val pointerValue = "builtin"
        override val regionId = "austin"
        override val displayName = "Austin"
        override val version = "bundled"
        override val searchSchema = 2
        // Coverage of the APK-bundled extract — adjust to match your actual build.
        override val bounds = RegionBounds(30.05, 30.52, -98.05, -97.53)
    }

    class Installed(
        val installId: String,
        override val regionId: String,
        override val displayName: String,
        override val version: String,
        override val searchSchema: Int,
        override val bounds: RegionBounds?,
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