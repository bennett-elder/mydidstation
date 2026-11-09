package com.willingtocode.voicepathfinder

import java.security.MessageDigest

/** The parts of a 401 or 407 challenge this client uses. */
internal data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val qop: String?,
    val algorithm: String?,
    val opaque: String?,
)

/**
 * HTTP Digest for SIP, which is the same arithmetic with a different URI.
 *
 * `android.net.sip` did this for apps once. It was deprecated at API 31 and never
 * offered codec control, so the computation is here, in one place, with the RFC 2617
 * test vector as a unit test. A digest that is subtly wrong produces a 403, and a 403
 * looks identical to a bad password, which is a bad afternoon to debug.
 *
 * `qop=auth` is what Asterisk and VoIP.ms send. RFC 2069 (no qop) is supported because
 * the branch is three lines and its absence is another mysterious 403.
 */
internal object Digest {

    /**
     * Parses a `WWW-Authenticate` or `Proxy-Authenticate` value. Returns null when the
     * scheme is not Digest, which is the caller's signal that this is not a challenge it
     * can answer.
     */
    fun parse(value: String): DigestChallenge? {
        val text = value.trim()
        if (!text.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) return null
        val parameters = splitParameters(text.substring(6))
        val realm = parameters["realm"] ?: return null
        val nonce = parameters["nonce"] ?: return null
        val qopOptions = parameters["qop"]
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = qopOptions?.firstOrNull { it == "auth" || it == "auth-int" },
            algorithm = parameters["algorithm"],
            opaque = parameters["opaque"],
        )
    }

    /** The 32 hex characters that go in `response=`. Separated so the test can pin it. */
    fun response(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        cnonce: String,
        nc: String = "00000001",
    ): String {
        val ha1 = md5Hex("$username:${challenge.realm}:$password")
        val ha2 = md5Hex("$method:$uri")
        val qop = challenge.qop
        return if (qop == null) {
            md5Hex("$ha1:${challenge.nonce}:$ha2")
        } else {
            md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:$qop:$ha2")
        }
    }

    /** A complete header value, ready to add as `Authorization` or `Proxy-Authorization`. */
    fun authorization(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        cnonce: String,
        nc: String = "00000001",
    ): String {
        val value = response(challenge, username, password, method, uri, cnonce, nc)
        val out = StringBuilder()
        out.append("Digest username=\"").append(username).append("\"")
        out.append(", realm=\"").append(challenge.realm).append("\"")
        out.append(", nonce=\"").append(challenge.nonce).append("\"")
        out.append(", uri=\"").append(uri).append("\"")
        out.append(", response=\"").append(value).append("\"")
        out.append(", algorithm=").append(challenge.algorithm ?: "MD5")
        challenge.qop?.let { out.append(", qop=").append(it).append(", nc=").append(nc) }
        challenge.qop?.let { out.append(", cnonce=\"").append(cnonce).append("\"") }
        challenge.opaque?.let { out.append(", opaque=\"").append(it).append("\"") }
        return out.toString()
    }

    internal fun md5Hex(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.ISO_8859_1))
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * Splits `realm="a, b", qop="auth,auth-int", nonce=x` on commas that are not inside
     * quotes. A naive split on every comma turns the qop list into a second bogus
     * parameter and the challenge is then subtly unusable.
     */
    internal fun splitParameters(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val current = StringBuilder()
        var quoted = false
        val pieces = mutableListOf<String>()

        fun flush() {
            val piece = current.toString().trim()
            current.setLength(0)
            if (piece.isNotEmpty()) pieces += piece
        }

        for (character in text) {
            when {
                character == '"' -> {
                    quoted = !quoted
                    current.append(character)
                }
                character == ',' && !quoted -> flush()
                else -> current.append(character)
            }
        }
        flush()

        for (piece in pieces) {
            val name = piece.substringBefore('=', "").trim().lowercase()
            if (name.isEmpty()) continue
            val value = piece.substringAfter('=', "").trim().removeSurrounding("\"")
            result[name] = value
        }
        return result
    }

    private const val HEX = "0123456789abcdef"
}
