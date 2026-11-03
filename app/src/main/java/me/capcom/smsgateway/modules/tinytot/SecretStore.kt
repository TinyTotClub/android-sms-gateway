package me.capcom.smsgateway.modules.tinytot

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the verifier API key encrypted with an AES-GCM key that never leaves the
 * AndroidKeyStore (API 23+). A restored backup cannot decrypt it, so the key reads as
 * "not set" on another phone. Below API 23 there is no symmetric keystore key and the
 * value is kept in private app storage as-is.
 */
class SecretStore(
    private val preferences: SharedPreferences,
) {
    fun get(name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return preferences.getString(plainKey(name), null)
        }

        val stored = preferences.getString(encryptedKey(name), null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decrypt $name", e)
            null
        }
    }

    fun set(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            preferences.edit {
                remove(encryptedKey(name))
                remove(plainKey(name))
            }
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            preferences.edit { putString(plainKey(name), value) }
            return
        }

        preferences.edit { putString(encryptedKey(name), encrypt(value)) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + ciphertext, Base64.NO_WRAP)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptedKey(name: String) = "$PREFIX.$name.enc"
    private fun plainKey(name: String) = "$PREFIX.$name.plain"

    companion object {
        private const val TAG = "TinyTotSecretStore"
        private const val PREFIX = "tinytot.secret"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tinytot_upi_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
