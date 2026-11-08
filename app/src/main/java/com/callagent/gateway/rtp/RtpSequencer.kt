package com.callagent.gateway.rtp

/**
 * RTP timeline gate in front of the jitter buffer.
 *
 * Why this exists.  The receive path used to hand the jitter buffer whatever
 * arrived, in arrival order, and never looked at `sequenceNumber`, `timestamp`
 * or `ssrc` on an audio packet (only DTMF ever read the timestamp, in
 * [RtpSession.receiveLoop]).  A packet was therefore defined by *when it turned
 * up*, not by *where it belongs in the call*, and that has exactly three
 * consequences, all of them audible on the far end of the GSM leg:
 *
 *  - **a duplicate is played twice.**  A packet re-sent by anything on the path —
 *    an 802.11 retransmission under power save reaching the stack, a NAT
 *    re-injecting a datagram, a PBX repeating a packet after a re-INVITE — is
 *    decoded and appended to the reservoir as though it were new audio.  The
 *    listener hears the same 20 ms twice: literal repetition, which is what the
 *    field report of 2026-11-06 calls "robotic audio repetition".
 *  - **a late packet is played late.**  The reservoir appends in arrival order, so
 *    a packet that turns up 200 ms after its turn reaches the speaker 200 ms after
 *    the audio in front of it.  Content that has already been heard comes back
 *    (the reordered burst), the reservoir goes over its target depth, and
 *    [PlaybackSlip.dropTo] cuts a hole out of the middle of it to catch up:
 *    repetition, then stutter.
 *  - **a reordered pair plays in the wrong order.**  Two 20 ms segments of someone's
 *    word delivered swapped are that word spoken out of order, which reads as
 *    robotic rather than as a glitch.
 *
 * None of these are exotic on this handset.  [RtpSession] already documents what
 * its Wi-Fi does to a call: bursts separated by gaps, because the driver powers the
 * link down between traffic.  Bursts with holes and reordering in them are the
 * expected arrival pattern, not an exception.
 *
 * What this class does is put the timeline back.  The RTP timestamp *is* the
 * playback position (RFC 3550 §4.1), so every packet is placed on that timeline,
 * anything belonging at or behind the point this stream has already been handed
 * over to the buffer is dropped rather than played, and anything that arrives out
 * of order is held briefly and released in the right order.  The reservoir behind
 * it stays as it was: it appends in arrival order, which now means timeline order.
 *
 * Three details worth being precise about:
 *
 *  - The 32-bit timestamp wraps (every 14.9 h at 8 kHz), so it is unwrapped into a
 *    monotonic 64-bit value the way RFC 3550 recommends — serial-number
 *    arithmetic, not a comparison.
 *  - How much time a packet occupies comes from its own payload length and the
 *    codec's bytes-per-tick, which is exact rather than inferred: G.711 carries one
 *    sample per byte against an 8 kHz clock (160 bytes = 20 ms), G.722 carries two
 *    samples per byte against a 16 kHz clock (160 bytes = 20 ms).  Nothing is
 *    assumed about ptime, so a 30 ms sender, a comfort-noise frame and a 20 ms
 *    sender all place correctly.  Conveniently, for both codecs the RTP clock
 *    equals the sample rate, so one timestamp tick is one reservoir sample and a
 *    gap in the timeline is directly a gap in samples.
 *  - A packet that *straddles* the frontier is dropped whole rather than trimmed.
 *    Trimming would mean cutting a coded frame in half at a byte offset that
 *    depends on the codec, to save a sliver of audio at most; the ear cannot tell
 *    the difference and the no-replay property is absolute this way.
 *
 * Holding a packet costs latency, so it is bounded twice: [reorderWaitMs] of
 * patience for the packet that is missing, and [maxHeldPackets] of it.  Past
 * either, the frontier jumps the hole — which is what the reservoir was already
 * doing by accident, with the difference that it is now counted (`holes`) and that
 * the late packet which turns up afterwards is dropped instead of replayed.
 */
internal class RtpSequencer(
    /** RTP clock of this stream: 8000 for G.711, 16000 for G.722.  It is what the
     *  SDP negotiated, so it also says how to turn ticks into milliseconds. */
    private val clockRate: Int,
    /** How long to wait for a hole to fill before playing across it. */
    private val reorderWaitMs: Long = DEFAULT_REORDER_WAIT_MS,
    /** Most packets that may sit in front of an unfilled hole. */
    private val maxHeldPackets: Int = DEFAULT_MAX_HELD,
    /** Injectable clock, so the tests do not have to wait on real time. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** Packets waiting their turn, keyed by unwrapped start timestamp. */
    private val held = java.util.TreeMap<Long, RtpPacket>()

    /** Unwrapped timestamp where the next packet belongs: the frontier of what has
     *  already been handed to the jitter buffer.  Anything at or behind it has
     *  been played or is queued to be.
     *
     *  [NOTHING_RELEASED] means the current stream has not handed anything over
     *  yet, so nothing can be behind the frontier; see [release] for the settling
     *  window that uses that. */
    private var releasedEnd = NOTHING_RELEASED

    /** Start timestamp of the newest packet that turned up.  Comparing an arrival
     *  against it is how "out of order" is counted. */
    private var receivedStart = -1L

    /** How long the most recent packet took, in timestamp ticks.  Diagnostic only,
     *  and useful: it is the number that says what ptime the far end really uses. */
    private var lastTicks = clockRate / 50L

    /** Slop: a sender whose ptime wobbles by a few ticks is still in line, and the
     *  reservoir's slip absorbs a few milliseconds without comment.  Deliberately
     *  far short of one frame, so a genuinely missing packet is never read as
     *  wobble. */
    private val driftTicks = (clockRate / 1000L * 5).coerceAtLeast(1L)   // 5 ms

    /** More than this of missing timeline is not a lost packet, it is a sender that
     *  changed the subject: re-anchor rather than charge the call for a hole of
     *  minutes. */
    private val maxGapTicks = 2L * clockRate

    private var ssrc = -1L
    private var tsOffset = 0L
    private var lastRawTs = -1L

    /** When the current stream's first arrival was seen, for the settling window. */
    private var startMs = -1L

    /** When an unfilled hole was first seen, in [now] units; -1 when there is none. */
    private var holeSinceMs = -1L

    /** Start timestamps of the most recently released packets, so an exact repeat
     *  can be reported as a duplicate rather than as merely old. */
    private val releasedRecently = java.util.ArrayDeque<Long>()

    // ── Counters: for the stats line, and asserted by the tests ────────────
    /** Packets handed on to the jitter buffer. */
    @JvmField var passed = 0L

    /** Arrivals older than the newest one already seen: out of order, or a repeat
     *  of a packet still waiting its turn. */
    @JvmField var outOfOrder = 0L

    /** The same timeline position seen twice. */
    @JvmField var duplicates = 0L

    /** Packets dropped because their position was already handed over — the audio
     *  they carry has been played or queued.  Preventing that replay is the whole
     *  point of this class, and this is the number the next real call reports. */
    @JvmField var stale = 0L

    /** Holes played across because the packet that should have filled them never
     *  turned up, and the timeline lost doing it, in ticks. */
    @JvmField var holes = 0L
    @JvmField var holeTicks = 0L

    /** Streams taken over mid-call: SSRC change, or a jump in the timeline. */
    @JvmField var restarts = 0L

    /** How long the most recent packet occupied, in timestamp ticks: a diagnostic
     *  in itself, since it says what ptime the far end is really using. */
    val lastPacketTicks: Long get() = lastTicks

    /**
     * One received audio packet.
     *
     * Everything that should now go to the jitter buffer is passed to [out], in
     * timeline order — usually this packet itself, sometimes a run of previously
     * held packets, sometimes nothing because this one is being held.
     */
    @Synchronized
    fun accept(pkt: RtpPacket, out: (RtpPacket) -> Unit) {
        // A different synchronisation source is a different stream: a re-INVITE, a
        // PBX restart, a transfer.  Its timestamps have no relation to the old
        // ones, so the timeline starts over rather than reading a four-billion-
        // tick "jump".  Unwrapping has to be reset before the timestamp is read,
        // and the very first packet of a session is not a stream change, so it is
        // not counted as one.
        val newStream = pkt.ssrc != ssrc
        if (newStream) {
            if (ssrc >= 0) restarts++
            tsOffset = 0L
            lastRawTs = -1L
        }
        val ts = unwrap(pkt.timestamp)
        if (newStream) startStream(pkt.ssrc, ts)

        // How much of the timeline this packet occupies: read off its own payload,
        // which is also why nothing here assumes a 20 ms ptime.
        lastTicks = ticksOf(pkt)

        if (receivedStart >= 0 && ts < receivedStart) outOfOrder++

        if (held.containsKey(ts)) {
            // The same timeline position twice while it is still waiting: the same
            // datagram seen again.  Playing it twice is the repetition bug.
            duplicates++
            return
        }
        if (ts < releasedEnd) {
            // At or behind the frontier.  Everything up to that point has gone to
            // the reservoir, so this packet's content has been played or is about
            // to be; appending it now would speak that piece of the call again.
            if (releasedRecently.contains(ts)) duplicates++ else stale++
            return
        }

        held[ts] = pkt
        if (ts > receivedStart) receivedStart = ts
        release(out)
    }

    /**
     * Called with no packet in hand — the playback thread's tick.  Without it a
     * held packet would wait for the *next arrival* before being released, which
     * is exactly wrong when the far end has stalled.
     */
    @Synchronized
    fun pump(out: (RtpPacket) -> Unit) = release(out)

    /** Forgets the stream entirely: the next packet starts the timeline over. */
    @Synchronized
    fun reset() {
        tsOffset = 0L
        lastRawTs = -1L
        startStream(-1L, 0L)
    }

    /** One line for the RTP stats, so the next real call can say whether the path
     *  really is repeating and reordering packets. */
    fun stats(): String {
        val ms = holeTicks * 1000 / clockRate
        return "gate: pass=$passed ooo=$outOfOrder dup=$duplicates stale=$stale " +
            "hole=$holes/${ms}ms restart=$restarts ticks=$lastTicks"
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Releases everything now in line, jumping the frontier over a hole once
     *  patience has run out. */
    private fun release(out: (RtpPacket) -> Unit) {
        while (true) {
            val head = held.firstEntry() ?: break
            if (releasedEnd == NOTHING_RELEASED) {
                // Settling: this stream has not handed anything over yet, so
                // nothing can already have been played, and the earliest packet of
                // its first burst may still be on its way — a pair delivered the
                // wrong way round is exactly that.  Waiting for one more arrival
                // costs at most one frame before playback has even begun (the
                // reservoir still has to fill its prefill), and in exchange the
                // first milliseconds of the call are kept instead of being read as
                // ancient on the strength of one datagram arriving first.
                if (held.size < 2 && now() - startMs < reorderWaitMs) break
                releasedEnd = head.key
                continue
            }
            val ts = head.key
            if (ts <= releasedEnd + driftTicks) {
                held.remove(ts)
                handOver(head.value, ts, out)
                continue
            }
            // A hole sits between the frontier and this packet.
            if (holeSinceMs < 0L) holeSinceMs = now()
            val ahead = ts - releasedEnd
            if (ahead > maxGapTicks) {
                // Not a missing packet: the sender's timeline moved.  Re-anchor
                // rather than counting an absurd hole.
                restarts++
                releasedEnd = ts
                holeSinceMs = -1L
                continue
            }
            if (now() - holeSinceMs >= reorderWaitMs || held.size > maxHeldPackets) {
                holes++
                holeTicks += ahead
                releasedEnd = ts
                holeSinceMs = -1L
                continue
            }
            break
        }
        if (held.isEmpty()) holeSinceMs = -1L
    }

    private fun handOver(pkt: RtpPacket, ts: Long, out: (RtpPacket) -> Unit) {
        releasedEnd = maxOf(releasedEnd, ts + ticksOf(pkt))
        passed++
        releasedRecently.addLast(ts)
        while (releasedRecently.size > RECENT_WINDOW) releasedRecently.removeFirst()
        holeSinceMs = -1L
        out(pkt)
    }

    /**
     * How much of the timeline one packet occupies, in timestamp ticks: payload
     * bytes times the codec's samples per tick.
     *
     * Read off the packet's own payload type rather than the session's, because
     * [RtpSession.receiveLoop] also accepts the other codec it negotiated: a packet
     * placed at the wrong length would show up as a hole or an overlap that never
     * happened.  The two numbers come from RFC 3550's static payload table — G.722
     * (PT 9) is a 16 kHz clock at two samples per byte, G.711 (PT 8 and PT 0) an
     * 8 kHz clock at one — and both codecs' clock equals this session's sample rate,
     * which is what makes one tick one reservoir sample.
     */
    private fun ticksOf(pkt: RtpPacket): Long =
        (pkt.payload.size * if (pkt.payloadType == RtpPacket.PT_G722) 2 else 1).toLong()

    private fun startStream(newSsrc: Long, firstTs: Long) {
        ssrc = newSsrc
        held.clear()
        releasedRecently.clear()
        releasedEnd = NOTHING_RELEASED
        startMs = now()
        receivedStart = firstTs
        holeSinceMs = -1L
    }

    /**
     * Serial-number arithmetic on a wrapping 32-bit timestamp: the offset is
     * adjusted whenever the raw value crosses the midpoint of the span, so the
     * result keeps climbing across a wrap and also survives a sender that starts
     * over from zero.
     */
    private fun unwrap(raw: Long): Long {
        if (lastRawTs >= 0L) {
            val d = raw - lastRawTs
            if (d <= -HALF_SPAN) tsOffset += SPAN            // wrapped forward
            else if (d > HALF_SPAN) tsOffset -= SPAN         // sender started over
        }
        lastRawTs = raw
        return tsOffset + raw
    }

    private companion object {
        /** Frontier value meaning "nothing of this stream has been handed over
         *  yet", so nothing can be behind it. */
        const val NOTHING_RELEASED = Long.MIN_VALUE

        /** RTP timestamps are 32 bits; these are the wrap-arithmetic bounds. */
        const val SPAN = 1L shl 32
        const val HALF_SPAN = 1L shl 31

        /** How many released timestamps to remember when deciding whether a
         *  dropped packet was an exact repeat or merely old. */
        const val RECENT_WINDOW = 64

        /** The phone's Wi-Fi delivers bursts, and one burst is far shorter than
         *  this.  80 ms is two reservoir frames of patience — the most that can be
         *  spent without pushing the call's latency past what the prefill already
         *  costs. */
        const val DEFAULT_REORDER_WAIT_MS = 80L

        /** Latency ceiling in packets, independent of the clock: three frames of
         *  waiting is already behind the reservoir's own prefill. */
        const val DEFAULT_MAX_HELD = 3
    }
}
