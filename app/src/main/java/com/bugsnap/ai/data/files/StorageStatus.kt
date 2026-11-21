package com.bugsnap.ai.data.files

import java.io.File

/** NFR-8 uses binary MB (MiB). The start threshold does not interrupt an active export. */
data class StorageStatus(val availableBytes: Long) {
    init {
        require(availableBytes >= 0)
    }

    val shouldWarn: Boolean get() = availableBytes < WARN_BYTES
    val canStartSession: Boolean get() = availableBytes >= MIN_START_BYTES

    companion object {
        const val MIN_START_BYTES = 200L * 1024 * 1024
        const val WARN_BYTES = 500L * 1024 * 1024
    }
}

fun interface StorageSpace {
    fun availableBytes(root: File): Long
}

data class StoredEvidenceFile(val relPath: String, val sizeBytes: Long)
