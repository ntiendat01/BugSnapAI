package com.bugsnap.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bug_marker",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "indexInSession"], unique = true)],
)
data class BugMarkerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val indexInSession: Int,
    val tMarkPtsUs: Long,
    val wallClockMs: Long,
    val sessionOffsetMs: Long,
    val note: String? = null,
    val clipStatus: ClipStatus = ClipStatus.PENDING,
    val actualPreRollMs: Int? = null,
    val actualPostRollMs: Int? = null,
)
