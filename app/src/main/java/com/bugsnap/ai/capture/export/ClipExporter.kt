package com.bugsnap.ai.capture.export

import com.bugsnap.ai.capture.buffer.ClipSnapshot

fun interface ClipExporter {
    fun export(snapshot: ClipSnapshot, outputDir: String): ExportResult
}

sealed class ExportResult {
    data class Success(
        val id: String,
        val clipRelativePath: String,
        val markJpegRelativePath: String?,
        val preJpegRelativePath: String?,
        val durationMs: Long,
        val actualPreRollMs: Long,
        val actualPostRollMs: Long,
    ) : ExportResult()

    data class Failure(
        val id: String,
        val message: String,
        val attempt: Int,
    ) : ExportResult()
}
