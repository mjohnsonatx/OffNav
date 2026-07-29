package com.example.offnav.map

import android.content.Context
import java.io.File

class TileAssetManager(private val context: Context) {

    companion object {
        private const val TILE_ASSET = "tiles/region.mbtiles"
        private const val TILE_FILE = "region.mbtiles"
        private const val STYLE_ASSET = "style.json"
    }

    /** Copies the bundled mbtiles to internal storage (once) and returns its path. */
    fun ensureTilesOnDisk(): File {
        val dest = File(context.filesDir, TILE_FILE)
        if (!dest.exists()) {
            context.assets.open(TILE_ASSET).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    /** Loads the style JSON and injects the on-device mbtiles path. */
    fun buildStyleJson(): String {
        val tilePath = ensureTilesOnDisk().absolutePath
        val raw = context.assets.open(STYLE_ASSET)
            .bufferedReader().use { it.readText() }
        return raw.replace("{MBTILES_PATH}", "mbtiles:///$tilePath")
    }
}