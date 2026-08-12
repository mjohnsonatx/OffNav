package com.example.offnav.map

import android.content.Context
import com.example.offnav.region.RegionSelection
import com.example.offnav.region.RegionSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TileAssetManager(
    val context: Context,
    private val regions: RegionSelection,
) {
    companion object {
        private const val TILE_ASSET = "tiles/region.mbtiles"
        private const val TILE_FILE = "region.mbtiles"
        private const val STYLE_ASSET = "style.json"
    }

    /** Resolves every selected MBTiles file. Imported regions are already on disk. */
    fun ensureTilesOnDisk(): List<File> = regions.snapshots.map { region -> when (region) {
        is RegionSnapshot.Installed -> region.tilesFile.also {
            check(it.isFile) { "${region.displayName} is missing tiles.mbtiles" }
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
    } }

    fun buildStyleJson(): String {
        val raw = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
        val style = JSONObject(raw)
        val sources = style.getJSONObject("sources")
        val sourceTemplate = JSONObject(sources.getJSONObject("offline").toString())
        sources.remove("offline")

        val sourceIds = ensureTilesOnDisk().mapIndexed { index, tileFile ->
            val sourceId = "offline-region-$index"
            sources.put(
                sourceId,
                JSONObject(sourceTemplate.toString())
                    .put("url", "mbtiles:///${tileFile.absolutePath}"),
            )
            sourceId
        }

        val originalLayers = style.getJSONArray("layers")
        val composedLayers = JSONArray()
        for (layerIndex in 0 until originalLayers.length()) {
            val template = originalLayers.getJSONObject(layerIndex)
            if (template.optString("source") != "offline") {
                composedLayers.put(JSONObject(template.toString()))
                continue
            }
            sourceIds.forEachIndexed { regionIndex, sourceId ->
                composedLayers.put(
                    JSONObject(template.toString())
                        .put("id", "${template.getString("id")}--region-$regionIndex")
                        .put("source", sourceId),
                )
            }
        }
        style.put("layers", composedLayers)
        return style.toString()
    }
}
