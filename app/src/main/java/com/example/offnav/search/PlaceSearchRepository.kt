package com.example.offnav.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.offnav.region.RegionSelection
import com.example.offnav.region.RegionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File
import kotlin.collections.buildList
import kotlin.math.cos
import kotlin.math.hypot

data class PlaceSearchResult(
    val name: String,
    val subtitle: String,
    val category: String,       // human-readable, e.g. "Restaurant"
    val osmClass: String,       // raw class from your index, e.g. "restaurant"
    val latitude: Double,
    val longitude: Double,
    /** Filled in after search when sorted by distance. */
    val distanceMeters: Double = 0.0,
) {
    val distanceText: String
        get() = when {
            distanceMeters >= 10_000 -> "%.0f km".format(distanceMeters / 1000.0)
            distanceMeters >= 1_000  -> "%.1f km".format(distanceMeters / 1000.0)
            else                     -> "${distanceMeters.toInt()} m"
        }
}

private data class RankedPlaceSearchResult(
    val place: PlaceSearchResult,
    val sourceRank: Int,
)

class PlaceSearchRepository(
    context: Context,
    private val regions: RegionSelection,
) {
    companion object {
        private const val SEARCH_ASSET = "search/austin_places.db"
        private const val SEARCH_FILE = "austin_places.db"
        private const val DEFAULT_LIMIT = 12
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        fun haversineMeters(center: LatLng, lat: Double, lon: Double): Double {
            val mLat = 111_132.0
            val mLon = 111_320.0 * cos(Math.toRadians((center.latitude + lat) * 0.5))
            return hypot((lat - center.latitude) * mLat, (lon - center.longitude) * mLon)
        }
    }

    private val appContext = context.applicationContext

    private val databases = linkedMapOf<String, SQLiteDatabase>()

    @Synchronized
    private fun openDatabases(): List<SQLiteDatabase> = regions.snapshots.map { region ->
        databases[region.pointerValue]?.takeIf { it.isOpen } ?: run {
            val file = when (region) {
                is RegionSnapshot.Installed -> region.searchDb.also {
                    check(it.isFile) { "${region.displayName} is missing search.db" }
                }
                RegionSnapshot.BuiltIn -> ensureDatabaseOnDisk()
            }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { opened ->
                    check(opened.version == region.searchSchema) {
                        "Unsupported ${region.displayName} search index version: " +
                            "${opened.version} (expected ${region.searchSchema})"
                    }
                    databases[region.pointerValue] = opened
                }
        }
    }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<PlaceSearchResult> {
        val tokens = searchTokens(query)
        if (tokens.isEmpty()) return emptyList()
        // FTS4 treats adjacent terms as AND. The explicit AND operator is FTS5 syntax.
        val matchQuery = tokens.joinToString(" ") { token -> "$token*" }
        val nameMatchQuery = tokens.joinToString(" ") { token -> "name:$token*" }
        val resultLimit = limit.coerceIn(1, 50)
        val candidateLimit = (resultLimit * 25).coerceAtMost(500)
        val candidates = openDatabases().flatMap { db ->
            queryCandidates(db, nameMatchQuery, candidateLimit) +
                queryCandidates(db, matchQuery, candidateLimit)
        }.distinctBy { candidate ->
                with(candidate.place) { "$name\u0000$latitude\u0000$longitude" }
            }
        return candidates
            .sortedWith(
                compareBy<RankedPlaceSearchResult> { nameMatchQuality(it.place.name, tokens) }
                    .thenByDescending { it.sourceRank }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.place.name }
            )
            .take(resultLimit)
            .map { it.place }
    }

    /**
     * Search within [radiusMeters] of [center], optionally filtered by [categories]
     * and/or a text [query]. Results sorted by distance from [center].
     */
    suspend fun searchNearby(
        center: LatLng,
        radiusMeters: Double = 5_000.0,
        query: String = "",
        categories: Set<PlaceCategory> = emptySet(),
        limit: Int = 60,
    ): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        // Bounding box (equirectangular approximation)
        val dLat = radiusMeters / 111_132.0
        val dLon = radiusMeters / (111_320.0 * cos(Math.toRadians(center.latitude)))
        val minLat = center.latitude - dLat
        val maxLat = center.latitude + dLat
        val minLon = center.longitude - dLon
        val maxLon = center.longitude + dLon
        val clauses = mutableListOf("latitude BETWEEN ? AND ?", "longitude BETWEEN ? AND ?")
        val args = mutableListOf(
            minLat.toString(), maxLat.toString(),
            minLon.toString(), maxLon.toString(),
        )
        // Category filter
        if (categories.isNotEmpty()) {
            val labels = categories.flatMap(::searchCategories).toSet()
            val placeholders = labels.joinToString(",") { "?" }
            clauses += "category IN ($placeholders)"
            args += labels
        }
        // Text filter
        if (query.isNotBlank()) {
            clauses += "(name LIKE ? COLLATE NOCASE OR subtitle LIKE ? COLLATE NOCASE)"
            val wild = "%${query.trim()}%"
            args += wild
            args += wild
        }
        val where = clauses.joinToString(" AND ")
        // Fetch more than limit, then sort/trim client-side (SQLite can't do haversine ORDER BY)
        val fetchLimit = limit * 3
        val raw = openDatabases().flatMap { db ->
            val sql = """
                SELECT name, subtitle, category, latitude, longitude
                FROM places
                WHERE $where
                LIMIT $fetchLimit
            """.trimIndent()
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PlaceSearchResult(
                                name = cursor.getString(0),
                                subtitle = cursor.getString(1),
                                category = cursor.getString(2),
                                osmClass = cursor.getString(2).lowercase(),
                                latitude = cursor.getDouble(3),
                                longitude = cursor.getDouble(4),
                            )
                        )
                    }
                }
            }
        }.distinctBy { result -> "${result.name}\u0000${result.latitude}\u0000${result.longitude}" }
        // Compute distances and sort
        raw.map { it.copy(distanceMeters = haversineMeters(center, it.latitude, it.longitude)) }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    private fun queryCandidates(
        db: SQLiteDatabase,
        matchQuery: String,
        limit: Int,
    ): List<RankedPlaceSearchResult> {
        val sql = """
            SELECT p.name, p.subtitle, p.category, p.latitude, p.longitude,
                   p.rank
            FROM places_fts
            JOIN places p ON p.id = places_fts.docid
            WHERE places_fts MATCH ?
            ORDER BY p.rank DESC,
                     p.name COLLATE NOCASE
            LIMIT ?
        """.trimIndent()
        return db.rawQuery(sql, arrayOf(matchQuery, limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RankedPlaceSearchResult(
                            place = PlaceSearchResult(
                                name = cursor.getString(0),
                                subtitle = cursor.getString(1),
                                category = cursor.getString(2),
                                osmClass = cursor.getString(2).lowercase(),
                                latitude = cursor.getDouble(3),
                                longitude = cursor.getDouble(4),
                            ),
                            sourceRank = cursor.getInt(5),
                        )
                    )
                }
            }
        }
    }

    private fun searchCategories(category: PlaceCategory): Set<String> = when (category) {
        PlaceCategory.RESTAURANTS -> setOf("Food and drink", "Ice cream")
        PlaceCategory.FUEL -> setOf("Fuel", "EV charging")
        PlaceCategory.HOSPITALS -> setOf("Healthcare", "Veterinary")
        PlaceCategory.PARKS -> setOf("Park")
        PlaceCategory.BUSINESSES -> setOf("Local business", "Bank", "Post office", "Car wash")
    }

    private fun ensureDatabaseOnDisk(): File {
        val destination = File(appContext.filesDir, SEARCH_FILE)
        val assetLength = try {
            appContext.assets.openFd(SEARCH_ASSET).use { it.length }
        } catch (missing: Exception) {
            throw IllegalStateException(
                "Austin search index is not packaged. Run tools\\build-austin-search.ps1.",
                missing,
            )
        }
        if (destination.isFile && destination.length() == assetLength) return destination

        val partial = File(appContext.filesDir, "$SEARCH_FILE.partial")
        if (partial.exists()) check(partial.delete()) { "Could not replace partial search index" }
        try {
            appContext.assets.open(SEARCH_ASSET).use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(partial.length() == assetLength) { "Austin search index copy was incomplete" }
            if (destination.exists()) check(destination.delete()) { "Could not replace Austin search index" }
            check(partial.renameTo(destination)) { "Could not install Austin search index" }
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
        return destination
    }

    private fun searchTokens(query: String): List<String> {
        val rawTokens = WORD.findAll(query.lowercase())
            .map { it.value }
            .take(8)
            .toList()
        val usefulTokens = rawTokens.filter { token ->
            token.length > 1 || token.all(Char::isDigit)
        }
        return when {
            usefulTokens.isNotEmpty() -> usefulTokens
            rawTokens.size > 1 -> listOf(rawTokens.joinToString(""))
            else -> emptyList()
        }
    }

    private fun nameMatchQuality(name: String, tokens: List<String>): Int {
        val nameTokens = WORD.findAll(name.lowercase()).map { it.value }.toList()
        val allTokensMatchName = tokens.all { queryToken ->
            nameTokens.any { nameToken ->
                if (queryToken.length <= 2) {
                    nameToken == queryToken
                } else {
                    nameToken.startsWith(queryToken)
                }
            }
        }
        return if (allTokensMatchName) 0 else 1
    }

    private fun android.database.Cursor.toResult(): PlaceSearchResult {
        val name = getString(0) ?: ""
        val cls = getString(1) ?: ""
        val sub = getString(2) ?: ""
        val house = getString(3) ?: ""
        val street = getString(4) ?: ""
        val lat = getDouble(5)
        val lon = getDouble(6)
        val subtitle = buildString {
            if (house.isNotBlank()) append("$house ")
            if (street.isNotBlank()) append(street)
        }.trim()
        return PlaceSearchResult(
            name = name.ifBlank { subtitle.ifBlank { "%.5f, %.5f".format(lat, lon) } },
            subtitle = subtitle,
            category = sub.ifBlank { cls }.replaceFirstChar { it.uppercase() },
            osmClass = cls,
            latitude = lat,
            longitude = lon,
        )
    }


}
