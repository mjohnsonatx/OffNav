package com.example.offnav.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Insert
    suspend fun insertActivity(activity: ActivityEntity): Long

    @Update
    suspend fun updateActivity(activity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM recorded_activity WHERE status = 'COMPLETED' ORDER BY startedAt DESC")
    fun observeCompleted(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM recorded_activity WHERE id = :id")
    suspend fun activity(id: Long): ActivityEntity?

    @Query("SELECT * FROM recorded_activity WHERE id = :id")
    fun observeActivity(id: Long): Flow<ActivityEntity?>

    /** At most one can exist; returns the newest if a previous crash left several. */
    @Query("SELECT * FROM recorded_activity WHERE status != 'COMPLETED' ORDER BY startedAt DESC LIMIT 1")
    suspend fun danglingSession(): ActivityEntity?

    @Query("SELECT * FROM track_point WHERE activityId = :id ORDER BY timestamp ASC")
    suspend fun points(id: Long): List<TrackPointEntity>

    /** Streaming read for GPX export — avoids materialising 20k rows. */
    @Query("SELECT * FROM track_point WHERE activityId = :id ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun pointsPage(id: Long, limit: Int, offset: Int): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_point WHERE activityId = :id")
    suspend fun pointCount(id: Long): Int

    @Query("DELETE FROM recorded_activity WHERE id = :id")
    suspend fun deleteActivity(id: Long)   // track_point rows cascade

    @Query("DELETE FROM recorded_activity WHERE status != 'COMPLETED'")
    suspend fun deleteIncomplete()
}