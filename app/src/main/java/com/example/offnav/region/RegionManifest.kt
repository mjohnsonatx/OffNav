package com.example.offnav.region

/** Declared size + digest for one payload member. The zip's own size field is never trusted. */
data class PayloadSpec(val bytes: Long, val sha256: String)

data class RegionManifest(
    val regionId: String,
    val version: String,
    val searchSchema: Int,
    val tiles: PayloadSpec,
    val routing: PayloadSpec,
    val search: PayloadSpec,
) {
    val declaredPayloadBytes: Long get() = tiles.bytes + routing.bytes + search.bytes
}

object ManifestParser {

    private const val K_FORMAT = "format"
    private const val K_ID = "id"
    private const val K_VERSION = "version"
    private const val K_SCHEMA = "searchSchema"
    private const val K_TILES_B = "tiles.bytes"
    private const val K_TILES_H = "tiles.sha256"
    private const val K_ROUTE_B = "routing.bytes"
    private const val K_ROUTE_H = "routing.sha256"
    private const val K_SEARCH_B = "search.bytes"
    private const val K_SEARCH_H = "search.sha256"

    private val REQUIRED = listOf(
        K_FORMAT, K_ID, K_VERSION, K_SCHEMA,
        K_TILES_B, K_TILES_H, K_ROUTE_B, K_ROUTE_H, K_SEARCH_B, K_SEARCH_H,
    )

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

        val version = values.getValue(K_VERSION)
        if (!BundleSpec.REGION_VERSION.matches(version)) importFailure("Invalid region version \"$version\"")

        val schema = positiveInt(values.getValue(K_SCHEMA), K_SCHEMA)

        val manifest = RegionManifest(
            regionId = id,
            version = version,
            searchSchema = schema,
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