package app.novaiptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.novaiptv.R
import app.novaiptv.core.common.AppError

/** Localized, credential-free user message for an [AppError]. */
@Composable
fun errorMessage(error: AppError): String = when (error) {
    is AppError.Network -> stringResource(R.string.error_network)
    is AppError.Http -> when (error.code) {
        401, 403 -> stringResource(R.string.error_auth)
        404 -> stringResource(R.string.error_not_found)
        else -> stringResource(R.string.error_http, error.code)
    }
    is AppError.InvalidInput ->
        if (error.detail == "playlist_limit") stringResource(R.string.error_playlist_limit)
        else stringResource(R.string.error_invalid_input)
    is AppError.TooLarge -> stringResource(R.string.error_too_large, error.limitBytes / (1024 * 1024))
    is AppError.Parse -> stringResource(R.string.error_parse)
    AppError.AuthFailed -> stringResource(R.string.error_auth)
    AppError.AccountExpired -> stringResource(R.string.error_account_expired)
    AppError.Empty -> stringResource(R.string.error_empty)
    is AppError.Unknown -> stringResource(R.string.error_generic)
}

/** Same as [errorMessage], as an extension for call sites that prefer it. */
@Composable
fun AppError.toMessage(): String = errorMessage(this)

/**
 * Maps the short code stored in Playlist.lastError ("http_404", "network", "auth_failed",
 * "account_expired", "parse", "too_large", "empty") to localized text; unknown codes fall back
 * to the generic message.
 */
@Composable
fun playlistErrorMessage(code: String): String {
    val c = code.trim().lowercase()
    return when {
        c.startsWith("http_") -> {
            val status = c.removePrefix("http_").toIntOrNull()
            if (status != null) errorMessage(AppError.Http(status)) else stringResource(R.string.error_generic)
        }
        c == "network" -> stringResource(R.string.error_network)
        c == "auth_failed" -> stringResource(R.string.error_auth)
        c == "account_expired" -> stringResource(R.string.error_account_expired)
        c == "parse" -> stringResource(R.string.error_parse)
        c == "too_large" -> stringResource(R.string.error_too_large, 100)
        c == "empty" -> stringResource(R.string.error_empty)
        c == "playlist_limit" -> stringResource(R.string.error_playlist_limit)
        else -> stringResource(R.string.error_generic)
    }
}
