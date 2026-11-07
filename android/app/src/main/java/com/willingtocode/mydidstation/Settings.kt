package com.willingtocode.mydidstation

import android.content.Context
import org.json.JSONObject

/**
 * The two answers the API layer needs, and nothing else.
 *
 * An interface so [VoipMs] never needs a [Context], which is what makes the API layer
 * testable.
 */
interface ApiSettings {
    fun credentials(): Pair<String, String>?

    fun endpoint(): String
}

/**
 * Everything the app remembers, split in two.
 *
 * The API credentials are one sealed blob, read and written through [SecretBox]. Nothing
 * else stored here is secret, so it sits in the same preference file and can be read by
 * the settings screen and the poll worker without touching the Keystore. That split is
 * why constructing this class cannot fail: no key is created until a credential is
 * actually read or written.
 */
class Settings(
    private val context: Context,
    private val box: SecretBox = KeystoreBox(),
) : ApiSettings {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // The old EncryptedSharedPreferences ciphertext cannot be opened without the key
        // that library owned, so the file goes and the credentials get asked for once.
        runCatching { context.deleteSharedPreferences(LEGACY_PREFS) }
    }

    /** Both halves or neither: they are one blob, not two independent values. */
    fun save(email: String, password: String) {
        val payload = JSONObject()
            .put(FIELD_EMAIL, email.trim())
            .put(FIELD_PASSWORD, password)
            .toString()
        // commit, not apply: a credential the user just typed should be on disk before
        // the call returns, and this runs off the main thread. apply would leave a
        // window in which a kill loses the value the user believes is saved.
        prefs.edit().putString(KEY_CREDENTIALS, box.seal(PURPOSE, payload)).commit()
    }

    override fun credentials(): Pair<String, String>? {
        val sealed = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        val json = box.open(PURPOSE, sealed) ?: return forget()
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return forget()
        val email = parsed.optString(FIELD_EMAIL).takeIf { it.isNotBlank() } ?: return null
        val password = parsed.optString(FIELD_PASSWORD).takeIf { it.isNotEmpty() } ?: return null
        return email to password
    }

    /**
     * A blob that will not open is dropped, so a damaged value is not retried on every
     * poll. That covers a corrupted file and a key the device no longer holds.
     */
    private fun forget(): Pair<String, String>? {
        prefs.edit().remove(KEY_CREDENTIALS).apply()
        return null
    }

    fun email(): String {
        val sealed = prefs.getString(KEY_CREDENTIALS, null) ?: return ""
        val json = box.open(PURPOSE, sealed) ?: return ""
        return runCatching { JSONObject(json).optString(FIELD_EMAIL) }.getOrDefault("")
    }

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

    internal companion object {
        const val PREFS = "settings"

        /** Where the encrypted file lived before this class stopped using that library. */
        const val LEGACY_PREFS = "secure"

        const val KEY_CREDENTIALS = "credentials"

        /** Authenticated alongside the blob, so it cannot be opened as some other value. */
        private const val PURPOSE = "credentials"

        private const val FIELD_EMAIL = "email"
        private const val FIELD_PASSWORD = "password"
        private const val KEY_LAST_POLL = "last_poll_at"
        private const val KEY_ENDPOINT = "api_endpoint"
        private const val KEY_FOREGROUND_SECONDS = "foreground_seconds"
        private const val KEY_BACKGROUND_MINUTES = "background_minutes"
        private const val KEY_NOTIFY = "notify_incoming"
        private const val KEY_NOTIFY_ASKED = "notify_permission_asked"

        const val DEFAULT_ENDPOINT = "https://voip.ms/api/v1/rest.php"
        private const val DEFAULT_FOREGROUND_SECONDS = 60
        private const val DEFAULT_BACKGROUND_MINUTES = 15
    }
}
