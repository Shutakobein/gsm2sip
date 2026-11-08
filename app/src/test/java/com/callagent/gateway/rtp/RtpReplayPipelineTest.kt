package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue

/**
 * Does the far end of the GSM leg ever hear the same audio twice?
 *
 * [RtpSequencerTest] checks the gate's bookkeeping.  This checks what the field
 * report of 2026-11-06 was about — "robotic audio repetition" heard by the remote
 * party — by putting a known audio stream in at the network end, driving it through
 * the pieces a call uses ([RtpSequencer], the packet queue, [PcmaCodec],
 * [PlaybackSlip]) with the same steps the playback loop runs, and measuring what
 * comes out of the loud end.
 *
 * How.  The stream is deterministic pseudo-random PCM, so no part of it resembles
 * another part by accident, and each 20 ms block is a label for a place in the call.
 * The listener is *owed* those blocks in timeline order.  Every frame the reservoir
 * emits is looked up in that reference at sample resolution, and consecutive
 * located frames then say one of three things:
 *
 *  - the next frame starts exactly where the last one ended — the call advancing;
 *  - it starts *before* that — the listener hearing audio they had already heard,
 *    which is the complaint written as a number;
 *  - it starts after a gap — audio deleted somewhere on the way.
 *
 * Frames that cannot be found at all are not failures: they are the ramped seams
 * [PlaybackSlip.dropTo] leaves, and the frames written as silence while the
 * reservoir fills or after it ran dry.  Invented samples do not move the listener
 * through the call, so each frame's padding is carried along and subtracted from
 * where the next frame is expected — otherwise a pause reads as a replay, which is
 * exactly the wrong conclusion.  What is required with the gate in the path is that
 * nothing overlaps, and that every gap is paid for by something that was reported:
 * a hole the gate says the network lost, or the samples the reservoir's own latency
 * ceiling discarded.
 *
 * The slip is deliberately held at exactly 1.0.  It is separately proven
 * ([PlaybackSlipTest]) to be a sub-frame resampler whose cursor never reads
 * backwards, and at 1.0 the reservoir is a plain FIFO of samples, so the order of
 * samples — which is what the complaint is about — is fully exposed to the
 * measurement.
 *
 * The duplicate and late-copy cases run twice: with the gate, and with it switched
 * off — the receive path as it was.  Switched off, the same measurement has to *find*
 * the repetition, otherwise the harness proves nothing and the passing cases mean
 * nothing either.
 */
class RtpReplayPipelineTest {

    private val rate = 8000
    private val frame = 160                       // samples per 20 ms
    private val frameBytes = frame * 2
    private val capacity = 12                     // RtpSession.JITTER_CAPACITY
    private val target = 3                        // RtpSession.JITTER_TARGET_FRAMES
    private val highWater = 7                     // RtpSession.JITTER_HIGH_WATER
    private val ramp = PlaybackSlip.RAMP_SAMPLES  // how wide a trim seam is

    // ── the audio ──────────────────────────────────────────────────────────

    /** Deterministic pseudo-random PCM blocks: content with no self-similarity, so
     *  finding a frame inside the reference means something. */
    private fun blocks(count: Int): List<ByteArray> {
        val rnd = java.util.Random(20261106L)
        return List(count) {
            val b = ByteArray(frameBytes)
            for (i in 0 until frame) {
                // ±8000: clear of A-law's coarse low segments, far from clipping.
                val s = (rnd.nextInt(16001) - 8000).toShort()
                b[i * 2] = (s.toInt() and 0xFF).toByte()
                b[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
            b
        }
    }

    /** The reference: those blocks in timeline order, through the codec the session
     *  decodes with — byte for byte what the listener is owed, so quantisation is
     *  identical and cannot be blamed for anything. */
    private fun referenceOf(bl: List<ByteArray>): ByteArray {
        val out = ByteArray(bl.size * frameBytes)
        for ((i, b) in bl.withIndex()) {
            val pcm = PcmaCodec.decode8k(PcmaCodec.encode8k(b))
            System.arraycopy(pcm, 0, out, i * frameBytes, minOf(frameBytes, pcm.size))
        }
        return out
    }

    /** Self-check on the measurement itself: this content must not match itself at a
     *  neighbouring offset, or "found it" would prove nothing. */
    @Test
    fun theReferenceContentIsOnlyFoundWhereItBelongs() {
        val ref = referenceOf(blocks(8))
        val w = ref.copyOfRange(3 * frameBytes, 4 * frameBytes)
        assertTrue("a block matches itself", matches(ref, 3 * frameBytes, w))
        var shifted = 0
        for (off in 0 until ref.size - frameBytes) {
            if (off % frameBytes != 0 && matches(ref, off, w)) shifted++
        }
        assertEquals("content matches at an offset it does not belong", 0, shifted)
    }

    // ── the arrival script ─────────────────────────────────────────────────

    /** Which blocks' packets turn up at which virtual 20 ms tick.  A tick with
     *  nothing in it is the sender stalling; the same block listed twice is a path
     *  that repeats a datagram. */
    private class Fed {
        val at = ArrayList<MutableList<Int>>()

        fun at(t: Int): MutableList<Int> {
            while (at.size <= t) at += ArrayList<Int>()
            return at[t]
        }

        /** One packet per tick, in timeline order, for `n` ticks from `from`. */
        fun inOrder(from: Int, n: Int) = (0 until n).forEach { at(from + it) += from + it }
    }

    // ── the pipeline, mirroring RtpSession's playback loop ─────────────────

    private class Outcome {
        /** Every 20 ms the loud end was given something, in order — including the
         *  silence the prefill and an underrun write in its place, because that is
         *  what the AudioTrack actually plays. */
        val frames = ArrayList<ByteArray>()
        /** Parallel to [frames]: how many of that frame's samples the pipeline
         *  invented itself (silence for the prefill, or the tail of a frame after
         *  the reservoir ran dry) rather than received.  Invented samples do not
         *  advance where the call is in the reference, which is what lets the
         *  measurement tell "the call went backwards" from "the call paused". */
        val pads = ArrayList<Int>()
        var underruns = 0
        var trims = 0
        var droppedByTrim = 0
        var gateStats: String? = null
        var dup = 0L
        var stale = 0L
        var holes = 0L
        var holeTicks = 0L
        var outOfOrder = 0L
    }

    private fun drive(bl: List<ByteArray>, fed: Fed, gateOn: Boolean): Outcome {
        val res = Outcome()
        val q = ArrayBlockingQueue<ByteArray>(capacity)
        val slip = PlaybackSlip(frame)
        var virtualMs = 0L
        val gate = if (gateOn) RtpSequencer(rate, 80L, 3) { virtualMs } else null
        var seq = 0
        var primed = false

        fun offer(payload: ByteArray) {
            if (!q.offer(payload)) {          // the same overflow rule as offerPlayback
                q.poll()
                q.offer(payload)
            }
        }

        for (t in 0 until fed.at.size) {
            virtualMs = t * 20L

            // The socket thread: whatever turned up at this moment.
            for (blk in fed.at[t]) {
                val pkt = RtpPacket(
                    RtpPacket.PT_PCMA, seq++, (blk * frame).toLong(), 0xACCE55L,
                    PcmaCodec.encode8k(bl[blk]),
                )
                if (gate != null) gate.accept(pkt) { offer(it.payload) } else offer(pkt.payload)
            }

            // The playback thread's tick.  Its clock is the same virtual time, so the
            // gate's patience is measured on the loop that consumes it.
            if (gate != null) gate.pump { offer(it.payload) }

            // 1. queue → reservoir, in the order it now arrives in.
            while (true) {
                val encoded = q.poll() ?: break
                slip.appendLe(PcmaCodec.decode8k(encoded))
            }

            var depthFrames = (slip.depth + q.size * frame) / frame

            // 2. latency ceiling.
            if (depthFrames > highWater) {
                val dropped = slip.dropTo(target * frame)
                if (dropped > 0) {
                    res.trims++
                    res.droppedByTrim += dropped
                    depthFrames = slip.depth / frame
                }
            }

            // 3. prefill: nothing is played until the reservoir has room to breathe,
            //    which is what both the slip and the ceiling work against.
            if (!primed) {
                if (depthFrames < target) {
                    res.underruns++
                    res.frames += ByteArray(frameBytes)     // a silence frame, like the
                    res.pads += frame                       // AudioTrack gets in the field
                    continue
                }
                primed = true
            }

            // 4. one frame out.  Step held at 1.0 — see the class comment.
            val buf = ByteArray(frameBytes)
            val padded = slip.drainLe(1.0, buf)
            res.frames += buf
            res.pads += padded
            if (padded > 0) {
                res.underruns++
                primed = false
            }
        }
        res.gateStats = gate?.stats()
        res.dup = gate?.duplicates ?: 0
        res.stale = gate?.stale ?: 0
        res.holes = gate?.holes ?: 0
        res.holeTicks = gate?.holeTicks ?: 0
        res.outOfOrder = gate?.outOfOrder ?: 0
        return res
    }

    // ── the measurement ────────────────────────────────────────────────────

    private class Verdict {
        /** Reference sample offset of each located frame, in the order heard. */
        val at = ArrayList<Int>()
        var notFound = 0
        var overlaps = 0          // frames that start before the last heard one ended
        var overlapSamples = 0    // how much of the call was said twice
        var gapSamples = 0        // how much was said once but never heard at all
        var largestGap = 0
    }

    /**
     * Finds each output frame in the reference, at sample resolution: a trim leaves
     * the surviving audio out of step with block boundaries, so only a search that
     * ignores boundaries can follow the call across one.  The search stays local —
     * a bounded queue cannot put a frame far from where the last one was found, and
     * a match far away would itself be the defect.
     */
    private fun measure(ref: ByteArray, res: Outcome): Verdict {
        val v = Verdict()
        /** padBefore[n] = samples the pipeline made up in the frames before n, so the
         *  reference position only advances by what was really said. */
        val padBefore = IntArray(res.frames.size + 1)
        for (i in res.frames.indices) padBefore[i + 1] = padBefore[i] + res.pads[i]

        var near = 0
        var prevAt = -1
        var prevFrame = -1
        for ((n, f) in res.frames.withIndex()) {
            val found = findNear(ref, f, near)
            if (found < 0) {
                v.notFound++
                continue
            }
            // Byte offset → sample offset, which is the unit the gate counts holes
            // in and the reservoir counts its discarded samples in.
            val at = found / 2
            if (prevAt >= 0) {
                val spoken = (n - prevFrame) * frame -
                    (padBefore[n] - padBefore[prevFrame])
                val expected = prevAt + spoken
                when {
                    at < expected -> {
                        v.overlaps++
                        v.overlapSamples += expected - at
                    }
                    at > expected -> {
                        v.gapSamples += at - expected
                        v.largestGap = maxOf(v.largestGap, at - expected)
                    }
                }
            }
            v.at += at
            prevAt = at
            prevFrame = n
            near = found
        }
        return v
    }

    private fun findNear(ref: ByteArray, f: ByteArray, near: Int): Int {
        val from = maxOf(0, near - (highWater + 2) * frameBytes)
        val to = minOf(ref.size - frameBytes, near + (highWater + 2) * frameBytes)
        var p = from
        while (p <= to) {
            if (matches(ref, p, f)) return p
            p += 2                       // sample boundaries
        }
        return -1
    }

    private fun matches(ref: ByteArray, at: Int, f: ByteArray): Boolean {
        if (at < 0 || at + frameBytes > ref.size) return false
        for (i in 0 until frameBytes) if (ref[at + i] != f[i]) return false
        return true
    }

    private fun describe(name: String, res: Outcome, v: Verdict) =
        "$name: located=${v.at.size} notFound=${v.notFound} overlap=${v.overlaps}" +
            "(${v.overlapSamples} samples) gap=${v.gapSamples}(${v.largestGap}) " +
            "underruns=${res.underruns} trims=${res.trims}(${res.droppedByTrim}) " +
            "gate=[${res.gateStats}]"

    /** Nothing already heard comes round again, and everything that is missing was
     *  reported as missing somewhere. */
    private fun assertHeardForwardOnly(name: String, res: Outcome, v: Verdict, owed: Int) {
        val coverage = v.at.size.toDouble() / res.frames.size
        assertTrue("${describe(name, res, v)} — too few frames found to say anything",
            coverage > 0.60)
        assertEquals("${describe(name, res, v)} — the listener heard the same audio twice",
            0, v.overlaps)
        assertTrue("${describe(name, res, v)} — stopped early", v.at.size >= owed - highWater)
        // Audio may only go missing when somebody can account for it: the packets the
        // gate says the network lost, and the samples the reservoir's own ceiling
        // discarded.  The slack is the ramped seam each trim leaves around itself.
        val accounted = res.holeTicks + res.droppedByTrim + res.trims * 2 * ramp + 2 * frame
        assertTrue("${describe(name, res, v)} — ${res.trims} trims and ${res.holes} " +
            "holes cannot account for ${v.gapSamples} samples of missing audio",
            v.gapSamples <= accounted)
    }

    // ── the cases ──────────────────────────────────────────────────────────

    /** The control on the control: a stream that arrives perfectly must come out
     *  perfectly, or it is the measurement that is broken and not the network. */
    @Test
    fun aCleanStreamComesOutAsTheSameSamplesInOrder() {
        val bl = blocks(150)
        val fed = Fed()
        fed.inOrder(0, bl.size)
        repeat(20) { fed.at(bl.size + it) }

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)

        assertHeardForwardOnly("clean", res, v, bl.size)
        assertEquals("every frame in its place", (0 until v.at.size).map { it * frame }, v.at)
        assertEquals("nothing thrown away", 0, res.trims)
        assertEquals("no gate events on a clean path: ${res.gateStats}", 0L,
            res.dup + res.stale + res.holes + res.outOfOrder)
    }

    /** The complaint and the fix in one pair of cases: a path that hands the same
     *  datagram twice.  With the gate the repetition is gone; switched off, this same
     *  measurement finds it. */
    @Test
    fun aRepeatedPacketDoesNotReachTheLoudEndTwice() {
        val bl = blocks(150)
        val fed = Fed()
        repeat(bl.size) { i ->
            fed.at(i) += i
            if (i % 20 == 7) fed.at(i) += i            // the same payload, twice
        }
        repeat(20) { fed.at(bl.size + it) }

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)
        assertHeardForwardOnly("duplicated", res, v, bl.size)
        assertTrue("the gate should have seen the repeats: ${res.gateStats}", res.dup > 0)
        // Dropping the duplicate also keeps the reservoir on target, so the latency
        // ceiling never has to delete anything either.
        assertEquals("no reason to trim: ${describe("duplicated", res, v)}", 0, res.trims)

        val off = drive(bl, fed, gateOn = false)
        val v2 = measure(referenceOf(bl), off)
        assertTrue("${describe("gate off", off, v2)} — the same script has to show the " +
            "repetition the gate removes", v2.overlaps > 0)
    }

    /** The other shape of the same defect: a copy that turns up after its own moment,
     *  which is what a retransmission or a power-save burst looks like. */
    @Test
    fun aLateCopyOfAlreadyHeardAudioDoesNotComeBack() {
        val bl = blocks(150)
        val fed = Fed()
        val parked = ArrayList<Int>()
        repeat(bl.size) { i ->
            fed.at(i) += i                                     // everything on time once…
            if (i % 15 == 9) parked += i                        // …and a copy held back
            else if (parked.isNotEmpty()) fed.at(i) += parked.removeAt(0)  // …delivered on
        }
        repeat(20) { fed.at(bl.size + it) }

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)
        assertHeardForwardOnly("late copies", res, v, bl.size)
        assertTrue("the gate should have dropped the late copies: ${res.gateStats}",
            res.dup + res.stale > 0)

        val off = drive(bl, fed, gateOn = false)
        val v2 = measure(referenceOf(bl), off)
        assertTrue("${describe("gate off", off, v2)} — late copies come back as audio " +
            "already heard", v2.overlaps > 0)
    }

    /** A burst that delivers a pair the wrong way round has to come out the right way
     *  round: one packet per tick, adjacent pairs swapped. */
    @Test
    fun aSwappedPairComesOutInTimelineOrder() {
        val bl = blocks(150)
        val fed = Fed()
        var i = 0
        while (i < bl.size) {                          // one packet per tick, pairs swapped
            fed.at(i) += (i + 1).coerceAtMost(bl.size - 1)
            fed.at(i + 1) += i
            i += 2
        }
        fed.at(bl.size)                                // let the tail drain out
        repeat(20) { fed.at(bl.size + it) }

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)
        assertHeardForwardOnly("swapped", res, v, bl.size)
        assertEquals("in place, in order", (0 until v.at.size).map { it * frame }, v.at)
        assertTrue("the gate saw the swaps: ${res.gateStats}", res.outOfOrder > 0)
    }

    /** Loss, duplicates, late copies and a stalled sender together — the pattern this
     *  handset's Wi-Fi is documented to produce — with the gate's patience and the
     *  reservoir's ceiling both in play. */
    @Test
    fun aBurstyLossyPathStillNeverRepeatsWhatWasAlreadyHeard() {
        val bl = blocks(300)
        val fed = Fed()
        val parked = ArrayList<Int>()
        var tick = 0
        repeat(bl.size) { i ->
            if (i % 17 != 0) {                                  // every 17th packet lost
                if (i % 23 == 5) fed.at(tick) += i              // every 23rd arrives thrice
                fed.at(tick) += i
                if (i % 23 == 5) fed.at(tick) += i
            }
            if (i % 11 == 4) parked += i                        // plus a copy delivered late
            else if (parked.isNotEmpty() && i % 5 == 0) fed.at(tick) += parked.removeAt(0)
            if (i % 60 == 57) tick += 5                         // and a brief stall
            tick++
        }
        repeat(40) { fed.at(tick + it) }

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)
        assertHeardForwardOnly("bursty", res, v, bl.size - bl.size / 17)
        assertTrue("the gate saw the mess: ${res.gateStats}", res.dup + res.stale > 0)
        assertTrue("lost packets expected: ${res.gateStats}", res.holes > 0)
    }

    /** The gate has to keep working when the sender stops: its patience expires on
     *  the playback clock, the reservoir drains, and when the far end starts up again
     *  still holding blocks it had already sent, the call carries on forward instead
     *  of saying those blocks a second time. */
    @Test
    fun aStalledSenderThenARestartDoesNotReplayWhatWasBuffered() {
        val bl = blocks(150)
        val fed = Fed()
        fed.inOrder(0, 40)                                // first 40 blocks on time
        repeat(100) { fed.at(40 + it) }                    // then two seconds of nothing
        fed.inOrder(40, bl.size - 40)                      // the rest, one per tick…
        fed.at(141) += 34                                  // …with three old blocks on top
        fed.at(142) += 35
        fed.at(143) += 36

        val res = drive(bl, fed, gateOn = true)
        val v = measure(referenceOf(bl), res)
        val coverage = v.at.size.toDouble() / res.frames.size
        assertTrue("${describe("stall", res, v)} — too few frames found to say anything",
            coverage > 0.30)
        assertEquals("${describe("stall", res, v)} — the listener heard the same audio twice",
            0, v.overlaps)
        assertTrue("the restart carried old audio with it: ${res.gateStats}",
            res.dup + res.stale > 0)
    }
}
