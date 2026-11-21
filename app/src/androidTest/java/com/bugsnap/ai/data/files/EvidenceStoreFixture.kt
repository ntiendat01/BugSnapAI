package com.bugsnap.ai.data.files

import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.Result
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before

open class EvidenceStoreFixture {
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected lateinit var sandbox: File
    protected lateinit var root: File
    protected lateinit var store: EvidenceStore
    @Volatile protected var freeBytes = 1024L * 1024 * 1024
    protected val clip = EvidencePaths.markerFile("s1", "m1", "clip.mp4")

    @Before
    fun createStore() {
        sandbox = Files.createTempDirectory(context.cacheDir.toPath(), "evidence-test-").toFile()
        root = File(sandbox, "files").apply { mkdirs() }
        store = EvidenceStore(root, Dispatchers.IO, StorageSpace { freeBytes })
    }

    @After
    fun removeSandbox() {
        Files.walkFileTree(sandbox.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    protected fun <T> Result<T, DomainError>.value(): T = when (this) {
        is Result.Ok -> value
        is Result.Err -> throw AssertionError("Expected success, got $error")
    }

    protected fun createFile(path: String, content: String = "bytes"): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }

    protected fun partials(): List<File> = root.walkTopDown().filter { it.name.endsWith(".part") }.toList()
}
