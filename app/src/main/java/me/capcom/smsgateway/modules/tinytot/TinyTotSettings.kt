package me.capcom.smsgateway.modules.tinytot

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * TinyTot UPI verifier settings. Keys are shared with the preference screen
 * (`tinytot_preferences.xml`), which writes the same default SharedPreferences.
 */
class TinyTotSettings(
    private val preferences: SharedPreferences,
    private val secrets: SecretStore,
) {
    val verifierUrl: String?
        get() = preferences.getString(KEY_VERIFIER_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    var apiKey: String?
        get() = secrets.get(SECRET_API_KEY)?.takeIf { it.isNotEmpty() }
        set(value) = secrets.set(SECRET_API_KEY, value?.trim())

    var deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null)?.trim()
            ?.takeIf { DeviceIds.isValid(it) }
            ?: defaultDeviceId
        set(value) = preferences.edit { putString(KEY_DEVICE_ID, value) }

    val forwardEnabled: Boolean
        get() = preferences.getBoolean(KEY_FORWARD_ENABLED, true)

    val isConfigured: Boolean
        get() = verifierUrl != null && apiKey != null

    val lastSuccessAt: Long?
        get() = preferences.getLong(KEY_LAST_SUCCESS_AT, 0L).takeIf { it > 0 }

    val lastError: String?
        get() = preferences.getString(KEY_LAST_ERROR, null)

    val lastErrorAt: Long?
        get() = preferences.getLong(KEY_LAST_ERROR_AT, 0L).takeIf { it > 0 }

    fun markSuccess(at: Long = System.currentTimeMillis()) {
        preferences.edit {
            putLong(KEY_LAST_SUCCESS_AT, at)
            remove(KEY_LAST_ERROR)
            remove(KEY_LAST_ERROR_AT)
        }
    }

    fun markError(message: String, at: Long = System.currentTimeMillis()) {
        preferences.edit {
            putString(KEY_LAST_ERROR, message)
            putLong(KEY_LAST_ERROR_AT, at)
        }
    }

    companion object {
        const val KEY_VERIFIER_URL = "tinytot.verifier_url"
        const val KEY_API_KEY = "tinytot.api_key"
        const val KEY_DEVICE_ID = "tinytot.device_id"
        const val KEY_FORWARD_ENABLED = "tinytot.forward_enabled"
        const val KEY_TEST_CONNECTION = "tinytot.test_connection"
        const val KEY_RESEND_RECENT = "tinytot.resend_recent"
        const val KEY_STATUS = "tinytot.status"

        private const val KEY_LAST_SUCCESS_AT = "tinytot.last_success_at"
        private const val KEY_LAST_ERROR = "tinytot.last_error"
        private const val KEY_LAST_ERROR_AT = "tinytot.last_error_at"

        private const val SECRET_API_KEY = "api_key"

        val defaultDeviceId: String
            get() = DeviceIds.slug(Build.MODEL)
    }
}
