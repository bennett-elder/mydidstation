package com.willingtocode.voicepathfinder

/** The first line of a SIP message: a request line or a status line. */
internal data class SipStart(
    val method: String? = null,
    val uri: String? = null,
    val status: Int? = null,
    val reason: String = "",
) {
    val isRequest: Boolean get() = status == null

    val firstLine: String
        get() = if (isRequest) "$method $uri SIP/2.0" else "SIP/2.0 $status $reason"
}

/**
 * A SIP message, parsed or about to be sent, with no socket in it.
 *
 * Header order and repetition are preserved because they matter: a 200 to an INVITE
 * must carry back every Via, and a test that reads only the first one passes while the
 * response is broken. `Content-Length` is recomputed on the way out, so a caller can
 * never send a body the receiver will truncate.
 */
internal class SipMessage(
    val start: SipStart,
    val headers: List<Pair<String, String>>,
    val body: String = "",
) {

    /** First value for [name], case-insensitive, or null. */
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun allHeaders(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun toBytes(): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.ISO_8859_1)
        val out = StringBuilder()
        out.append(start.firstLine).append("\r\n")
        for ((name, value) in headers) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            out.append(name).append(": ").append(value).append("\r\n")
        }
        out.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        out.append("\r\n")
        if (body.isNotEmpty()) out.append(body)
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    companion object {

        const val USER_AGENT = "VoicePathfinder/0.1"

        fun request(
            method: String,
            uri: String,
            headers: List<Pair<String, String>>,
            body: String = "",
        ): SipMessage = SipMessage(SipStart(method = method, uri = uri), headers, body)

        /**
         * A response that echoes what the request requires, with a To tag added when the
         * request had none. A response missing its Via or CSeq is discarded by the peer
         * without a reply, which is indistinguishable from the packet never arriving.
         */
        fun replyingTo(
            request: SipMessage,
            code: Int,
            reason: String,
            toTag: String?,
            extra: List<Pair<String, String>> = emptyList(),
        ): SipMessage {
            val headers = mutableListOf<Pair<String, String>>()
            for (via in request.allHeaders("Via")) headers += "Via" to via
            request.header("From")?.let { headers += "From" to it }
            val to = request.header("To").orEmpty()
            if (to.isNotEmpty()) {
                val tagged = if (toTag != null && !to.contains("tag=", ignoreCase = true)) {
                    "$to;tag=$toTag"
                } else {
                    to
                }
                headers += "To" to tagged
            }
            request.header("Call-ID")?.let { headers += "Call-ID" to it }
            request.header("CSeq")?.let { headers += "CSeq" to it }
            headers += "User-Agent" to USER_AGENT
            headers += extra
            return SipMessage(SipStart(status = code, reason = reason), headers)
        }

        fun parse(bytes: ByteArray, length: Int = bytes.size): SipMessage? {
            if (length <= 0) return null
            // ISO-8859-1 maps one byte to one char, so Content-Length, which counts
            // bytes, is the same as a character count here and the SDP body survives.
            val text = String(bytes, 0, length, Charsets.ISO_8859_1)
            val marker = when {
                text.contains("\r\n\r\n") -> "\r\n\r\n"
                text.contains("\n\n") -> "\n\n"
                else -> return null
            }
            val at = text.indexOf(marker)
            val head = text.substring(0, at)
            val body = text.substring(at + marker.length)
            val lines = head.split("\r\n", "\n")
            val start = parseStart(lines.firstOrNull()?.trim().orEmpty()) ?: return null

            val headers = mutableListOf<Pair<String, String>>()
            for (raw in lines.drop(1)) {
                if (raw.isEmpty()) continue
                if ((raw.startsWith(" ") || raw.startsWith("\t")) && headers.isNotEmpty()) {
                    // Folded header: RFC 3261 folds by inserting CRLF before whitespace.
                    val last = headers.removeAt(headers.size - 1)
                    headers += last.first to (last.second + " " + raw.trim())
                    continue
                }
                val colon = raw.indexOf(':')
                if (colon <= 0) continue
                headers += raw.substring(0, colon).trim() to raw.substring(colon + 1).trim()
            }

            val declared = headers
                .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second
                ?.toIntOrNull()
            val kept = if (declared != null && declared in 0 until body.length) {
                body.substring(0, declared)
            } else {
                body
            }
            return SipMessage(start, headers, kept)
        }

        private fun parseStart(line: String): SipStart? {
            if (line.startsWith("SIP/2.0 ")) {
                val rest = line.removePrefix("SIP/2.0 ")
                val code = rest.substringBefore(' ').toIntOrNull() ?: return null
                val reason = if (rest.contains(' ')) rest.substringAfter(' ') else ""
                return SipStart(status = code, reason = reason)
            }
            val parts = line.split(" ")
            if (parts.size < 3) return null
            if (!parts.last().startsWith("SIP/2.0")) return null
            return SipStart(method = parts[0], uri = parts[1])
        }

        /** The number and method from `CSeq`, or null when the header is malformed. */
        fun cseqOf(message: SipMessage): Pair<Int, String>? {
            val value = message.header("CSeq") ?: return null
            val parts = value.trim().split(Regex("\\s+"))
            val number = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val method = parts.getOrNull(1) ?: return null
            return number to method
        }

        /** The `branch` parameter of a Via, which is what ties an ACK to its INVITE. */
        fun branchOf(via: String): String? {
            val at = via.indexOf("branch=", ignoreCase = true)
            if (at < 0) return null
            return via.substring(at + 7).substringBefore(';').trim().ifEmpty { null }
        }
    }
}
