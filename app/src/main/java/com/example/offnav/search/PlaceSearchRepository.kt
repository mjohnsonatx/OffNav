package com.example.offnav.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

class PlaceSearchRepository(context: Context) {
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

    @Volatile
    private var database: SQLiteDatabase? = null

    private val dbPath = context.getDatabasePath("austin_places.db").absolutePath
    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<PlaceSearchResult> {
        val tokens = searchTokens(query)
        if (tokens.isEmpty()) return emptyList()
        // FTS4 treats adjacent terms as AND. The explicit AND operator is FTS5 syntax.
        val matchQuery = tokens.joinToString(" ") { token -> "$token*" }
        val nameMatchQuery = tokens.joinToString(" ") { token -> "name:$token*" }
        val resultLimit = limit.coerceIn(1, 50)
        val candidateLimit = (resultLimit * 25).coerceAtMost(500)
        val db = openDatabase()
        val candidates = (
            queryCandidates(db, nameMatchQuery, candidateLimit) +
                queryCandidates(db, matchQuery, candidateLimit)
            ).distinctBy { candidate ->
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
        val clauses = mutableListOf("lat BETWEEN ? AND ?", "lon BETWEEN ? AND ?")
        val args = mutableListOf(
            minLat.toString(), maxLat.toString(),
            minLon.toString(), maxLon.toString(),
        )
        // Category filter
        if (categories.isNotEmpty()) {
            val allClasses = categories.flatMap { it.osmClasses }.toSet()
            val placeholders = allClasses.joinToString(",") { "?" }
            clauses += "class IN ($placeholders)"
            args += allClasses
        }
        // Text filter
        if (query.isNotBlank()) {
            clauses += "(name LIKE ? COLLATE NOCASE OR street LIKE ? COLLATE NOCASE)"
            val wild = "%${query.trim()}%"
            args += wild
            args += wild
        }
        val where = clauses.joinToString(" AND ")
        // Fetch more than limit, then sort/trim client-side (SQLite can't do haversine ORDER BY)
        val fetchLimit = limit * 3
        val sql = """
            SELECT name, class, subclass, housenumber, street, lat, lon
            FROM places
            WHERE $where
            LIMIT $fetchLimit
        """.trimIndent()
        val raw = db.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toResult()) }
        }
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
                                osmClass = cursor.getString(3),
                                latitude = cursor.getDouble(4),
                                longitude = cursor.getDouble(5),
                            ),
                            sourceRank = cursor.getInt(6),
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    private fun openDatabase(): SQLiteDatabase {
        database?.takeIf { it.isOpen }?.let { return it }
        val file = ensureDatabaseOnDisk()
        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).also { opened ->
            check(opened.version == 2) {
                "Unsupported Austin search index version: ${opened.version}"
            }
            database = opened
        }
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
