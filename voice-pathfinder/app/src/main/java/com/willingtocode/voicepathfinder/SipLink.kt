package com.willingtocode.voicepathfinder

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One inbound SIP message, and where it came from when that is knowable. */
internal data class Incoming(val bytes: ByteArray, val source: InetSocketAddress?)

/**
 * The signalling connection, as the rest of the code needs it: send a message, receive a
 * message, close.
 *
 * Two implementations, because the account's setting decides which one applies. UDP is
 * what this app shipped with. TLS is what the target account actually uses, and its cost
 * is a stream framing layer rather than one message per datagram.
 */
internal interface SipLink {
    val localPort: Int

    /** `UDP` or `TLS`, for the Via header and the report. */
    val transport: String

    /** True when the transport is reliable, so a request must not be retransmitted. */
    val reliable: Boolean

    fun send(bytes: ByteArray, target: InetSocketAddress? = null)

    /** Blocks for one complete message, or returns null when the connection is gone. */
    fun receive(): Incoming?

    fun close()
}

internal class UdpLink(private val socket: DatagramSocket, private val server: InetSocketAddress) : SipLink {

    override val localPort: Int get() = socket.localPort
    override val transport: String get() = "UDP"
    override val reliable: Boolean get() = false

    override fun send(bytes: ByteArray, target: InetSocketAddress?) {
        socket.send(DatagramPacket(bytes, bytes.size, target ?: server))
    }

    override fun receive(): Incoming? {
        val buffer = ByteArray(8192)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            Incoming(packet.data.copyOf(packet.length), InetSocketAddress(packet.address, packet.port))
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        socket.close()
    }
}

internal class TlsLink(host: String, port: Int) : SipLink {

    private val socket: SSLSocket
    private val input: BufferedInputStream
    private val output: BufferedOutputStream
    private var buffer = ByteArray(0)

    init {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val created = factory.createSocket() as SSLSocket
        created.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        // Two things have to be asked for explicitly on a raw SSLSocket. Hostname
        // verification is not on by default, and a shared provider needs SNI to return
        // the certificate for the POP rather than its default one.
        val parameters = created.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        parameters.serverNames = listOf(SNIHostName(host))
        created.sslParameters = parameters
        created.startHandshake()
        socket = created
        input = BufferedInputStream(created.inputStream)
        output = BufferedOutputStream(created.outputStream)
    }

    override val localPort: Int get() = socket.localPort
    override val transport: String get() = "TLS"
    override val reliable: Boolean get() = true

    override fun send(bytes: ByteArray, target: InetSocketAddress?) {
        output.write(bytes)
        output.flush()
    }

    /** CRLFCRLF, which a TLS connection needs to survive a NAT idle timer. */
    fun keepalive() {
        output.write(KEEPALIVE)
        output.flush()
    }

    override fun receive(): Incoming? {
        while (true) {
            when (val frame = SipFraming.next(buffer, buffer.size)) {
                is Frame.Keepalive -> buffer = buffer.copyOfRange(frame.skip, buffer.size)
                is Frame.Message -> {
                    val bytes = buffer.copyOfRange(frame.offset, frame.offset + frame.length)
                    buffer = buffer.copyOfRange(frame.offset + frame.length, buffer.size)
                    return Incoming(bytes, null)
                }
                Frame.Incomplete -> {
                    val chunk = ByteArray(READ_CHUNK)
                    val read = try {
                        input.read(chunk)
                    } catch (_: Exception) {
                        return null
                    }
                    if (read < 0) return null
                    buffer += chunk.copyOf(read)
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_CHUNK = 4096
        val KEEPALIVE = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
