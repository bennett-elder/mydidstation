package com.willingtocode.voicepathfinder

/**
 * The string surgery between what a person types and what goes on the wire.
 *
 * Small, and wrong silently. A server field holding `sip:atlanta.voip.ms:5060` produces
 * a Request-URI no proxy recognizes, and a dialed number with a leading `+` or a space
 * produces a call to a number that does not exist. Each rule is pinned by a test.
 */
internal object Provider {

    /** The host from a server field, with any scheme, path, port or parameter removed. */
    fun host(input: String): String {
        var text = input.trim().lowercase()
        text = text.removePrefix("sips:").removePrefix("sip:").removePrefix("udp:").removePrefix("tcp:")
        text = text.removePrefix("//")
        text = text.substringBefore('/').substringBefore(';').substringBefore(':')
        return text.trim()
    }

    /** A port typed into the server field, if there was one. */
    fun port(input: String): Int? {
        val afterScheme = input.trim().substringAfter("sip:", input.trim())
        val hostPart = afterScheme.substringBefore('/').substringBefore(';')
        if (!hostPart.contains(':')) return null
        return hostPart.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Digits, and a leading `+` when there was one.
     *
     * The plus is kept because a sub-account in E164 dialing mode expects it, and
     * dropping it turns a valid call into an unroutable one. Everything else that is
     * punctuation is removed. What was dialed is logged, so a mismatch is visible.
     */
    fun normalizeNumber(input: String): String {
        val trimmed = input.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }

    fun serverUri(host: String): String = "sip:$host"

    fun userUri(host: String, username: String): String = "sip:$username@$host"

    fun dialUri(host: String, number: String): String = "sip:$number@$host"

    fun contactUri(
        host: String,
        username: String,
        address: String,
        port: Int,
        transport: String? = null,
    ): String {
        val suffix = if (transport != null) ";transport=$transport" else ""
        return "<sip:$username@$address:$port$suffix>"
    }
}
