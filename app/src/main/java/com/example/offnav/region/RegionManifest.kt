package com.example.offnav.region

/** Declared size + digest for one payload member. The zip's own size field is never trusted. */
data class PayloadSpec(val bytes: Long, val sha256: String)

/** WGS84 bounding box of a region's coverage. */
data class RegionBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLatitude..maxLatitude && lon in minLongitude..maxLongitude
}

data class RegionManifest(
    val regionId: String,
    val displayName: String,
    val version: String,
    val searchSchema: Int,
    val bounds: RegionBounds,
    val tiles: PayloadSpec,
    val routing: PayloadSpec,
    val search: PayloadSpec,
) {
    val declaredPayloadBytes: Long get() = tiles.bytes + routing.bytes + search.bytes
}

object ManifestParser {

    private const val K_FORMAT = "format"
    private const val K_ID = "id"
    private const val K_DISPLAY_NAME = "displayName"
    private const val K_VERSION = "version"
    private const val K_SCHEMA = "searchSchema"
    private const val K_MIN_LAT = "minLatitude"
    private const val K_MAX_LAT = "maxLatitude"
    private const val K_MIN_LON = "minLongitude"
    private const val K_MAX_LON = "maxLongitude"
    private const val K_TILES_B = "tiles.bytes"
    private const val K_TILES_H = "tiles.sha256"
    private const val K_ROUTE_B = "routing.bytes"
    private const val K_ROUTE_H = "routing.sha256"
    private const val K_SEARCH_B = "search.bytes"
    private const val K_SEARCH_H = "search.sha256"

    /** Exhaustive and closed: anything not listed here is rejected, anything missing is rejected. */
    private val REQUIRED = listOf(
        K_FORMAT, K_ID, K_DISPLAY_NAME, K_VERSION, K_SCHEMA,
        K_MIN_LAT, K_MAX_LAT, K_MIN_LON, K_MAX_LON,
        K_TILES_B, K_TILES_H, K_ROUTE_B, K_ROUTE_H, K_SEARCH_B, K_SEARCH_H,
    )

    private const val MAX_DISPLAY_NAME = 128

    fun parse(raw: ByteArray): RegionManifest {
        if (raw.isEmpty()) importFailure("Bundle manifest is empty")
        // Strict ASCII. Rejects BOMs, UTF-16, smuggled control characters.
        for (b in raw) {
            val c = b.toInt() and 0xFF
            val ok = c == 0x09 || c == 0x0A || c == 0x0D || (c in 0x20..0x7E)
            if (!ok) importFailure("Bundle manifest contains invalid characters")
        }

        val values = LinkedHashMap<String, String>()
        for (line in String(raw, Charsets.US_ASCII).split('\n')) {
            val trimmed = line.removeSuffix("\r")
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) importFailure("Malformed manifest line: \"$trimmed\"")
            val key = trimmed.substring(0, eq)
            val value = trimmed.substring(eq + 1)
            if (key != key.trim() || value != value.trim()) {
                importFailure("Manifest key/value must not be padded: \"$trimmed\"")
            }
            if (key !in REQUIRED) importFailure("Unknown manifest key: \"$key\"")
            if (values.put(key, value) != null) importFailure("Duplicate manifest key: \"$key\"")
        }

        REQUIRED.firstOrNull { it !in values }?.let { importFailure("Manifest is missing \"$it\"") }

        if (values.getValue(K_FORMAT) != "1") {
            importFailure("Unsupported bundle format \"${values.getValue(K_FORMAT)}\"")
        }

        val id = values.getValue(K_ID)
        if (!BundleSpec.REGION_ID.matches(id)) importFailure("Invalid region id \"$id\"")

        val displayName = values.getValue(K_DISPLAY_NAME)
        if (displayName.isEmpty() || displayName.length > MAX_DISPLAY_NAME) {
            importFailure("\"$K_DISPLAY_NAME\" must be 1-$MAX_DISPLAY_NAME characters")
        }
        if (displayName.any { it.code < 0x20 || it.code == 0x7F }) {
            importFailure("\"$K_DISPLAY_NAME\" contains control characters")
        }

        val version = values.getValue(K_VERSION)
        if (!BundleSpec.REGION_VERSION.matches(version)) importFailure("Invalid region version \"$version\"")

        val schema = positiveInt(values.getValue(K_SCHEMA), K_SCHEMA)
        val bounds = bounds(values)

        val manifest = RegionManifest(
            regionId = id,
            displayName = displayName,
            version = version,
            searchSchema = schema,
            bounds = bounds,
            tiles = payload(values, K_TILES_B, K_TILES_H),
            routing = payload(values, K_ROUTE_B, K_ROUTE_H),
            search = payload(values, K_SEARCH_B, K_SEARCH_H),
        )

        // Fail before a single payload byte is read if the declared total cannot fit.
        if (manifest.declaredPayloadBytes > BundleSpec.MAX_BUNDLE_BYTES) {
            importFailure("Bundle declares more than 4 GB of content")
        }
        return manifest
    }

    private fun bounds(v: Map<String, String>): RegionBounds {
        val minLat = coordinate(v.getValue(K_MIN_LAT), K_MIN_LAT, 90.0)
        val maxLat = coordinate(v.getValue(K_MAX_LAT), K_MAX_LAT, 90.0)
        val minLon = coordinate(v.getValue(K_MIN_LON), K_MIN_LON, 180.0)
        val maxLon = coordinate(v.getValue(K_MAX_LON), K_MAX_LON, 180.0)
        if (minLat >= maxLat) importFailure("\"$K_MIN_LAT\" must be less than \"$K_MAX_LAT\"")
        if (minLon >= maxLon) importFailure("\"$K_MIN_LON\" must be less than \"$K_MAX_LON\"")
        return RegionBounds(minLat, maxLat, minLon, maxLon)
    }

    private fun coordinate(value: String, key: String, limit: Double): Double {
        // Invariant-culture decimal only: no ',', no '+', no exponent, no NaN/Infinity.
        if (!BundleSpec.DECIMAL_SIGNED.matches(value)) {
            importFailure("\"$key\" must be a plain decimal number")
        }
        val parsed = value.toDoubleOrNull() ?: importFailure("\"$key\" is not a valid number")
        if (!parsed.isFinite() || parsed < -limit || parsed > limit) {
            importFailure("\"$key\" is outside the valid range")
        }
        return parsed
    }

    private fun payload(v: Map<String, String>, byteKey: String, hashKey: String): PayloadSpec {
        val bytes = nonNegativeLong(v.getValue(byteKey), byteKey)
        val hash = v.getValue(hashKey)
        if (!BundleSpec.SHA256_HEX.matches(hash)) {
            importFailure("\"$hashKey\" must be 64 lowercase hex characters")
        }
        return PayloadSpec(bytes, hash)
    }

    private fun nonNegativeLong(value: String, key: String): Long {
        if (!BundleSpec.DECIMAL.matches(value)) importFailure("\"$key\" must be a non-negative integer")
        val parsed = value.toLongOrNull() ?: importFailure("\"$key\" is out of range")
        if (parsed > BundleSpec.MAX_BUNDLE_BYTES) importFailure("\"$key\" exceeds the 4 GB limit")
        return parsed
    }

    private fun positiveInt(value: String, key: String): Int {
        if (!BundleSpec.DECIMAL.matches(value)) importFailure("\"$key\" must be a positive integer")
        val parsed = value.toIntOrNull() ?: importFailure("\"$key\" is out of range")
        if (parsed <= 0) importFailure("\"$key\" must be greater than zero")
        return parsed
    }
}