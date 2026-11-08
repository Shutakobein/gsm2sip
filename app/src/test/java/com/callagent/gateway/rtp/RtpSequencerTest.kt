package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RTP timeline gate in front of the jitter buffer.
 *
 * What is being held to here is the property the receive path did not have: a
 * packet whose place on the call's timeline has already been handed to the
 * playback side is never handed over again, and packets that turn up out of order
 * are released in timeline order.  Every scenario below is a scripted arrival
 * pattern — the packet train an 802.11 power-save path, a NAT, or a PBX that
 * repeats itself actually produces — driven by an injected clock, so none of it
 * waits on real time.
 */
class RtpSequencerTest {

    /** G.711 at 8 kHz: one timestamp tick is one sample, one frame is 160. */
    private val rate = 8000
    private val frame = 160

    /** Injectable "now", so the reorder wait is tested rather than slept through. */
    private var t = 1000L
    private val clock: () -> Long = { t }

    private fun pkt(
        seq: Int,
        ts: Long,
        ssrc: Long = 0xABCDL,
        bytes: Int = frame,
        pt: Int = RtpPacket.PT_PCMA,
    ): RtpPacket = RtpPacket(pt, seq, ts, ssrc, ByteArray(bytes) { seq.toByte() })

    /** Collects what the gate releases, in the order it releases it. */
    private class Sink {
        val seqs = ArrayList<Int>()
        val payloads = ArrayList<ByteArray>()
        val consume: (RtpPacket) -> Unit = { seqs += it.sequenceNumber; payloads += it.payload }
    }

    private fun gate(waitMs: Long = 80L, maxHeld: Int = 3) =
        RtpSequencer(rate, waitMs, maxHeld, clock)

    /** Runs `count` ordinary in-order packets through the gate. */
    private fun runInOrder(g: RtpSequencer, out: Sink, count: Int, ts0: Long = 0L) {
        for (i in 0 until count) g.accept(pkt(i, ts0 + i * frame), out.consume)
    }

    @Test
    fun anInOrderStreamPassesThroughCompletelyAndInOrder() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 20)

        assertEquals((0 until 20).toList(), out.seqs)
        assertEquals(0L, g.outOfOrder)
        assertEquals(0L, g.duplicates)
        assertEquals(0L, g.stale)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.restarts)
        assertEquals(frame.toLong(), g.lastPacketTicks)
    }

    /** The repetition bug in its simplest form: the same datagram twice.  The
     *  reservoir appends in arrival order, so a second copy of a packet is the same
     *  20 ms of someone's speech played again. */
    @Test
    fun aDuplicatedPacketIsNeverPassedTwice() {
        val g = gate()
        val out = Sink()
        for (i in 0 until 10) {
            g.accept(pkt(i, i * frame.toLong()), out.consume)
            if (i == 5) g.accept(pkt(i, i * frame.toLong()), out.consume)   // same seq, same ts
        }

        assertEquals("packet 5 must appear once", 1, out.seqs.count { it == 5 })
        assertEquals((0 until 10).toList(), out.seqs)
        assertEquals(1L, g.duplicates)
        assertEquals(0L, g.stale)
    }

    /** The other half of the same defect: a retransmission that arrives long after
     *  its own moment.  Its content has already been played, so letting it through
     *  says that piece of the call a second time, later. */
    @Test
    fun aRetransmissionThatArrivesAfterItsMomentIsDroppedNotReplayed() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 100)             // packet 3's timestamp leaves the recent window
        g.accept(pkt(3, 3 * frame.toLong()), out.consume)

        assertEquals("the late copy must not reach the buffer", 100, out.seqs.size)
        assertEquals("packet 3 was played exactly once", 1, out.seqs.count { it == 3 })
        assertEquals(1L, g.stale)
        assertEquals(0L, g.duplicates)
    }

    /** A packet that only partly overlaps what has already been handed over is
     *  dropped whole.  Trimming it would mean cutting a coded frame at a byte
     *  offset that depends on the codec, to save at most one frame's sliver. */
    @Test
    fun aPacketStraddlingTheFrontierIsDroppedRatherThanHalfReplayed() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 5)                        // frontier is at 5*160 = 800
        g.accept(pkt(99, 800L - 20), out.consume)    // starts 20 ticks too early

        assertEquals(5, out.seqs.size)
        assertEquals(1L, g.stale)
    }

    /** Reordering is what a burst with a swapped pair looks like.  Both packets are
     *  still ahead of the frontier, so nothing is dropped: they are released where
     *  they belong on the timeline. */
    @Test
    fun aSwappedPairIsReleasedInTimelineOrder() {
        val g = gate()
        val out = Sink()
        for (i in listOf(0, 1, 3, 2, 4, 5, 7, 6)) g.accept(pkt(i, i * frame.toLong()), out.consume)
        g.pump(out.consume)

        assertEquals((0 until 8).toList(), out.seqs)
        assertEquals(2L, g.outOfOrder)
        assertEquals(0L, g.holes)
    }

    /** A hole is worth waiting for: the missing packet of the same burst turns up
     *  two packets later and everything is released contiguously. */
    @Test
    fun aHoleIsHeldAndThenFilledWhenTheMissingPacketTurnsUpInTime() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 20)
        g.accept(pkt(21, 21 * frame.toLong()), out.consume)
        g.accept(pkt(22, 22 * frame.toLong()), out.consume)
        assertEquals("21 and 22 wait behind the hole", 20, out.seqs.size)
        g.accept(pkt(20, 20 * frame.toLong()), out.consume)

        assertEquals((0 until 23).toList(), out.seqs)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.holeTicks)
    }

    /** Patience is bounded: past the wait the frontier jumps the hole, and the
     *  packet that then turns up late is the case that used to be replayed. */
    @Test
    fun patienceExpiresAndTheLatePacketIsThenNotReplayed() {
        val g = gate(waitMs = 80)
        val out = Sink()
        runInOrder(g, out, 20)
        g.accept(pkt(21, 21 * frame.toLong()), out.consume)
        assertEquals("still held while the wait runs", 20, out.seqs.size)

        t += 80                                      // wait expires with nothing to fill it
        g.pump(out.consume)
        assertEquals(listOf(21), out.seqs.drop(20))
        assertEquals(1L, g.holes)
        assertEquals(frame.toLong(), g.holeTicks)

        g.accept(pkt(20, 20 * frame.toLong()), out.consume)
        assertEquals("the hole has been played across: that late packet is a replay",
            21, out.seqs.size)
        assertEquals(1L, g.stale)
    }

    /** The other bound: holding cannot be unbounded in packets either, whatever the
     *  clock says. */
    @Test
    fun theHeldQueueIsBoundedInPacketsEvenWhenTheClockStandsStill() {
        val g = gate(waitMs = 10_000, maxHeld = 2)
        val out = Sink()
        runInOrder(g, out, 10)
        for (i in listOf(12, 13, 14)) g.accept(pkt(i, i * frame.toLong()), out.consume)

        assertEquals((0 until 10).toList() + listOf(12, 13, 14), out.seqs)
        assertEquals(1L, g.holes)
    }

    /** 32-bit timestamps wrap (14.9 h at 8 kHz).  Serial-number arithmetic, not a
     *  comparison: across the wrap the stream must look contiguous, not like a
     *  four-billion-tick jump. */
    @Test
    fun aTimestampWrapIsNotALossOfTheStream() {
        val g = gate()
        val out = Sink()
        val base = 0x1_0000_0000L - frame           // one frame short of the wrap
        for (i in 0 until 6) {
            val raw = (base + i * frame) and 0xFFFF_FFFFL   // as the 32-bit field carries it
            g.accept(pkt(i, raw), out.consume)
        }
        g.pump(out.consume)

        assertEquals((0 until 6).toList(), out.seqs)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.stale)
        assertEquals(0L, g.restarts)
    }

    /** A re-INVITE, a transfer or a PBX restart changes the SSRC and starts its
     *  timestamps over.  The new stream must play from its first packet rather than
     *  being read as one enormous jump or an eternity of stale packets. */
    @Test
    fun aNewSsrcStartsANewTimeline() {
        val g = gate()
        val out = Sink()
        for (i in 0 until 10) g.accept(pkt(i, 4_000_000_000L + i * frame, ssrc = 111L), out.consume)
        assertEquals(10, out.seqs.size)

        for (i in 0 until 5) g.accept(pkt(100 + i, 50L + i * frame, ssrc = 222L), out.consume)
        g.pump(out.consume)

        assertEquals(15, out.seqs.size)
        assertEquals((0 until 10).toList() + (100 until 105).toList(), out.seqs)
        assertEquals(1L, g.restarts)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.stale)
    }

    /** A sender that changes the subject — a timeline minutes ahead of where this
     *  one had got to, which is what a media path that restarted without changing
     *  its SSRC looks like — is neither a hole to wait for nor a stream of packets
     *  too old to play.  The gate re-anchors on it. */
    @Test
    fun aSenderThatJumpsItsClockReAnchorsOnTheNewTimeline() {
        val g = gate()
        val out = Sink()
        for (i in 0 until 10) g.accept(pkt(i, 1_000_000L + i * frame), out.consume)
        assertEquals(10, out.seqs.size)

        // 100 000 ticks = 12.5 s: far more than any missing packet can explain.
        for (i in 0 until 5) g.accept(pkt(50 + i, 1_100_000L + i * frame), out.consume)
        g.pump(out.consume)

        assertEquals(15, out.seqs.size)
        assertEquals((0 until 10).toList() + (50 until 55).toList(), out.seqs)
        assertEquals(1L, g.restarts)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.stale)
    }

    /** Not every PBX uses 20 ms.  The time a packet occupies comes from its own
     *  payload length, so a 30 ms sender is contiguous rather than a hole every
     *  packet and a duplicate the next. */
    @Test
    fun theFrameLengthComesFromThePayloadNotFromA20msAssumption() {
        val g = gate()
        val out = Sink()
        val ptime = 240                                  // 30 ms at 8 kHz
        for (i in 0 until 6) {
            g.accept(pkt(i, i * ptime.toLong(), bytes = ptime), out.consume)
            t += 30
        }
        g.pump(out.consume)

        assertEquals((0 until 6).toList(), out.seqs)
        assertEquals(ptime.toLong(), g.lastPacketTicks)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.duplicates)
        assertEquals(0L, g.stale)
    }

    /** G.722 is a 16 kHz clock carrying two samples per byte, so the same 160-byte
     *  payload is twice the timeline.  Getting that wrong would turn every wideband
     *  call into a hole per packet. */
    @Test
    fun aWidebandPacketCoversTwiceTheTimelinePerByte() {
        val g = RtpSequencer(16000, 80L, 3, clock)
        val out = Sink()
        for (i in 0 until 8) {
            g.accept(pkt(i, i * frame * 2L, pt = RtpPacket.PT_G722), out.consume)
            t += 20
        }
        g.pump(out.consume)

        assertEquals((0 until 8).toList(), out.seqs)
        assertEquals(0L, g.holes)
        assertEquals(0L, g.duplicates)
    }

    /** A held packet has to be released on the playback thread's clock: if the far
     *  end stops sending, the next arrival never comes. */
    @Test
    fun pumpReleasesAHeldPacketWhenNothingElseArrives() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 5)
        g.accept(pkt(6, 6 * frame.toLong()), out.consume)
        assertEquals(5, out.seqs.size)

        t += 200
        g.pump(out.consume)
        assertEquals(listOf(6), out.seqs.drop(5))
        assertEquals(1L, g.holes)
    }

    /** Sub-frame slop: a sender whose ptime wobbles by a few milliseconds is still
     *  in line.  Anything that small is the reservoir's business, not the gate's. */
    @Test
    fun subFrameWobbleIsNotReportedAsAHole() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 5)                            // frontier at 800
        g.accept(pkt(9, 800L + 40), out.consume)         // 40 ticks: 5 ms of "hole"
        g.pump(out.consume)

        assertEquals(listOf(9), out.seqs.drop(5))
        assertEquals(0L, g.holes)
    }

    /** reset() is what a session uses when it is torn down and reused: the next
     *  packet starts the timeline over rather than being compared against the
     *  previous call's timestamps, and the fresh stream is not counted as a change
     *  of stream, because there is no stream left to change from. */
    @Test
    fun resetForgetsTheOldStream() {
        val g = gate()
        val out = Sink()
        runInOrder(g, out, 10)
        g.reset()
        runInOrder(g, out, 10)

        assertEquals(20, out.seqs.size)
        assertEquals(0L, g.stale)
        assertEquals(0L, g.duplicates)
        assertEquals(0L, g.restarts)
    }

    /** Jitter, loss, duplication and reordering at once, as a packet train: the
     *  invariants are that nothing is passed twice, nothing is passed out of
     *  timeline order, and the mess is reported rather than absorbed silently. */
    @Test
    fun aMessyStreamKeepsTheTimelineMonotonicAndNothingDuplicated() {
        val g = gate(waitMs = 40, maxHeld = 4)
        val out = Sink()
        var seq = 0
        // Deterministic pattern: every 17th packet never arrives, every 11th is sent
        // three times, and a copy of the current moment is handed over four packets
        // late.
        val lateQueue = ArrayList<RtpPacket>()
        for (i in 0 until 400) {
            val ts = i * frame.toLong()
            val p = pkt(seq++, ts)
            if (i % 17 != 0) {                                   // 17th: lost entirely
                if (i % 11 == 0) g.accept(p, out.consume)       // and sent three times
                g.accept(p, out.consume)
                if (i % 11 == 0) g.accept(p, out.consume)
            }
            if (i % 7 == 3) {
                lateQueue.add(pkt(seq++, ts))
            } else if (lateQueue.isNotEmpty() && i % 4 == 0) {
                g.accept(lateQueue.removeAt(0), out.consume)    // delivered 4 packets on
            }
            t += 20
            g.pump(out.consume)
        }
        t += 1000
        g.pump(out.consume)

        // No packet may be handed over twice, and the release order must be the
        // timeline order.  Timestamps are what the reservoir turns into samples, so
        // this is the no-replay property stated directly.
        val released = out.seqs
        assertEquals("every packet passed exactly once", released.size, released.distinct().size)
        assertEquals("passed in timeline order", released.sorted(), released)
        assertEquals(out.seqs.size.toLong(), g.passed)

        // And the gate noticed the mess rather than absorbing it silently.
        assertTrue("duplicates seen: ${g.stats()}", g.duplicates > 0)
        assertTrue("reordering seen: ${g.stats()}", g.outOfOrder > 0)
        assertTrue("holes counted: ${g.stats()}", g.holes > 0)
        assertTrue("stale drops counted: ${g.stats()}", g.stale > 0)
        assertTrue("the stats line carries them: ${g.stats()}", g.stats().contains("dup="))
    }
}
