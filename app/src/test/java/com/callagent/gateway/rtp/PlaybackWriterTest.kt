package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback hand-off, and the failure it replaces.
 *
 * The model is a byte-accurate `AudioTrack.write(..., WRITE_NON_BLOCKING)` buffer in front of a
 * route that consumes bytes at a fixed rate.  Every number in it is measured, not chosen — it is
 * what our own logcat and a `dumpsys audio` printed for the one call in which the bridge injected
 * into the GSM uplink (`gateway-test/out/miciso-try-via0-20261107-063700`, 2026-11-07):
 *
 * - `POLICY ... createAudioTrackSource role=INJECTOR ... buf=1032f` — our injector's track is
 *   1032 frames = 2064 bytes = 129 ms of 8 kHz mono PCM16, i.e. 6.45 of our frames deep;
 * - 320-byte frames every 20 ms, which is what the playback loop hands over (`rate/50 * 2`);
 * - that track's thread ran `HAL frame count: 256` (32 ms) at `Threadloop write latency stats:
 *   ave=63.6139` — about half real time, which the model reproduces as [halfRateNanosPerByte];
 * - the field log then printed 473 `AudioTrack nonblocking write timed out (N bytes left)` lines
 *   with every N in 64-320, 77 360 bytes discarded from a 22.6 s call, and write gaps that moved
 *   from the expected 30-39 ms (281 of them) to 60-69 ms (129 of them).
 *
 * Each audio frame carries the absolute number of every 16-byte block inside it, repeated four
 * times, so the stream the route played can be examined without decoding audio: a block whose
 * position in the played stream disagrees with its own sequence number means the far end was fed
 * audio spliced from the middle of two frames, and a gap between consecutive blocks says whether
 * what went missing was a whole frame or the inside of one.
 *
 * What these tests can settle is our half — the rate we deliver at, what we throw away, and
 * whether what we hand over is frame-aligned.  What they cannot settle is whether `r_submix`
 * pairs this injector with the carrier's uplink client at all; that needs the next call on air,
 * and the route's own counters from that run disagree with each other (`Frames written: 145 408`
 * against `stop(93): called with 182 536 frames delivered` for the same track).
 */
class PlaybackWriterTest {

    private val frameBytes = 320
    private val slotNanos = 20_000_000L

    /** 1032 frames of 8 kHz mono PCM16, as the policy reported the injector's buffer. */
    private val injectorTrackBytes = 1032 * 2

    /** 8 kHz mono PCM16 at real time: 16 000 bytes a second, one byte per 62.5 us. */
    private val realTimeNanosPerByte = 62_500L

    /** The route that track ran at in the field dump: half real time. */
    private val halfRateNanosPerByte = 125_000L

    /** 22.6 s of call at one 20 ms frame each, the length of that field run. */
    private val fieldCycles = 1130

    // The field log's own numbers, kept here so the comparison below is against a record, not a
    // recollection.  See gateway-test/gw-uplink-hardening-470.md for where each came from.
    private val fieldTimeouts = 473
    private val fieldLostBytes = 77_360L
    private val fieldLeftoverMin = 64
    private val fieldLeftoverMax = 320

    // ── the model ───────────────────────────────────────────────────────────────────────

    /** Time that only moves when something waits, so the 20 ms grid is assertable. */
    private class FakeClock(var now: Long = 0) : PlaybackWriter.Clock {
        override fun nanos(): Long = now
        override fun park(nanos: Long): Long {
            now += nanos
            return nanos
        }
    }

    /** What the route actually played, read back through the block numbers inside the audio. */
    private inner class Played(private val bytes: IntArray) {
        /** (block sequence number, position) for every block that survived intact. */
        private val blocks: List<Pair<Int, Int>> by lazy {
            val found = ArrayList<Pair<Int, Int>>()
            var pos = 0
            while (pos + 16 <= bytes.size) {
                val a = le(pos); val b = le(pos + 4); val c = le(pos + 8); val d = le(pos + 12)
                if (a == b && b == c && c == d) found.add(a to pos)
                pos += 16
            }
            found
        }

        /**
         * Places where the four words of a block no longer agree: the route was fed a slice of
         * audio that is not a whole number of blocks, i.e. a frame cut at a non-block offset.
         */
        val corruptBlocks: Int by lazy {
            var bad = 0
            var pos = 0
            while (pos + 16 <= bytes.size) {
                val a = le(pos); val b = le(pos + 4); val c = le(pos + 8); val d = le(pos + 12)
                if (!(a == b && b == c && c == d)) bad++
                pos += 16
            }
            bad
        }

        /**
         * Gaps between intact blocks that begin inside a frame (`seq % 20 != 19`): part of one
         * frame reached the route and part of it did not, so the route plays the head of one
         * frame welded to the tail of another.  A bridge that loses whole frames never produces
         * this, and this is the signature of what build 470 did on the injected route.
         */
        val cutInsideFrames: Int by lazy {
            blocks.zipWithNext().count { (p, n) -> n.first != p.first + 1 && p.first % 20 != 19 }
        }

        /**
         * Gaps on a frame boundary: whole frames the route never got.  Loss, but the audio the
         * route *does* play is still continuous, frame-aligned speech.
         */
        val wholeFramesMissing: Int by lazy {
            blocks.zipWithNext().count { (p, n) -> n.first != p.first + 1 && p.first % 20 == 19 }
        }

        val frames: Int by lazy { blocks.map { it.first / 20 }.distinct().size }

        val intactBlocks: Int get() = blocks.size

        private fun le(i: Int) =
            (bytes[i] and 0xFF) or ((bytes[i + 1] and 0xFF) shl 8) or
                ((bytes[i + 2] and 0xFF) shl 16) or ((bytes[i + 3] and 0xFF) shl 24)
    }

    /**
     * An `AudioTrack` with [capacityBytes] of buffer in front of a route consuming one byte every
     * [nanosPerByte].  A full buffer makes a write return 0, exactly as `WRITE_NON_BLOCKING` does
     * on the device; [refused] counts those.
     */
    private inner class Route(
        private val clock: FakeClock,
        val capacityBytes: Int = injectorTrackBytes,
        private val nanosPerByte: Long = realTimeNanosPerByte,
    ) {
        private val buffer = ByteArray(capacityBytes)
        private var head = 0
        private var filled = 0
        private var lastDrainNanos = clock.now
        private val played = ArrayList<Int>()
        var refused = 0; private set
        var bytesIntoTrack = 0L; private set

        private fun drainRoute() {
            val take = ((clock.now - lastDrainNanos) / nanosPerByte)
                .coerceAtMost(filled.toLong()).toInt()
            repeat(take) {
                played.add(buffer[head].toInt() and 0xFF)
                head = (head + 1) % capacityBytes
                filled--
            }
            lastDrainNanos += take.toLong() * nanosPerByte
        }

        fun write(data: ByteArray, off: Int, len: Int): Int {
            drainRoute()
            if (filled >= capacityBytes) {
                refused++
                return 0
            }
            val take = minOf(len, capacityBytes - filled)
            repeat(take) {
                buffer[(head + filled) % capacityBytes] = data[off + it]
                filled++
            }
            bytesIntoTrack += take
            return take
        }

        fun pendingBytes() = filled

        /** Empties the track and reads back everything the route was fed. */
        fun playEverything(): Played {
            clock.now += pendingBytes().toLong() * nanosPerByte + slotNanos
            drainRoute()
            return Played(played.toIntArray())
        }
    }

    /**
     * Frame [index] is 20 blocks of 16 bytes; block [j] carries the absolute block number
     * `index*20 + j` as a little-endian int repeated four times.  The repetition makes a block
     * self-identifying: shifted by any number of bytes, its four words stop agreeing.
     */
    private fun frame(index: Int): ByteArray {
        val b = ByteArray(frameBytes)
        for (j in 0 until 20) {
            val seq = index * 20 + j
            for (k in 0 until 4) {
                b[j * 16 + k * 4] = (seq and 0xFF).toByte()
                b[j * 16 + k * 4 + 1] = ((seq shr 8) and 0xFF).toByte()
                b[j * 16 + k * 4 + 2] = ((seq shr 16) and 0xFF).toByte()
                b[j * 16 + k * 4 + 3] = ((seq shr 24) and 0xFF).toByte()
            }
        }
        return b
    }

    private fun staged(clock: FakeClock, route: Route, queueFrames: Int = 4): PlaybackWriter =
        PlaybackWriter(
            sink = PlaybackWriter.Sink { d, o, l -> route.write(d, o, l) },
            frameBytes = frameBytes,
            queueFrames = queueFrames,
            clock = clock,
        )

    /**
     * Verbatim [RtpSession.writePlaybackFrame] against the same model, so both sides of the
     * comparison are measured with one ruler: non-blocking write, a flat 30 ms deadline, 1 ms
     * waits, the remainder discarded.  Returns the bytes thrown away, 0 when the frame went
     * through whole.
     */
    private fun writeBuild470(clock: FakeClock, sink: (ByteArray, Int, Int) -> Int, data: ByteArray): Int {
        var offset = 0
        val deadline = clock.now + 30_000_000L
        while (offset < data.size) {
            val written = sink(data, offset, data.size - offset)
            if (written > 0) {
                offset += written
                continue
            }
            if (written < 0) return -1
            if (clock.now >= deadline) return data.size - offset
            clock.now += 1_000_000L
        }
        return 0
    }

    /** One field call's worth of frames, arriving on a 20 ms network cadence. */
    private fun feedOnNetworkCadence(
        clock: FakeClock,
        cycles: Int,
        one: (ByteArray) -> Int,
    ): Pair<ArrayList<Int>, ArrayList<Long>> {
        val leftovers = ArrayList<Int>()
        val gaps = ArrayList<Long>()
        var last = clock.now
        var due = slotNanos
        repeat(cycles) {
            val left = one(frame(it))
            if (left > 0) leftovers += left
            gaps += clock.now - last
            last = clock.now
            // RTP does not wait for us: the next packet is due 20 ms after the last one arrived,
            // regardless of how much time the previous write burned.
            if (clock.now < due) clock.now = due
            due += slotNanos
        }
        return leftovers to gaps
    }

    // ── the healthy route: build 470 was fine there, and so is this ─────────────────────

    @Test
    fun `a route that drains at real time loses nothing for either writer`() {
        val clock = FakeClock()
        val route = Route(clock)
        val sink: (ByteArray, Int, Int) -> Int = { d, o, l -> route.write(d, o, l) }
        val (oldLeftovers, _) = feedOnNetworkCadence(clock, fieldCycles) {
            writeBuild470(clock, sink, it)
        }
        assertEquals("build 470 never saw a refusal on this route, which is the via=1 control",
            0, oldLeftovers.size)
        val played = route.playEverything()
        assertEquals(fieldCycles, played.frames)
        assertEquals(0, played.corruptBlocks)
        assertEquals(0, played.cutInsideFrames)

        val clock2 = FakeClock()
        val route2 = Route(clock2)
        val writer = staged(clock2, route2)
        repeat(fieldCycles) { writer.write(frame(it)) }
        val played2 = route2.playEverything()
        assertEquals("the staged writer loses nothing either", fieldCycles, played2.frames)
        assertEquals(0L, writer.framesDropped)
        assertEquals("and nothing was ever staged", 0, writer.framesQueued())
        assertEquals(0, played2.cutInsideFrames)
        assertEquals(0, played2.corruptBlocks)
    }

    @Test
    fun `the grid paces the loop even when packets free-run`() {
        val clock = FakeClock()
        val route = Route(clock)
        val writer = staged(clock, route)
        repeat(fieldCycles) { writer.write(frame(it)) }   // no clock advance between frames

        assertEquals("one frame out per 20 ms, whatever the network did",
            (fieldCycles - 1) * slotNanos, clock.now)
        assertEquals("no catch-up burst into the track", 0, writer.framesQueued())
        assertEquals(0L, writer.framesDropped)
        assertEquals(0, writer.lateReanchors)
    }

    // ── the field failure, reproduced offline ───────────────────────────────────────────

    @Test
    fun `build 470 jams the 129 ms injector track and discards a frame tail every cycle`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = halfRateNanosPerByte)
        val sink: (ByteArray, Int, Int) -> Int = { d, o, l -> route.write(d, o, l) }
        val (leftovers, gaps) = feedOnNetworkCadence(clock, fieldCycles) {
            writeBuild470(clock, sink, it)
        }

        assertTrue("the route jams and the track starts refusing: ${leftovers.size} timeouts",
            leftovers.size > fieldTimeouts)
        assertTrue("every discard is less than one frame, as in the field log " +
                "($fieldLeftoverMin-$fieldLeftoverMax bytes there)",
            leftovers.minOrNull()!! > 0 && leftovers.maxOrNull()!! < frameBytes)
        assertTrue("the flat 30 ms deadline burns at least 29 ms of every such cycle",
            gaps.count { it >= 29_000_000L } >= leftovers.size)
        val discarded = leftovers.sum().toLong()
        assertEquals("the field run discarded this many bytes of far-end speech",
            fieldLostBytes, 77_360L)
        assertTrue("and the model lands in the same order of magnitude: $discarded bytes",
            discarded in fieldLostBytes..(fieldLostBytes * 3 / 2))

        val slowDown = clock.now.toDouble() / (fieldCycles * slotNanos)
        assertTrue("so the loop runs far below real time: ${clock.now / 1_000_000} ms for " +
                "${fieldCycles * 20} ms of audio ($slowDown x)", slowDown in 1.3..1.8)
        println("build470 sim: timeouts=${leftovers.size} leftover=${leftovers.minOrNull()}-" +
            "${leftovers.maxOrNull()} discarded=$discarded bytes (${discarded / 16 / 1000.0}s) " +
            "intoTrack=${route.bytesIntoTrack} bytes (${route.bytesIntoTrack / 16 / 1000.0}s) " +
            "elapsed=${clock.now / 1_000_000}ms x$slowDown refused=${route.refused}")
        println("field  run: timeouts=$fieldTimeouts leftover=$fieldLeftoverMin-$fieldLeftoverMax " +
            "discarded=$fieldLostBytes bytes (${fieldLostBytes / 16 / 1000.0}s) 22600ms call, " +
            "gap buckets {30-39=281, 60-69=129}")
    }

    @Test
    fun `build 470 hands the far end audio cut inside frames`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = halfRateNanosPerByte)
        val sink: (ByteArray, Int, Int) -> Int = { d, o, l -> route.write(d, o, l) }
        repeat(fieldCycles) { writeBuild470(clock, sink, frame(it)) }
        val played = route.playEverything()

        // The track is a byte FIFO.  Every partial write build 470 made left the rest of that
        // frame on the floor, so the bytes the route went on to play are the head of one 20 ms
        // frame welded to the middle of another: the surviving blocks jump inside frames, never
        // on a frame boundary.  That is the waveform shape of what the far end reported as
        // silence, and build 470's own log called it "304 bytes left" 473 times.
        assertTrue("the losses begin inside frames, not on their boundaries: " +
                "${played.cutInsideFrames} cuts vs ${played.wholeFramesMissing} whole frames",
            played.cutInsideFrames > 100 && played.wholeFramesMissing == 0)
    }

    // ── what the staged writer does on the same route ──────────────────────────────────

    @Test
    fun `the staged writer loses only whole frames on the route that broke build 470`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = halfRateNanosPerByte)
        val writer = staged(clock, route)
        repeat(fieldCycles) { writer.write(frame(it)) }
        val played = route.playEverything()

        assertEquals("no frame is ever delivered in pieces", 0, played.cutInsideFrames)
        assertEquals("and no block of the audio was corrupted on the way in", 0, played.corruptBlocks)
        assertTrue("what is lost sits on frame boundaries, not inside frames: dropped=" +
                "${writer.framesDropped}, boundary gaps in the played stream=" +
                played.wholeFramesMissing,
            played.wholeFramesMissing in 1..writer.framesDropped.toInt())
        assertTrue("the route was fed every frame the writer handed it (plus, at most, the one " +
                "frame split between the track and the staging window): delivered=" +
                "${played.frames} handed=${writer.framesHanded}",
            played.frames in writer.framesHanded..writer.framesHanded + 1)
        assertTrue("a half-rate route can only carry half the speech; that is the route's limit, " +
                "not a choice: delivered=${played.frames} of $fieldCycles",
            played.frames in 400..700)
        println("staged sim: handed=${writer.framesHanded} dropped=${writer.framesDropped} " +
            "held=${writer.framesQueued()} maxQ=${writer.maxQueuedFrames} refusals=${writer.refusals} " +
            "deliveredFrames=${played.frames} cutInsideFrames=${played.cutInsideFrames} " +
            "wholeMissing=${played.wholeFramesMissing} corruptBlocks=${played.corruptBlocks} " +
            "elapsed=${clock.now / 1_000_000}ms")
    }

    @Test
    fun `the staged writer keeps the 20 ms grid on the route that broke build 470`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = halfRateNanosPerByte)
        val writer = staged(clock, route)
        repeat(fieldCycles) { writer.write(frame(it)) }

        assertEquals("the loop still runs at one frame per 20 ms even while the route drowns",
            (fieldCycles - 1) * slotNanos, clock.now)
        assertEquals("and it never decided to chase the route", 0, writer.lateReanchors)
    }

    @Test
    fun `the byte accounting is exact whatever the route does`() {
        for (npb in longArrayOf(realTimeNanosPerByte, halfRateNanosPerByte, Long.MAX_VALUE / 8)) {
            val clock = FakeClock()
            val route = Route(clock, nanosPerByte = npb)
            val writer = staged(clock, route)
            repeat(fieldCycles) { writer.write(frame(it)) }
            assertEquals("handed + lost + still staged = every frame submitted, at npb=$npb",
                fieldCycles.toLong(), writer.framesHanded + writer.framesDropped +
                    writer.framesQueued())
            assertEquals("loss is always counted in whole frames, never pieces",
                writer.framesDropped * frameBytes, writer.bytesDropped)
        }
    }

    @Test
    fun `a dead route costs the staging window and never blocks the RTP thread`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = Long.MAX_VALUE / 8)
        val writer = staged(clock, route, queueFrames = 4)
        var worst = 0L
        repeat(200) {
            val before = clock.now
            writer.write(frame(it))
            worst = maxOf(worst, clock.now - before)
        }
        assertTrue("the RTP thread is never held more than one slot per frame: ${worst / 1_000_000}ms",
            worst <= 2 * slotNanos)
        assertEquals("the window is exactly as large as it was set", 4, writer.maxQueuedFrames)
        assertEquals(4, writer.framesQueued())
        assertTrue("the track took what it had room for and then refused", route.refused > 0)
        assertEquals("the track never gave a byte back", route.capacityBytes, route.pendingBytes())
    }

    @Test
    fun `stage 0 keeps build 470 behaviour but counts what it discards`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = halfRateNanosPerByte)
        val writer = staged(clock, route, queueFrames = 0)
        repeat(fieldCycles) { writer.write(frame(it)) }

        assertTrue("this mode still loses audio to a refusing track", writer.framesDropped > 0)
        assertEquals("nothing is staged in this mode", 0, writer.framesQueued())
        assertEquals("every byte is either in the track or counted as lost",
            fieldCycles.toLong() * frameBytes, route.bytesIntoTrack + writer.bytesDropped)
        // Unlike the staged mode, what this mode loses is a frame's tail rather than a whole
        // frame: that is build 470's contract, kept here so an on-air A/B can measure the old
        // behaviour instead of re-reading its log lines, with the loss counted rather than only
        // warned about once per call.
        assertTrue("the loss falls inside frames, which is what staging exists to stop: " +
                "intoTrack=${route.bytesIntoTrack} dropped=${writer.bytesDropped}",
            route.bytesIntoTrack % frameBytes != 0L || writer.bytesDropped % frameBytes != 0L)
        assertTrue(writer.summary(), writer.summary().contains("drop(${writer.framesDropped})"))
    }

    // ── grid, life and death ───────────────────────────────────────────────────────────

    @Test
    fun `a long gap re-anchors the grid instead of chasing it`() {
        val clock = FakeClock()
        val route = Route(clock)
        val writer = staged(clock, route)
        repeat(10) { writer.write(frame(it)) }
        val handedBefore = writer.framesHanded
        clock.now += 500_000_000L                 // the thread was held: a GC, a blocked read
        val before = clock.now
        writer.write(frame(10))

        assertEquals(1L, writer.lateReanchors)
        assertEquals("no burst to catch up", 1L, writer.framesHanded - handedBefore)
        assertTrue("and it does not race the track", clock.now - before <= slotNanos)
    }

    @Test
    fun `close gives up what is staged, counts it, and writes nothing more`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = Long.MAX_VALUE / 8)
        val writer = staged(clock, route, queueFrames = 4)
        repeat(20) { writer.write(frame(it)) }
        val stagedFrames = writer.framesQueued()
        val refusedBefore = route.refused
        val droppedBefore = writer.framesDropped

        writer.close()

        assertEquals(0, writer.framesQueued())
        assertEquals("closing accounts for the staged frames as lost",
            droppedBefore + stagedFrames, writer.framesDropped)
        writer.write(frame(21))
        assertEquals("nothing reaches the track after close", refusedBefore, route.refused)
    }

    @Test
    fun `the alive flag lets the session stop without waiting for a wedged route`() {
        val clock = FakeClock()
        val route = Route(clock, nanosPerByte = Long.MAX_VALUE / 8)
        var alive = true
        val writer = PlaybackWriter(
            sink = PlaybackWriter.Sink { d, o, l -> route.write(d, o, l) },
            frameBytes = frameBytes,
            queueFrames = 4,
            alive = { alive },
            clock = clock,
        )
        repeat(12) { writer.write(frame(it)) }
        alive = false
        val before = clock.now
        writer.write(frame(99))
        assertEquals("no waiting once the call is over", before, clock.now)
    }

    @Test
    fun `a sink error is not retried forever`() {
        val clock = FakeClock()
        val writer = PlaybackWriter(
            sink = PlaybackWriter.Sink { _, _, _ -> -89 },   // the status AudioTrack returns
            frameBytes = frameBytes,
            clock = clock,
        )
        val before = clock.now
        repeat(20) { writer.write(frame(it)) }
        assertTrue("20 erroring frames must not stall the loop",
            clock.now - before <= 20 * slotNanos)
        assertEquals("and every one of them is counted, not merely warned about",
            20L, writer.sinkErrors)
    }

    /**
     * A track released underneath a live loop — which is what happens when a worker ignores
     * `interrupt()` and the stop thread gives up waiting for it — answers every later write with
     * `IllegalStateException`.  In build 470 that exception escaped into the playback loop, where
     * it was logged as `Playback error` once a cycle with no counter anywhere to show how many
     * frames it had cost.  Here it is the same event as a refused write: counted, no bytes taken,
     * the frame kept in order in front of the dead sink, and the loop's cadence untouched.
     */
    @Test
    fun `a sink that throws is counted and never reaches the loop`() {
        val clock = FakeClock()
        var calls = 0
        val writer = PlaybackWriter(
            sink = PlaybackWriter.Sink { _, _, _ ->
                calls++
                throw IllegalStateException("Error=-38")     // what a released AudioTrack throws
            },
            frameBytes = frameBytes,
            queueFrames = 4,
            clock = clock,
        )
        repeat(100) { writer.write(frame(it)) }
        // 99 slots, not 100: the first frame anchors the grid at the moment it arrives, so the
        // grid is only measurable from the second frame on.  Same figure the grid test above
        // pins for a live route.
        assertEquals("one slot per frame, even against a dead sink",
            99 * slotNanos, clock.now)
        assertEquals("every attempt is counted somewhere a field run can read",
            100L, writer.sinkErrors)
        assertEquals(0L, writer.accepted)
        assertEquals(0L, writer.framesHanded)
        assertEquals("the window fills and holds its ceiling, never grows",
            4, writer.maxQueuedFrames)
        assertEquals(4, writer.framesQueued())
        assertEquals(96L, writer.framesDropped)
        assertEquals("handed + lost + staged equals what was submitted",
            100L, writer.framesHanded + writer.framesDropped + writer.framesQueued())
        assertTrue(writer.summary(), writer.summary().contains("err(100)"))
    }

    /**
     * The partial hand-off case: the sink took part of a frame and then stopped answering.  What
     * it took stays taken, the remainder of that frame stays at the head of the FIFO in order,
     * and the frames behind it are refused whole — so a route that came back would be fed that
     * frame from where it stopped rather than a later frame welded onto its head.
     */
    @Test
    fun `a sink that dies mid-frame keeps that frame at the head of the queue`() {
        val clock = FakeClock()
        var calls = 0
        val writer = PlaybackWriter(
            sink = PlaybackWriter.Sink { _, _, _ ->
                calls++
                if (calls == 1) 100 else -89     // part of the first frame, then an error status
            },
            frameBytes = frameBytes,
            queueFrames = 4,
            clock = clock,
        )
        repeat(40) { writer.write(frame(it)) }
        assertEquals("nothing was handed over whole", 0L, writer.framesHanded)
        assertEquals("and the 100 bytes the sink did take are still counted as delivered",
            100L, writer.accepted)
        assertEquals("the half-handed frame is still waiting at the head, plus three whole ones",
            4, writer.framesQueued())
        assertEquals(36L, writer.framesDropped)
        assertEquals("bytes lost are counted as whole frames, never pieces",
            writer.framesDropped * frameBytes, writer.bytesDropped)
        assertEquals("handed + lost + staged equals what was submitted",
            40L, writer.framesHanded + writer.framesDropped + writer.framesQueued())
        assertTrue(writer.summary(), writer.summary().contains("err(40)"))

        writer.close()
        assertEquals("hang-up closes the account: the staged four, partial head included, are lost",
            40L, writer.framesDropped)
        assertEquals("the sink's 100 bytes are still counted as taken", 100L, writer.accepted)
    }

    @Test
    fun `the summary line reports every counter a field run then argues about`() {
        val clock = FakeClock()
        val route = Route(clock)
        val writer = staged(clock, route)
        repeat(50) { writer.write(frame(it)) }
        val s = writer.summary()
        assertTrue(s, s.startsWith("wr:out(50)/held(0)/drop(0)/refus(0)/err(0)/late(0)/maxQ(1)"))
    }

    // ── measurements pinned so nobody argues them later ────────────────────────────────

    @Test
    fun `the injector track is only six of our frames deep, which is why it jams`() {
        assertEquals(injectorTrackBytes, 2064)
        assertEquals("6.45 frames of buffer, so one bad cadence week jams it",
            6, injectorTrackBytes / frameBytes)
        assertEquals("one frame at real time is exactly one slot",
            slotNanos, realTimeNanosPerByte * frameBytes)
        assertEquals("the field route ran at half that rate",
            halfRateNanosPerByte, realTimeNanosPerByte * 2)
    }
}
