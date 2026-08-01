package com.example.offnav.map

import android.content.Context
import com.example.offnav.region.RegionSnapshot
import java.io.File

class TileAssetManager(
    val context: Context,
    private val region: RegionSnapshot,
) {
    companion object {
        private const val TILE_ASSET = "tiles/region.mbtiles"
        private const val TILE_FILE = "region.mbtiles"
        private const val STYLE_ASSET = "style.json"
    }

    /** Resolves the mbtiles for the *active* snapshot only. Imported regions are already on disk. */
    fun ensureTilesOnDisk(): File = when (region) {
        is RegionSnapshot.Installed -> region.tilesFile.also {
            check(it.isFile) { "Active region is missing tiles.mbtiles" }
        }
        RegionSnapshot.BuiltIn -> File(context.filesDir, TILE_FILE).also { dest ->
            if (!dest.exists()) {
                val partial = File(context.filesDir, "$TILE_FILE.partial")
                try {
                    context.assets.open(TILE_ASSET).use { input ->
                        partial.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(partial.renameTo(dest)) { "Could not install bundled tiles" }
                } catch (t: Throwable) { partial.delete(); throw t }
            }
        }
    }

    fun buildStyleJson(): String {
        val tilePath = ensureTilesOnDisk().absolutePath
        val raw = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
        return raw.replace("{MBTILES_PATH}", "mbtiles:///$tilePath")
    }
}