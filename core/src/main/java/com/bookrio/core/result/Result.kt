package com.bookrio.core.result

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val throwable: Throwable, val message: String? = null) : Result<Nothing>
    data object Loading : Result<Nothing>

    val isLoading get() = this is Loading
    val isSuccess get() = this is Success<*>
    val isError get() = this is Error

    fun getOrNull(): T? = (this as? Success<T>)?.data
    fun errorOrNull(): Throwable? = (this as? Error)?.throwable
}

fun <T> Result<T>.getOrDefault(default: T): T = (this as? Result.Success<T>)?.data ?: default

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
    is Result.Loading -> this
}

inline fun <T> Result<T>.onSuccess(block: (T) -> Unit): Result<T> {
    if (this is Result.Success) block(data)
    return this
}

inline fun <T> Result<T>.onError(block: (Throwable, String?) -> Unit): Result<T> {
    if (this is Result.Error) block(throwable, message)
    return this
}

fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map<T, Result<T>> { Result.Success(it) }
        .onStart { emit(Result.Loading) }
        .catch { emit(Result.Error(it, it.message)) }

suspend fun <T> runCatchingResult(block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (t: Throwable) {
        Result.Error(t, t.message)
    }
