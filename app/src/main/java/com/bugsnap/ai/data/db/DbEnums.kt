package com.bugsnap.ai.data.db

/** marker.clipStatus — docs/09 §3. */
enum class ClipStatus {
    PENDING,
    READY,
    CLIP_FAILED,
    CLIP_LOST,
}

/** evidence.type — docs/09 §3. VOICE and ANNOTATED are reserved for V2. */
enum class EvidenceType {
    CLIP,
    SCREENSHOT_MARK,
    SCREENSHOT_PRE,
    KEYFRAME,
    VOICE,
    ANNOTATED,
}
