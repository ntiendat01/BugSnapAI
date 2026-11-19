package com.bugsnap.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = BugMarkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["markerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["markerId"])],
)
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val markerId: String,
    val type: EvidenceType,
    val relPath: String,
    val sizeBytes: Long,
    val offsetMs: Long? = null,
    val approvedForAi: Boolean = false,
) {
    init {
        require(relPath.isNotBlank()) { "relPath must not be blank" }
        require(!relPath.startsWith("/")) { "relPath must be relative, was $relPath" }
    }
}
