package com.willingtocode.mydidstation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The two answers the API layer needs, and nothing else.
 *
 * An interface because constructing the real [Settings] needs a Keystore-backed
 * preference store, and that is the only thing between [VoipMs] and a unit test.
 */
interface ApiSettings {
    fun credentials(): Pair<String, String>?

    fun endpoint(): String
}

/** API credentials, encrypted at rest with a Keystore-backed key. */
class Settings(context: Context) : ApiSettings {

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

    override fun credentials(): Pair<String, String>? {
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() } ?: return null
        return email to password
    }

    fun email(): String = prefs.getString(KEY_EMAIL, "").orEmpty()

    /** When the background poll last finished, so the UI can estimate the next one. */
    fun lastPollAt(): Long = prefs.getLong(KEY_LAST_POLL, 0L)

    fun setLastPollAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_POLL, millis).apply()
    }

    /** API base URL. Overridable so the app can be pointed at a local mock. */
    override fun endpoint(): String =
        prefs.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

    fun setEndpoint(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == DEFAULT_ENDPOINT) {
            prefs.edit().remove(KEY_ENDPOINT).apply()
        } else {
            prefs.edit().putString(KEY_ENDPOINT, trimmed).apply()
        }
    }

    /** True when the endpoint has been pointed somewhere other than VoIP.ms. */
    fun usingCustomEndpoint(): Boolean =
        prefs.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } != null

    /** Seconds between polls while a number is on screen. Zero turns it off. */
    fun foregroundSeconds(): Int = prefs.getInt(KEY_FOREGROUND_SECONDS, DEFAULT_FOREGROUND_SECONDS)

    fun setForegroundSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_FOREGROUND_SECONDS, seconds).apply()
    }

    /** Minutes between background polls. WorkManager refuses anything under 15. */
    fun backgroundMinutes(): Int = prefs.getInt(KEY_BACKGROUND_MINUTES, DEFAULT_BACKGROUND_MINUTES)

    fun setBackgroundMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_BACKGROUND_MINUTES, minutes.coerceAtLeast(15)).apply()
    }

    /** Whether a poll that finds new messages is allowed to notify. */
    fun notificationsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFY, true)

    fun setNotificationsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY, on).apply()
    }

    /** True once the permission has been asked for, so the prompt does not nag. */
    fun notifyAsked(): Boolean = prefs.getBoolean(KEY_NOTIFY_ASKED, false)

    fun setNotifyAsked() {
        prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
    }

    private companion object {
        const val KEY_EMAIL = "api_email"
        const val KEY_PASSWORD = "api_password"
        const val KEY_LAST_POLL = "last_poll_at"
        const val KEY_ENDPOINT = "api_endpoint"
        const val DEFAULT_ENDPOINT = "https://voip.ms/api/v1/rest.php"
        const val KEY_FOREGROUND_SECONDS = "foreground_seconds"
        const val KEY_BACKGROUND_MINUTES = "background_minutes"
        const val KEY_NOTIFY = "notify_incoming"
        const val KEY_NOTIFY_ASKED = "notify_permission_asked"

        const val DEFAULT_FOREGROUND_SECONDS = 60
        const val DEFAULT_BACKGROUND_MINUTES = 15
    }
}
