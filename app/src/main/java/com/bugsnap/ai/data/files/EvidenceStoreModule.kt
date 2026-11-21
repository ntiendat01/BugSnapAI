package com.bugsnap.ai.data.files

import android.content.Context
import android.os.StatFs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EvidenceRoot

@Module
@InstallIn(SingletonComponent::class)
object EvidenceStoreModule {
    @Provides
    @EvidenceRoot
    fun root(@ApplicationContext context: Context): File = context.filesDir

    @Provides
    fun storageSpace(): StorageSpace = StorageSpace { root -> StatFs(root.path).availableBytes }
}
