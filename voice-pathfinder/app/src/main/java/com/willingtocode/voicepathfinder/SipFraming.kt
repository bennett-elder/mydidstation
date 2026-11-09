package com.willingtocode.voicepathfinder

/**
 * What a SIP message looks like on a stream.
 *
 * UDP hands over one datagram per message and the problem does not exist. Over TLS the
 * bytes arrive in whatever chunks the network chose, so a message ends where
 * `Content-Length` says it does, not where a read ends. Getting this wrong produces
 * responses that vanish, because a truncated message parses into nothing and two joined
 * messages parse into one.
 */
internal sealed interface Frame {
    /** A whole message at [offset], [length] bytes long. */
    data class Message(val offset: Int, val length: Int) : Frame

    /** Keepalive CRLFs before the next message. Discard this many bytes. */
    data class Keepalive(val skip: Int) : Frame

    data object Incomplete : Frame
}

internal object SipFraming {

    private const val HEADER_END = "\r\n\r\n"

    fun next(buffer: ByteArray, length: Int): Frame {
        var start = 0
        while (
            start + 1 < length &&
            buffer[start] == '\r'.code.toByte() &&
            buffer[start + 1] == '\n'.code.toByte()
        ) {
            start += 2
        }
        if (start >= length) return if (start > 0) Frame.Keepalive(start) else Frame.Incomplete

        val text = String(buffer, start, length - start, Charsets.ISO_8859_1)
        val end = text.indexOf(HEADER_END)
        if (end < 0) return if (start > 0) Frame.Keepalive(start) else Frame.Incomplete

        val header = text.substring(0, end)
        val bodyLength = header.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (bodyLength < 0) return Frame.Incomplete

        val total = start + end + HEADER_END.length + bodyLength
        if (length < total) return Frame.Incomplete
        return Frame.Message(start, total - start)
    }
}
