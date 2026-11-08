package com.callagent.gateway.rtp

import android.util.Log
import java.util.ArrayDeque

/**
 * Hands encoded playback frames to the track that a real call path owns.
 *
 * Why this class exists.  Build 470 fed its `AudioTrack` with [RtpSession.writePlaybackFrame]:
 * write, wait up to a flat 30 ms for a refusal to clear, and throw away whatever was left with
 * one warning line.  That is where the GSM uplink injection route died — see
 * `gateway-test/out/miciso-try-via0-20261107-063700`, whose own log says it:
 *
 * - the audio policy created our injector as `role=INJECTOR ... buf=1032f`, i.e. 1032 frames =
 *   2064 bytes = 129 ms of 8 kHz audio, and the thread behind it runs `HAL frame count: 256`
 *   (32 ms) with `Threadloop write latency stats: ave=63.6139` — so the track holds more than
 *   six of our frames and drains slower than one 20 ms frame per 20 ms arrives;
 * - 473 writes timed out with `N bytes left`, every leftover in that log is 64-320 bytes (never
 *   a whole frame), 77 360 bytes were discarded — 4.83 s of a 22.6 s call — and the write gaps
 *   moved from 30-39 ms (281 of them, the expected rate) to 60-69 ms (129 of them, the timeout
 *   eating a whole extra cycle) so the stream ran at 0.6-0.7x real time;
 * - the control call on the same device, same build, one setting different
 *   (`miciso-ctl-via1-20261107-061517`) logged **zero** timeouts, because the earpiece track
 *   drains.
 *
 * So the fault is ours and it is mechanical: every accepted partial write chopped the tail off a
 * 20 ms frame, and the retries then broke the cadence, and the remainder was silently discarded.
 * A far end receiving that is handed a stream that is 40 % chopped at 160-sample granularity and
 * running at two-thirds speed — which is what "the remote could not hear me" sounds like.
 *
 * What this class does instead: the part a track will not take stays in a bounded FIFO, in order,
 * and is offered again on the next cycle.  Pacing is explicit on a monotonic clock (this loop is
 * normally paced by RTP packet arrivals, which is not a clock this component can trust once the
 * network hiccups or Asterisk's pacer falls behind), so the delivery rate is known and measured
 * rather than emergent.  Blocking is capped at one slot per frame, so a wedged Qualcomm output
 * still cannot stall the RTP receive thread.  Every byte is counted, and the overflow policy
 * refuses whole frames rather than leaving a frame half delivered: what the route gets, it gets
 * complete and in order.
 *
 * It deliberately does *not* claim the injected route can carry this audio at real time.  Whether
 * `r_submix` pairs this injector with the carrier's uplink client is a device question, and the
 * AudioFlinger counters from that run contradict each other (`Frames written: 145 408` against
 * `stop(93): called with 182 536 frames delivered` for the same track).  What this class makes
 * answerable on the next call is ours: the rate the route takes from us, the loss, and the
 * delivery cadence.
 */
class PlaybackWriter(
    private val sink: Sink,
    private val frameBytes: Int,
    private val queueFrames: Int = DEFAULT_QUEUE_FRAMES,
    private val alive: () -> Boolean = { true },
    private val clock: Clock = SystemNanoClock,
    private val slotNanos: Long = DEFAULT_SLOT_NANOS,
) {
    /**
     * The track side.  Returns bytes accepted, 0 when the track is full, negative on error.
     * A `fun interface` so the session can hand it an `AudioTrack.write` lambda.
     */
    fun interface Sink {
        fun write(data: ByteArray, offset: Int, length: Int): Int
    }

    /** Injectable time base, so the pacing can be asserted without waiting in real time. */
    fun interface Clock {
        fun nanos(): Long

        /** Sleeps, and reports the nanos actually slept (0 when it could not). */
        fun park(nanos: Long): Long = 0L
    }

    private class Frame(val bytes: ByteArray, var offset: Int)

    private val queue = ArrayDeque<Frame>()
    private var nextSlotNanos = -1L
    private var closed = false

    /** Bytes the sink accepted, ever. */
    @Volatile var accepted: Long = 0L; private set

    /** Frames the sink took completely; a partial hand-off is not a frame. */
    @Volatile var framesHanded = 0L; private set

    /** Frames dropped whole (or in part) because the route could not take them. */
    @Volatile var framesDropped = 0L; private set
    @Volatile var bytesDropped = 0L; private set

    /** Cycles that ended with the sink refusing, i.e. the route was behind. */
    @Volatile var refusals = 0L; private set

    /** Times the sink answered with an error rather than a byte count — either a negative
     *  status or an exception.  A track released under a live loop answers every write with
     *  `IllegalStateException`, and that has to land here instead of escaping into the
     *  playback loop: the loop's own catch already logs one line per frame forever, and no
     *  counter in the session would have shown it.  Nothing about the audio changes: an error
     *  takes no bytes, exactly as a refusal does, and the frame stays staged (or is counted
     *  lost at `stage=0`) in the order it was offered. */
    @Volatile var sinkErrors = 0L; private set

    /** Cycles this loop arrived too late to hold the 20 ms grid, and re-anchored. */
    @Volatile var lateReanchors = 0L; private set

    @Volatile var maxQueuedFrames = 0; private set

    fun framesQueued(): Int = synchronized(queue) { queue.size }

    /** The sink, with its two failure modes reduced to one: a negative status, counted.
     *
     *  Callers treat every negative answer the same way — no bytes taken, stop for this cycle —
     *  so a sink that throws and a sink that reports `INVALID_OPERATION` cannot take the call
     *  down or leave the loop spinning against a dead output. */
    private fun sinkWrite(data: ByteArray, offset: Int, length: Int): Int = try {
        val taken = sink.write(data, offset, length)
        if (taken < 0) sinkErrors += 1
        taken
    } catch (t: Throwable) {
        sinkErrors += 1
        // Once, not every cycle: a track released under a live loop would otherwise print a
        // line every 20 ms for the rest of the call.  The running total is in `err()` of the
        // stats line, which is where a field run reads it.
        if (sinkErrors == 1L)
            Log.e(TAG, "Playback sink threw ${t.javaClass.simpleName}; later ones are counted " +
                    "in err(), not printed")
        -1
    }

    /**
     * One encoded frame: hand it to the sink, keep what the sink refused, and hold the grid.
     *
     * Runs on the RTP receive thread.  Blocks for at most one slot — less than the 30 ms build
     * 470 was willing to burn per frame, and it never burns it twice in one cycle.
     */
    fun write(frame: ByteArray) {
        require(frame.size == frameBytes) { "frame ${frame.size} bytes, expected $frameBytes" }
        if (closed || !alive()) return

        val now = clock.nanos()
        if (nextSlotNanos < 0) {
            nextSlotNanos = now
        } else if (now - nextSlotNanos > REANCHOR_SLOTS * slotNanos) {
            // Behind by more than the grid can absorb: start the schedule afresh instead of
            // racing to catch up, which is what pushed build 470's cadence to 0.6x real time.
            lateReanchors += 1
            nextSlotNanos = now
        }

        if (queueFrames <= 0) {
            handOffDirect(frame, now)
        } else {
            stage(frame)
            drain(now)
        }
        holdGrid()
    }

    /** The route is going away (track released, call ended): drop what is still staged. */
    fun close() {
        if (closed) return
        closed = true
        val left = synchronized(queue) {
            val n = queue.sumOf { it.bytes.size - it.offset }
            queue.clear()
            n
        }
        if (left > 0) {
            framesDropped += (left + frameBytes - 1) / frameBytes
            bytesDropped += left
        }
    }

    /**
     * Build 470's contract, with the loss counted instead of only warned about: push the frame,
     * retry until the slot budget is spent, then discard the remainder.  Selected by
     * `callagent_play_stage=0` so an on-air A/B can measure the old behaviour instead of
     * re-reading its log lines afterwards.
     */
    private fun handOffDirect(frame: ByteArray, now: Long) {
        var offset = 0
        val until = now + slotNanos
        while (offset < frameBytes && alive() && !closed) {
            val taken = sinkWrite(frame, offset, frameBytes - offset)
            if (taken > 0) {
                offset += taken
                accepted += taken
                if (offset >= frameBytes) framesHanded += 1
                continue
            }
            if (taken < 0) {
                Log.e(TAG, "Playback sink error $taken with ${frameBytes - offset} bytes left")
                break
            }
            refusals += 1
            if (clock.nanos() >= until) break
            clock.park(until - clock.nanos())
        }
        if (offset < frameBytes) {
            framesDropped += 1
            bytesDropped += frameBytes - offset
            if (bytesDropped == (frameBytes - offset).toLong()) {
                Log.w(TAG, "Playback route refused ${frameBytes - offset} of $frameBytes bytes; " +
                    "staging is off (callagent_play_stage=0), discarding")
            }
        }
    }

    /** Copies a frame into the FIFO, refusing the incoming frame when the window is full.
     *
     *  Refusing the newest, rather than dropping the oldest, is deliberate: the frame at the head
     *  of the FIFO may already be partly handed over, and dropping the oldest would then leave a
     *  20 ms frame delivered in pieces, which is the exact defect this class exists to remove.
     *  With this policy every frame the route gets is delivered whole, in order, and the loss is
     *  whole frames counted in [framesDropped]. */
    private fun stage(frame: ByteArray): Boolean {
        if (synchronized(queue) { queue.size >= queueFrames }) {
            framesDropped += 1
            bytesDropped += frameBytes
            if (framesDropped == 1L) {
                Log.w(TAG, "Playback staging full at $queueFrames frames; the output route is " +
                    "behind, refusing whole frames instead of delivering them in pieces")
            }
            return false
        }
        val copy = ByteArray(frameBytes)
        System.arraycopy(frame, 0, copy, 0, frameBytes)
        synchronized(queue) {
            queue.addLast(Frame(copy, 0))
            if (queue.size > maxQueuedFrames) maxQueuedFrames = queue.size
        }
        return true
    }

    /** Pushes staged frames until the sink refuses; never past the current slot. */
    private fun drain(now: Long) {
        val until = now + slotNanos
        while (alive() && !closed) {
            val head = synchronized(queue) { queue.peekFirst() } ?: return
            val taken = sinkWrite(head.bytes, head.offset, head.bytes.size - head.offset)
            if (taken > 0) {
                head.offset += taken
                accepted += taken
                if (head.offset >= head.bytes.size) {
                    synchronized(queue) { queue.removeFirst() }
                    framesHanded += 1
                }
                continue
            }
            if (taken < 0) {
                Log.e(TAG, "Playback sink error $taken with ${framesQueued()} frames staged")
                return
            }
            refusals += 1
            if (clock.nanos() >= until) return
            clock.park(until - clock.nanos())
        }
    }

    /** Waits out the rest of the slot, so one frame per 20 ms leaves this loop. */
    private fun holdGrid() {
        val due = nextSlotNanos
        nextSlotNanos = if (due > Long.MAX_VALUE - slotNanos) clock.nanos() else due + slotNanos
        while (alive() && !closed) {
            val before = clock.nanos()
            val left = due - before
            if (left <= 0) return
            clock.park(left)
            // A clock that cannot move time (a test, or a park that was interrupted and
            // reported nothing) must not turn the wait into a spin.
            if (clock.nanos() == before) return
        }
    }

    /**
     * Appended to the periodic stats line.  Compact, because it rides a log line that already
     * carries the whole session, and every number in it is one that a field run then cannot
     * disagree about.
     *
     * `out` counts frames the track took *completely*: a frame it is still holding part of shows
     * up in `held`, and the byte-exact figure is in [accepted], which `playbackConsumed()` reads
     * against the track's own `playbackHeadPosition`.
     *
     * `err` counts the times the sink answered with an error instead of a byte count — a negative
     * status or a thrown exception.  It is separate from `drop` because the two mean different
     * things on a field run: `drop` says the route was behind, `err` says the route went away.
     */
    fun summary(): String = "wr:out($framesHanded)" +
        "/held(${framesQueued()})/drop($framesDropped)/refus($refusals)/err($sinkErrors)/" +
        "late($lateReanchors)/maxQ($maxQueuedFrames)"

    companion object {
        private const val TAG = "CallAgentRtp"

        /** One 20 ms RTP packet, which is what the loop is fed. */
        const val DEFAULT_SLOT_NANOS = 20_000_000L

        /** Four frames is 80 ms of staging: enough to absorb one 32 ms HAL loop plus scheduler
         *  jitter on the 470 route, short enough that dropped audio cannot cost a sentence. */
        const val DEFAULT_QUEUE_FRAMES = 4

        /** Ceiling a field value may ask for; 10 frames is 200 ms, which is already noticeable
         *  delay on a two-way conversation. */
        const val MAX_QUEUE_FRAMES = 10

        /** More than three frames behind is not jitter any more; re-anchor instead of chasing. */
        private const val REANCHOR_SLOTS = 3

        private val SystemNanoClock = object : Clock {
            override fun nanos(): Long = System.nanoTime()
            override fun park(nanos: Long): Long {
                val before = System.nanoTime()
                val step = if (nanos < PARK_STEP_NS) nanos else PARK_STEP_NS
                java.util.concurrent.locks.LockSupport.parkNanos(step)
                return System.nanoTime() - before
            }
        }

        /** Parked in 2 ms steps so recovery latency stays well under one slot. */
        private const val PARK_STEP_NS = 2_000_000L
    }
}
