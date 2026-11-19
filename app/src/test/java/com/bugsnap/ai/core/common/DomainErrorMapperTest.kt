package com.bugsnap.ai.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class DomainErrorMapperTest {
    @Test
    fun securityExceptionMapsToPermissionDenied() {
        assertTrue(DomainErrorMapper.fromThrowable(SecurityException("denied")) is DomainError.PermissionDenied)
    }

    @Test
    fun timeoutExceptionsMapToAiTimeout() {
        assertEquals(DomainError.AiTimeout, DomainErrorMapper.fromThrowable(SocketTimeoutException()))
        assertEquals(DomainError.AiTimeout, DomainErrorMapper.fromThrowable(TimeoutException()))
    }

    @Test
    fun connectionFailuresMapToNetworkUnavailable() {
        assertEquals(DomainError.NetworkUnavailable, DomainErrorMapper.fromThrowable(UnknownHostException("x")))
        assertEquals(DomainError.NetworkUnavailable, DomainErrorMapper.fromThrowable(ConnectException()))
        assertEquals(DomainError.NetworkUnavailable, DomainErrorMapper.fromThrowable(NoRouteToHostException()))
    }

    @Test
    fun noSpaceIoExceptionMapsToStorageLow() {
        assertEquals(DomainError.StorageLow, DomainErrorMapper.fromThrowable(IOException("ENOSPC")))
        assertEquals(
            DomainError.StorageLow,
            DomainErrorMapper.fromThrowable(IOException("No space left on device")),
        )
    }

    @Test
    fun genericIoExceptionMapsToUnknown() {
        val error = DomainErrorMapper.fromThrowable(IOException("broken pipe"))
        assertTrue(error is DomainError.Unknown)
    }

    @Test
    fun domainExceptionIsUnwrapped() {
        val original = DomainError.ClipExportFailed()
        val mapped = DomainErrorMapper.fromThrowable(DomainException(original))
        assertEquals(original, mapped)
    }

    @Test
    fun causeChainIsWalked() {
        val wrapped = RuntimeException("wrapper", SocketTimeoutException())
        assertEquals(DomainError.AiTimeout, DomainErrorMapper.fromThrowable(wrapped))
    }

    @Test
    fun httpStatusesMapAsSpecified() {
        assertEquals(DomainError.Unauthorized, DomainErrorMapper.fromHttp(401))
        assertEquals(DomainError.Unauthorized, DomainErrorMapper.fromHttp(403))
        assertEquals(DomainError.AiTimeout, DomainErrorMapper.fromHttp(408))
        assertEquals(DomainError.AiTimeout, DomainErrorMapper.fromHttp(504))
        val schema = DomainErrorMapper.fromHttp(422, "missing title")
        assertTrue(schema is DomainError.AiSchemaInvalid)
        assertEquals("missing title", (schema as DomainError.AiSchemaInvalid).detail)
        assertEquals(DomainError.NetworkUnavailable, DomainErrorMapper.fromHttp(0))
        val unknown = DomainErrorMapper.fromHttp(429)
        assertTrue(unknown is DomainError.Unknown)
        assertTrue((unknown as DomainError.Unknown).cause is HttpStatusException)
        assertEquals(429, (unknown.cause as HttpStatusException).statusCode)
    }

    @Test
    fun unknownKeepsTheOriginalCause() {
        val cause = IllegalStateException("boom")
        val error = DomainErrorMapper.fromThrowable(cause) as DomainError.Unknown
        assertSame(cause, error.cause)
    }
}
