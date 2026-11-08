package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reservoir between RTP arrival and the playback device.
 *
 * What these tests hold it to is the reason it replaced one-packet-per-tick:
 * a frame that is on target leaves the samples byte for byte alone, a gap is
 * counted in samples instead of costing a whole 20 ms of silence, the cursor
 * never wanders away from the data after a gap, and the only way samples are
 * ever deleted is the seam-ramped ceiling.
 */
class PlaybackSlipTest {

    /** 20 ms at 8 kHz — the rate this route runs at. */
    private val frame = 160

    private fun le(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, i: Int): Int {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    /** Deterministic, speech-like enough: no repeating period the filter could
     *  be tuned to by accident. */
    private fun pattern(n: Int): IntArray =
        IntArray(n) { ((it * 977) % 20000) - 10000 }

    private fun appendFrames(slip: PlaybackSlip, frames: Int, src: IntArray) {
        var at = 0
        repeat(frames) {
            val slice = IntArray(frame) { src[at + it] }
            at += frame
            slip.appendLe(le(slice))
        }
    }

    @Test
    fun depthCountsSamplesNotPackets() {
        val slip = PlaybackSlip(frame)
        assertEquals(0, slip.depth)
        val src = pattern(frame * 4)
        appendFrames(slip, 4, src)
        assertEquals("four 20ms frames", frame * 4, slip.depth)
        val out = ByteArray(frame * 2)
        slip.drainLe(1.0, out)
        assertEquals(frame * 3, slip.depth)
    }

    /** The no-op case: on target, nothing may touch the audio at all. */
    @Test
    fun stepOfExactlyOnePassesEverySampleThroughUnaltered() {
        val slip = PlaybackSlip(frame)
        val src = pattern(frame * 5)
        appendFrames(slip, 5, src)

        val out = ByteArray(frame * 2)
        for (f in 0 until 4) {
            assertEquals(0, slip.drainLe(1.0, out))
            for (i in 0 until frame) {
                val want = src[f * frame + i]
                assertEquals("frame $f sample $i", want, sampleAt(out, i))
            }
        }
        assertEquals(frame, slip.depth)
    }

    /** Not "about 60 ms of silence": the exact number of samples that had to be
     *  invented, so the fade the caller applies lands on the right sample. */
    @Test
    fun gapIsCountedInSamplesAndIsExactlyTheShortfall() {
        val slip = PlaybackSlip(frame)
        val src = pattern(100)
        slip.appendLe(le(src))

        val out = ByteArray(frame * 2)
        assertEquals(60, slip.drainLe(1.0, out))   // 160 wanted, 100 there
        for (i in 0 until 100) assertEquals(src[i], sampleAt(out, i))
        for (i in 100 until frame) assertEquals("padded $i", 0, sampleAt(out, i))
        assertEquals(0, slip.depth)
    }

    /** The failure mode this has to avoid: after a dry frame the cursor must not
     *  sit past the end of the data, because depth is (size - pos) and such a
     *  cursor reads silence for ever however much arrives afterwards. */
    @Test
    fun cursorStaysAttachedToTheDataAfterRunningDry() {
        val slip = PlaybackSlip(frame)
        val out = ByteArray(frame * 2)

        val first = pattern(60)
        slip.appendLe(le(first))
        assertEquals("60 samples there, 160 wanted", frame - 60, slip.drainLe(1.0, out))
        repeat(3) { assertEquals("still nothing to play", frame, slip.drainLe(1.0, out)) }
        assertEquals(0, slip.depth)

        val next = pattern(frame)
        slip.appendLe(le(next))
        assertEquals(frame, slip.depth)
        assertEquals(0, slip.drainLe(1.0, out))
        for (i in 0 until frame) assertEquals("sample $i", next[i], sampleAt(out, i))
    }

    /** A whole frame of slip per frame would be heard as pitch; the controller
     *  is proportional on purpose and capped. */
    @Test
    fun stepIsExactlyOneOnTargetAndBoundedAwayFromIt() {
        val slip = PlaybackSlip(frame)
        assertEquals(1.0, slip.stepFor(3, 3), 1e-12)
        assertEquals(1.002, slip.stepFor(4, 3), 1e-9)
        assertEquals(0.998, slip.stepFor(2, 3), 1e-9)
        assertEquals(1.0 + PlaybackSlip.MAX_SLIP, slip.stepFor(8, 3), 1e-9)
        assertEquals(1.0 - PlaybackSlip.MAX_SLIP, slip.stepFor(0, 3), 1e-9)
        // Saturating, not runaway: whatever the backlog, one frame never moves by
        // more than MAX_SLIP of itself.
        for (depth in 0..500) {
            val step = slip.stepFor(depth, 3)
            // +1e-12 because 1.0 - 0.006 is not exactly representable, and the
            // bound is meant to be about magnitude, not about the last bit.
            assertTrue("step $step at depth $depth", kotlin.math.abs(step - 1.0) <= PlaybackSlip.MAX_SLIP + 1e-12)
        }
    }

    /** Drift is absorbed by interpolation, and interpolation deletes nothing:
     *  the reservoir shrinks by exactly the slip, no frame at a time. */
    @Test
    fun boundedSlipShrinksTheQueueWithoutDeletingAnything() {
        val slip = PlaybackSlip(frame)
        val frames = 60
        appendFrames(slip, frames, pattern(frame * frames))
        val before = slip.depth
        val out = ByteArray(frame * 2)
        repeat(50) { slip.drainLe(1.0 + PlaybackSlip.MAX_SLIP, out) }

        // 50 frames of output read 50*160*(1+MAX_SLIP) reservoir samples.
        val expected = before - (50 * frame * (1.0 + PlaybackSlip.MAX_SLIP)).toInt()
        assertTrue(
            "depth ${slip.depth}, expected about $expected",
            kotlin.math.abs(slip.depth - expected) <= 5
        )
        assertTrue("nothing may run dry here", slip.depth > 0)
    }

    /** The one place content really is thrown away: far above target, and even
     *  then the seam ramps on both sides rather than stepping. */
    @Test
    fun ceilingDropRemovesTheExcessAndRampsTheSeam() {
        val slip = PlaybackSlip(frame)
        val loud = IntArray(frame * 12) { 20000 }
        appendFrames(slip, 12, loud)

        val dropped = slip.dropTo(3 * frame)
        assertEquals(frame * 12 - 3 * frame, dropped)
        assertEquals(3 * frame, slip.depth)

        val out = ByteArray(frame * 2)
        assertEquals(0, slip.drainLe(1.0, out))
        val ramp = PlaybackSlip.RAMP_SAMPLES
        // Into the seam: descending, so the drop does not arrive as a cliff.
        for (i in 1 until ramp) {
            assertTrue(
                "seam not descending at $i: ${sampleAt(out, i - 1)} -> ${sampleAt(out, i)}",
                sampleAt(out, i) <= sampleAt(out, i - 1)
            )
        }
        // And out of it: the step across the seam itself is only 1/ramp of full
        // scale, which is the whole point of placing the cut after the ramp.
        assertEquals(20000 / ramp, sampleAt(out, ramp - 1))
        assertEquals(20000 / ramp, sampleAt(out, ramp))
        for (i in ramp + 1 until 2 * ramp) {
            assertTrue(
                "seam not climbing at $i: ${sampleAt(out, i - 1)} -> ${sampleAt(out, i)}",
                sampleAt(out, i) >= sampleAt(out, i - 1)
            )
        }
        assertEquals(20000, sampleAt(out, 2 * ramp - 1))
        assertEquals(20000, sampleAt(out, 2 * ramp))
    }

    @Test
    fun ceilingDropDoesNothingWhenAlreadyAtTarget() {
        val slip = PlaybackSlip(frame)
        appendFrames(slip, 4, pattern(frame * 4))
        assertEquals(0, slip.dropTo(4 * frame))
        assertEquals(0, slip.dropTo(5 * frame))
        assertEquals(4 * frame, slip.depth)
    }

    /** A drop is bounded by what is unplayed: it can never reach behind the
     *  cursor into audio that has already been written out, and the samples that
     *  lead into the seam are kept and played, not deleted with the rest. */
    @Test
    fun ceilingDropOnlyTouchesUnplayedSamples() {
        val slip = PlaybackSlip(frame)
        val src = pattern(frame * 8)
        appendFrames(slip, 8, src)
        val out = ByteArray(frame * 2)

        slip.drainLe(1.0, out)                  // first frame leaves as itself
        for (i in 0 until frame) assertEquals(src[i], sampleAt(out, i))

        val dropped = slip.dropTo(frame)
        assertEquals("six of the seven frames still buffered", 7 * frame - frame, dropped)
        assertEquals(frame, slip.depth)

        assertEquals(0, slip.drainLe(1.0, out))
        // What is left is the last frame of the input, six frames' worth of
        // samples having been cut out of the middle of it.  The samples just
        // after the cursor were kept (they lead into the seam), so the drop
        // starts at sample 160+RAMP of the input, not at the cursor.
        val ramp = PlaybackSlip.RAMP_SAMPLES
        assertEquals(src[frame], sampleAt(out, 0))
        assertEquals(src[frame + ramp - 1] / ramp, sampleAt(out, ramp - 1))
        // Coming out of the seam: the climb ends back at the real samples, six
        // frames further into the input than the cursor was.
        assertEquals(src[7 * frame + 2 * ramp - 1], sampleAt(out, 2 * ramp - 1))
        assertEquals(src[7 * frame + 2 * ramp], sampleAt(out, 2 * ramp))
    }

    /**
     * A cubic of the shape the class comment promises reproduces a straight line.
     *
     * Worth pinning down, because the kernel here was wrong once: the Horner
     * nesting multiplied the cubic group by f instead of f², leaving
     * `y = b + f·½(c−a) + f²·(a − 1½b + ½c)` — a quadratic whose answer at f = ½ is
     * 0.625·b + 0.375·c, asymmetric, and 8 % off on a straight line where even
     * plain linear interpolation is exact.  It only bites while the read rate is
     * fractional, i.e. while the queue is off target, which is exactly when it is
     * easy to blame on the network.  A ramp run through the reservoir exposes it on
     * every sample.
     */
    @Test
    fun fractionalReadReproducesAStraightLineExactly() {
        val slip = PlaybackSlip(frame)
        val slope = 30                      // value = slope × source index, fits in 16 bits
        var written = 0
        fun append(n: Int) {
            repeat(n) {
                val b = ByteArray(frame * 2)
                for (i in 0 until frame) {
                    val v = (written + i) * slope
                    b[i * 2] = (v and 0xFF).toByte()
                    b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                slip.appendLe(b)
                written += frame
            }
        }
        append(5)

        val out = ByteArray(frame * 2)
        var worst = 0.0
        var k = 0
        repeat(4) {
            assertEquals("queue held by the sender cannot run dry", 0, slip.drainLe(1.05, out))
            for (i in 0 until frame) {
                // Read k lands at source position k × 1.05, and on a straight line
                // the right answer is the line itself.
                val err = Math.abs(sampleAt(out, i) - slope * k * 1.05)
                if (err > worst) worst = err
                k++
            }
        }
        // One sample of tolerance covers the filter's own rounding and nothing else.
        // The kernel this replaces missed by 3.75 here, at f = 0.5.
        assertTrue("straight line read wrong by up to $worst (slope $slope per sample)",
            worst <= 1.0)
    }

    /** Constant audio must come out constant, whatever the read rate does to the
     *  phase — a kernel that does not hold DC is changing the level of the call. */
    @Test
    fun constantAudioSurvivesAFractionalRead() {
        val slip = PlaybackSlip(frame)
        val level = 12345
        val b = ByteArray(frame * 2)
        for (i in 0 until frame) {
            b[i * 2] = (level and 0xFF).toByte()
            b[i * 2 + 1] = ((level shr 8) and 0xFF).toByte()
        }
        repeat(4) { slip.appendLe(b) }

        val out = ByteArray(frame * 2)
        repeat(3) {
            assertEquals(0, slip.drainLe(1.05, out))
            for (i in 0 until frame)
                assertEquals("sample $i of a constant read at 1.05", level, sampleAt(out, i))
        }
    }

    @Test
    fun clearForgetsEverything() {
        val slip = PlaybackSlip(frame)
        appendFrames(slip, 3, pattern(frame * 3))
        slip.clear()
        assertEquals(0, slip.depth)
        val out = ByteArray(frame * 2)
        assertEquals(frame, slip.drainLe(1.0, out))
    }

    /**
     * A fractional read rate must never look backwards.
     *
     * This is the property the 2026-11-06 field complaint — "robotic audio
     * repetition" — would be caused by if the reservoir were a interpolating
     * reader with a wandering cursor: replay a stretch of samples and the listener
     * hears that piece of the call twice.  Deleting samples ahead of the cursor is
     * legal and is what [dropTo] does; re-reading samples behind it is not, and
     * nothing in the reservoir is allowed to do it, least of all the phase
     * arithmetic that keeps the cubic filter's history across a frame boundary.
     *
     * A ramp makes the cursor visible.  A Catmull-Rom filter passes a straight line
     * through unaltered, so with the input sample at reservoir index i carrying the
     * value `i × 8`, the value that comes out of [drainLe] *is* the position that
     * was read, to within a fraction of a sample.  Backwards motion is then a
     * number, measured on every single sample rather than asserted about the
     * bookkeeping.
     */
    @Test
    fun fractionalReadNeverGoesBackwardsThroughTheSource() {
        val scale = 8
        val frames = 45
        val total = frames * frame
        // Centred on zero so the ramp stays inside 16 bits at this scale; centring
        // changes nothing about its slope, which is what the measurement reads.
        fun ramp(from: Int, n: Int): ByteArray {
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = ((from + i - total / 2) * scale).toShort()
                out[i * 2] = (v.toInt() and 0xFF).toByte()
                out[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
            return out
        }

        val slip = PlaybackSlip(frame)
        val out = ByteArray(frame * 2)
        var written = 0
        repeat(4) {                                   // a queue that starts off target
            slip.appendLe(ramp(written, frame)); written += frame
        }

        // The controller's own saturation band, alternating both ways: the state in
        // which stepFor answers something other than 1.0, averaged to 1.0 so the
        // queue — and the test — stays stable.
        val band = doubleArrayOf(
            1.0 + PlaybackSlip.MAX_SLIP, 1.0 - PlaybackSlip.MAX_SLIP,
            1.0 + PlaybackSlip.MAX_SLIP / 2, 1.0 - PlaybackSlip.MAX_SLIP / 2,
        )

        var first = Double.NaN
        var last = Double.NaN
        var worst = 0.0
        var read = 0
        var framesOut = 0
        while (written < total) {
            slip.appendLe(ramp(written, frame)); written += frame
            val padded = slip.drainLe(band[framesOut % band.size], out)
            assertEquals("a queue the network keeps feeding cannot run dry", 0, padded)
            for (i in 0 until frame) {
                // The first taps read history that does not exist yet; skip them.
                if (framesOut == 0 && i < 2) continue
                val where = sampleAt(out, i).toDouble() / scale
                if (first.isNaN()) first = where
                if (!last.isNaN()) worst = minOf(worst, where - last)
                last = where
                read++
            }
            framesOut++
        }

        // interp() truncates, so a position is only known to 1/scale of a sample —
        // 0.125 here — and comparing two of them doubles that.  Anything worse than
        // half a sample behind is the cursor really reading samples it has already
        // played, which is what "the same audio twice" would be.
        assertTrue("cursor moved backwards by $worst samples over $read reads",
            worst > -0.5)

        // Not a cursor that merely never moved: it advanced one source sample per
        // output sample, because the band averages to exactly 1.0.  Otherwise the
        // assertion above would also pass for a reader that sat still.
        val span = last - first
        assertTrue("cursor advanced $span samples over $read reads",
            span in (read - 4).toDouble()..(read + 1).toDouble())
    }
}
