package com.bugsnap.ai.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Two-parameter result used across layers: [Result] of value [T] or [DomainError].
 * Distinct from [kotlin.Result], which only carries a [Throwable].
 */
sealed class Result<out T, out E> {
    data class Ok<out T>(val value: T) : Result<T, Nothing>()

    data class Err<out E>(val error: E) : Result<Nothing, E>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): E? = (this as? Err)?.error
}

inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> = when (this) {
    is Result.Ok -> Result.Ok(transform(value))
    is Result.Err -> this
}

inline fun <T, E, F> Result<T, E>.mapError(transform: (E) -> F): Result<T, F> = when (this) {
    is Result.Ok -> this
    is Result.Err -> Result.Err(transform(error))
}

inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> = when (this) {
    is Result.Ok -> transform(value)
    is Result.Err -> this
}

inline fun <T, E, R> Result<T, E>.fold(onOk: (T) -> R, onErr: (E) -> R): R = when (this) {
    is Result.Ok -> onOk(value)
    is Result.Err -> onErr(error)
}

inline fun <T, E> Result<T, E>.getOrElse(onErr: (E) -> T): T = fold(onOk = { it }, onErr = onErr)

/**
 * Runs [block] and maps failures to [DomainError].
 * [CancellationException] is rethrown so coroutines stay cancellable.
 */
inline fun <T> catchDomain(block: () -> T): Result<T, DomainError> = try {
    Result.Ok(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (throwable: Throwable) {
    Result.Err(DomainErrorMapper.fromThrowable(throwable))
}
