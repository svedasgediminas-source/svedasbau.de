package app.novaiptv.core.common

/** Error categories shown to the user. Messages must never contain credentials. */
sealed class AppError(open val detail: String? = null) {
    data class Network(override val detail: String? = null) : AppError(detail)
    data class Http(val code: Int, override val detail: String? = null) : AppError(detail)
    data class InvalidInput(override val detail: String? = null) : AppError(detail)
    data class TooLarge(val limitBytes: Long) : AppError("Limit $limitBytes bytes")
    data class Parse(override val detail: String? = null) : AppError(detail)
    data object AuthFailed : AppError()
    data object AccountExpired : AppError()
    data object Empty : AppError()
    data class Unknown(override val detail: String? = null) : AppError(detail)
}

sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(f: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(f(value))
    is AppResult.Err -> this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value
