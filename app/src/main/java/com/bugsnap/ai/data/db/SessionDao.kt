package com.bugsnap.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bugsnap.ai.session.FailureReason
import com.bugsnap.ai.session.PauseReason
import com.bugsnap.ai.session.SessionState
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SessionEntity)

    @Update
    suspend fun update(entity: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY createdAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM session WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        """
        UPDATE session
        SET state = :state, pauseReason = :pauseReason, failureReason = :failureReason
        WHERE id = :id
        """,
    )
    suspend fun updateState(
        id: String,
        state: SessionState,
        pauseReason: PauseReason?,
        failureReason: FailureReason?,
    ): Int

    @Query("UPDATE session SET pendingDelete = :pendingDelete WHERE id = :id")
    suspend fun setPendingDelete(id: String, pendingDelete: Boolean): Int

    @Query("SELECT * FROM session WHERE pendingDelete = 1")
    suspend fun getPendingDelete(): List<SessionEntity>

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
