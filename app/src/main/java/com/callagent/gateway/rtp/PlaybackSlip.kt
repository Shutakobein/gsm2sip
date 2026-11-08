package com.callagent.gateway.rtp

/**
 * Sample-accurate slip between the clock RTP arrives on and the clock the
 * playback device drains it at.
 *
 * Why this exists.  The playback loop used to take one packet per tick: poll
 * the jitter buffer, decode it, write it.  That makes the *packet* the unit of
 * time, and a packet is 20 ms.  Three things then happen, all audible:
 *
 *  - a packet that turns up late writes 20 ms of digital silence in its place
 *    ([RtpSession.underruns] counted them; one 33 s call logged 90 of them out
 *    of ~1650 frames, i.e. a hole roughly every third of a second);
 *  - a burst that leaves the queue deeper than the high-water mark throws away a
 *    whole 20 ms packet ([RtpSession.trimmedFrames]) — 20 ms of someone's speech
 *    deleted from the middle of a word, with a hard discontinuity on each side
 *    of the hole;
 *  - the buffer capacity and the drain rate fight each other, which is what the
 *    same call shows: 87 drops *and* 90 underruns, pinned at capacity and empty,
 *    in the same call.
 *
 * Each of those is 20 ms of hole or 20 ms of click, which is what a listener on
 * the far end of the GSM leg calls static (reported from the field 2026-11-06,
 * on the injected leg).  None of them has to be 20 ms.  The drift between the
 * sender's clock and this device's clock is parts per million, and a burst is a
 * few frames: a fraction of a sample per frame of correction absorbs both
 * without ever deleting or inventing a whole frame.
 *
 * So the buffer here holds *samples*, not packets, and each tick reads exactly
 * the number of samples the playback device has made room for — at a fractional
 * read rate ([drainLe]'s `step`) that is nudged away from 1.0 while the depth is
 * off target.  Content is only deleted, and only ever as a ramped seam, at the
 * hard ceiling, far above target, as a last resort against latency running away.
 *
 * The reader is a 4-tap Catmull-Rom interpolator, the same shape
 * [RtpSession.upsample8kTo16k] uses, so a fractional position does not add the
 * imaging that linear interpolation would.  At `step` = 1.0 — the queue exactly
 * on target, which is the normal case — the filter passes samples through
 * unaltered: the whole mechanism is a no-op until something is off.
 *
 * Nothing here is specific to 8 kHz: the frame size is a constructor parameter,
 * and a G.722 session at 16 kHz works the same way.
 */
internal class PlaybackSlip(private val frameSamples: Int) {

    /** Samples kept before the read cursor so the cubic filter has its history.
     *  Never reported as depth. */
    private val preRoll = 2

    private var buf = ShortArray(frameSamples * 16 + 16)
    private var size = 0

    /** Read cursor, in samples from the start of [buf].  Fractional: that is the
     *  whole point.  Starts at 0 so the first sample of the first packet is
     *  played, not consumed as filter history; history only exists once there is
     *  something before the cursor to be history. */
    private var pos = 0.0

    /** Samples ready to play, excluding the pre-roll history. */
    val depth: Int
        get() {
            val d = size - pos
            return if (d < 0.0) 0 else d.toInt()
        }

    fun clear() {
        size = 0
        pos = 0.0
    }

    /** One decoded packet's worth of little-endian PCM16 arrives here. */
    fun appendLe(pcm: ByteArray) {
        val n = pcm.size / 2
        if (n == 0) return
        val need = size + n + 4
        if (need > buf.size) {
            val grown = ShortArray(need.coerceAtLeast(buf.size * 2))
            System.arraycopy(buf, 0, grown, 0, size)
            buf = grown
        }
        var i = 0
        var at = size
        while (i < n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            buf[at++] = ((hi shl 8) or lo).toShort()
            i++
        }
        size += n
    }

    /**
     * Read the next [out] bytes (that is `out.size/2` samples) at a fractional
     * rate of [step] reservoir samples per output sample, as little-endian PCM16.
     *
     * @return how many of the returned samples had to be invented because the
     *   reservoir had run dry — the audible-gap measure, now in samples rather
     *   than whole frames.  The caller fades into them ([RtpSession.applyFadeOutLe])
     *   so the seam is not a click; the samples themselves come out as zeroes.
     */
    fun drainLe(step: Double, out: ByteArray): Int {
        val n = out.size / 2
        if (n == 0) return 0
        val rate = step.coerceIn(0.9..1.1)

        // Real data covers the first `useful` output samples; the rest are gaps.
        val available = size - pos
        val useful = if (available <= 0.0) 0
        else kotlin.math.ceil(available / rate).toInt().coerceAtMost(n)
        val padded = n - useful

        var i = 0
        var p = pos
        while (i < n) {
            val i1 = p.toInt()
            val f = (p - i1).toFloat()
            // Catmull-Rom on four taps.  f == 0 answers with exactly the sample
            // at i1, which is the pass-through case: nothing is filtered and
            // nothing is rounded while the queue is on target.
            val s: Int = if (f == 0f) at(i1).toInt()
            else interp(at(i1 - 1), at(i1), at(i1 + 1), at(i1 + 2), f).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            p += rate
            i++
        }

        // Commit: everything the reader passed is done with, except the pre-roll
        // the cubic filter still needs for its first taps of the next frame.
        //
        // With data left over that leaves the cursor at preRoll + frac(p), which
        // is the phase the next frame continues from.  When the frame ran dry the
        // whole buffer has been read, so there is neither data nor history left
        // and the cursor goes to the start: a cursor parked past the end of the
        // reservoir would go on reading silence for ever, however much arrived
        // afterwards, because depth is measured as (size - pos).
        val keep = (p.toInt() - preRoll).coerceIn(0, size)
        if (keep > 0) {
            System.arraycopy(buf, keep, buf, 0, size - keep)
            size -= keep
        }
        pos = p - keep
        if (pos > size) pos = 0.0
        return padded
    }

    /**
     * Discard the oldest unplayed samples until the depth is back to
     * [targetSamples], and return how many were dropped.
     *
     * This is a real deletion, so it is not silent.  The cut is placed
     * [RAMP_SAMPLES] *after* the cursor rather than at it, so the samples that
     * lead into the seam are still played and can be ramped down, and the ones
     * that come out of it ramp up: the biggest step across the seam is then
     * 1/[RAMP_SAMPLES] of full scale instead of whatever the level happened to
     * be, which is what turns the old hard 20 ms hole-and-click into the soft
     * seam a crossfade makes.  The cursor does not move, because everything
     * before the cut is still to be played — moving it would make the first ramp
     * decorative.  It is still only wanted when latency has run away: the slip
     * handles ordinary drift and ordinary bursts.
     */
    fun dropTo(targetSamples: Int): Int {
        val n = depth - targetSamples
        if (n <= 0) return 0
        val ramp = minOf(RAMP_SAMPLES, n / 2)
        val cut = pos.toInt() + ramp
        if (ramp > 0) {
            for (k in 0 until ramp) {
                val i = cut - ramp + k             // leading into the seam
                if (i in 0 until size) buf[i] = (buf[i] * (ramp - k) / ramp).toShort()
            }
            for (k in 0 until ramp) {
                val i = cut + n + k                // coming out of it
                if (i in 0 until size) buf[i] = (buf[i] * (k + 1) / ramp).toShort()
            }
        }
        // Compacting the tail down over the cut is what removes it from the queue
        // while leaving the cursor pointing at audio that has not been played yet.
        val move = (size - cut - n).coerceAtLeast(0)
        if (move > 0) System.arraycopy(buf, cut + n, buf, cut, move)
        size -= n
        return n
    }

    /** Sample at [index], clamped at both ends: silence past the end, the
     *  nearest sample before the beginning. */
    private fun at(index: Int): Short = when {
        index >= size || size == 0 -> 0
        index < 0 -> buf[0]
        else -> buf[index]
    }

    /**
     * 4-tap Catmull-Rom at fraction [f] between the middle two taps.
     *
     * Horner form of the standard matrix
     *
     *     y = b + f·½(c−a) + f²·(a − 2½b + 2c − ½d) + f³·(−½a + 1½b − 1½c + ½d)
     *
     * which is the same kernel [RtpSession.upsample8kTo16k] uses: setting f = ½
     * here gives exactly (−a + 9b + 9c − d)/16.  Those two facts are worth testing
     * for, because they are what makes this the interpolation the class comment
     * promises: a cubic of this shape reproduces a straight line and a constant
     * exactly, so the only thing it ever does to audio is nothing, or the
     * band-limiting a fractional read needs.
     *
     * The nesting matters.  Written with the last group multiplied by f instead of
     * f³ — which is how this function first shipped — the cubic term folds into the
     * quadratic one and the kernel degenerates to `0.625·b + 0.375·c` at f = ½:
     * asymmetric, reading a straight line 8 % wrong and a 300 Hz sine 10 % wrong,
     * where plain linear interpolation is 0.2 % wrong.  That is a level- and
     * phase-dependent distortion that appears only while the read rate is
     * fractional, i.e. only while the queue is off target.
     */
    private fun interp(p0: Short, p1: Short, p2: Short, p3: Short, f: Float): Short {
        val a = p0.toFloat(); val b = p1.toFloat(); val c = p2.toFloat(); val d = p3.toFloat()
        val y = b + f * (0.5f * (c - a) +
            f * ((a - 2.5f * b + 2.0f * c - 0.5f * d) +
            f * (-0.5f * a + 1.5f * b - 1.5f * c + 0.5f * d)))
        // Round, not truncate: truncation toward zero is a distortion of its own
        // (it flattens low amplitudes), tiny but free to avoid.
        val r = if (y >= 0) y + 0.5f else y - 0.5f
        return r.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Read rate for one frame given how far the depth is from where it should be.
     *
     * Proportional, clamped, and exactly 1.0 on target.  One frame of error buys
     * 0.2 % of slip, i.e. 160*0.002 = 0.32 samples per frame, so one frame of
     * queue is removed in about ten seconds of slip: far too little to be heard
     * as a pitch change, and enough for the clock difference between two ordinary
     * 8 kHz oscillators (tens of parts per million) many times over.  Five frames
     * of error saturate the controller at [MAX_SLIP], which is where [dropTo]
     * takes over.
     */
    internal fun stepFor(depthFrames: Int, targetFrames: Int): Double {
        val err = depthFrames - targetFrames
        if (err == 0) return 1.0
        val slip = (err * 0.002).coerceIn(-MAX_SLIP, MAX_SLIP)
        return 1.0 + slip
    }

    companion object {
        /** Most time compression/stretch one frame may take, i.e. ±6 ms per
         *  second — about a tenth of a semitone.  Enough for clock drift and
         *  small bursts, far too little to be heard as a pitch change. */
        internal const val MAX_SLIP = 0.006

        /** Half-width, in samples, of the fade [dropTo] puts around a seam.
         *  5 ms at 8 kHz: long enough that the ear hears a soft dip rather than
         *  a click, short enough that no whole syllable disappears. */
        internal const val RAMP_SAMPLES = 40
    }
}
