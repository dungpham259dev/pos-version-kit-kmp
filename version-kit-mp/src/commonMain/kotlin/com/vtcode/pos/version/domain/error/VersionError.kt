package com.vtcode.pos.version.domain.error

/**
 * Unified error types for Version Kit domain layer.
 * All errors are converted to this sealed class for type-safe error handling.
 */
sealed class VersionError(val message: String) {

    /**
     * Network connectivity error (no internet, DNS failure, etc.)
     */
    data class NetworkError(val details: String) : VersionError("Network error: $details")

    /**
     * HTTP API error (4xx, 5xx status codes)
     */
    data class ApiError(val code: Int, val errorMessage: String?) :
        VersionError("API Error $code: ${errorMessage ?: "Unknown error"}")

    /**
     * Request timeout
     */
    object TimeoutError : VersionError("Request timeout - please try again")

    /**
     * File not found during install
     */
    data class FileNotFound(val path: String) : VersionError("APK file not found: $path")

    /**
     * Installation permission not granted
     */
    object InstallPermissionRequired : VersionError("Install permission required")

    /**
     * Installation failed
     */
    data class InstallError(val details: String) : VersionError("Installation failed: $details")

    /**
     * Download was cancelled
     */
    object DownloadCancelled : VersionError("Download cancelled")

    /**
     * Unknown/unexpected error
     */
    data class UnknownError(val details: String) : VersionError("Unexpected error: $details")
}

/**
 * Extension function to convert Throwable to VersionError
 */
fun Throwable.toVersionError(): VersionError = when (this) {
    is VersionError -> this
    else -> VersionError.UnknownError(this.message ?: "Unknown error")
}

/**
 * Result type with VersionError for domain layer operations.
 * Alias for Result<T> with VersionError as the error type.
 */
typealias VersionResult<T> = Result<T, VersionError>

/**
 * Custom Result type with typed errors.
 */
sealed class Result<out T, out E> {
    data class Success<out T>(val value: T) : Result<T, Nothing>()
    data class Error<out E>(val error: E) : Result<Nothing, E>()
}

/**
 * Helper functions for Result type
 */
inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Error -> this
}

inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> = when (this) {
    is Result.Success -> transform(value)
    is Result.Error -> this
}

inline fun <T, E> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> = when (this) {
    is Result.Success -> {
        action(value)
        this
    }
    is Result.Error -> this
}

inline fun <T, E> Result<T, E>.onError(action: (E) -> Unit): Result<T, E> = when (this) {
    is Result.Success -> this
    is Result.Error -> {
        action(error)
        this
    }
}

fun <T, E> Result<T, E>.getOrNull(): T? = when (this) {
    is Result.Success -> value
    is Result.Error -> null
}

fun <T, E> Result<T, E>.getOrThrow(): T = when (this) {
    is Result.Success -> value
    is Result.Error -> throw IllegalStateException("Result is error: $error")
}
