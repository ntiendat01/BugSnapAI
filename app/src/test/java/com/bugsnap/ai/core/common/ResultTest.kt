package com.bugsnap.ai.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

class ResultTest {
    @Test
    fun mapTransformsOkAndPreservesErr() {
        val ok = Result.Ok(2).map { it * 3 }
        assertEquals(6, (ok as Result.Ok).value)
        val err: Result<Int, DomainError> = Result.Err(DomainError.Unauthorized)
        assertSame(err, err.map { it + 1 })
    }

    @Test
    fun mapErrorTransformsErrAndPreservesOk() {
        val ok: Result<Int, DomainError> = Result.Ok(1)
        assertSame(ok, ok.mapError { DomainError.AiTimeout })
        val mapped = Result.Err(DomainError.Unauthorized).mapError { DomainError.AiTimeout }
        assertEquals(DomainError.AiTimeout, (mapped as Result.Err).error)
    }

    @Test
    fun flatMapChainsOkAndShortCircuitsErr() {
        val chained = Result.Ok(2).flatMap { Result.Ok(it + 8) }
        assertEquals(10, chained.getOrNull())
        val failed = Result.Ok(2).flatMap { Result.Err(DomainError.StorageLow) }
        assertEquals(DomainError.StorageLow, failed.errorOrNull())
        val skipped = Result.Err(DomainError.Unauthorized).flatMap<Int, DomainError, Int> {
            fail("should not run")
            Result.Ok(0)
        }
        assertEquals(DomainError.Unauthorized, skipped.errorOrNull())
    }

    @Test
    fun foldAndGetOrElse() {
        assertEquals("ok-4", Result.Ok(4).fold(onOk = { "ok-$it" }, onErr = { "err" }))
        assertEquals(
            "err",
            Result.Err(DomainError.AiTimeout).fold(onOk = { "ok" }, onErr = { "err" }),
        )
        assertEquals(9, Result.Err(DomainError.AiTimeout).getOrElse { 9 })
        assertNull(Result.Err(DomainError.AiTimeout).getOrNull())
    }

    @Test
    fun catchDomainMapsThrowablesAndKeepsValue() {
        assertEquals(5, catchDomain { 5 }.getOrNull())
        val err = catchDomain { throw UnknownHostException("offline") }
        assertEquals(DomainError.NetworkUnavailable, err.errorOrNull())
    }

    @Test
    fun catchDomainRethrowsCancellation() {
        try {
            catchDomain<Unit> { throw CancellationException("cancelled") }
            fail("CancellationException must be rethrown")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled", cancelled.message)
        }
    }
}
