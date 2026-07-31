package com.example.offnav.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteHistoryDao {

    @Query("SELECT * FROM route_history ORDER BY pinned DESC, lastUsedAt DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<RouteHistoryEntity>>

    /**
     * LIKE search. For a history table (hundreds of rows) this is sub-millisecond and
     * avoids the FTS4 content-entity setup entirely. Swap in @Fts4 later if the corpus
     * grows into the tens of thousands (e.g. when you add an offline POI index).
     */
    @Query(
        """
        SELECT * FROM route_history
        WHERE label LIKE '%' || :q || '%' COLLATE NOCASE
           OR subtitle LIKE '%' || :q || '%' COLLATE NOCASE
        ORDER BY pinned DESC, useCount DESC, lastUsedAt DESC
        LIMIT :limit
        """
    )
    fun search(q: String, limit: Int = 100): Flow<List<RouteHistoryEntity>>

    @Query("SELECT * FROM route_history WHERE destKey = :destKey LIMIT 1")
    suspend fun findByDestKey(destKey: String): RouteHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: RouteHistoryEntity): Long

    @Update
    suspend fun update(entry: RouteHistoryEntity)

    @Query("DELETE FROM route_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM route_history WHERE pinned = 0")
    suspend fun clearUnpinned()

    @Query("UPDATE route_history SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** Keep the table bounded; pinned rows always survive. */
    @Query(
        """
        DELETE FROM route_history WHERE id NOT IN (
            SELECT id FROM route_history ORDER BY pinned DESC, lastUsedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)

    /** Insert-or-bump in one transaction so repeated destinations don't duplicate. */
    @Transaction
    suspend fun recordVisit(entry: RouteHistoryEntity) {
        val existing = findByDestKey(entry.destKey)
        if (existing == null) {
            insert(entry)
        } else {
            update(
                existing.copy(
                    label = entry.label.ifBlank { existing.label },
                    subtitle = entry.subtitle.ifBlank { existing.subtitle },
                    originLat = entry.originLat,
                    originLon = entry.originLon,
                    distanceMeters = entry.distanceMeters,
                    durationMillis = entry.durationMillis,
                    lastUsedAt = entry.lastUsedAt,
                    useCount = existing.useCount + 1,
                )
            )
        }
    }
}