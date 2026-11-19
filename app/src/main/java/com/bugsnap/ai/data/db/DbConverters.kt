package com.bugsnap.ai.data.db

import androidx.room.TypeConverter
import com.bugsnap.ai.session.FailureReason
import com.bugsnap.ai.session.PauseReason
import com.bugsnap.ai.session.SessionState

class DbConverters {
    @TypeConverter
    fun fromSessionState(value: SessionState): String = value.name

    @TypeConverter
    fun toSessionState(value: String): SessionState = SessionState.valueOf(value)

    @TypeConverter
    fun fromPauseReason(value: PauseReason?): String? = value?.name

    @TypeConverter
    fun toPauseReason(value: String?): PauseReason? = value?.let { PauseReason.valueOf(it) }

    @TypeConverter
    fun fromFailureReason(value: FailureReason?): String? = value?.name

    @TypeConverter
    fun toFailureReason(value: String?): FailureReason? = value?.let { FailureReason.valueOf(it) }

    @TypeConverter
    fun fromClipStatus(value: ClipStatus): String = value.name

    @TypeConverter
    fun toClipStatus(value: String): ClipStatus = ClipStatus.valueOf(value)

    @TypeConverter
    fun fromEvidenceType(value: EvidenceType): String = value.name

    @TypeConverter
    fun toEvidenceType(value: String): EvidenceType = EvidenceType.valueOf(value)
}
