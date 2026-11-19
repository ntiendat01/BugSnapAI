package com.bugsnap.ai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bugsnap.ai.session.FailureReason
import com.bugsnap.ai.session.PauseReason
import com.bugsnap.ai.session.SessionState

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val autPackage: String? = null,
    val autLabel: String? = null,
    val autVersion: String? = null,
    val state: SessionState,
    val pauseReason: PauseReason? = null,
    val failureReason: FailureReason? = null,
    @ColumnInfo(defaultValue = "30") val bufferSec: Int = 30,
    @ColumnInfo(defaultValue = "5") val postRollSec: Int = 5,
    val createdAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val deviceJson: String? = null,
    @ColumnInfo(defaultValue = "0") val pendingDelete: Boolean = false,
) {
    init {
        require(bufferSec == 15 || bufferSec == 30) {
            "bufferSec must be 15 or 30 (default 30), was $bufferSec"
        }
    }
}
