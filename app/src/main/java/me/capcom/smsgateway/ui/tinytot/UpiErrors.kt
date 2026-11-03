package me.capcom.smsgateway.ui.tinytot

import android.content.Context
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.tinytot.api.UpiApiException

/** Short, actionable text for verifier errors, shown in snackbars and the status line. */
fun Context.describeUpiError(error: Throwable?): String {
    if (error !is UpiApiException) {
        return getString(R.string.tinytot_error_generic, error?.message ?: error.toString())
    }

    return when {
        error.code == UpiApiException.CODE_NOT_CONFIGURED ->
            getString(R.string.tinytot_error_not_configured)

        error.code == UpiApiException.CODE_NETWORK ->
            getString(R.string.tinytot_error_network, error.message)

        error.code == UpiApiException.CODE_BAD_RESPONSE_SIGNATURE ->
            getString(R.string.tinytot_error_response_signature)

        error.authReason == "stale" -> getString(R.string.tinytot_error_stale)
        error.authReason == "bad_signature" -> getString(R.string.tinytot_error_bad_signature)
        error.authReason == "bad_device" -> getString(R.string.tinytot_error_bad_device)
        error.authReason != null -> getString(R.string.tinytot_error_auth, error.authReason)

        else -> getString(R.string.tinytot_error_generic, error.message ?: error.code)
    }
}
