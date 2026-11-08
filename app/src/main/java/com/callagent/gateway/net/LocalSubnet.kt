package com.callagent.gateway.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Which of this handset's own IPv4 addresses should be written into SIP.
 *
 * The address the gateway advertises in `Contact:` and in the SDP `c=` line is
 * the address the PBX uses for everything it sends later: the ACK for our 200 OK,
 * in-dialog re-INVITE and BYE, and the RTP it aims at our call.  One answer has
 * to serve both a carrier trunk across the Internet and a box on the same LAN,
 * so the choice is made by looking at where the server is:
 *
 *  - The server is on a network this handset is attached to: advertise the local
 *    address on that network.  A STUN address there is a hole, not a feature - it
 *    names the far side of a NAT the PBX never has to cross.  Measured on
 *    2026-11-06 with Asterisk at 192.168.0.240 while the phone held
 *    192.168.0.6/24 on wlan0: the 200 OK carried `c=IN IP4 203.0.113.97` (the
 *    broadband WAN address STUN had found; written here as a documentation-range
 *    address, the live one is not published) and `Contact: <sip:...@203.0.113.97>`,
 *    which a LAN peer cannot answer.  Only the test tool's habit of replying to
 *    the *source* of what it received kept that call audible.
 *
 *  - The server is somewhere else: a STUN address is exactly right, because the
 *    PBX has to come back through the NAT and it can only do that to the public
 *    address the NAT handed us.
 *
 * This is also why the local address is not simply "the active network's"
 * address: with mobile data and Wi-Fi both up, the default network can be the
 * cellular one while the SIP socket runs over Wi-Fi, and the directly-connected
 * route is what the reply actually follows.
 *
 * Addresses are compared as four octets, and an address that is already a dotted
 * literal is never run through DNS.  That keeps the common case - a numeric
 * server, which is what this deployment uses - both faster and deterministic,
 * and it lets these rules be tested without a network.
 */
object LocalSubnet {

    /** One of our own IPv4 addresses together with the size of its network. */
    data class LocalNet(val address: String, val prefixLength: Int)

    /**
     * Our address on the network that can reach [host], or null when none of our
     * networks can.  Longest prefix wins, so a /24 LAN beats a /8 side effect of
     * a VPN or tethering interface.
     */
    fun bestFor(host: String, nets: List<LocalNet>): String? {
        if (nets.isEmpty()) return null
        val target = addressOf(host) ?: return null
        return nets
            .filter { net -> octets(net.address)?.let { sameNet(it, target, net.prefixLength) } == true }
            .maxByOrNull { it.prefixLength }
            ?.address
    }

    /** True when [host] sits on one of our own networks. */
    fun serverIsOurs(host: String, nets: List<LocalNet>): Boolean = bestFor(host, nets) != null

    /** True when [net]'s network contains [target]. */
    fun covers(net: LocalNet, target: String): Boolean =
        covers(net, addressOf(target))

    /** Same, for an address already resolved - e.g. by the caller, or by IPv6 code. */
    fun covers(net: LocalNet, target: InetAddress?): Boolean =
        covers(net, target?.address?.map { it.toInt() and 0xFF }?.toIntArray())

    private fun covers(net: LocalNet, target: IntArray?): Boolean {
        val local = octets(net.address) ?: return false
        return target != null && sameNet(local, target, net.prefixLength)
    }

    /**
     * This device's own IPv4 networks, read from the interfaces.  Deliberately
     * not the ConnectivityManager answer: that reports the *default* network,
     * which with cellular data and Wi-Fi up at the same time is often the
     * cellular one, while SIP traffic to a LAN peer follows the Wi-Fi route.
     */
    fun ourNets(): List<LocalNet> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { iface ->
                iface.interfaceAddresses
                    .filter { it.address is Inet4Address }
                    .mapNotNull { la ->
                        la.address.hostAddress?.let { LocalNet(it, la.networkPrefixLength.toInt()) }
                    }
            }
    } catch (t: Throwable) {
        emptyList()
    }

    /** Four octets from a dotted-quad string, or null if it is not one. */
    private fun octets(text: String): IntArray? {
        val parts = text.trim().split(".")
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it < '0' || it > '9' }) return null
            // "010" means 8 to some stacks and 10 to others; nothing this system
            // produces writes it that way, so it is treated as not an address.
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /**
     * A dotted quad is taken as it stands; anything else is given one chance at
     * DNS, because a SIP server may well be configured by name and a name that
     * does not resolve at boot is not an error - it just means we know nothing
     * about where the server is.
     */
    private fun addressOf(host: String): IntArray? {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return null
        octets(trimmed)?.let { return it }
        // Nothing but digits and dots was meant to be an IPv4 literal, and ours is
        // the stricter reading of one.  Sending it to DNS instead invites an answer
        // for something that is not an address - resolvers have been known to
        // accept "192.168.0.06" (is that 6, or octal 6?) or to invent one for a
        // single label, and either way the caller is left with an address nobody
        // configured.
        if (trimmed.all { it == '.' || it.isDigit() }) return null
        return try {
            InetAddress.getByName(trimmed).address?.let { addr ->
                // Only a 4-byte answer can be on an IPv4 network of ours.
                if (addr.size != 4) null
                else IntArray(4) { addr[it].toInt() and 0xFF }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Compare the first [prefix] bits of two IPv4 addresses given as octets. */
    private fun sameNet(local: IntArray, target: IntArray, prefix: Int): Boolean {
        if (local.size != 4 || target.size != 4) return false
        // A /0 is not a network we could ever identify ourselves with, and a
        // negative prefix is not a network at all.
        if (prefix <= 0 || prefix > 32) return false
        var bits = prefix
        for (i in 0 until 4) {
            if (bits <= 0) break
            val take = if (bits >= 8) 8 else bits
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((local[i] and mask) != (target[i] and mask)) return false
            bits -= take
        }
        return true
    }
}
