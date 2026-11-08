package com.callagent.gateway.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The address the gateway puts in `Contact:` and in SDP `c=` has to be one the
 * peer can actually reach.
 *
 * The first case is not invented: on 2026-11-06 the phone held 192.168.0.6/24 on
 * wlan0, Asterisk sat at 192.168.0.240, and the 200 OK named a public WAN address -
 * the broadband address STUN had found, written here as the documentation-range
 * 203.0.113.97 rather than the live one - which is on the wrong side of a NAT that
 * does not exist between those two hosts.
 */
class LocalSubnetTest {

    private val wifi = LocalSubnet.LocalNet("192.168.0.6", 24)
    private val cell = LocalSubnet.LocalNet("10.140.22.7", 8)

    @Test
    fun serverOnOurLanPicksTheLanAddress() {
        assertEquals("192.168.0.6", LocalSubnet.bestFor("192.168.0.240", listOf(cell, wifi)))
        assertTrue(LocalSubnet.serverIsOurs("192.168.0.240", listOf(cell, wifi)))
    }

    @Test
    fun serverAcrossTheInternetHasNoLocalMatchSoStunStaysInPlay() {
        assertNull(LocalSubnet.bestFor("203.0.113.97", listOf(cell, wifi)))
        assertFalse(LocalSubnet.serverIsOurs("203.0.113.97", listOf(cell, wifi)))
    }

    @Test
    fun aCellularAddressIsNotAMatchForALanServer() {
        // The default network can be cellular while the SIP socket runs on Wi-Fi,
        // which is why the route is looked up instead of trusting the active one.
        assertNull(LocalSubnet.bestFor("192.168.0.240", listOf(cell)))
    }

    @Test
    fun longestPrefixWins() {
        val wide = LocalSubnet.LocalNet("192.168.0.60", 16)
        assertEquals("192.168.0.6", LocalSubnet.bestFor("192.168.0.240", listOf(wide, wifi)))
        assertEquals("192.168.0.6", LocalSubnet.bestFor("192.168.0.240", listOf(wifi, wide)))
    }

    @Test
    fun prefixLengthIsHonoured() {
        fun covers(prefix: Int, target: String) =
            LocalSubnet.covers(LocalSubnet.LocalNet("192.168.0.6", prefix), target)

        assertTrue(covers(24, "192.168.0.240"))
        assertFalse(covers(24, "192.168.1.6"))
        assertTrue(covers(16, "192.168.1.6"))
        assertTrue(covers(32, "192.168.0.6"))     // a host route covers itself
        assertFalse(covers(32, "192.168.0.7"))
        assertFalse(covers(0, "192.168.0.6"))     // /0 is no network to advertise from
        assertFalse(covers(-1, "192.168.0.6"))
        assertFalse(covers(33, "192.168.0.6"))
    }

    @Test
    fun oddBitBoundaries() {
        fun covers(address: String, prefix: Int, target: String) =
            LocalSubnet.covers(LocalSubnet.LocalNet(address, prefix), target)

        // 10 = 0000_1010, 11 = 0000_1011: they agree on the first seven bits.
        assertTrue(covers("10.0.0.1", 7, "11.0.0.0"))
        assertFalse(covers("10.0.0.1", 8, "11.0.0.0"))
        // A /1 covers everything whose first bit matches, i.e. 0.x through 127.x.
        assertTrue(covers("10.0.0.1", 1, "100.200.30.4"))
        assertFalse(covers("10.0.0.1", 1, "200.1.1.1"))
        assertTrue(covers("192.168.0.6", 31, "192.168.0.7"))
        assertFalse(covers("192.168.0.6", 31, "192.168.0.8"))
    }

    @Test
    fun nothingIsAssumedAboutUnusableInput() {
        // An IPv4 network never contains an IPv6 address.
        assertFalse(LocalSubnet.covers(wifi, InetAddress.getByName("::1")))
        assertFalse(LocalSubnet.covers(LocalSubnet.LocalNet("nonsense", 24), "192.168.0.6"))
        assertFalse(LocalSubnet.covers(wifi, "1.2.3"))
        assertFalse(LocalSubnet.covers(wifi, "256.1.1.1"))
        assertFalse(LocalSubnet.covers(wifi, "192.168.0.06"))   // leading zeroes: not taken
        // A server we know nothing about must not read as local: "" would otherwise
        // be read as "this host" and match a loopback interface.
        assertNull(LocalSubnet.bestFor("", listOf(wifi)))
        assertNull(LocalSubnet.bestFor("   ", listOf(LocalSubnet.LocalNet("127.0.0.1", 8))))
        assertNull(LocalSubnet.bestFor("192.168.0.240", emptyList()))
    }

    @Test
    fun aNameThatDoesNotResolveIsNotAnError() {
        // Whether DNS answers is the resolver's business; what has to hold either
        // way is that the caller is not given an exception at gateway start-up.
        val outcome = runCatching { LocalSubnet.serverIsOurs("trunk.invalid", listOf(wifi)) }
        assertTrue(outcome.exceptionOrNull()?.toString() ?: "ok", outcome.isSuccess)
    }

    @Test
    fun bracketsFromASipUriAreStripped() {
        assertTrue(LocalSubnet.serverIsOurs("[192.168.0.240]", listOf(wifi)))
    }

    @Test
    fun ourNetsDoesNotThrowOnALaptop() {
        // Cannot assert on the addresses of whatever machine runs the test; what
        // is testable is that collecting them never explodes and never hands back
        // a loopback or a nonsense prefix.
        for (net in LocalSubnet.ourNets()) {
            assertTrue(net.address, net.address.isNotEmpty())
            assertFalse(net.address, net.address.startsWith("127."))
            assertTrue("${net.address}/${net.prefixLength}", net.prefixLength in 0..32)
        }
    }
}
