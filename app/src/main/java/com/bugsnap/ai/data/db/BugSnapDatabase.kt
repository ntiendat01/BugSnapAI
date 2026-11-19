package com.bugsnap.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SessionEntity::class,
        BugMarkerEntity::class,
        EvidenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class BugSnapDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun markerDao(): MarkerDao
    abstract fun evidenceDao(): EvidenceDao
}
