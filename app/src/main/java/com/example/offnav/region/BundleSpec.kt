package com.example.offnav.region


/** The on-disk contract for a `.offnav` bundle. Nothing here is negotiable at runtime. */
object BundleSpec {
    const val MANIFEST_ENTRY = "manifest.properties"
    const val TILES_ENTRY = "tiles.mbtiles"
    const val ROUTING_ENTRY = "routing.ghz"
    const val SEARCH_ENTRY = "search.db"

    /** Exact, ordered entry list. Anything else is a malformed bundle. */
    val ORDERED_PAYLOAD: List<String> = listOf(TILES_ENTRY, ROUTING_ENTRY, SEARCH_ENTRY)

    const val MAX_MANIFEST_BYTES = 64L * 1024L
    const val MAX_BUNDLE_BYTES = 4L * 1024L * 1024L * 1024L      // 4 GiB, hard abort
    const val IO_BUFFER = 1 shl 16

    val REGION_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    val REGION_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")   // becomes part of a dir name
    val SHA256_HEX = Regex("[0-9a-f]{64}")
    val DECIMAL = Regex("[0-9]{1,19}")                              // no '+', '-', ',', '_', spaces
    /** Invariant-culture decimal: optional '-', digits, optional '.'+digits. No exponent, no ','. */
    val DECIMAL_SIGNED = Regex("-?[0-9]{1,3}(\\.[0-9]{1,15})?")
}

/** Every rejection path funnels through this so the UI always has a user-readable reason. */
class RegionImportException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

internal fun importFailure(message: String, cause: Throwable? = null): Nothing =
    throw RegionImportException(message, cause)

/** Global uncompressed-byte ceiling for a single bundle. Aborts the moment it is crossed. */
class ByteBudget(private val max: Long) {
    var used: Long = 0L
        private set

    fun consume(n: Long) {
        used += n
        if (used > max) importFailure("Bundle exceeds the ${max / (1024 * 1024 * 1024)} GB limit")
    }
}