package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The terminator rules of the parser, pinned after the incident of 2026-11-05:
 * a test client sent a BYE whose header block was closed by a single CRLF rather
 * than RFC 3261's CRLF CRLF, the app parsed nothing, said nothing, and the
 * cellular call outlived its window until the network ended it.
 *
 * A dropped BYE is the expensive failure, so the parser completes the terminator
 * for requests that cannot carry a body.  A truncated INVITE is the dangerous
 * one - reading a fragment as complete would dial whatever it names - so those
 * stay refused.
 */
class SipMessageTest {

    /** Asterisk-shaped BYE, minus the blank line that RFC 3261 section 7 wants. */
    private val byeHeaders = "BYE sip:9333@192.168.0.6:5062 SIP/2.0\r\n" +
        "Via: SIP/2.0/UDP 192.168.0.240:5060;branch=z9hG4bK-1122;rport\r\n" +
        "From: <sip:trunk@192.168.0.240>;tag=aaaa1111\r\n" +
        "To: <sip:9333@192.168.0.6:5062>;tag=bbbb2222\r\n" +
        "Call-ID: 123456call@192.168.0.240\r\n" +
        "CSeq: 2 BYE\r\n" +
        "Max-Forwards: 70\r\n" +
        "User-Agent: Asterisk PBX 21.6"

    @Test
    fun terminatedByeParsesAsExpected() {
        val msg = SipMessage.parse("$byeHeaders\r\n\r\n")
        assertNotNull(msg)
        assertEquals("BYE", msg!!.method)
        assertEquals("123456call@192.168.0.240", msg.callId)
        assertEquals("bbbb2222", msg.toTag)
        assertEquals("aaaa1111", msg.fromTag)
        assertEquals(Pair("192.168.0.240", 5060), viaHostAndPort(msg.via))
    }

    @Test
    fun byeClosedByASingleCrlfIsStillParsed() {
        val msg = SipMessage.parse("$byeHeaders\r\n")
        assertNotNull(msg)
        assertEquals("BYE", msg!!.method)
        assertEquals("123456call@192.168.0.240", msg.callId)
        assertEquals(2 to "BYE", msg.cseq?.let { Pair(it.split(" ")[0].toInt(), it.split(" ")[1]) })
        // The missing terminator is completed, not merely appended to, so the body
        // really is empty and nothing has to know that the sender was malformed.
        assertEquals("", msg.body)
    }

    @Test
    fun byeClosedByASingleBareLfIsStillParsed() {
        val msg = SipMessage.parse("$byeHeaders\n")
        assertNotNull(msg)
        assertEquals("BYE", msg!!.method)
    }

    @Test
    fun byeNotClosedAtAllIsStillParsed() {
        // Not on the wire, but it proves the leniency is about the body, not
        // about a line ending: nothing here can hide anything.
        val msg = SipMessage.parse("$byeHeaders")
        assertNotNull(msg)
        assertEquals("123456call@192.168.0.240", msg!!.callId)
    }

    @Test
    fun contentLengthAgreeingWithNoBodyIsAccepted() {
        val msg = SipMessage.parse("$byeHeaders\r\nContent-Length: 0\r\n")
        assertNotNull(msg)
        assertEquals("BYE", msg!!.method)
    }

    @Test
    fun byeClaimingABodyItDidNotSendIsRefused() {
        // If a sender says there are 20 bytes of body and they are not there, the
        // message really is truncated: completing it would be guessing.
        assertNull(SipMessage.parse("$byeHeaders\r\nContent-Length: 20\r\n"))
    }

    @Test
    fun aTruncatedInviteIsNeverCompleted() {
        // Same missing terminator, but an INVITE may have an SDP behind it and it
        // names the number to dial, so a fragment must not be read as a call.
        val invite = "INVITE sip:9333@192.168.0.6:5062 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 192.168.0.240:5060;branch=z9hG4bK-1;rport\r\n" +
            "Call-ID: 77@192.168.0.240\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "Content-Length: 142\r\n"
        assertNull(SipMessage.parse(invite))
        // With no Content-Length the rule is the same: only the bodyless set is completed.
        assertNull(SipMessage.parse(invite.replace("Content-Length: 142\r\n", "")))
    }

    @Test
    fun otherBodylessRequestsAreTreatedLikeBye() {
        for (method in listOf("ACK", "CANCEL", "OPTIONS", "REGISTER", "NOTIFY")) {
            val msg = SipMessage.parse("$method sip:9333@192.168.0.6 SIP/2.0\r\nCall-ID: x@y\r\n")
            assertEquals(method, msg?.method)
        }
    }

    @Test
    fun aGarbageStartLineIsNotGivenTheBenefitOfTheDoubt() {
        assertNull(SipMessage.parse("NOTAREQUEST sip:9333@192.168.0.6 SIP/2.0\r\nCall-ID: x@y\r\n"))
        assertNull(SipMessage.parse("BYExy sip:9333@192.168.0.6 SIP/2.0\r\nCall-ID: x@y\r\n"))
        assertNull(SipMessage.parse("\r\n"))
        assertNull(SipMessage.parse(""))
    }

    @Test
    fun responsesAreNotCompletedOnAMissingTerminator() {
        // A response without a body is legal and rarer trouble: the dialog's own
        // transaction layer deals with it.  Only requests get the leniency.
        assertNull(SipMessage.parse("SIP/2.0 200 OK\r\nCall-ID: x@y\r\n"))
        assertNotNull(SipMessage.parse("SIP/2.0 200 OK\r\nCall-ID: x@y\r\n\r\n"))
    }

    @Test
    fun foldedAndIndentedHeadersStillParse() {
        val msg = SipMessage.parse(
            "BYE sip:9333@192.168.0.6 SIP/2.0\r\n" +
                "To: <sip:9333@192.168.0.6>\r\n\t;tag=cccc3333\r\n" +
                "Call-ID: x@y\r\n"
        )
        assertNotNull(msg)
        assertEquals("cccc3333", msg!!.toTag)
    }

    /** host and port out of a `Via: SIP/2.0/UDP host:port;branch=...` value. */
    private fun viaHostAndPort(value: String?): Pair<String, Int>? = value?.let {
        val hostPort = it.substringAfter(' ').substringBefore(';')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "5060").toIntOrNull() ?: 5060
        Pair(host, port)
    }
}
