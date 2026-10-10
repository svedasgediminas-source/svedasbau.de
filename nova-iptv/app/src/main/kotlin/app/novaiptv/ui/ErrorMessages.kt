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
    is AppError.InvalidInput -> stringResource(R.string.error_invalid_input)
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
