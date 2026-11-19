package com.bugsnap.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MarkerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BugMarkerEntity)

    @Update
    suspend fun update(entity: BugMarkerEntity)

    @Query("SELECT * FROM bug_marker WHERE id = :id")
    suspend fun getById(id: String): BugMarkerEntity?

    @Query("SELECT * FROM bug_marker WHERE sessionId = :sessionId ORDER BY indexInSession ASC")
    suspend fun getBySession(sessionId: String): List<BugMarkerEntity>

    @Query("SELECT * FROM bug_marker WHERE sessionId = :sessionId ORDER BY indexInSession ASC")
    fun observeBySession(sessionId: String): Flow<List<BugMarkerEntity>>

    @Query("UPDATE bug_marker SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String): Int

    @Query("UPDATE bug_marker SET clipStatus = :status WHERE id = :id")
    suspend fun updateClipStatus(id: String, status: ClipStatus): Int

    @Query("DELETE FROM bug_marker WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String): Int
}
