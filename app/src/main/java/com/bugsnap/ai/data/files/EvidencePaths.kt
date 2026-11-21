package com.bugsnap.ai.data.files

/** Paths relative to Context.filesDir. These values may be persisted in Room. */
object EvidencePaths {
    fun markerFile(sessionId: String, markerId: String, fileName: String): String =
        "sessions/${component(sessionId)}/markers/${component(markerId)}/${finalName(fileName)}"

    fun frame(sessionId: String, markerId: String, index: Int): String {
        require(index > 0) { "Frame index must be positive" }
        return "sessions/${component(sessionId)}/markers/${component(markerId)}/frames/f$index.jpg"
    }

    fun export(sessionId: String, fileName: String): String =
        "sessions/${component(sessionId)}/exports/${finalName(fileName)}"

    internal fun components(relativePath: String): List<String> {
        val parts = relativePath.split('/')
        parts.forEach(::component)
        require(parts.first() == "sessions") { "Evidence must be under sessions/" }
        require(
            (parts.size == 5 && parts[2] == "markers") ||
                (parts.size == 6 && parts[2] == "markers" && parts[4] == "frames") ||
                (parts.size == 4 && parts[2] == "exports"),
        ) { "Unsupported evidence layout" }
        finalName(parts.last())
        return parts
    }

    internal fun component(value: String): String {
        require(value.isNotBlank() && value != "." && value != "..") { "Invalid path component" }
        require(value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
            "Path component contains a separator or control character"
        }
        return value
    }

    private fun finalName(value: String): String = component(value).also {
        require(!it.endsWith(".part")) { ".part is reserved for incomplete writes" }
    }
}
