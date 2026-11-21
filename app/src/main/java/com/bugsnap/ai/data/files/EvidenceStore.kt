package com.bugsnap.ai.data.files

import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException
import com.bugsnap.ai.core.common.IoDispatcher
import com.bugsnap.ai.core.common.Result
import com.bugsnap.ai.core.common.catchDomain
import com.bugsnap.ai.core.common.flatMap
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/**
 * File-only storage. The coordinator must stop/join a session's exports before deleting it,
 * then delete its DB row only after deleteSession succeeds (otherwise set pendingDelete).
 * All users of sessions/ must use this singleton so cleanup cannot race active writers.
 */
@Singleton
class EvidenceStore @Inject constructor(
    @EvidenceRoot private val root: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val storageSpace: StorageSpace,
) {
    private val gate = ReentrantReadWriteLock(true)
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    suspend fun storageStatus(): Result<StorageStatus, DomainError> = access {
        StorageStatus(storageSpace.availableBytes(base().toFile()))
    }

    suspend fun checkCanStartSession(): Result<StorageStatus, DomainError> =
        storageStatus().flatMap {
            if (it.canStartSession) Result.Ok(it) else Result.Err(DomainError.StorageLow)
        }

    /**
     * writer runs on IO and must close its resources before returning. It must only write
     * the supplied staging file and must not call back into the store. expectedBytes is a
     * preflight estimate, not a disk reservation. Cancellation/failure removes staging.
     */
    suspend fun writeAtomic(
        relPath: String,
        expectedBytes: Long = 0,
        writer: (File) -> Unit,
    ): Result<StoredEvidenceFile, DomainError> = access { context ->
        require(expectedBytes >= 0) { "expectedBytes must be nonnegative" }
        val parts = EvidencePaths.components(relPath)
        withFileLock(relPath) {
            val target = resolve(parts)
            val available = storageSpace.availableBytes(base().toFile())
            if (available < maxOf(expectedBytes, 1)) throw DomainException(DomainError.StorageLow)
            Files.createDirectories(target.parent)
            // Recheck ancestors after directory creation, before opening any file.
            resolve(parts)
            val staging = Files.createTempFile(target.parent, ".${target.fileName}.", ".part")
            try {
                writer(staging.toFile())
                context.ensureActive()
                require(Files.isRegularFile(staging, NOFOLLOW_LINKS)) { "Writer must produce a file" }
                val size = Files.size(staging)
                require(size > 0) { "Evidence must not be empty" }
                RandomAccessFile(staging.toFile(), "rw").use { it.fd.sync() }
                context.ensureActive()
                resolve(parts)
                // Same-directory atomic rename. Unsupported filesystems fail explicitly.
                Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
                StoredEvidenceFile(relPath, size)
            } catch (failure: Throwable) {
                try {
                    Files.deleteIfExists(staging)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
    }

    /** reader runs on IO. Do not retain the File or call back into the store. */
    suspend fun <T> read(relPath: String, reader: (File) -> T): Result<T, DomainError> =
        access {
            val parts = EvidencePaths.components(relPath)
            withFileLock(relPath) {
                val file = resolve(parts)
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("Evidence is missing")
                reader(file.toFile())
            }
        }

    /** Idempotent. Removes all marker evidence, exports and incomplete files in this session. */
    suspend fun deleteSession(sessionId: String): Result<Unit, DomainError> = access(exclusive = true) { context ->
        EvidencePaths.component(sessionId)
        val sessions = resolve(listOf("sessions"))
        val directory = sessions.resolve(sessionId)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            // walkFileTree defaults to NOFOLLOW: unlink a symlink, never traverse its target.
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    context.ensureActive()
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        fileLocks.keys.removeAll { it.startsWith("sessions/$sessionId/") }
    }

    /** Startup sweep. The exclusive gate waits for this process's active reads/writes. */
    suspend fun cleanupOrphanParts(): Result<Int, DomainError> = access(exclusive = true) { context ->
        val sessions = resolve(listOf("sessions"))
        var removed = 0
        if (Files.exists(sessions, NOFOLLOW_LINKS)) {
            Files.walkFileTree(sessions, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (file.fileName.toString().endsWith(".part")) {
                        Files.delete(file)
                        removed++
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        fileLocks.clear()
        removed
    }

    private fun base(): Path {
        val directory = root.canonicalFile.toPath()
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "File store root must exist" }
        return directory
    }

    /** Refuse any symlink below the trusted Context root, including links within sessions/. */
    private fun resolve(parts: List<String>): Path {
        var current = base()
        parts.forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Symlink in evidence path" }
        }
        return current
    }

    private fun <T> withFileLock(relPath: String, block: () -> T): T {
        val lock = fileLocks.computeIfAbsent(relPath) { ReentrantLock(true) }
        lock.lockInterruptibly()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun <T> access(
        exclusive: Boolean = false,
        block: (CoroutineContext) -> T,
    ): Result<T, DomainError> {
        val context = currentCoroutineContext()
        return catchDomain {
            runInterruptible(ioDispatcher) {
                val lock: Lock = if (exclusive) gate.writeLock() else gate.readLock()
                lock.lockInterruptibly()
                try {
                    context.ensureActive()
                    block(context)
                } finally {
                    lock.unlock()
                }
            }
        }
    }
}
