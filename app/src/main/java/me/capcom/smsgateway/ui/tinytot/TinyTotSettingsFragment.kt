package me.capcom.smsgateway.ui.tinytot

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.tinytot.DeviceIds
import me.capcom.smsgateway.modules.tinytot.TinyTotSettings
import me.capcom.smsgateway.modules.tinytot.UpiForwarding
import me.capcom.smsgateway.modules.tinytot.UpiSmsForwarder
import me.capcom.smsgateway.modules.tinytot.api.UpiApiException
import me.capcom.smsgateway.modules.tinytot.api.UpiVerifierClient
import me.capcom.smsgateway.ui.settings.BasePreferenceFragment
import org.koin.android.ext.android.inject
import kotlin.math.abs

class TinyTotSettingsFragment : BasePreferenceFragment() {
    private val settings: TinyTotSettings by inject()
    private val client: UpiVerifierClient by inject()
    private val forwarder: UpiSmsForwarder by inject()

    private var busy = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.tinytot_preferences, rootKey)

        findPreference<EditTextPreference>(TinyTotSettings.KEY_VERIFIER_URL)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.hint = getString(R.string.tinytot_verifier_url_hint)
            }
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue?.toString()?.trim().orEmpty()
                (url.isEmpty() || URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)).also {
                    if (!it) showToast(R.string.tinytot_verifier_url_hint)
                }
            }
        }

        findPreference<EditTextPreference>(TinyTotSettings.KEY_API_KEY)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setText("")
            }
            setOnPreferenceChangeListener { _, newValue ->
                val key = newValue?.toString()?.trim().orEmpty()
                // Blank keeps the current key so an accidental OK does not wipe it.
                if (key.isNotEmpty()) settings.apiKey = key
                updateApiKeySummary()
                updateStatus()
                false
            }
        }
        updateApiKeySummary()

        findPreference<EditTextPreference>(TinyTotSettings.KEY_DEVICE_ID)?.apply {
            if (text.isNullOrBlank()) text = settings.deviceId
            setOnPreferenceChangeListener { pref, newValue ->
                val value = newValue?.toString()?.trim().orEmpty()
                when {
                    value.isEmpty() -> {
                        (pref as EditTextPreference).text = TinyTotSettings.defaultDeviceId
                        false
                    }

                    DeviceIds.isValid(value) -> true
                    else -> {
                        showToast(R.string.tinytot_device_id_invalid)
                        false
                    }
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("tinytot.") == true) updateStatus()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        updateStatus()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            TinyTotSettings.KEY_TEST_CONNECTION -> {
                testConnection(preference)
                true
            }

            TinyTotSettings.KEY_RESEND_RECENT -> {
                resendRecent()
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun testConnection(preference: Preference) {
        if (busy) return
        busy = true
        preference.summary = getString(R.string.tinytot_testing)

        lifecycleScope.launch {
            try {
                val result = client.ping()
                val skewSeconds = result.serverTime?.let {
                    (System.currentTimeMillis() - it) / 1000
                }
                val message = buildString {
                    append(getString(R.string.tinytot_connected, result.schoolId ?: "server"))
                    if (skewSeconds != null && abs(skewSeconds) >= CLOCK_WARNING_SECONDS) {
                        append(". ")
                        append(getString(R.string.tinytot_clock_skew, skewSeconds.toInt()))
                    }
                }
                preference.summary = message
                showToast(message)
            } catch (e: UpiApiException) {
                val message = requireContext().describeUpiError(e)
                preference.summary = message
                showToast(message)
            } finally {
                busy = false
                updateStatus()
            }
        }
    }

    private fun resendRecent() {
        if (busy) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showToast(R.string.tinytot_resend_no_permission)
            return
        }
        if (!settings.isConfigured) {
            showToast(R.string.tinytot_error_not_configured)
            return
        }

        busy = true
        lifecycleScope.launch {
            try {
                val count = forwarder.resendRecent(requireContext().applicationContext)
                showToast(getString(R.string.tinytot_resend_done, count))
            } catch (e: Exception) {
                showToast(requireContext().describeUpiError(e))
            } finally {
                busy = false
            }
        }
    }

    private fun updateApiKeySummary() {
        findPreference<Preference>(TinyTotSettings.KEY_API_KEY)?.summary = getString(
            if (settings.apiKey != null) R.string.tinytot_api_key_set
            else R.string.tinytot_api_key_not_set
        )
    }

    private fun updateStatus() {
        // Every settings change lands here, so this is where forwarding gets switched on.
        context?.let { UpiForwarding.ensure(it, settings) }
        val pref = findPreference<Preference>(TinyTotSettings.KEY_STATUS) ?: return
        val lines = mutableListOf<String>()

        lines += settings.lastSuccessAt?.let {
            getString(R.string.tinytot_status_last_sync, relative(it))
        } ?: getString(R.string.tinytot_status_never)

        val lastError = settings.lastError
        val lastErrorAt = settings.lastErrorAt
        if (lastError != null && lastErrorAt != null) {
            lines += getString(R.string.tinytot_status_last_error, relative(lastErrorAt), lastError)
        }

        pref.summary = lines.joinToString("\n")
    }

    private fun relative(time: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
        time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    )

    companion object {
        private const val CLOCK_WARNING_SECONDS = 60L
    }
}
