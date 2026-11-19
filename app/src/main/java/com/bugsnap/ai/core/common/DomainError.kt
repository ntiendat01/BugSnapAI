package com.bugsnap.ai.core.common

/**
 * Domain-level errors returned by every layer as [Result.Err].
 * Keep this sealed set aligned with docs/05 §8.
 */
sealed class DomainError {
    data class PermissionDenied(val permission: String? = null) : DomainError()

    data object ProjectionLost : DomainError()

    data class EncoderFailure(val cause: Throwable? = null) : DomainError()

    data object StorageLow : DomainError()

    data class ClipExportFailed(val cause: Throwable? = null) : DomainError()

    data object NetworkUnavailable : DomainError()

    data object AiTimeout : DomainError()

    data class AiSchemaInvalid(val detail: String? = null) : DomainError()

    data object Unauthorized : DomainError()

    data class Unknown(val cause: Throwable? = null) : DomainError()
}

/** Wraps a [DomainError] so call sites can throw and [catchDomain] can unwrap it. */
class DomainException(
    val error: DomainError,
    cause: Throwable? = null,
) : RuntimeException(error.toString(), cause)
