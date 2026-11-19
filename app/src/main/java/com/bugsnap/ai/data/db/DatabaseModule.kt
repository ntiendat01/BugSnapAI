package com.bugsnap.ai.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BugSnapDatabase =
        Room.databaseBuilder(context, BugSnapDatabase::class.java, "bugsnap.db")
            .build()

    @Provides
    fun sessionDao(database: BugSnapDatabase): SessionDao = database.sessionDao()

    @Provides
    fun markerDao(database: BugSnapDatabase): MarkerDao = database.markerDao()

    @Provides
    fun evidenceDao(database: BugSnapDatabase): EvidenceDao = database.evidenceDao()
}
