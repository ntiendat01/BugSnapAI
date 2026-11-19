package com.bugsnap.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<EvidenceEntity>)

    @Query("SELECT * FROM evidence WHERE id = :id")
    suspend fun getById(id: String): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE markerId = :markerId")
    suspend fun getByMarker(markerId: String): List<EvidenceEntity>

    @Query("SELECT * FROM evidence WHERE markerId = :markerId")
    fun observeByMarker(markerId: String): Flow<List<EvidenceEntity>>

    @Query("DELETE FROM evidence WHERE markerId = :markerId")
    suspend fun deleteByMarker(markerId: String): Int
}
