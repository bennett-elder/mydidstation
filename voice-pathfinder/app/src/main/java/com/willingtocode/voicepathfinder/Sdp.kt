package com.willingtocode.voicepathfinder

import java.security.SecureRandom

/** One codec as it appears in an SDP `a=rtpmap` line. */
internal data class Codec(
    val payloadType: Int,
    val encoding: String,
    val clockRate: Int,
    val fmtp: String? = null,
)

/**
 * Codec order and encryption in the offer, which is the whole point of this app.
 *
 * With `sip_traffic` on, the provider refuses unencrypted media with 488, so every call
 * is `RTP/SAVP` with an `a=crypto` line. [PCMU_CLEAR] is the one deliberate exception: it
 * exists to test the audio stack while encryption is switched off on the sub-account, so
 * that a silent call has one possible cause instead of two.
 *
 * - [PCMU] is the control. It asks for one codec, so nothing can be negotiated away.
 * - [G722_FIRST] and [G722_ONLY] answer whether the server honors our SDP order. It does
 *   not: an offer of `9 0 101` came back as `0 9 101`, and the account's list is
 *   normalized server-side, so withholding G.711 is the only way to make it pick G.722.
 * - [PCMU_CLEAR] is the audio test, and the only mode that can carry sound today.
 */
enum class OfferMode(val label: String, val detail: String) {
    PCMU(
        "PCMU only",
        "G.711u, 8 kHz, and nothing else. The control: one codec cannot be reordered.",
    ),
    G722_FIRST(
        "G.722 offered first",
        "Offers G.722 before G.711. Answers whether the server honors our order. " +
            "G.722 is not decoded here, so a call that picks it is silent.",
    ),
    G722_ONLY(
        "G.722 only",
        "Offers G.722 and nothing else. The provider keeps its own codec order and " +
            "normalizes the account's list, so this is the only way to make it pick G.722.",
    ),
    PCMU_CLEAR(
        "PCMU, unencrypted",
        "RTP/AVP with no crypto line. The only mode that can carry audio in this build, " +
            "and it works only while Encrypted SIP Traffic is off on the sub-account.",
    ),
}

/** A parsed SDP answer, reduced to what a call needs. */
internal data class SdpAnswer(
    /** `RTP/AVP` or `RTP/SAVP`. The first is the answer to open question 1. */
    val protocol: String,
    val address: String?,
    val port: Int,
    val payloadTypes: List<Int>,
    val codec: Codec?,
    val crypto: List<String>,
    val ptime: Int?,
) {
    /** True when the media is encrypted, by protocol or by the presence of a crypto line. */
    val encrypted: Boolean
        get() = protocol.equals(Sdp.PROTO_SRTP, ignoreCase = true) || crypto.isNotEmpty()
}

/**
 * SDP offer and answer, with no sockets and no call state.
 *
 * A deliberate narrowing: one audio stream, the codecs this probe can name, and no
 * attribute it does not read. Anything else is preserved in the raw body, which is
 * logged, because the answer is evidence before it is code.
 */
internal object Sdp {

    const val PROTO_RTP = "RTP/AVP"
    const val PROTO_SRTP = "RTP/SAVP"

    /**
     * Fixed payload type for `telephone-event`. DTMF is not sent yet; it is offered so
     * the answer shows the server's view of it, and so Phase A does not have to
     * renegotiate the shape of the offer.
     */
    const val TELEPHONE_EVENT_PT = 101

    val PCMU = Codec(0, "PCMU", 8000)
    val G722 = Codec(9, "G722", 8000) // clock rate stays 8000, as the RTP mapping requires
    val TELEPHONE_EVENT = Codec(TELEPHONE_EVENT_PT, "telephone-event", 8000, fmtp = "0-16")

    /** The suite Asterisk and VoIP.ms name when SRTP is configured. */
    private const val SRTP_SUITE = "AES_CM_128_HMAC_SHA1_80"

    private val known = mapOf(0 to PCMU, 9 to G722, TELEPHONE_EVENT_PT to TELEPHONE_EVENT)

    private val random = SecureRandom()

    /**
     * The offer, with CRLF endings because that is what SIP carries.
     *
     * [cryptoKey] exists for tests. In a call it is a fresh 30-byte key and salt, which
     * is what the suite requires, and it is never used to encrypt anything here.
     */
    fun offer(
        sessionId: Long,
        address: String,
        port: Int,
        mode: OfferMode,
        ptime: Int = G711.FRAME_MILLIS,
        cryptoKey: String = randomKey(),
    ): String {
        val codecs = when (mode) {
            OfferMode.PCMU -> listOf(PCMU, TELEPHONE_EVENT)
            OfferMode.G722_FIRST -> listOf(G722, PCMU, TELEPHONE_EVENT)
            OfferMode.G722_ONLY -> listOf(G722, TELEPHONE_EVENT)
            OfferMode.PCMU_CLEAR -> listOf(PCMU, TELEPHONE_EVENT)
        }
        // Encrypted unless this is the deliberate unencrypted control, which the provider
        // refuses with 488 while `sip_traffic` is on.
        val encrypted = mode != OfferMode.PCMU_CLEAR
        val protocol = if (encrypted) PROTO_SRTP else PROTO_RTP
        // G.722 is a 16 kHz signal carried with an 8 kHz RTP clock. Writing 16000 here
        // is the classic bug, and it produces audio at half speed rather than an error.
        val types = codecs.joinToString(" ") { it.payloadType.toString() }

        val out = StringBuilder()
        fun line(text: String) {
            out.append(text).append("\r\n")
        }
        line("v=0")
        line("o=- $sessionId 1 IN IP4 $address")
        line("s=VoicePathfinder")
        line("c=IN IP4 $address")
        line("t=0 0")
        line("m=audio $port $protocol $types")
        for (codec in codecs) {
            line("a=rtpmap:${codec.payloadType} ${codec.encoding}/${codec.clockRate}")
            codec.fmtp?.let { line("a=fmtp:${codec.payloadType} $it") }
        }
        if (encrypted) line("a=crypto:1 $SRTP_SUITE inline:$cryptoKey")
        line("a=ptime:$ptime")
        line("a=sendrecv")
        return out.toString()
    }

    fun parse(body: String): SdpAnswer? {
        var sessionAddress: String? = null
        var mediaAddress: String? = null
        var port = -1
        var protocol = ""
        var types: List<Int> = emptyList()
        var inAudio = false
        var sawAudio = false
        var ptime: Int? = null
        val crypto = mutableListOf<String>()
        val rtpmaps = mutableMapOf<Int, Codec>()

        for (raw in body.split("\r\n", "\n")) {
            val text = raw.trim()
            when {
                text.startsWith("m=") -> {
                    if (inAudio) break // only the first audio stream is used
                    val parts = text.removePrefix("m=").split(" ")
                    if (parts.size >= 4 && parts[0].equals("audio", ignoreCase = true)) {
                        inAudio = true
                        sawAudio = true
                        port = parts[1].toIntOrNull() ?: -1
                        protocol = parts[2]
                        types = parts.drop(3).mapNotNull { it.toIntOrNull() }
                    }
                }
                text.startsWith("c=IN IP4 ") -> {
                    val address = text.removePrefix("c=IN IP4 ").substringBefore('/').trim()
                    if (address.isNotEmpty()) {
                        if (inAudio) mediaAddress = address else sessionAddress = address
                    }
                }
                text.startsWith("a=crypto:") && inAudio -> crypto += text.removePrefix("a=")
                text.startsWith("a=ptime:") && inAudio ->
                    ptime = text.removePrefix("a=ptime:").trim().toIntOrNull()
                text.startsWith("a=rtpmap:") && inAudio -> {
                    val rest = text.removePrefix("a=rtpmap:").trim()
                    val type = rest.substringBefore(' ').toIntOrNull()
                    val encoding = rest.substringAfter(' ', "").substringBefore('/')
                    val clock = rest.substringAfter('/', "").substringBefore('/').toIntOrNull()
                    if (type != null && encoding.isNotEmpty() && clock != null) {
                        rtpmaps[type] = Codec(type, encoding, clock)
                    }
                }
            }
        }

        if (!sawAudio) return null
        val selected = types.firstNotNullOfOrNull { rtpmaps[it] ?: known[it] }
        return SdpAnswer(
            protocol = protocol,
            address = mediaAddress ?: sessionAddress,
            port = port,
            payloadTypes = types,
            codec = selected,
            crypto = crypto,
            ptime = ptime,
        )
    }

    /** The selected codec, falling back to the payload type when rtpmap is absent. */
    fun codecFor(payloadType: Int, answer: SdpAnswer): Codec? =
        answer.payloadTypes.firstOrNull { it == payloadType }?.let { known[payloadType] }
            ?: answer.codec

    private fun randomKey(): String {
        val bytes = ByteArray(30) // 16-byte key plus 14-byte salt
        random.nextBytes(bytes)
        return base64(bytes)
    }

    /** A fresh inline key for the offer, exposed so the caller can keep the one it sent. */
    fun newCryptoKey(): String = randomKey()

    /**
     * The `inline:` key from an `a=crypto` line, or null when the line has none.
     *
     * SDES carries the master key and salt in the SDP itself, base64, with an optional
     * lifetime after a pipe. VoIP.ms sends thirty bytes and no lifetime, so a missing key
     * or a short one means we cannot decrypt and the call should say so rather than play
     * noise.
     */
    fun inlineKey(cryptoLine: String): ByteArray? {
        val marker = "inline:"
        val at = cryptoLine.indexOf(marker, ignoreCase = true)
        if (at < 0) return null
        val encoded = cryptoLine.substring(at + marker.length).trim().substringBefore('|').trim()
        return unbase64(encoded)
    }

    /** The decoder that pairs with [base64], and by hand for the same two reasons. */
    internal fun unbase64(text: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val cleaned = text.trim().trimEnd('=')
        if (cleaned.isEmpty()) return null
        val out = ByteArray(cleaned.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var written = 0
        for (character in cleaned) {
            val value = alphabet.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(written)
    }

    /**
     * Base64 by hand: `java.util.Base64` needs API 26 and `android.util.Base64` is not
     * available to JVM tests. This encoder is only ever handed 30 random bytes.
     */
    internal fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(alphabet[b2 and 0x3F])
            i += 3
        }
        while (out.length % 4 != 0) out.append('=')
        return out.toString()
    }
}

