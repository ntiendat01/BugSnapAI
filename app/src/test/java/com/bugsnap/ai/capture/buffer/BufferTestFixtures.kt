package com.bugsnap.ai.capture.buffer

internal fun generation(id: Int = 1, start: Long = 0) =
    Generation(id, 1280, 720, "video/avc", byteArrayOf(1, 2), byteArrayOf(3, 4), start)

internal fun ready(result: MarkResult): ClipSnapshot =
    (result as? MarkResult.Ready)?.snapshot ?: error("Expected ready snapshot, got $result")

internal fun RingBuffer.feedThrough(end: Long, step: Long = 100_000, generation: Int = 1): List<ClipSnapshot> {
    val completed = mutableListOf<ClipSnapshot>()
    var pts = (lastPts() + step).coerceAtLeast(0)
    if (lastPts() == -1L) pts = 0
    while (pts <= end) {
        completed += append(if (pts % 1_000_000 == 0L) EncodedSample.keyFrame(pts, generation) else EncodedSample.delta(pts, generation))
        pts += step
    }
    return completed
}
