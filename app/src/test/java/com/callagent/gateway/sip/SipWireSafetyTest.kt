package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes *out* on the wire, pinned after the incident of 2026-11-06.
 *
 * The gateway puts text it did not choose into SIP URI positions: the sender of
 * a received SMS is free text from the radio, and a number with separators or a
 * name in another alphabet is ordinary.  A raw space inside the angle brackets of
 * a URI is not a blemish but a parse failure — Asterisk 22 logged "Dropping 897
 * bytes packet from UDP 192.168.0.6:5062 : PJSIP syntax error exception when
 * parsing 'From' header on line 6 col 17" and answered nothing at all, so the
 * gateway waited out its timeout and re-queued a message the server had never
 * looked at.  One such sender sat at the head of the queue for nine hours.
 *
 * SipMessageTest covers the parser; this covers the builder.
 */
class SipWireSafetyTest {

    // ── encodeSipUser ─────────────────────────────────────────────────────

    @Test
    fun aSenderNameWithASpaceBecomesEscaped() {
        // The message that started this: an alphanumeric sender from a Turkish
        // marketer, delivered to the URI as "sip:KIA TURKIYE@…".
        assertEquals("KIA%20TURKIYE", SipBuilder.encodeSipUser("KIA TURKIYE"))
    }

    @Test
    fun anE164NumberIsLeftExactlyAsItWas() {
        // '+' stays literal on purpose: the server matches the Request-URI user
        // against _+X., and an escaped '+' would stop matching and route nothing.
        assertEquals("+905073040000", SipBuilder.encodeSipUser("+905073040000"))
        assertEquals("905073040000", SipBuilder.encodeSipUser("905073040000"))
        assertEquals("android-gw", SipBuilder.encodeSipUser("android-gw"))
    }

    @Test
    fun separatorsThatAUriAllowsAreNotTouched() {
        assertEquals("+90-507.304_0000~x", SipBuilder.encodeSipUser("+90-507.304_0000~x"))
    }

    @Test
    fun spacesAroundDigitsAreEscapedNotDropped() {
        // A softphone may present a number in groups; the digits have to survive
        // the trip, only the separators change shape.
        assertEquals("+90%20507%20304%2000%2000", SipBuilder.encodeSipUser("+90 507 304 00 00"))
    }

    @Test
    fun nonAsciiIsEncodedAsUtf8Bytes() {
        // Ş = U+015E = C5 9E, İ = U+0130 = C4 B0.  Percent-encoding the *bytes*,
        // not the code units, is what a SIP URI calls UTF-8-escaped.
        assertEquals("%C5%9E%C4%B0%C5%9Eecam", SipBuilder.encodeSipUser("ŞİŞecam"))
        assertEquals("T%C3%9CRKTELEKOM", SipBuilder.encodeSipUser("TÜRKTELEKOM"))
    }

    @Test
    fun aPercentSignIsItselfEscaped() {
        // Otherwise "100%" reaches the server as "100%", which reads as a broken
        // escape sequence, and some stacks reject the request over it.
        assertEquals("100%25", SipBuilder.encodeSipUser("100%"))
    }

    @Test
    fun controlCharactersCannotHideInAUser() {
        assertFalse(SipBuilder.encodeSipUser("a\r\nX: 1").contains("\r"))
        assertFalse(SipBuilder.encodeSipUser("a\r\nX: 1").contains("\n"))
        assertEquals("a%0D%0AX%3A%201", SipBuilder.encodeSipUser("a\r\nX: 1"))
    }

    @Test
    fun anEmptySenderStaysEmptyRatherThanInventingOne() {
        assertEquals("", SipBuilder.encodeSipUser(""))
    }

    // ── headerLine ────────────────────────────────────────────────────────

    @Test
    fun aHeaderValueCannotStartASecondHeader() {
        // Carrier names, sender names and failure reasons all end up in a header.
        // Whatever they contain, the request must still have the headers we meant
        // it to have and no others.
        val smuggled = SipBuilder.headerLine("X-SMS-From: A\r\nX-SMS-To: victim")
        assertEquals("X-SMS-From: AX-SMS-To: victim", smuggled)
        assertFalse(smuggled.contains("\r"))
        assertFalse(smuggled.contains("\n"))
    }

    @Test
    fun anOrdinaryHeaderLineIsReturnedUnchanged() {
        val line = "X-SMS-From: TURKTELEKOM"
        assertEquals(line, SipBuilder.headerLine(line))
    }

    // ── the requests themselves ───────────────────────────────────────────

    private fun message(fromUser: String, vararg extra: String) = SipBuilder.message(
        targetUri = "sip:+905073040000@192.168.0.240",
        fromUser = fromUser,
        domain = "192.168.0.240",
        localIp = "192.168.0.6",
        localPort = 5062,
        callId = "1234msg@192.168.0.6",
        cseq = 1,
        body = "test message",
        extraHeaders = extra.toList()
    ).split("\r\n")

    @Test
    fun messageFromHeaderIsParseableForAnySender() {
        val from = message("KIA TURKIYE").first { it.startsWith("From:") }
        assertTrue(from, from.matches(Regex("""From: <sip:KIA%20TURKIYE@192\.168\.0\.240>;tag=\w+""")))
    }

    @Test
    fun messageKeepsTheReadableSenderWhereItBelongsTo() {
        // The URI is the part that has to be escaped; X-SMS-From is the part the
        // dialplan reads, and it is the sender's own name unchanged.
        val lines = message("KIA TURKIYE", "X-SMS-From: KIA TURKIYE", "X-SMS-Id: abc")
        assertTrue(lines.contains("X-SMS-From: KIA TURKIYE"))
        assertTrue(lines.contains("X-SMS-Id: abc"))
        assertFalse(lines.any { it.startsWith("X-SMS-From: KIA%20") })
    }

    @Test
    fun aSmuggledHeaderInExtraHeadersCannotForgeOne() {
        val lines = message(
            "sender",
            "X-SMS-From: A\r\nContent-Length: 0\r\nINVITE sip:9333@192.168.0.6 SIP/2.0"
        )
        // Folded into one line: ugly, but the request is still exactly one request
        // and its Content-Length is still ours.
        assertEquals(1, lines.count { it.startsWith("Content-Length:") })
        assertFalse(lines.any { it.startsWith("INVITE ") })
        assertEquals("Content-Length: 12", lines.first { it.startsWith("Content-Length:") })
    }

    @Test
    fun bodyLengthCountsBytesNotCharacters() {
        // A UCS-2 SMS arrives as UTF-8; declaring the character count would tell
        // the server the body was shorter than it is, and the tail would be lost.
        val lines = SipBuilder.message(
            targetUri = "sip:+905073040000@192.168.0.240",
            fromUser = "sender",
            domain = "192.168.0.240",
            localIp = "192.168.0.6",
            localPort = 5062,
            callId = "1@192.168.0.6",
            cseq = 1,
            body = "Türk Telekom"
        ).split("\r\n")
        assertEquals("Content-Length: 13", lines.first { it.startsWith("Content-Length:") })
    }

    @Test
    fun anInvitedCallerNumberWithSeparatorsIsStillParseable() {
        val invite = SipBuilder.invite(
            targetUri = "sip:9333@192.168.0.240",
            username = "android-gw",
            domain = "192.168.0.240",
            localIp = "192.168.0.6",
            localPort = 5062,
            callId = "1@192.168.0.6",
            cseq = 1,
            localRtpPort = 46100,
            fromTag = "gw1",
            callerIdNumber = "021 555 0100",
            callerIdName = "İstanbul Ofis"
        ).split("\r\n")
        val from = invite.first { it.startsWith("From:") }
        assertEquals("From: \"İstanbul Ofis\" <sip:021%20555%200100@192.168.0.240>;tag=gw1", from)
    }

    @Test
    fun aQuoteInACallerNameDoesNotEndTheQuotedString() {
        val invite = SipBuilder.invite(
            targetUri = "sip:9333@192.168.0.240",
            username = "android-gw",
            domain = "192.168.0.240",
            localIp = "192.168.0.6",
            localPort = 5062,
            callId = "1@192.168.0.6",
            cseq = 1,
            localRtpPort = 46100,
            fromTag = "gw1",
            callerIdNumber = "1001",
            callerIdName = "Ali \"the \" only"
        ).split("\r\n")
        val from = invite.first { it.startsWith("From:") }
        // Exactly one pair of surrounding quotes; the ones inside are escaped, so
        // the display name cannot close itself and turn the rest into headers.
        assertEquals("From: \"Ali \\\"the \\\" only\" <sip:1001@192.168.0.240>;tag=gw1", from)
    }

    @Test
    fun aCallersNameCarriesNoLineBreaksIntoTheRequest() {
        val invite = SipBuilder.invite(
            targetUri = "sip:9333@192.168.0.240",
            username = "android-gw",
            domain = "192.168.0.240",
            localIp = "192.168.0.6",
            localPort = 5062,
            callId = "1@192.168.0.6",
            cseq = 1,
            localRtpPort = 46100,
            fromTag = "gw1",
            callerIdName = "one\r\ntwo"
        )
        val fromLine = invite.split("\r\n").first { it.startsWith("From:") }
        assertEquals("From: \"onetwo\" <sip:android-gw@192.168.0.240>;tag=gw1", fromLine)
    }
}
