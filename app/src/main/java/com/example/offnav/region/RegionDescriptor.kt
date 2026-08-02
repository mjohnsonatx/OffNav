package com.example.offnav.region

import org.json.JSONObject
import java.io.File

/**
 * The on-disk `region.json` written once at publish time and never mutated.
 * Format 2 added `installedBytes`; format 1 descriptors are still readable.
 */
data class RegionDescriptor(
    val regionId: String,
    val displayName: String,
    val version: String,
    val searchSchema: Int,
    val bounds: RegionBounds?,
    /** Bytes actually written during import. Null for format-1 descriptors. */
    val installedBytes: Long?,
) {
    fun toJson(): String = JSONObject()
        .put("format", FORMAT)
        .put("id", regionId)
        .put("displayName", displayName)
        .put("version", version)
        .put("searchSchema", searchSchema)
        .also { json ->
            bounds?.let {
                json.put("minLatitude", it.minLatitude)
                json.put("maxLatitude", it.maxLatitude)
                json.put("minLongitude", it.minLongitude)
                json.put("maxLongitude", it.maxLongitude)
            }
            installedBytes?.let { json.put("installedBytes", it) }
        }
        .toString()

    companion object {
        const val FORMAT = 2

        fun of(manifest: RegionManifest, installedBytes: Long?) = RegionDescriptor(
            regionId = manifest.regionId,
            displayName = manifest.displayName,
            version = manifest.version,
            searchSchema = manifest.searchSchema,
            bounds = manifest.bounds,
            installedBytes = installedBytes,
        )

        /** Returns null for anything we cannot fully trust — callers treat that as "not a region". */
        fun parse(raw: String): RegionDescriptor? = runCatching {
            val json = JSONObject(raw)
            val format = json.getInt("format")
            require(format in 1..FORMAT) { "unsupported descriptor format $format" }
            val id = json.getString("id")
            require(BundleSpec.REGION_ID.matches(id))
            val bounds = if (json.has("minLatitude")) {
                RegionBounds(
                    json.getDouble("minLatitude"),
                    json.getDouble("maxLatitude"),
                    json.getDouble("minLongitude"),
                    json.getDouble("maxLongitude"),
                )
            } else null
            RegionDescriptor(
                regionId = id,
                displayName = json.optString("displayName", "").ifBlank { id },
                version = json.getString("version"),
                searchSchema = json.getInt("searchSchema"),
                bounds = bounds,
                installedBytes = if (json.has("installedBytes")) json.getLong("installedBytes") else null,
            )
        }.getOrNull()
    }
}

/**
 * Best-effort recursive size. Returns null (rather than a lie) on any symlink escape,
 * unreadable directory, or absurd node count. "Safely available" means exactly this.
 */
internal fun safeDiskUsage(root: File, maxNodes: Int = 20_000): Long? {
    val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
    var total = 0L
    var nodes = 0
    val stack = ArrayDeque<File>().apply { add(root) }
    while (stack.isNotEmpty()) {
        val file = stack.removeLast()
        if (++nodes > maxNodes) return null
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) return null
        when {
            file.isDirectory -> (file.listFiles() ?: return null).forEach(stack::addLast)
            file.isFile -> total += file.length()
            else -> return null
        }
    }
    return total
}