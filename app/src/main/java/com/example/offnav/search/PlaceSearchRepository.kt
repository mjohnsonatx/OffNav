package com.example.offnav.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class PlaceSearchResult(
    val name: String,
    val subtitle: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
)

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
    }

    private val appContext = context.applicationContext

    @Volatile
    private var database: SQLiteDatabase? = null

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
}
