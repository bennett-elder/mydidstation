package com.willingtocode.voicepathfinder

import android.content.Context
import org.json.JSONObject

/**
 * The two answers the REST layer needs, and nothing else.
 *
 * An interface so [VoipMs] never needs a [Context], which is what lets the API layer be
 * tested against recorded responses instead of a live account.
 */
interface ApiSettings {
    fun credentials(): Pair<String, String>?

    fun endpoint(): String
}

/**
 * Everything needed to register, assembled from what the API reported.
 *
 * Deliberately not persisted. The provider's API returns the sub-account password in
 * the clear, so this app receives it, uses it, and drops it when the process dies. The
 * alternative, writing it down, would mean a second copy of a secret that the provider
 * will hand over again for free.
 *
 * [label] is the human name from the API, for the screen only. [codecs] is the
 * sub-account's `allowed_codecs`, which decides whether offering G.722 can do anything.
 */
data class SipCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val expiresSeconds: Int,
    val label: String,
    val codecs: String,
    /** True for TLS on the signalling path, which the target account uses. */
    val tls: Boolean,
) {
    val transport: String get() = if (tls) "TLS" else "UDP"

    fun problem(): String? = when {
        host.isBlank() -> "No SIP server was found. Type one, or load the servers again."
        port !in 1..65535 -> "The SIP port must be between 1 and 65535."
        username.isBlank() -> "The sub-account has no SIP username."
        password.isEmpty() -> "The sub-account has no SIP password and none was written back."
        expiresSeconds !in 60..3600 -> "Registration expiry must be 60 to 3600 seconds."
        else -> null
    }
}

/**
 * Stored settings, split by whether they are secret.
 *
 * The API email and API password are one sealed blob, the same shape the product app
 * uses: AES-GCM with a key held by the Android Keystore, and the value authenticated
 * against its purpose so one blob cannot be opened as another. The sub-account's SIP
 * password is never stored here at all, because the API returns it on demand.
 *
 * Everything else, the chosen sub-account, a server override, the last number, is not
 * secret and sits in plain preferences where the screen can read it cheaply.
 */
class PathfinderSettings(
    private val context: Context,
    private val box: SecretBox = KeystoreBox(),
) : ApiSettings {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveApi(email: String, password: String) {
        val payload = JSONObject()
            .put(FIELD_EMAIL, email.trim())
            .put(FIELD_PASSWORD, password)
            .toString()
        // commit, not apply: a credential the user just typed should be on disk before
        // the call returns, and this runs off the main thread.
        prefs.edit().putString(KEY_API, box.seal(PURPOSE, payload)).commit()
    }

    override fun credentials(): Pair<String, String>? {
        val sealed = prefs.getString(KEY_API, null) ?: return null
        val json = when (val opened = box.open(PURPOSE, sealed)) {
            is Unsealed.Ok -> opened.value
            Unsealed.Missing, Unsealed.Unavailable -> return null
            Unsealed.Unreadable -> return forget()
        }
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return forget()
        val email = parsed.optString(FIELD_EMAIL).takeIf { it.isNotBlank() } ?: return null
        val password = parsed.optString(FIELD_PASSWORD).takeIf { it.isNotEmpty() } ?: return null
        return email to password
    }

    private fun forget(): Pair<String, String>? {
        prefs.edit().remove(KEY_API).apply()
        return null
    }

    /** For the screen's greeting. Never the password. */
    fun apiEmail(): String {
        val sealed = prefs.getString(KEY_API, null) ?: return ""
        val opened = box.open(PURPOSE, sealed)
        if (opened !is Unsealed.Ok) return ""
        return runCatching { JSONObject(opened.value).optString(FIELD_EMAIL) }.getOrDefault("")
    }

    fun clearApi() {
        prefs.edit().remove(KEY_API).apply()
    }

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

    /** The sub-account the last successful preparation used, by id. */
    var selectedAccountId: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT, value).apply()
        }

    /** A server the user typed over the one the provider reported. */
    var serverOverride: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER, value.trim()).apply()
        }

    /**
     * Encrypted signalling. TLS is what the target account uses, so it is the default.
     * The account's `sip_traffic` decides it too, and that is what `prepare` starts from.
     */
    var useTls: Boolean
        get() = prefs.getBoolean(KEY_TLS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TLS, value).apply()
        }

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 0)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    /** The port to use, with the conventional default for the transport. */
    fun portFor(tls: Boolean): Int = serverPort.takeIf { it in 1..65535 } ?: if (tls) 5061 else 5060

    var lastNumber: String
        get() = prefs.getString(KEY_LAST_NUMBER, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_NUMBER, value).apply()
        }

    var offerMode: String
        get() = prefs.getString(KEY_OFFER, OfferMode.PCMU.name) ?: OfferMode.PCMU.name
        set(value) {
            prefs.edit().putString(KEY_OFFER, value).apply()
        }

    private companion object {
        const val PREFS = "pathfinder"

        /** Authenticated alongside the blob, so it cannot be opened as some other value. */
        const val PURPOSE = "voipms_api"

        const val KEY_API = "api"
        const val FIELD_EMAIL = "email"
        const val FIELD_PASSWORD = "password"
        const val KEY_ENDPOINT = "api_endpoint"
        const val KEY_ACCOUNT = "sub_account_id"
        const val KEY_SERVER = "sip_server_override"
        const val KEY_PORT = "sip_port"
        const val KEY_TLS = "sip_tls"
        const val KEY_LAST_NUMBER = "last_number"
        const val KEY_OFFER = "offer_mode"

        const val DEFAULT_ENDPOINT = "https://voip.ms/api/v1/rest.php"
    }
}
