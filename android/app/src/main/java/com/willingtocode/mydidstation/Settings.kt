package com.willingtocode.mydidstation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API credentials, encrypted at rest with a Keystore-backed master key. */
class Settings(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(email: String, password: String) {
        prefs.edit().putString(KEY_EMAIL, email.trim()).putString(KEY_PASSWORD, password).apply()
    }

    fun credentials(): Pair<String, String>? {
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() } ?: return null
        return email to password
    }

    fun email(): String = prefs.getString(KEY_EMAIL, "").orEmpty()

    private companion object {
        const val KEY_EMAIL = "api_email"
        const val KEY_PASSWORD = "api_password"
    }
}
