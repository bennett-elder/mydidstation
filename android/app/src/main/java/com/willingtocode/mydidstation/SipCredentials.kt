package com.willingtocode.mydidstation

/**
 * The credentials for one line, as the SIP stack needs them.
 *
 * Deliberately not persisted. The provider's API returns the sub-account password in the
 * clear, so this app receives it, uses it, and drops it when the process dies. Writing it
 * down would mean a second copy of a secret the provider will hand over again for free.
 *
 * [label] is the human name from the API, for the screen only. [codecs] is the sub-account's
 * `allowed_codecs`, which decides whether offering G.722 can do anything.
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
