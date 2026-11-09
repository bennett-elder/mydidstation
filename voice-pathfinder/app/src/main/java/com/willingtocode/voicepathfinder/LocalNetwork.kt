package com.willingtocode.voicepathfinder

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * The two addresses a softphone has to find for itself.
 *
 * A private SDP address is not fatal, because `rport` and the provider's NAT handling
 * cover the SIP leg, but a wrong one means the far end sends audio to a computer that
 * does not exist. Cellular is the case that hurts: the interface is `rmnet0` behind
 * carrier NAT. Wi-Fi is therefore preferred, and the chosen address is logged so a
 * failed call can be read rather than guessed at.
 */
internal object LocalNetwork {

    data class Binding(val interfaceName: String, val address: String)

    fun ipv4(): Binding? {
        val found = mutableListOf<Binding>()
        for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses.toList()) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                found += Binding(nic.name, host)
            }
        }
        if (found.isEmpty()) return null
        return found.minByOrNull { preference(it.interfaceName) }
    }

    private fun preference(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") -> 1
        name.startsWith("eth") -> 2
        name.startsWith("rmnet") || name.startsWith("ccmni") -> 3
        else -> 4
    }

    /**
     * An even port, because RTP's companion RTCP stream would be the odd one and some
     * peers assume the pair. Nothing here sends RTCP, but an odd port invites a server
     * to send RTCP to a port nothing is listening on.
     */
    fun rtpSocket(): DatagramSocket? {
        for (port in RTP_FIRST..RTP_LAST step 2) {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(port))
                return socket
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    const val RTP_FIRST = 40000
    const val RTP_LAST = 40399
}
