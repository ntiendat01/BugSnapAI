package com.bugsnap.ai.core.common

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Maps throwables and HTTP statuses onto [DomainError].
 * Call sites that already know the error should construct [DomainError] directly.
 */
object DomainErrorMapper {
    fun fromThrowable(throwable: Throwable): DomainError {
        var current: Throwable? = throwable
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            when (current) {
                is DomainException -> return current.error
                is SecurityException -> return DomainError.PermissionDenied()
                is SocketTimeoutException, is TimeoutException -> return DomainError.AiTimeout
                is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                    return DomainError.NetworkUnavailable
                is IOException -> if (isNoSpace(current)) return DomainError.StorageLow
            }
            current = current.cause
        }
        return if (isNoSpace(throwable)) DomainError.StorageLow else DomainError.Unknown(throwable)
    }

    /**
     * HTTP mapping used by the future AI client (T-033). Statuses without a
     * dedicated [DomainError] become [DomainError.Unknown].
     */
    fun fromHttp(statusCode: Int, detail: String? = null): DomainError = when (statusCode) {
        401, 403 -> DomainError.Unauthorized
        408, 504 -> DomainError.AiTimeout
        400, 422 -> DomainError.AiSchemaInvalid(detail)
        else -> if (statusCode == 0) {
            DomainError.NetworkUnavailable
        } else {
            DomainError.Unknown(HttpStatusException(statusCode, detail))
        }
    }

    private fun isNoSpace(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase().orEmpty()
        return "enospc" in message || "no space left" in message
    }
}

class HttpStatusException(
    val statusCode: Int,
    detail: String? = null,
) : RuntimeException(
    buildString {
        append("HTTP ").append(statusCode)
        if (!detail.isNullOrBlank()) append(": ").append(detail)
    },
)
