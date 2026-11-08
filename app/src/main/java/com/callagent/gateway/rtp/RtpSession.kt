package com.callagent.gateway.rtp

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.provider.Settings
import android.util.Log
import com.callagent.gateway.DeviceProfile
import com.callagent.gateway.CallRedirectRoute
import com.callagent.gateway.RootCapture
import com.callagent.gateway.RootPcmRoute
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP session: handles bidirectional audio between speaker/mic and remote RTP endpoint.
 *
 * On S4 Mini (MSM8930), VOICE_DOWNLINK routes through the physical mic
 * (HAL usecase incall-rec-downlink → voice-handset-mic).  Speaker mode
 * plays GSM caller audio through the speaker; the mic picks it up.
 *
 * Audio path:
 * - Capture: VOICE_DOWNLINK via mic → gain boost → encode → RTP → SIP agent.
 * - Playback: RTP → decode → AudioTrack (usage from profile).
 *   MSM8930: USAGE_MEDIA → STREAM_MUSIC.  incall_music_enabled=true
 *   injects STREAM_MUSIC into voice TX, bypassing modem AEC.
 *   Exynos 9820: USAGE_VOICE_COMMUNICATION → STREAM_VOICE_CALL.
 *   Samsung HAL may route this into the modem uplink directly.
 *
 * The Magisk module disables Android's audio concurrency restrictions.
 * Uses G.722 codec for wideband (16 kHz), falls back to PCMA (G.711 A-law).
 */
class RtpSession(
    private val context: Context,
    private val localPort: Int,
    private val remoteAddr: String,
    private val remotePort: Int,
    private val payloadType: Int = RtpPacket.PT_PCMA,
    /** A UDP socket the bridge bound before it named this port in its SDP and is
     *  handing over, so the port the peer was told about is already ours.  Null
     *  means this session has to bind one itself — see [start]. */
    private val mediaSocket: DatagramSocket? = null
) {
    private val running = AtomicBoolean(false)
    /** Ensures the stop callback is delivered once, including a session that
     * failed during startup and never reached the running state. */
    private val stopNotified = AtomicBoolean(false)
    /** The four loop threads, so [stop] can wait for them to leave the
     *  AudioRecord/AudioTrack before those get released. */
    @Volatile private var workers: List<Thread> = emptyList()
    private var socket: DatagramSocket? = null
    /** Where the caller is read from: an AudioRecord, or a PCM over root. */
    @Volatile private var capture: CaptureSource? = null
    private var audioTrack: AudioTrack? = null
    /** 1 or 2, per [DeviceProfile.playbackStereo]. */
    private var playbackChannels = 1
    /** The tinypcm processes carrying the call when the profile routes it over
     *  raw PCMs ([DeviceProfile.rootPcm]); null on the AudioRecord/AudioTrack
     *  path, which is every other device. */
    @Volatile private var pcmCapture: RootPcm? = null
    @Volatile private var pcmPlayback: RootPcm? = null
    /** When the next playback frame is due, on the root PCM path. */
    private var nextPlayNs = 0L
    /** The call-redirection policy carrying the call on the Exynos 990
     *  ([DeviceProfile.callRedirect]): its sink is [capture] and its injector is
     *  [audioTrack], so the loops do not otherwise know they are not looking at
     *  a HAL stream.  Null on every other route.
     *
     *  Owned by the session: registered in initAudio(), withdrawn in the stop()
     *  teardown thread, after the two streams it created are released.
     *
     *  On this device it is normally not registered here at all but handed over
     *  already registered — see [preArmedRedirect]. */
    @Volatile private var callRedirect: CallRedirection? = null

    /** A call-redirection policy the bridge registered before it dialled the GSM
     *  leg, waiting here for this session to adopt it.
     *
     *  AudioFlinger decides whether a client belongs to a policy mix at the
     *  moment the client is created.  The two clients that carry a call on this
     *  device are created by the modem's media path when the call starts, so a
     *  mix registered after the call is ACTIVE matches nothing: the sink reads
     *  digital silence and the injector has no record client to feed, which
     *  shows up as `capRMS=0` for the whole call and `AudioTrack nonblocking
     *  write timed out` on every frame written.  The air-proven harness
     *  registered the policy and then dialled, so the dial does the same and the
     *  registration arrives here.  Null means no pre-arm happened (a call this
     *  class did not dial, or a pre-arm the framework refused), and
     *  [startCallRedirect] falls back to registering on its own. */
    @Volatile var preArmedRedirect: CallRedirection? = null

    // ── Monitor ("snoop") ────────────────────────────────────────────
    // A second track on the phone's speaker carrying both sides of the call
    // mixed together, for listening in without touching the bridge.  It cannot
    // work by unmuting the call: the caller arrives on the modem downlink and
    // the agent goes out to Telephony Tx, so neither passes through the
    // speaker any more.  Both sides are therefore mixed here in software.
    //
    // The microphone stays muted throughout — this only ever plays audio out.
    @Volatile private var monitorTrack: AudioTrack? = null
    /** Most recent caller frame, 16-bit mono at [captureRate], for mixing. */
    @Volatile private var lastCallerFrame: ByteArray? = null

    /** Silence the agent towards the caller without disturbing the bridge.
     *  RTP keeps flowing in both directions and the SIP call stays up; only the
     *  audio written towards the modem is zeroed.  Intended for cutting the
     *  agent off mid-sentence when it is saying something it should not. */
    @Volatile private var agentMuted = false

    // Codec
    private val g722Encoder = G722Codec()
    private val g722Decoder = G722Codec()

    // RTP state
    private var txSequence = 0
    private var txTimestamp = 0L
    private val txSsrc = (Math.random() * 0xFFFFFFFFL).toLong()

    // ── SRTP ────────────────────────────────────────────
    // Set before start() when the call negotiated SDES, left null for plain
    // RTP.  One context per direction: they hold independent keys and their
    // own rollover counters, and each is touched by exactly one thread --
    // send by the capture loop, receive by the receive loop.

    @Volatile var srtpSend: SrtpContext? = null
    @Volatile var srtpRecv: SrtpContext? = null

    /** Packets dropped because they failed authentication. */
    @Volatile var srtpAuthFailures = 0L
        private set

    // ── DTMF (RFC 4733) ──
    // Payload types carrying telephone-event: the one in the peer's SDP plus
    // the 101 this gateway offers itself.  Set before start().
    @Volatile var telephoneEventPts: Set<Int> = setOf(RtpPacket.PT_TELEPHONE_EVENT)
    /** Receives key presses from the SIP side; null drops them as before. */
    @Volatile var dtmfDetector: DtmfDetector? = null
    @Volatile var rxDtmfPackets = 0L
        private set

    /**
     * Encrypt if this call is protected, then send.
     *
     * If protect() cannot produce a packet the packet is dropped, never sent
     * in the clear: falling back would silently downgrade a call the user was
     * told is encrypted, which is worse than losing 20ms of audio.
     */
    private fun sendRtp(data: ByteArray, addr: InetAddress, port: Int) {
        val ctx = srtpSend
        val out = if (ctx == null) data else (ctx.protect(data) ?: return)
        socket?.send(DatagramPacket(out, out.size, addr, port))
    }

    // Symmetric RTP: latch onto the actual source address of received packets
    @Volatile private var latchedAddr: InetAddress? = null
    @Volatile private var latchedPort: Int = 0

    /** Received packets, in arrival order, until the playback loop decodes them
     *  into the sample reservoir ([PlaybackSlip]).
     *
     *  Four frames was too tight to be workable.  Measured over a 33s call it
     *  sat at 3-4 — pinned at capacity — and logged 87 dropped frames *and* 90
     *  underruns out of ~1650: about three audible glitches a second, because
     *  at that depth every burst overflows and every gap empties it.  There is
     *  no depth that both absorbs real network jitter and holds 80ms.
     *
     *  A shorter stats window on the S20+ shows the same shape in miniature:
     *  `play=411 drop=6 under=3 trim=3` inside eight seconds, i.e. a glitch about
     *  every second while the queue read `jbuf=0` — burst arrivals, and a drain
     *  that took one packet at a time and treated a late one as a lost one.
     *  This phone is a Wi-Fi client in power save; its driver is seen powering
     *  the PCIe link down between traffic, so what arrives is bursts separated by
     *  gaps, exactly the pattern that measurement shows.
     *
     *  Since the loop now drains this queue into a sample reservoir every cycle,
     *  the queue is only a rendezvous between the socket thread and the playback
     *  thread: a burst lands here and is absorbed by the reservoir's elasticity,
     *  not by the queue's depth.  The capacity stays as the socket thread's
     *  backstop for the case where the playback thread is stalled. */
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(JITTER_CAPACITY)

    // RTP inactivity tracking
    @Volatile private var lastRtpReceivedTime = 0L
    private val RTP_TIMEOUT_MS = 30_000L  // 30 seconds with no RTP = dead call

    // Packet counters and audio diagnostics
    @Volatile var txPacketCount = 0L; private set
    @Volatile var rxPacketCount = 0L; private set
    @Volatile var playbackFrames = 0L; private set
    @Volatile var captureRms = 0; private set
    @Volatile var playbackRms = 0; private set
    @Volatile var rawCaptureRms = 0; private set  // Before echo gate — 0 means source is silent
    /** How many playback frames since the last stats line were above
     *  [LOUD_FRAME_FLOOR], and the loudest of them.
     *
     *  A duration witness, not a level judgement.  `playRMS` is the level of the
     *  single most recent frame, so on build 461 a press that the host tool had
     *  (wrongly) kept emitting for the whole call looked identical to one that
     *  lasted the requested 200 ms: both read one line at press level.  The count
     *  times the frame length is what the handset was actually being asked to
     *  speak, and it is what the AudioFlinger power plateau has to agree with. */
    @Volatile private var windowLoudFrames = 0L
    @Volatile private var windowMaxRms = 0
    /** Frames discarded because the packet queue was already full — the far
     *  end is arriving faster than playback drains it, or in bursts. */
    @Volatile private var rxDropped = 0L
    /** Frames that contained invented silence because the reservoir had run dry,
     *  including the frames written while it refills afterwards — the audible
     *  gaps, i.e. late or lost packets from the far end.
     *
     *  Counted per frame, not per sample: what matters to the listener is that a
     *  frame went missing, and one is now as short as the slip can make it. */
    @Volatile private var underruns = 0L
    /** Frames' worth of samples cut out at the latency ceiling to keep a burst
     *  from parking its latency in the queue.  [PlaybackSlip.dropTo] ramps the
     *  seam; the old code deleted a whole packet at a hard discontinuity. */
    @Volatile private var trimmedFrames = 0L
    /** The current call's sample reservoir, for the stats line: with the packet
     *  queue emptied into it every cycle, this is where the buffered time now
     *  lives.  Written by the playback thread, read by the stats thread. */
    @Volatile private var playbackReservoir: PlaybackSlip? = null
    @Volatile var audioSourceName = "none"; private set
    @Volatile private var playbackUsageName = "MEDIA"

    /**
     * The RTP timeline gate in front of [jitterBuffer] — see [RtpSequencer].
     *
     * It is what stops a duplicated or late packet from being played a second
     * time and puts reordered packets back in order.  Before it, an audio packet
     * was defined by when it turned up rather than by where it belongs, and a
     * Wi-Fi path that delivers bursts with holes in them (see [jitterBuffer])
     * therefore made the far end hear its own words again: the "robotic audio
     * repetition" reported from the field on 2026-11-06.
     *
     * Off with `settings put global callagent_rtp_gate 0`, so a real call can be
     * run both ways against the same network path.
     *
     * The clock is the one the SDP negotiated — [wideband] is a getter over
     * `payloadType`, a constructor property, so it is already valid here.
     */
    private val rtpGate = RtpSequencer(clockRate = if (wideband) 16000 else 8000)
    private val gateOn = globalFlag(SETTING_RTP_GATE, 1) != 0

    /** Whether the sample-accurate slip ([PlaybackSlip.stepFor]) is allowed to
     *  read at a fractional rate, or the reservoir is drained at exactly 1:1 and
     *  only the ceiling ever removes time.
     *
     *  Worth switching on a call: the slip resamples by at most [PlaybackSlip.MAX_SLIP]
     *  (0.6 %, an order of magnitude too small to be heard as repetition), while
     *  the reservoir and its prefill stay in place either way.  That is what makes
     *  an on-air A/B able to *exclude* it rather than leave it argued about. */
    private val slipOn = globalFlag(SETTING_PLAYBACK_SLIP, 1) != 0

    /** How the playback loop feeds its `AudioTrack`.  This is the knob that
     *  decides what happens to the part of a frame the track will not take:
     *
     *  - unset, or `-2` — follow the route this call took: [PlaybackWriter] with
     *    [PlaybackWriter.DEFAULT_QUEUE_FRAMES] of room on the injector mix
     *    (`callagent_uplink_via=0`, the one route measured refusing writes), and
     *    [writePlaybackFrame] exactly as shipped on the acoustic routes (via 1 and
     *    2), where the same loop ran 22.6 s of a call with not one refused byte;
     *  - `0` to `10` — [PlaybackWriter] with that many frames of room.  `0` still
     *    holds the 20 ms cycle but a refusal discards, which is build 470's
     *    contract with the loss counted instead of only warned about;
     *  - `-1` — [PlaybackWriter] out of it entirely on any route, so the loop runs
     *    the write [writePlaybackFrame] as shipped.
     *
     *  The default follows the route rather than being one number for every call
     *  because the two routes have opposite evidence: one is known to lose 4.83 s
     *  of a 22.6 s call and the other is the configuration the gateway has shipped
     *  and relied on.  An acoustic call with no setting written therefore runs
     *  byte-for-byte what build 470 ran, and any of the three modes can still be
     *  forced for one call with `settings put global callagent_play_stage`.
     *
     *  All of it is reachable from one `settings put` because this is the mechanism
     *  that discarded 4.83 s of one agent's speech on the injected route on
     *  2026-11-07 (`gateway-test/out/miciso-try-via0-20261107-063700`, 473 lines of
     *  `AudioTrack nonblocking write timed out`), and the question of how much of
     *  that was ours has to be answerable on a single call rather than argued about
     *  afterwards.  See [PlaybackWriter] for what the two field measurements were,
     *  and `PlaybackWriterTest` for the harness that reproduces them offline. */
    private val playStage: Int =
        globalFlag(SETTING_PLAY_STAGE, PLAY_STAGE_FOLLOW_ROUTE).let { stage ->
            if (stage != PLAY_STAGE_FOLLOW_ROUTE) stage
            else if (globalFlag(CallRedirection.UPLINK_SETTING, 0) == 0)
                PlaybackWriter.DEFAULT_QUEUE_FRAMES
            else -1
        }.coerceAtMost(PlaybackWriter.MAX_QUEUE_FRAMES)

    /** The staged writer for this call's track: null while build 470's own write
     *  path is selected ([playStage] is -1), or when the session plays to the root
     *  PCM pipe instead of a track.  Written on the playback thread, read by the
     *  stats thread for its counters. */
    private var playWriter: PlaybackWriter? = null

    /** Where [playbackConsumed] last read the track's own read cursor to. */
    private var playedFramesAtLastReport = -1L

    // Capture and playback rates may differ.  VOICE_CALL on MSM8930
    // only initializes at 8 kHz; G.722 decoding outputs 16 kHz PCM.
    private var captureRate = 8000
    private var playbackRate = 8000

    // Audio session ID from AudioRecord (for logging/diagnostics)
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    // Silence detection: track audio source IDs that produce no audio so we
    // can fall back to alternatives.  E.g., VOICE_CALL initializes on
    // Exynos 9820 but delivers silence — the HAL doesn't route voice data
    // to the capture path.  Falling back to MIC captures the caller's voice
    // acoustically from the speaker.
    private val silentSourceIds = mutableSetOf<Int>()
    @Volatile private var currentSourceId: Int = -1
    private data class SourceConfig(val source: Int, val name: String, val rate: Int)

    /** G.722 carries 16 kHz audio; the G.711 codecs carry 8 kHz. */
    private val wideband get() = payloadType != RtpPacket.PT_PCMA && payloadType != RtpPacket.PT_PCMU
    // Silence detection thresholds — only counted during non-echo periods
    // (when decayingPlaybackRms <= echoGateThreshold) to avoid false resets
    // from incall_music echo leaking back through VOICE_CALL capture.
    private val SILENCE_RMS_THRESHOLD = 10   // Below this = truly dead source (ADC noise floor)

    /** How long to let a source settle before judging its frame rate. */
    private val RATE_CHECK_AFTER_MS = 4000L

    /** Below this share of the expected 50 frames/s, the source is failing.
     *  Generous on purpose: a healthy source sits at ~100%, and the case this
     *  catches measured 70%, so anything near the threshold is unambiguous. */
    private val RATE_MIN_PERCENT = 85L
    private val SILENCE_FRAME_LIMIT get() = profile.captureSilenceFrames

    var listener: Listener? = null

    interface Listener {
        fun onRtpStarted()
        fun onRtpStopped()
        fun onRtpError(error: String)
        fun onRtpTimeout() {}  // No RTP received for RTP_TIMEOUT_MS
        fun onRtpStats(stats: String) {}  // Periodic detailed stats
    }

    fun start() {
        if (running.getAndSet(true)) return
        stopNotified.set(false)
        Log.i(TAG, "Starting RTP session: local=$localPort remote=$remoteAddr:$remotePort pt=$payloadType")

        try {
            val held = mediaSocket
            val sock = if (held != null) {
                // The bridge bound this port before it put it into the SDP and is
                // handing it over.  The binding is not redone — that is the whole
                // point: between the SDP going out and this moment the peer may
                // already be sending, and a port that is only probed and closed
                // (the old allocateRtpPort) is listening by nobody during that
                // window.  Only the options this class needs are set here.
                held.apply {
                    soTimeout = 100
                    receiveBufferSize = 262144
                }
            } else DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
                soTimeout = 100
                receiveBufferSize = 262144
            }
            socket = sock
            // Ask the kernel what it has rather than repeating what we asked for.
            // Every unexplained air test in this project turned on these two
            // numbers disagreeing: on 2026-11-06 the SDP named 192.168.0.6:33642
            // and the host measured our RTP arriving from :44131, while the log
            // said only "local=33642" — the number we asked for, not the number
            // in the packet.
            if (sock.localPort != localPort) {
                Log.e(TAG, "RTP socket is on port ${sock.localPort}, NOT the $localPort named in our SDP — the peer will send to a port nothing holds")
            } else {
                Log.i(TAG, "RTP socket ready: port ${sock.localPort} bound on ${sock.localSocketAddress}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTP socket on port $localPort: ${e.message}")
            // A socket handed over but never adopted is ours to close, or it keeps
            // the port out of reach for the next call.
            try { mediaSocket?.close() } catch (_: Exception) {}
            running.set(false)
            listener?.onRtpError("Socket bind failed: ${e.message}")
            return
        }

        if (!initAudio()) {
            // Close it here: stop() takes the early return for a session that
            // never reached the running state, so a socket left bound here holds
            // the port the SDP just named — and with SO_REUSEADDR the next
            // call's port probe calls that port free.  One dead session then
            // steals the next call's inbound RTP: rx=0, silent far end.
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            running.set(false)
            Log.e(TAG, "RTP audio never came up — socket on port $localPort released")
            return
        }

        // Send initial RTP keepalive to punch NAT pinhole before audio starts
        try {
            val remoteInet = InetAddress.getByName(remoteAddr)
            val silence = RtpPacket(payloadType, 0, 0, txSsrc, ByteArray(160)).encode()
            sendRtp(silence, remoteInet, remotePort)
            Log.i(TAG, "Sent NAT punch-through packet to $remoteAddr:$remotePort")
        } catch (e: Exception) {
            Log.w(TAG, "NAT punch-through failed: ${e.message}")
        }

        lastRtpReceivedTime = System.currentTimeMillis()
        workers = listOf(
            loopThread("RTP-Recv-$localPort") { receiveLoop() },
            loopThread("RTP-Play-$localPort") { playbackLoop() },
            loopThread("RTP-Capt-$localPort") { captureInitAndLoop() },
            loopThread("RTP-Timeout-$localPort") { timeoutLoop() }
        )
        workers.forEach { it.start() }

        listener?.onRtpStarted()
    }

    /**
     * A loop thread that cannot take the process down with it.
     *
     * [stop] interrupts these threads, and several of them sleep outside a
     * try/catch — an InterruptedException escaping a thread body reaches the
     * default uncaught handler, which on Android kills the process.  Anything
     * thrown after the session has been stopped is expected and just noted.
     */
    private fun loopThread(name: String, body: () -> Unit): Thread =
        Thread({
            try {
                body()
            } catch (e: InterruptedException) {
                Log.d(TAG, "$name interrupted")
            } catch (e: Throwable) {
                if (running.get()) Log.e(TAG, "$name died: ${e.javaClass.simpleName}: ${e.message}", e)
                else Log.d(TAG, "$name ended during shutdown: ${e.message}")
            }
        }, name)

    /**
     * Initialize AudioRecord and AudioTrack.
     *
     * AudioRecord: Tries VOICE_DOWNLINK first (on S4 Mini this routes through
     * the physical mic via incall-rec-downlink HAL usecase).
     * AudioTrack: USAGE_MEDIA (STREAM_MUSIC) so that Qualcomm's
     * incall_music_enabled=true parameter injects it into voice TX (uplink).
     * USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL which the HAL
     * does NOT inject via incall_music — that's why SIP→GSM was silent.
     */
    private fun initAudio(): Boolean {
        // Before anything is opened: in-call recording and the per-session
        // voice mutes are gated on the HAL's is_call_active flag, and the HAL
        // chooses the input usecase when the record stream is created.  Telling
        // it after AudioRecord exists is too late — VOICE_CALL is already bound
        // to a plain capture path and returns silence.
        setHalCallState(2)
        applyHalCallParams()

        // Telecom may still be completing the earpiece -> speaker transition
        // when the GSM call first becomes ACTIVE.  On the Pixel 4a, opening an
        // AudioTrack during that window can produce a dead IAudioTrack which
        // AudioFlinger recreates as a normal output instead of the
        // incall_music_uplink output.  The framework can then report
        // routedDevice=TELEPHONY even though the modem injection usecase was
        // never opened.  Let the route settle before creating either stream.
        val settleDelay = profile.audioRouteSettleDelayMs
        if (settleDelay > 0) {
            Log.i(TAG, "Waiting ${settleDelay}ms for audio route to settle")
            try {
                Thread.sleep(settleDelay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        // Playback rate matches codec output rate.  G.722 decodes to 16 kHz.
        playbackRate = when (payloadType) {
            RtpPacket.PT_PCMA, RtpPacket.PT_PCMU -> 8000
            else -> 16000  // G.722
        }

        // Above the HAL rather than around it: where the profile says so, the
        // call's own AudioFlinger clients are taken over by an audio policy, and
        // that policy is what hands us both streams — so it has to run before
        // any stream of ours exists.  See DeviceProfile.callRedirect.
        profile.callRedirect?.let { route ->
            if (startCallRedirect(route)) return true
            // Deliberately no fallback to the code below.  On the firmware this
            // route was built for, every AudioRecord telephony source reads
            // silence and no playback track reaches the modem; opening a
            // microphone and a speaker track instead would keep the session
            // alive looking healthy while carrying something else entirely.
            Log.e(TAG, "Call redirection unavailable — no audio route for this call")
            listener?.onRtpError("Call redirection failed (see logcat: CallRedirection)")
            return false
        }

        profile.rootPcm?.let { route ->
            if (startRootPcm(route)) return true
            listener?.onRtpStats("Root PCM path failed — falling back to the audio HAL")
        }

        // Try telephony capture sources, then mic sources.
        // When using G.722 (wideband), prefer 16kHz capture to avoid upsampling
        // artifacts.  HD Voice (AMR-WB/EVS) provides native 16kHz audio.
        val configs = mutableListOf<SourceConfig>()

        // VOICE_CALL (source 4): captures uplink+downlink mixed digitally.
        // Best option on MSM8930 — if it initializes, it provides clean
        // digital capture of the caller's voice.  Requires CAPTURE_AUDIO_OUTPUT.
        if (wideband) {
            // G.722: prefer 16kHz native capture — avoids upsampling artifacts
            // in the 4-8kHz upper band that cause AI agent false interruptions.
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        // Mic-based sources: in speaker mode, the physical mic picks up the
        // caller's voice from the speaker.  This is acoustic coupling — not
        // ideal but functional when digital capture sources fail.
        // VOICE_RECOGNITION bypasses noise suppression that can mute call audio.
        // VOICE_DOWNLINK ahead of the acoustic sources when the profile says
        // it works here.  It captures the caller's voice digitally, off the
        // modem downlink, which is what lets the physical mic stay muted — on
        // the acoustic path the mic is the capture source, so the caller hears
        // the room and the agent hears itself through the speaker.
        if (profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferUnprocessedMic) {
            // Raw mic, ahead of the voice-tuned sources: no AEC, no noise
            // suppression, no AGC.  When the caller reaches us as sound out of
            // the phone's own speaker, those are all working against us.
            configs.add(SourceConfig(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", 8000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", 8000))
        }
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))

        if (profile.voiceDownlinkWorks) {
            // Where the modem downlink is capturable, it is the only source we
            // want: VOICE_CALL mixes uplink AND downlink, and the uplink now
            // carries the agent's own injected voice, so the agent hears itself
            // folded into the caller — which is what the far end perceives as
            // noise.  The downlink alone is the caller.  Acoustic sources stay
            // behind it purely as a last resort.
            configs.removeAll { it.source == MediaRecorder.AudioSource.VOICE_CALL }
            val downlink = configs.filter {
                it.source == MediaRecorder.AudioSource.VOICE_DOWNLINK
            }
            configs.removeAll(downlink)
            configs.addAll(0, downlink)
        }
        // VOICE_DOWNLINK (source 3): DEAD LAST — on MSM8930 it initializes
        // successfully (STATE_INITIALIZED) but captures SILENCE because the
        // Incall_Rec mixer controls don't exist on this SoC.  If it were
        // earlier in the list, it would "win" over mic-based sources that
        // actually work.  Kept only for devices where it genuinely works.
        if (!profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }

        // Same preference as the fallback list, applied here too -- this is
        // the list that picks the source a call actually opens on, and
        // reordering only the fallback list left every call still starting on
        // VOICE_CALL@16k and discovering it was dead ~11s later.  The agent
        // heard silence for those 11 seconds of every call.
        if (profile.preferVoiceRecognition) {
            val vr = configs.filter { it.source == MediaRecorder.AudioSource.VOICE_RECOGNITION }
            configs.removeAll(vr)
            configs.addAll(0, vr)
        }

        var record: AudioRecord? = null
        var usedRate = 8000

        // Where the caller is read over root, no AudioRecord is opened up
        // front: it would only hold a HAL usecase that delivers nothing, to be
        // torn down as soon as tinycap is up.  Verified on the Pixel 7 — the
        // telephony-rx PCM carries the caller with no AudioRecord open.  The
        // search in captureInitAndLoop opens one if root capture fails.
        if (profile.rootCapture != null) configs.clear()

        // No appops assertion and no propagation sleep here.  CallOrchestrator
        // .startRtp() runs the very same grant sequence synchronously
        // immediately before this, so doing it again was a second round of
        // eight root commands — each of pm/appops/cmd forks an app_process —
        // followed by a blind 500ms wait, on the path between answering the
        // call and the first frame of audio.  The wait was there for a retry
        // loop that no longer exists: this only ever made one attempt.
        // captureInitAndLoop() still re-asserts and retries if nothing here
        // initialises, which is the case the retry logic was really for.
        for (cfg in configs) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: invalid minBuf=$minBuf")
                    continue
                }
                val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2) // 40ms (two RTP frames)
                val rec = newAudioRecord(cfg.source, cfg.rate, bufSize)
                if (profile.captureFromTelephonyRx) {
                    routeCaptureToTelephonyRx(rec)
                }
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    usedRate = cfg.rate
                    audioSourceName = cfg.name
                    currentSourceId = cfg.source
                    Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize)")
                    break
                } else {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
            }
        }

        if (record != null) {
            capture = AudioRecordSource(record, audioSourceName)
            audioSessionId = record.audioSessionId
            captureRate = usedRate

            // Prime the Qualcomm in-call capture path before creating the
            // playback track.  On Pixel 4a the order matters: creating the
            // incall_music track first can leave the modem TX output waiting
            // on a voice path that has not been opened yet, producing random
            // SIP->GSM one-way calls after the first call.
            if (profile.primeCaptureBeforePlayback) try {
                record.startRecording()
                Log.i(TAG, "AudioRecord primed before AudioTrack: state=${record.recordingState}")
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord prime failed: ${e.message}")
            }

            // Diagnostic sweep — off unless explicitly enabled, see
            // audioDiagnosticsEnabled().
            if (audioDiagnosticsEnabled()) logCaptureDiagnostics(record)
        }

        // Minimum buffer for lowest latency.  incall_music injects
        // AudioTrack output digitally into the modem uplink — there is no
        // acoustic speaker→mic path, so deep-buffer headroom is unnecessary.
        // Writing a frame every cycle, with the reservoir holding
        // JITTER_TARGET_FRAMES of headroom behind it, is what prevents underruns.
        val playChannelMask = if (profile.playbackStereo)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        playbackChannels = if (profile.playbackStereo) 2 else 1
        val minPlayBuf = AudioTrack.getMinBufferSize(
            playbackRate, playChannelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // A minimum-sized buffer qualifies for the fast path, which the
        // Qualcomm HAL serves on MultiMedia5 — a front-end whose audio goes to
        // the speaker, not into the voice uplink.  Asking for a larger buffer
        // pushes the HAL to deep-buffer-playback on MultiMedia1, which is the
        // front-end the Incall_Music mixer injects from.
        val playBufSize = if (profile.playbackBufferMs > 0) {
            maxOf(minPlayBuf, playbackRate * 2 * playbackChannels * profile.playbackBufferMs / 1000)
        } else {
            minPlayBuf
        }

        // Profile-controlled playback usage:
        // USAGE_MEDIA (default / MSM8930): Maps to STREAM_MUSIC.  Qualcomm's
        // incall_music_enabled=true injects STREAM_MUSIC into voice TX.
        // USAGE_VOICE_COMMUNICATION (Exynos 9820): Maps to STREAM_VOICE_CALL.
        // Samsung's HAL may route this into the modem uplink when a voice
        // call is active.  On Exynos, no incall_music mixer exists, so
        // USAGE_MEDIA only plays on the speaker without reaching the modem.
        //
        // IMPORTANT: Do NOT use PERFORMANCE_MODE_LOW_LATENCY here.
        // Low-latency forces the HAL to use "low-latency-playback" usecase
        // which maps to MultiMedia5.  On MSM8930 (Galaxy S4 Mini), only
        // MultiMedia1 and MultiMedia2 have Incall_Music mixer controls.
        // MultiMedia5 has no incall_music mixer, so audio plays on the
        // earpiece but is NEVER injected into the modem uplink.
        // Using default (deep-buffer-playback → MultiMedia1) ensures the
        // Incall_Music Audio Mixer MultiMedia1 routes audio to the caller.
        val usage = if (profile.playbackUsage >= 0) profile.playbackUsage
                    else AudioAttributes.USAGE_MEDIA
        val contentType = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
            AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        playbackUsageName = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "usage=$usage"
        }
        // Must happen before the track exists on HALs that pick the output
        // usecase at creation time — see DeviceProfile.incallMusicBeforeTrack.
        if (profile.incallMusicBeforeTrack) {
            enableIncallMusic()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(playChannelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (profile.playbackLowLatency) {
                    // Deliberately no setBufferSizeInBytes here: asking for
                    // low latency and then handing the framework a large
                    // buffer is contradictory, and the request is silently
                    // ignored.  Let it size the buffer itself.
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                } else {
                    setBufferSizeInBytes(playBufSize)
                }
            }
            .build()
        audioTrack = track

        if (profile.playbackToTelephonyTx) {
            routeToTelephonyTx(track)
        }

        // No platform AEC — AudioTrack is on USAGE_MEDIA (different stream
        // from AudioRecord), so platform AEC can't reference it anyway.
        // For VOICE_DOWNLINK, AEC was over-canceling (capRMS dropped from 252 to ~5).
        // Echo cancellation is handled by Asterisk on the server side.

        // Log what was actually granted, not what was asked for: the
        // low-latency request can be refused silently.
        val grantedFrames = try { track.bufferSizeInFrames } catch (_: Exception) { -1 }
        val grantedMs = if (grantedFrames > 0) grantedFrames * 1000 / playbackRate else -1
        Log.i(TAG, "Audio init: playRate=$playbackRate playUsage=$playbackUsageName " +
            "playBuf=${grantedFrames}f/${grantedMs}ms (asked=$playBufSize min=$minPlayBuf " +
            "lowLatency=${profile.playbackLowLatency}) ch=$playbackChannels " +
            "capture=${if (capture != null) audioSourceName else "deferred"} profile=${profile.name}")
        return true
    }

    /**
     * Open the call over raw PCMs: route, start both tinypcm processes, then
     * switch the call over to them.  See [DeviceProfile.rootPcm].
     *
     * Both directions run at the codec's rate; the DSP converts to and from
     * the call's own.  Undoes its routing and returns false if either process
     * does not come up, so the caller can fall back to the HAL.
     */
    private fun startRootPcm(route: RootPcmRoute): Boolean = synchronized(ROOT_PCM_LOCK) {
        val setup = DeviceProfile.resolveCmd(route.setupCmd)
        if (setup.isEmpty()) {
            listener?.onRtpStats("Root PCM: tinymix not found")
            return false
        }
        listener?.onRtpStats("Root PCM setup: ${RootShell.execForOutput(setup, timeoutMs = 8000).trim()}")

        val rate = playbackRate
        // 10ms periods; the pipe is mono, the PCMs stereo.
        val common = "-D ${route.card} -c ${route.channels} -r $rate -b 16 -p ${rate / 100} -m -w 3000"
        val cap = RootPcm.start(TINYPCM, "cap $common -d ${route.captureDevice} -n 4 -q 10", "cap")
        val pacer = if (route.pacerDevice >= 0) " -P ${route.pacerDevice}" else ""
        val play = cap?.let {
            RootPcm.start(TINYPCM, "play $common -d ${route.playDevice} -n 3 -q 6$pacer", "play",
                banners = if (pacer.isEmpty()) 1 else 2)
        }
        if (cap == null || play == null) {
            val why = (play ?: cap)?.recentMessages() ?: ""
            cap?.stop()
            play?.stop()
            RootShell.exec(DeviceProfile.resolveCmd(route.teardownCmd), timeoutMs = 8000)
            listener?.onRtpStats("Root PCM did not start: ${why.ifEmpty { "see logcat RootPcm" }}")
            return false
        }

        pcmCapture = cap
        pcmPlayback = play
        captureRate = rate
        playbackChannels = 1
        nextPlayNs = 0L
        audioSourceName = "PCM C${route.card}D${route.captureDevice}"
        playbackUsageName = "PCM C${route.card}D${route.playDevice}"
        val connect = RootShell.execForOutput(DeviceProfile.resolveCmd(route.connectCmd), timeoutMs = 8000)
        Log.i(TAG, "Root PCM up at $rate Hz: capture=$audioSourceName playback=$playbackUsageName")
        listener?.onRtpStats("Root PCM up at $rate Hz, call switched over: ${connect.trim()}")
        return true
    }

    /**
     * Shut the raw PCM path down in the order the DSP needs: the call gets its
     * own audio back while our streams still run, the streams close, the
     * routes they needed go.
     */
    private fun stopRootPcm(): Unit = synchronized(ROOT_PCM_LOCK) {
        val route = profile.rootPcm ?: return
        val cap = pcmCapture
        val play = pcmPlayback
        if (cap == null && play == null) return
        RootShell.exec(DeviceProfile.resolveCmd(route.disconnectCmd), timeoutMs = 8000)
        pcmCapture = null
        pcmPlayback = null
        cap?.stop()
        play?.stop()
        RootShell.exec(DeviceProfile.resolveCmd(route.teardownCmd), timeoutMs = 8000)
        Log.i(TAG, "Root PCM closed")
    }

    /**
     * Open the call through the framework's call-redirection policy: register the
     * two mixes, then take the sink and the source they create as this session's
     * capture and playback.  See [DeviceProfile.callRedirect] and
     * [CallRedirection].
     *
     * Returns false, having registered nothing, if any step is refused — the
     * permission gate, the framework's own format checks, or a call whose media
     * clients are not there to be taken over.
     */
    private fun startCallRedirect(route: CallRedirectRoute): Boolean {
        // A pre-armed policy is taken over here, and ownership moves to this
        // session: from this point the stop() thread's closeCallRedirect() is the
        // only thing that withdraws it, whatever happens below.
        val pre = preArmedRedirect
        preArmedRedirect = null
        // The mix, the sink and the injector all have one geometry, and the
        // codec has to agree with it: a G.722 session decodes to 16 kHz and
        // would be written 40 ms at a time into an 8 kHz stream.  The profile
        // pins G.711 for exactly this reason (GatewayService), so reaching here
        // with a mismatch means the peer answered with something else.
        if (playbackRate != route.mixRate) {
            val why = "codec rate $playbackRate Hz, policy mix ${route.mixRate} Hz"
            Log.e(TAG, "Call redirect rate mismatch: $why")
            listener?.onRtpStats("Call redirect refused: $why (this device needs G.711)")
            // The pre-arm registered mixes for a call that cannot be bridged.
            // Withdrawing them here is what keeps the next call clean.
            pre?.close()
            return false
        }
        val gw = if (pre != null) {
            // Registered, sinked and sourced before the dial, so the call's own
            // modem clients were created inside these mixes.
            Log.i(TAG, "Call redirect adopted: policy was registered before the dial")
            pre
        } else {
            if (!CallRedirection.hiddenApiOpen(context)) {
                Log.w(TAG, "Call redirect: hidden-API enforcement is on for this process; " +
                    "the audiopolicy classes are not reachable without the module's " +
                    "'settings put global hidden_api_policy 0'")
            }
            CallRedirection.probe()
            Log.w(TAG, "Call redirect: no pre-armed policy for this call, registering it now — " +
                "the call's own clients already exist and may not be matched into the mixes")
            val opened = CallRedirection.open(context, route.mixRate, route.wantInjector) ?: return false
            if (!opened.start(route.mixRate)) {
                opened.close()
                return false
            }
            opened
        }
        val sink = gw.sink
        captureRate = sink.sampleRate
        audioSessionId = sink.audioSessionId
        audioSourceName = "POLICY-DL@${captureRate / 1000}k"
        capture = AudioRecordSource(sink, audioSourceName)
        // No AudioRecord source id: the silence detector's blacklist is for the
        // HAL sources, and this one is not interchangeable with them.
        currentSourceId = -1

        if (gw.hasInjector) {
            audioTrack = gw.injector
            playbackChannels = 1
            playbackUsageName = "${gw.uplinkRoute}@${gw.injector.sampleRate / 1000}k"
        } else {
            // A downlink-only policy: the agent would have nowhere to be written,
            // and a one-way bridge is not what this device is for.
            Log.e(TAG, "Call redirect registered but no injector: caller audible, agent cannot speak")
            listener?.onRtpStats("Call redirect: injector refused, caller-only route")
        }

        // The witness that has always counted on this device: what the framework
        // itself says about the mixes, not what any level meter reported.
        val state = gw.registeredMixes()
        Log.i(TAG, "Call redirect up: cap=$captureRate sinkRate=${sink.sampleRate} " +
            "injector=${gw.hasInjector} uplink=${gw.uplinkRoute} " +
            "session=$audioSessionId mixes=[$state]")
        listener?.onRtpStats("Call redirected above AudioFlinger at ${route.mixRate} Hz: mixes=[$state]")
        callRedirect = gw
        // Diagnostic, off unless `callagent_uplink_witness=1`: listen to what a
        // record client on the call's own capture preset is handed during this
        // call.  AudioFlinger's history for that path is taken before the echo
        // canceller and the noise suppresser registered on it, so the dump shows
        // the tone at the microphone and says nothing about what reaches the modem.
        // This does.
        gw.startUplinkWitness(context, captureRate)
        // The session may have been stopped while the policy was being opened:
        // stop() saw no streams to release, so undo the registration here.
        if (!running.get()) {
            closeCallRedirect()
            return false
        }
        return true
    }

    /** Withdraw the policy.  Called from the stop() teardown thread, once the
     *  worker threads have left their read/write and the sink and injector they
     *  used are released: un-registering a mix while one of its streams is live
     *  is how the next call inherits a half-dead route. */
    private fun closeCallRedirect() {
        val gw = callRedirect ?: return
        callRedirect = null
        gw.close()
        Log.i(TAG, "Call redirect closed")
    }

    /** Withdraws a pre-armed policy that was never adopted.  An adopted one is
     *  owned by [callRedirect] and closed by [closeCallRedirect], so the two
     *  never touch the same registration. */
    private fun closePreArmedRedirect() {
        val pre = preArmedRedirect ?: return
        preArmedRedirect = null
        pre.close()
        Log.i(TAG, "Pre-armed call redirect withdrawn without being adopted")
    }

    /**
     * Initialize AudioRecord with retries, then run the capture loop.
     *
     * On cold boot, AudioFlinger refuses to create record tracks for
     * 10-30+ seconds after the voice call starts ("could not create record
     * track, status: -1").  The audio HAL needs time to fully initialize
     * the recording infrastructure after the modem voice path starts.
     *
     * Playback (SIP→GSM via incall_music) runs in a separate thread and
     * starts immediately.  This method retries capture init for up to 30s
     * so the caller hears the agent right away, even if the reverse
     * direction (GSM→SIP) takes longer to come up.
     */
    private fun captureInitAndLoop() {
        pcmCapture?.let { rootCaptureLoop(it); return }

        val defaultRemoteInet = InetAddress.getByName(remoteAddr)

        // A call taken over by the audio policy has exactly one capture source:
        // that policy's sink.  No fallback list and no silent-source search here,
        // deliberately — see DeviceProfile.callRedirect.  On the firmware this
        // route exists for, every AudioRecord source reads silence, so the search
        // would end at the microphone; a call carried on a microphone is a
        // different route being measured, not the same one degraded.  All that is
        // left when the sink goes away mid-call is what the exhausted-source path
        // below already does for other devices: keep the RTP leg alive so the
        // agent is still heard.
        profile.callRedirect?.let {
            if (capture != null && captureLoop()) return
            if (!running.get()) return
            Log.e(TAG, "Call-redirection sink ended — capture off, RTP kept alive for playback")
            listener?.onRtpStats("Call-redirection sink ended (see logcat: CallRedirection)")
            while (running.get()) sendSilencePackets(5000, defaultRemoteInet)
            return
        }

        // Root PCM capture first where the profile asks for it — see
        // DeviceProfile.rootCapture.  tinycap can exit mid-call (the HAL
        // re-opens the in-call usecases on hold/resume or a RAT handover), and
        // on the devices that need this the AudioRecord sources below are the
        // ones that read silence, so a fresh tinycap gets a few tries before
        // they get a turn.
        profile.rootCapture?.let { cfg ->
            for (attempt in 1..ROOT_CAPTURE_ATTEMPTS) {
                if (!running.get()) return
                if (!startRootCapture(cfg)) break   // could not launch: retrying will not help
                if (captureLoop() || !running.get()) return
                Log.w(TAG, "Root PCM capture ended (attempt $attempt/$ROOT_CAPTURE_ATTEMPTS) — restarting")
                sendSilencePackets(500, defaultRemoteInet)
            }
            Log.w(TAG, "Root PCM capture unavailable — trying the AudioRecord sources")
        }

        // Fast path: the source was already opened in initAudio (warm boot)
        if (capture != null) {
            if (captureLoop() || !running.get()) return
            // Source produced silence for 3s — fall through to try alternatives
            Log.w(TAG, "Primary source $audioSourceName silent, searching for working source (skip: $silentSourceIds)")
        }

        // Deferred/fallback source finding + capture loop.
        // Rebuilds the source list each iteration, excluding sources detected
        // as silent.  This handles both cold-boot AudioRecord unavailability
        // AND sources that initialize but deliver no audio (e.g., VOICE_CALL
        // on Exynos 9820 where the HAL doesn't route voice data to capture).
        val maxFallbacks = 5  // Maximum silent-source fallback cycles

        for (fallback in 0 until maxFallbacks) {
            if (!running.get()) return

            // Build source list, filtering out sources that produced silence
            val configs = buildCaptureConfigs(wideband)
            if (configs.isEmpty()) {
                Log.e(TAG, "All capture sources produced silence — no working source found")
                break
            }

            if (fallback > 0 || capture == null) {
                Log.w(TAG, "Trying capture sources (fallback=$fallback): ${configs.joinToString { it.name }} (silent: $silentSourceIds)")
            }

            // Try to initialize AudioRecord with one of the remaining sources
            val maxAttempts = if (fallback == 0 && capture == null) 15 else 3
            var sourceFound = false

            for (attempt in 1..maxAttempts) {
                if (!running.get()) return

                reAssertAppOps()
                sendSilencePackets(500, defaultRemoteInet)

                for (cfg in configs) {
                    if (cfg.source in silentSourceIds) continue
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf <= 0) continue
                        val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2)
                        val rec = newAudioRecord(cfg.source, cfg.rate, bufSize)
                        if (profile.captureFromTelephonyRx) {
                        routeCaptureToTelephonyRx(rec)
                    }
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            if (!running.get()) { rec.release(); return }
                            capture = AudioRecordSource(rec, cfg.name)
                            audioSessionId = rec.audioSessionId
                            captureRate = cfg.rate
                            audioSourceName = cfg.name
                            currentSourceId = cfg.source
                            Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize, fallback=$fallback attempt=$attempt)")
                            sourceFound = true
                            break
                        } else {
                            Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                            rec.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                    }
                }

                if (sourceFound) break

                if (attempt < maxAttempts && running.get()) {
                    Log.w(TAG, "All remaining sources failed (fallback=$fallback attempt=$attempt/$maxAttempts), retrying in 2s")
                    sendSilencePackets(2000, defaultRemoteInet)
                }
            }

            if (!sourceFound) {
                Log.e(TAG, "Could not initialize any remaining capture source")
                break
            }

            // Run capture loop — returns false if silence detected
            Log.i(TAG, "Capture ready (fallback=$fallback), starting capture loop with $audioSourceName")
            if (captureLoop() || !running.get()) return
            // Silence detected — silentSourceIds updated, loop again with remaining sources
            Log.w(TAG, "Source $audioSourceName silent after 3s (skip: $silentSourceIds), trying next fallback")
        }

        // All sources exhausted — DON'T tear down the call.  Playback
        // (SIP→GSM via incall_music) still works if we keep NAT alive.
        // Continue sending silence RTP so the caller at least hears the agent.
        Log.e(TAG, "All capture sources exhausted — capture disabled, keeping NAT alive for playback")
        while (running.get()) {
            sendSilencePackets(5000, defaultRemoteInet)
        }
    }

    /**
     * Start reading the caller from [cfg] over root.
     *
     * False — with nothing changed — if the mixer command it needs cannot
     * be resolved, or tinycap could not be launched or produced nothing.
     */
    private fun startRootCapture(cfg: RootCapture): Boolean {
        val setup = DeviceProfile.resolveCmd(cfg.setupCmd)
        if (cfg.setupCmd.isNotEmpty() && setup.isEmpty()) {
            // Without the routing command the PCM opens un-routed and reads
            // zeros for the whole call — not something to find out slowly.
            Log.w(TAG, "Root PCM capture: tinymix NOT FOUND, cannot route the PCM")
            return false
        }
        val rate = if (wideband) 16000 else 8000
        val cap = try {
            RootPcmCapture(cfg.card, cfg.device, cfg.rate, rate, setup, DeviceProfile.resolveCmd(cfg.restoreCmd))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Root PCM capture: ${e.message}")
            return false
        }
        if (!cap.start()) return false
        capture = cap
        captureRate = rate
        audioSourceName = cap.name
        currentSourceId = -1
        // stop() may have run while su was spawning: it saw no capture to
        // close, so this one has to go the same way itself.
        if (!running.get()) {
            releaseCaptureSource()
            return false
        }
        return true
    }

    /** Drop the current capture source: unblock its reader now, give the
     *  device back off this thread. */
    private fun releaseCaptureSource() {
        val src = capture ?: return
        capture = null
        src.close()
        Thread({ src.release() }, "capture-release").start()
    }

    /** Leave the current source, saying why, and have the caller pick another.
     *  [blacklist] keeps an AudioRecord source from being tried again this
     *  call; a root PCM has no source id and is never blacklisted. */
    private fun giveUpSource(msg: String, blacklist: Boolean = true): Boolean {
        Log.w(TAG, msg)
        listener?.onRtpStats(msg)
        if (blacklist && currentSourceId >= 0) silentSourceIds.add(currentSourceId)
        releaseCaptureSource()
        return false
    }

    /** Build the prioritized list of capture source configs, excluding
     *  sources already detected as silent. */
    private fun buildCaptureConfigs(wideband: Boolean): List<SourceConfig> {
        val configs = mutableListOf<SourceConfig>()
        if (wideband) {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL@16k", 16000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        } else {
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000))
        }
        // VOICE_DOWNLINK ahead of the acoustic sources when the profile says
        // it works here.  It captures the caller's voice digitally, off the
        // modem downlink, which is what lets the physical mic stay muted — on
        // the acoustic path the mic is the capture source, so the caller hears
        // the room and the agent hears itself through the speaker.
        if (profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferUnprocessedMic) {
            // Raw mic, ahead of the voice-tuned sources: no AEC, no noise
            // suppression, no AGC.  When the caller reaches us as sound out of
            // the phone's own speaker, those are all working against us.
            configs.add(SourceConfig(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", 8000))
            configs.add(SourceConfig(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", 8000))
        }
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.MIC, "MIC", 8000))
        configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000))

        if (profile.voiceDownlinkWorks) {
            // Where the modem downlink is capturable, it is the only source we
            // want: VOICE_CALL mixes uplink AND downlink, and the uplink now
            // carries the agent's own injected voice, so the agent hears itself
            // folded into the caller — which is what the far end perceives as
            // noise.  The downlink alone is the caller.  Acoustic sources stay
            // behind it purely as a last resort.
            configs.removeAll { it.source == MediaRecorder.AudioSource.VOICE_CALL }
            val downlink = configs.filter {
                it.source == MediaRecorder.AudioSource.VOICE_DOWNLINK
            }
            configs.removeAll(downlink)
            configs.addAll(0, downlink)
        }
        if (!profile.voiceDownlinkWorks) {
            if (wideband) {
                configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK@16k", 16000))
            }
            configs.add(SourceConfig(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000))
        }
        if (profile.preferVoiceRecognition) {
            // Ahead of everything, including VOICE_DOWNLINK: on these handsets
            // it is the source that actually delivers a steady frame rate, and
            // arriving steadily matters more than arriving digitally.
            val vr = configs.filter { it.source == MediaRecorder.AudioSource.VOICE_RECOGNITION }
            configs.removeAll(vr)
            configs.addAll(0, vr)
        }
        return configs.filterNot { it.source in silentSourceIds }
    }

    /**
     * Send silence RTP packets for the specified duration (ms).
     *
     * Keeps the NAT pinhole alive and prevents Asterisk's RTP timeout
     * while AudioRecord is unavailable on cold boot.  Uses proper RTP
     * sequencing (txSequence/txTimestamp) so captureLoop() can take
     * over seamlessly when AudioRecord finally initializes.
     */
    private fun sendSilencePackets(durationMs: Long, defaultRemoteInet: InetAddress) {
        val intervalMs = 20L  // one RTP frame = 20ms
        val end = System.currentTimeMillis() + durationMs

        // Pre-encode silence for the negotiated codec (reused every frame)
        val silencePayload = when (payloadType) {
            RtpPacket.PT_PCMA -> ByteArray(160) { 0xD5.toByte() }  // A-law silence
            else -> ByteArray(160)  // G.722: zeros → near-silence
        }

        while (System.currentTimeMillis() < end && running.get()) {
            try {
                val destAddr = latchedAddr ?: defaultRemoteInet
                val destPort = if (latchedAddr != null) latchedPort else remotePort

                val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, silencePayload)
                val data = packet.encode()
                sendRtp(data, destAddr, destPort)

                txSequence = (txSequence + 1) and 0xFFFF
                txPacketCount++
                txTimestamp += 160  // 20ms at 8000Hz RTP clock
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Silence RTP send error: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
    }

    /**
     * Stop the session and release the audio devices.
     *
     * The release has to happen *after* the loops have left `record.read()`
     * and `track.write()` — releasing an AudioRecord while another thread is
     * inside a read is a use-after-free in the native layer, which the broad
     * catch in the loops was quietly papering over.
     *
     * The waiting is done on its own thread because stop() is reached from
     * tearDown(), which runs on the main thread via the Telecom callback;
     * blocking there for up to four joins is how ANRs happen.
     */
    fun stop() {
        val wasRunning = running.getAndSet(false)
        // A key held at hang-up must not leave a tone queued on the GSM leg.
        try { dtmfDetector?.flush() } catch (_: Exception) {}

        // Do this before waiting for AudioFlinger/AudioRecord threads.  On the
        // Pixel 4a an AudioTrack write can remain blocked while the old
        // incall_music_uplink output is still alive; waiting first lets the
        // next call open a second output on top of that stale one.
        stopHalAudioImmediately()
        // Put the in-call HAL parameters back here as well as in
        // GsmCallManager.restoreAudio(): that one is skipped when the
        // InCallService is already gone, which would leave the handset's mic and
        // earpiece muted for the next ordinary call.
        restoreHalCallParams()

        if (!wasRunning) {
            // A session that died during startup has a bound socket and no loop
            // threads; nothing below this return would ever close it.
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            notifyRtpStopped()
            return
        }
        Log.i(TAG, "Stopping RTP session on port $localPort")
        try { capDump?.close() } catch (_: Exception) {}
        try { playDump?.close() } catch (_: Exception) {}

        setMonitorEnabled(false)

        // Closing the socket unblocks receiveLoop's blocking receive at once
        // rather than leaving it to time out.
        try { socket?.close() } catch (_: Exception) {}

        val ws = workers
        workers = emptyList()
        val cap = capture
        val trk = audioTrack
        // Unblock the capture thread's read now — for tinycap that means
        // killing it — so the joins below do not wait on it.
        capture = null
        cap?.close()
        audioTrack = null
        socket = null
        jitterBuffer.clear()
        // The timeline goes with the buffer: if this session is ever reused, its
        // gate must not carry the previous call's frontier into the next one.
        rtpGate.reset()

        Thread({
            val me = Thread.currentThread()
            ws.forEach { if (it !== me) it.interrupt() }
            ws.forEach {
                if (it === me) return@forEach
                try { it.join(JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
                if (it.isAlive) Log.w(TAG, "${it.name} still running after ${JOIN_TIMEOUT_MS}ms")
            }
            try { stopRootPcm() } catch (e: Exception) { Log.w(TAG, "Root PCM stop: ${e.message}") }
            // A policy that was handed over for the dial but never adopted — the
            // session was stopped before its audio came up — still has to be
            // withdrawn, or the mixes sit registered with nothing using them.
            try { closePreArmedRedirect() } catch (e: Exception) { Log.w(TAG, "Pre-armed redirect stop: ${e.message}") }
            // Last, after the sink and injector the policy created are released:
            // a mix withdrawn while one of its streams is still live is how the
            // next call registers on top of a route the framework is halfway
            // through tearing down.
            try { closeCallRedirect() } catch (e: Exception) { Log.w(TAG, "Call redirect stop: ${e.message}") }
            cap?.let { try { it.release() } catch (_: Exception) {} }
            trk?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            // Repeat the inactive state after the native streams are released
            // in case AudioFlinger recreated the output while they drained.
            stopHalAudioImmediately()
            Log.i(TAG, "RTP session on port $localPort fully released")
            notifyRtpStopped()
        }, "RTP-Stop-$localPort").start()
    }

    /** Shut down the Qualcomm voice/in-call output without waiting for worker
     * threads that may be blocked inside AudioFlinger. */
    private fun stopHalAudioImmediately() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val param = profile.incallMusicParam
            if (am != null && param.isNotEmpty()) {
                am.setParameters("${param}=false")
                Log.i(TAG, "${param}=false (immediate RTP stop)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Immediate incall_music shutdown failed: ${e.message}")
        }
        setHalCallState(1)
    }

    private fun notifyRtpStopped() {
        if (stopNotified.compareAndSet(false, true)) {
            listener?.onRtpStopped()
        }
    }

    // ── Capture: VOICE_CALL → echo gate → gain → encode → RTP send ──

    // Audio parameters from device profile
    private val profile get() = GsmCallManager.profile
    private val captureGain get() = profile.captureGain
    /** The profile's digital gain with its dB trim and the manual trim from
     *  settings applied. */
    private val playbackGain: Double
        get() = profile.playbackGain * Math.pow(10.0, profile.playbackGainDb / 20) *
            GsmCallManager.agentGainFactor

    // Double-talk detection: VOICE_CALL captures uplink+downlink from
    // the modem DSP.  The uplink contains the SIP agent's voice (injected
    // via incall_music).  Instead of a hard echo gate (which made the
    // bridge half-duplex — caller COMPLETELY silenced during agent speech),
    // we adaptively estimate the echo level and detect when the caller is
    // speaking simultaneously (double-talk / barge-in).
    //
    // How it works: incall_music injects AudioTrack digitally into the
    // modem uplink with a consistent gain ratio.  We track that ratio
    // (echoGainRatio = captureRMS / playbackRMS) during echo-only frames.
    // When captureRMS significantly exceeds the expected echo level,
    // the caller must be speaking — forward the audio (some echo leaks
    // but the caller is audible).  This enables full-duplex barge-in.
    private val echoGateThreshold get() = profile.echoGateThreshold
    @Volatile private var currentPlaybackActive = false

    // Decaying echo reference: instead of a hard on/off echo gate, we
    // track a decaying playback RMS that persists through brief inter-word
    // gaps.  When the agent pauses between words (50-100ms), the echo
    // tail in the capture pipeline still has energy.  Without decay, the
    // gate flips off and the echo tail passes the noise gate, reaching
    // the agent as "caller speech" = false interruption.
    //
    // Decay factor 0.80 per 20ms frame:
    //   50ms (2-3 frames): ~64% of peak → echo tail suppressed
    //   100ms (5 frames):  ~33% → still suppressed
    //   200ms (10 frames): ~11% → fading, real speech passes easily
    //   300ms (15 frames): ~3%  → effectively zero
    // This allows barge-in after ~200ms while suppressing echo tails.
    private var decayingPlaybackRms = 0

    // Adaptive echo gain: ratio of capture RMS to playback RMS during
    // confirmed echo-only frames.  Updated via exponential moving average.
    // Initial 1.0 = assume 1:1 coupling; adapts to actual modem DSP gain.
    @Volatile private var echoGainRatio = 1.0f
    private var echoGainSamples = 0
    private val DOUBLE_TALK_RATIO get() = profile.doubleTalkRatio

    // Noise gate: below this RMS, send silence instead of captured audio.
    // Threshold is device-specific (modem DSP noise floor varies).
    private val noiseGateThreshold get() = profile.noiseGateThreshold

    // Raw PCM dump for diagnosing audio by ear or by analysis.  Opt-in:
    // only when files/dump_audio exists.  Overwritten every call.
    private val dumpDir = java.io.File(context.filesDir, "dump")
    private val dumpOn = java.io.File(context.filesDir, "dump_audio").exists()
    private val capDump: java.io.OutputStream? = if (dumpOn) try {
        dumpDir.mkdirs(); java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dumpDir, "cap.raw")))
    } catch (_: Exception) { null } else null
    private val playDump: java.io.OutputStream? = if (dumpOn) try {
        java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dumpDir, "play.raw")))
    } catch (_: Exception) { null } else null

    // Diagnostic counters: track how frames are classified for stats logging
    @Volatile private var echoGatedFrames = 0L
    @Volatile private var noiseGatedFrames = 0L
    @Volatile private var forwardedFrames = 0L
    @Volatile private var doubleTalkFrames = 0L  // caller spoke during agent playback

    // CPU tracking: process CPU ticks at last sample for delta calculation
    private var lastCpuTicks = 0L
    private var lastCpuSampleMs = 0L

    /**
     * Capture loop: read from AudioRecord, gate, encode, send via RTP.
     * Returns true on normal exit (call ended), false if the source was
     * detected as silent (rawCapRMS near zero for 3 seconds) and should
     * be replaced with a fallback source.
     */
    private fun captureLoop(): Boolean {
        val src = capture ?: return false
        if (!src.start()) return giveUpSource("Source $audioSourceName would not start — trying fallback")

        // Re-assert in-call capture routing now that the stream exists — see
        // DeviceProfile.mixerCaptureCmd.  Off the capture thread, because the
        // su round-trip takes ~100ms and would stall the first RTP frames.
        val capCmd = DeviceProfile.resolveCmd(profile.mixerCaptureCmd)
        if (capCmd.isNotEmpty()) {
            Thread({
                val out = RootShell.execForOutput(capCmd, timeoutMs = 8000)
                Log.i(TAG, "Mixer capture routing: $out")
            }, "mixer-capture").start()
        }

        Log.i(TAG, "Capture started: source=$audioSourceName capRate=$captureRate session=$audioSessionId gain=${captureGain}x profile=${profile.name}")
        // Also report via RTP stats so it appears in the app log viewer
        listener?.onRtpStats("Capture: source=$audioSourceName rate=$captureRate gain=${captureGain}x profile=${profile.name}")

        // Buffer: 20ms of PCM at the actual capture sample rate
        val samplesPerFrame = captureRate / 50  // 160 @ 8kHz, 320 @ 16kHz
        val pcmBuf = ByteArray(samplesPerFrame * 2)
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        var silenceFrameCount = 0
        // Whether this source has ever delivered audio.  The silence detector
        // answers "does this source work at all", which is only an open
        // question until the first frame arrives; after that, quiet means the
        // line is quiet.
        var sourceProven = false
        var lastDeadAirLog = 0L

        // Frame-rate watchdog.  The silence detector asks "is this source
        // delivering audio"; this asks "is it delivering it on time".  A
        // source can pass the first and fail the second, which is what a
        // bursty VOICE_CALL does, and only the second failure produces the
        // gappy uplink that makes an agent talk over itself.
        val captureStartMs = System.currentTimeMillis()
        var framesCaptured = 0L
        var rateChecked = false

        while (running.get()) {
            try {
                val read = src.read(pcmBuf)
                if (read < 0) {
                    // The source is gone: tinycap exited, or a dead IAudioRecord.
                    if (!running.get()) break
                    return giveUpSource("Source $audioSourceName ended — trying fallback", blacklist = false)
                }
                if (read == 0) continue
                framesCaptured++

                // Checked once, on a window early in the call: switching
                // sources mid-call is disruptive, and a source that is going
                // to stall does so from the start.  A proven source is still
                // eligible -- "proven" only means it produced sound, which is
                // exactly the state this failure hides behind.
                // Not where the profile has ruled fallback out: on the S4 Mini
                // VOICE_RECOGNITION ran at 83% on a live call (2026-10-24) and
                // was swapped for VOICE_CALL, which hands the agent its own
                // voice back.  A slightly slow right source beats a wrong one.
                if (!rateChecked && !profile.silenceFallback) rateChecked = true
                if (!rateChecked) {
                    val elapsed = System.currentTimeMillis() - captureStartMs
                    if (elapsed >= RATE_CHECK_AFTER_MS) {
                        rateChecked = true
                        val expected = elapsed / 20                 // 50 frames/s
                        if (expected > 0 && framesCaptured * 100 / expected < RATE_MIN_PERCENT) {
                            val pct = framesCaptured * 100 / expected
                            val msg = "Source $audioSourceName too slow: $framesCaptured/$expected " +
                                "frames ($pct%) in ${elapsed}ms — trying fallback"
                            return giveUpSource(msg)
                        }
                    }
                }

                // Measure raw capture level BEFORE echo gate for diagnostics.
                // If rawCaptureRms=0, the audio source itself is silent
                // (HAL/modem not providing audio).  If rawCaptureRms>0 but
                // captureRms=0, the echo gate is suppressing.
                rawCaptureRms = pcmRms(pcmBuf)
                capDump?.let { try { it.write(pcmBuf, 0, read) } catch (_: Exception) {} }
                if (monitorTrack != null) lastCallerFrame = pcmBuf.copyOf()

                // Silence detection: only count during non-echo periods (when
                // decayingPlaybackRms <= echoGateThreshold).  incall_music echo
                // leaks back through VOICE_CALL capture, spiking rawCapRMS
                // above the threshold during agent speech — this would reset a
                // naive counter even though the source delivers NO caller audio.
                // By only counting non-echo frames, we accurately detect dead
                // sources regardless of whether the agent is speaking.
                val noEchoPeriod = decayingPlaybackRms <= echoGateThreshold
                if (noEchoPeriod) {
                    if (rawCaptureRms < SILENCE_RMS_THRESHOLD) {
                        silenceFrameCount++
                        if (silenceFrameCount == 10 || silenceFrameCount == 20) {
                            Log.w(TAG, "Source $audioSourceName low audio (no-echo): rawCapRMS=$rawCaptureRms silence=${silenceFrameCount}/${SILENCE_FRAME_LIMIT} frames")
                        }
                        if (silenceFrameCount >= SILENCE_FRAME_LIMIT) {
                            if (sourceProven || !profile.silenceFallback) {
                                // Dead air, not a dead source.  Tearing the
                                // AudioRecord down here and blacklisting the
                                // source was catastrophic mid-call: with
                                // playbackLeaksIntoCapture off every quiet
                                // frame counts, so one limit's worth of nobody
                                // speaking — six seconds on this profile —
                                // retired a working source.  A few of those
                                // exhausted the source list and capture stayed
                                // dead for the rest of the call: the agent
                                // stopped hearing the caller and never got
                                // them back.
                                val now = System.currentTimeMillis()
                                if (now - lastDeadAirLog > 30_000) {
                                    lastDeadAirLog = now
                                    val msg = "Dead air ${silenceFrameCount / 50}s on " +
                                        "$audioSourceName (source proven — keeping it)"
                                    Log.w(TAG, msg)
                                    listener?.onRtpStats(msg)
                                }
                                silenceFrameCount = 0
                            } else {
                                return giveUpSource("Source $audioSourceName SILENT ($silenceFrameCount non-echo frames) — trying fallback")
                            }
                        }
                    } else {
                        // Source delivered audio during non-echo → working
                        silenceFrameCount = 0
                        sourceProven = true
                    }
                }
                // During echo periods: don't update counter (can't distinguish
                // caller audio from incall_music echo)

                gateEncodeAndSend(pcmBuf, read, defaultRemoteInet)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        return true  // Normal exit (call ended)
    }

    /**
     * Capture loop for the raw PCM path: 20ms mono frames off tinypcm's stdout.
     *
     * None of the AudioRecord machinery applies.  The source is the call
     * itself, fixed by the profile, so there is nothing to fall back to and no
     * silence to judge it by; and tinypcm drops rather than blocks, so a frame
     * that arrives is a frame on time.
     */
    private fun rootCaptureLoop(pcm: RootPcm) {
        val defaultRemoteInet = InetAddress.getByName(remoteAddr)
        val pcmBuf = ByteArray(captureRate / 50 * 2)
        val input = java.io.DataInputStream(pcm.input)
        listener?.onRtpStats("Capture: source=$audioSourceName rate=$captureRate gain=${captureGain}x profile=${profile.name}")
        while (running.get()) {
            try {
                input.readFully(pcmBuf)
            } catch (e: java.io.IOException) {
                if (running.get()) {
                    Log.e(TAG, "Root PCM capture ended: ${e.message} (${pcm.recentMessages()})")
                    listener?.onRtpStats("Root PCM capture ended: ${pcm.recentMessages()}")
                }
                break
            }
            rawCaptureRms = pcmRms(pcmBuf)
            capDump?.let { try { it.write(pcmBuf) } catch (_: Exception) {} }
            if (monitorTrack != null) lastCallerFrame = pcmBuf.copyOf()
            try {
                gateEncodeAndSend(pcmBuf, pcmBuf.size, defaultRemoteInet)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        // Keep the far end's NAT pinhole and RTP timer alive for the rest of
        // the call even with capture gone; the agent can still be heard.
        while (running.get()) sendSilencePackets(5000, defaultRemoteInet)
    }

    /** Gate, gain, encode and send one captured 20ms frame — the part of the
     *  capture path that does not care where the frame came from. */
    private fun gateEncodeAndSend(pcmBuf: ByteArray, read: Int, defaultRemoteInet: InetAddress) {
        // Double-talk-aware gating: replaces the old hard echo gate
        // that made the bridge half-duplex (caller completely silenced
        // during agent speech).  Now uses adaptive echo level estimation
        // to detect when the caller is speaking over the agent.
        //
        // VOICE_CALL captures uplink+downlink.  incall_music injects
        // the agent's voice digitally into the uplink with a consistent
        // gain ratio.  We track that ratio and detect when capture energy
        // exceeds the expected echo — that excess is the caller's voice.
        // Update decaying echo reference: tracks playback level
        // through brief inter-word gaps to suppress echo tails.
        if (currentPlaybackActive && playbackRms > 0) {
            decayingPlaybackRms = playbackRms
        } else if (decayingPlaybackRms > 0) {
            decayingPlaybackRms = (decayingPlaybackRms * 0.80).toInt()
        }

        val shouldForward: Boolean
        if (profile.playbackLeaksIntoCapture &&
            decayingPlaybackRms > echoGateThreshold) {
            // Agent is speaking (or echo tail still decaying) —
            // check for double-talk (barge-in).
            val expectedEcho = (echoGainRatio * decayingPlaybackRms).toInt()
                .coerceAtLeast(noiseGateThreshold)
            if (rawCaptureRms > (expectedEcho * DOUBLE_TALK_RATIO).toInt()) {
                // Double-talk: caller speaking over agent — forward.
                // Some echo leaks through but caller is audible.
                shouldForward = true
                doubleTalkFrames++
            } else {
                // Echo-only: agent speaking, caller silent — send silence.
                // Update echo gain estimate from confirmed echo frames.
                if (currentPlaybackActive && playbackRms > 500) {
                    val r = rawCaptureRms.toFloat() / playbackRms
                    echoGainRatio = echoGainRatio * 0.95f + r * 0.05f
                    echoGainSamples++
                }
                shouldForward = false
                echoGatedFrames++
            }
        } else if (rawCaptureRms < noiseGateThreshold) {
            // Noise gate: only modem digital noise, no caller speech.
            shouldForward = false
            noiseGatedFrames++
        } else {
            // Caller speaking, agent silent — forward normally.
            shouldForward = true
        }

        if (shouldForward) {
            // Apply gain boost
            if (captureGain > 1) {
                for (i in 0 until read / 2) {
                    val lo = pcmBuf[i * 2].toInt() and 0xFF
                    val hi = pcmBuf[i * 2 + 1].toInt()
                    val sample = ((hi shl 8) or lo) * captureGain
                    val clamped = sample.coerceIn(-32768, 32767)
                    pcmBuf[i * 2] = (clamped and 0xFF).toByte()
                    pcmBuf[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                }
            }
            captureRms = pcmRms(pcmBuf)
            forwardedFrames++
        } else {
            java.util.Arrays.fill(pcmBuf, 0, read, 0.toByte())
            captureRms = 0
        }

        // Encode based on codec and capture sample rate.
        // G.722 expects 16 kHz PCM; if capture is 8 kHz, upsample first.
        val encoded = when (payloadType) {
            RtpPacket.PT_G722 -> {
                val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                g722Encoder.encode(pcm16k)
            }
            RtpPacket.PT_PCMA -> {
                if (captureRate == 8000) PcmaCodec.encode8k(pcmBuf)
                else PcmaCodec.encode(pcmBuf)
            }
            else -> {
                val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                g722Encoder.encode(pcm16k)
            }
        }

        val destAddr = latchedAddr ?: defaultRemoteInet
        val destPort = if (latchedAddr != null) latchedPort else remotePort

        val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, encoded)
        val data = packet.encode()
        sendRtp(data, destAddr, destPort)

        txSequence = (txSequence + 1) and 0xFFFF
        txPacketCount++
        txTimestamp += 160 // 20ms at 8000Hz RTP clock
    }

    // ── Receive: RTP recv → jitter buffer ───────────────

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break

                // Authenticate before parsing.  A packet that fails is a
                // forgery, a replay or a key mismatch, and none of those
                // should reach the jitter buffer.
                var data = buf
                var len = packet.length
                val ctx = srtpRecv
                if (ctx != null) {
                    val plain = ctx.unprotect(buf, packet.length)
                    if (plain == null) {
                        srtpAuthFailures++
                        // Said out loud, because the symptom of dropping every
                        // packet is a call that simply dies at the 30s media
                        // timeout with nothing explaining why.  Rate-limited so
                        // a wrong key cannot flood the log at 50 packets/s.
                        if (srtpAuthFailures == 1L || srtpAuthFailures % 100L == 0L) {
                            Log.w(TAG, "SRTP auth failed on $srtpAuthFailures packet(s) — wrong key or forged")
                        }
                        continue
                    }
                    data = plain
                    len = plain.size
                }
                val rtp = RtpPacket.decode(data, len) ?: continue

                // Symmetric RTP: latch onto the actual source address
                if (latchedAddr == null) {
                    latchedAddr = packet.address
                    latchedPort = packet.port
                    Log.i(TAG, "Symmetric RTP: latched to ${packet.address.hostAddress}:${packet.port}")
                }

                lastRtpReceivedTime = System.currentTimeMillis()
                rxPacketCount++
                // Log first packet details for debugging
                if (rxPacketCount == 1L) {
                    // Names the port this packet actually arrived on.  Together
                    // with the "RTP socket ready" line at start() and the peer's
                    // own measurement of where our RTP came from, it says which
                    // of the two directions is not using the port the SDP agreed
                    // to — the failure that showed up as a one-way call.
                    Log.i(TAG, "First RX: pt=${rtp.payloadType} len=${rtp.payload.size} " +
                            "on our port ${socket?.localPort} (SDP said $localPort) " +
                            "from ${packet.address.hostAddress}:${packet.port}")
                }
                // Telephone-event first: a DTMF packet must never reach the
                // jitter buffer, and it must not be dropped either — that is
                // how IVR menus used to miss every key press.
                if (rtp.payloadType in telephoneEventPts && rtp.payloadType != payloadType) {
                    rxDtmfPackets++
                    dtmfDetector?.onPacket(rtp.timestamp, rtp.payload)
                    continue
                }
                if (rtp.payloadType == payloadType || rtp.payloadType == RtpPacket.PT_PCMA || rtp.payloadType == RtpPacket.PT_G722) {
                    if (gateOn) {
                        rtpGate.accept(rtp) { offerPlayback(it.payload) }
                        reportGateOnce()
                    } else {
                        offerPlayback(rtp.payload)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Receive error: ${e.message}")
            }
        }
    }

    /** Puts one audio packet's payload where the playback loop picks it up.
     *  Overflow drops the oldest, which is the only thing available to a socket
     *  thread that must never block: SIP shares this thread. */
    private fun offerPlayback(payload: ByteArray) {
        if (!jitterBuffer.offer(payload)) {
            jitterBuffer.poll() // drop oldest
            jitterBuffer.offer(payload)
            rxDropped++
        }
    }

    /** Says out loud, the first time it happens and every 50 packets after, that
     *  the path really is handing this call repeated or expired audio.  Without
     *  this line the only sign of it is someone on the GSM leg hearing themselves
     *  twice, which is not something anybody can quote back to a log.
     *
     *  The counters are read here without the gate's lock: they are diagnostics,
     *  and a stale read at worst delays the report by one packet. */
    private var gateReported = -1L
    private fun reportGateOnce() {
        val seen = rtpGate.duplicates + rtpGate.stale
        if (seen == 0L || seen == gateReported) return
        if (gateReported >= 0 && seen - gateReported < 50) return
        gateReported = seen
        Log.w(TAG, "Replay guard: ${rtpGate.stats()}")
    }

    // ── RTP inactivity timeout ─────────────────────────

    private fun timeoutLoop() {
        // Early re-assertion at 3s: combat Android re-revoking RECORD_AUDIO
        // when screen is off, and re-toggle incall_music after speaker route
        // is fully settled (~1.5s after configureAudioBridge).
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        if (running.get()) {
            reAssertAppOps()
            reToggleIncallMusic()
        }

        var tick = 0
        while (running.get()) {
            try {
                // Close together at first, then settle down.  At a flat 15s a
                // call that lasted sixteen seconds ended before a single stats
                // line was emitted, so there was nothing at all to diagnose it
                // with afterwards — which is exactly when it is wanted.
                Thread.sleep(if (tick < 3) 5_000L else 15_000L)
                tick++

                // Periodic appops check: Android's AppOpsService can revoke
                // RECORD_AUDIO for background apps when the screen goes off.
                // The check is cheap now (see reAssertAppOps), but there is no
                // reason to make it at all once a call has been up and
                // capturing for a while — the revocation, when it happens,
                // happens early.  First minute at 15s, then once a minute.
                if (tick <= 4 || tick % 4 == 0) reAssertAppOps()

                // Stats.  The stream volumes and mic-mute state used to be
                // queried and appended here every cycle; they are set once at
                // bridge setup and never change during a call, so reading them
                // fifty times over a call told us nothing new.
                val stats = "tx=$txPacketCount rx=$rxPacketCount play=$playbackFrames " +
                        "capRMS=$captureRms rawCapRMS=$rawCaptureRms playRMS=$playbackRms " +
                        "playWinLoud=$windowLoudFrames(${windowLoudFrames * 20}ms) playWinMax=$windowMaxRms " +
                        "src=$audioSourceName " +
                        "rate=${captureRate}/${playbackRate} jbuf=${jitterBuffer.size} " +
                        "res=${playbackReservoir?.let { it.depth / (playbackRate / 50) } ?: 0} " +
                        "drop=$rxDropped under=$underruns trim=$trimmedFrames dtmf=$rxDtmfPackets " +
                        "gates:echo=$echoGatedFrames noise=$noiseGatedFrames fwd=$forwardedFrames dt=$doubleTalkFrames " +
                        (if (gateOn) rtpGate.stats() else "gate=off") +
                        " slip=" + (if (slipOn) "on" else "off") +
                        // The playback writer's counters: what the sink took, what
                        // is still waiting for it, what was given up, and how many
                        // cycles the sink refused.  Read from volatile fields off the
                        // audio thread, so at worst a cycle stale; nothing here
                        // queries the framework, which is what the note above this
                        // block is about.
                        //
                        // `stage=` names the mode the call was actually running in,
                        // including when no writer was built (`wr=build470` for
                        // stage -1, `wr=none` if the loop never got as far as a
                        // track).  Without it, a stats line cannot say which half of
                        // an A/B it came from.
                        (playWriter?.let { " stage=$playStage " + it.summary() }
                            ?: if (playStage < 0) " stage=-1 wr=build470"
                               else " stage=$playStage wr=none") +
                        // ...and what the output did with it: rendered frames
                        // against handed frames, so the audio still inside the
                        // track's buffer shows up as a number instead of as
                        // someone on the far end waiting for a word.
                        playbackConsumed() +
                        getCpuStats()
                Log.i(TAG, "RTP: $stats")
                listener?.onRtpStats(stats)
                windowLoudFrames = 0
                windowMaxRms = 0

                val elapsed = System.currentTimeMillis() - lastRtpReceivedTime
                if (elapsed > RTP_TIMEOUT_MS) {
                    Log.w(TAG, "RTP timeout: no packets received for ${elapsed / 1000}s")
                    listener?.onRtpTimeout()
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── Playback: jitter buffer → decode → speaker → mic → GSM uplink ──

    /**
     * Ask this recorder to take its audio from the telephony downlink.
     *
     * Logs the available input device types, since whether TYPE_TELEPHONY is
     * offered at all is the thing worth knowing when the agent ends up hearing
     * the room instead of the caller.
     */
    private fun routeCaptureToTelephonyRx(rec: AudioRecord) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val telephony = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY input exposed — capture stays on the mic; " +
                    "inputs: " + inputs.joinToString { "${it.type}/${it.productName}" })
                return
            }
            val accepted = rec.setPreferredDevice(telephony)
            Log.i(TAG, "Capture preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
        } catch (e: Exception) {
            Log.w(TAG, "routeCaptureToTelephonyRx failed: ${e.message}")
        }
    }

    /**
     * Ask for this track to be routed to the telephony uplink.
     *
     * Logs every output device type it can see, because whether the platform
     * exposes TYPE_TELEPHONY at all is the thing worth knowing when injection
     * does not work.
     */
    private fun routeToTelephonyTx(track: AudioTrack) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                Log.w(TAG, "No TYPE_TELEPHONY output exposed — cannot request the uplink route; " +
                    "outputs: " + outputs.joinToString { "${it.type}/${it.productName}" })
                return
            }
            val accepted = track.setPreferredDevice(telephony)
            Log.i(TAG, "Preferred device -> TELEPHONY id=${telephony.id} accepted=$accepted")
        } catch (e: Exception) {
            Log.w(TAG, "routeToTelephonyTx failed: ${e.message}")
        }
    }

    // ── Per-frame scratch buffers ────────────────────────────────────
    // Fifty frames a second, each of these used to allocate a fresh array:
    // the upsampler's two, the stereo interleave, and the monitor mix.  Over a
    // ten-minute call that is on the order of a hundred thousand short-lived
    // arrays whose only effect is GC pressure — and a GC pause on a 20ms audio
    // loop is an audible glitch.  Each buffer belongs to exactly one thread
    // (upsample to capture; stereo and monitor to playback) and is handed
    // straight to the next call on that same thread, so reuse is safe.
    private var upsampleIn: IntArray = IntArray(0)
    private var upsampleOut: ByteArray = ByteArray(0)
    private var stereoBuf: ByteArray = ByteArray(0)
    private var monitorBuf: ByteArray = ByteArray(0)

    /**
     * Write one playback frame without allowing a wedged Qualcomm incall
     * output to block the RTP thread for seconds.  Blocking AudioTrack.write()
     * was observed to stall for 6-10 seconds when the voice route changed;
     * that left the old incall_music_uplink alive and made the next call
     * intermittently one-way.
     */
    private fun writePlaybackFrame(track: AudioTrack, data: ByteArray) {
        var offset = 0
        val deadline = System.nanoTime() + 30_000_000L
        while (offset < data.size && running.get()) {
            val written = track.write(
                data, offset, data.size - offset, AudioTrack.WRITE_NON_BLOCKING
            )
            if (written > 0) {
                offset += written
                continue
            }
            if (written < 0) {
                Log.w(TAG, "AudioTrack nonblocking write failed: $written")
                return
            }
            if (System.nanoTime() >= deadline) {
                Log.w(TAG, "AudioTrack nonblocking write timed out (${data.size - offset} bytes left)")
                return
            }
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** One playback frame to wherever this session plays: the AudioTrack, or
     *  the root PCM pipe.
     *
     *  The root pipe does its own pacing ([writeRootPlaybackFrame]) and never
     *  refuses anything, so it is written directly.  The track goes through
     *  [PlaybackWriter] unless `callagent_play_stage=-1` asked for build 470's
     *  write path. */
    private fun writePlaybackOut(track: AudioTrack?, data: ByteArray) {
        val root = pcmPlayback
        if (root != null) {
            writeRootPlaybackFrame(root, data)
            return
        }
        if (track == null) return
        val writer = playWriter
        if (writer != null) writer.write(data) else writePlaybackFrame(track, data)
    }

    /**
     * What the output has actually rendered, in frames since this call's track was
     * created, and what the bridge has handed it: the gap between the two is audio
     * sitting in the track's own buffer, which is delay the far end feels but no
     * counter in the app could see before now.
     *
     * Read on the stats thread, never the audio one.  `playbackHeadPosition` is the
     * position the framework has rendered to, kept in shared memory and advancing
     * monotonically; it is not reset except by `flush()`, and nothing here calls
     * `flush()`.  It is an Int, so it is masked to unsigned and the wrap is
     * reported rather than hidden: at 8 kHz it comes round every six days, which no
     * call reaches, but a session being replaced does not get a wrong number either.
     *
     * This is the half of the injected-uplink question that offline tests cannot
     * answer.  `wr:out=` in the same line counts what the route took from us; only
     * the route knows what it then played, and on 2026-11-07 the route's own
     * counters contradicted each other — `Frames written: 145 408` against
     * `stop(93): called with 182 536 frames delivered` for the same track.
     */
    private fun playbackConsumed(): String {
        val track = audioTrack ?: return ""
        val writer = playWriter ?: return ""
        return try {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val handed = writer.accepted / (playbackChannels * 2L)
            val since = if (playedFramesAtLastReport < 0L) 0L
            else played - playedFramesAtLastReport
            playedFramesAtLastReport = played
            val held = handed - played
            " played=${played}f(+$since) handed=${handed}f held=${held}f/${held * 1000L / playbackRate}ms"
        } catch (t: Throwable) {
            // A framework call that throws must never take a live call down; the
            // absence of the number is itself the information.
            " played=n/a(${t.javaClass.simpleName})"
        }
    }

    /**
     * Write one frame to tinypcm, then wait until the next is due.
     *
     * An AudioTrack paces this loop by blocking when its buffer is full; the
     * pipe does not, and tinypcm on the other end never pushes back — it
     * drops what it has no room for so that it can never stall the DMA.  So
     * the pacing is done here instead, one frame per 20ms of the monotonic
     * clock.  Behind by more than three frames (a stall, a GC), start the
     * schedule afresh rather than racing to catch up.
     */
    private fun writeRootPlaybackFrame(pcm: RootPcm, data: ByteArray) {
        try {
            pcm.output.write(data)
            pcm.output.flush()
        } catch (e: java.io.IOException) {
            if (running.get()) {
                Log.e(TAG, "Root PCM playback ended: ${e.message} (${pcm.recentMessages()})")
                listener?.onRtpStats("Root PCM playback ended: ${pcm.recentMessages()}")
                pcmPlayback = null
            }
            return
        }
        val now = System.nanoTime()
        if (nextPlayNs == 0L || now - nextPlayNs > 60_000_000L) nextPlayNs = now
        nextPlayNs += 20_000_000L
        val waitNs = nextPlayNs - System.nanoTime()
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    }

    /**
     * Duplicate each 16-bit mono sample into both channels.
     *
     * Needed only so the track can match the HAL's stereo-only
     * incall_music_uplink mixPort; the content is still mono.
     */
    private fun monoToStereo(mono: ByteArray): ByteArray {
        if (stereoBuf.size != mono.size * 2) stereoBuf = ByteArray(mono.size * 2)
        val out = stereoBuf
        var i = 0
        var j = 0
        while (i + 1 < mono.size) {
            val lo = mono[i]
            val hi = mono[i + 1]
            out[j] = lo; out[j + 1] = hi
            out[j + 2] = lo; out[j + 3] = hi
            i += 2
            j += 4
        }
        return out
    }

    /**
     * Mark the voice sessions ACTIVE (2) or INACTIVE (1) with the audio HAL.
     * See DeviceProfile.halCallActiveVsids for why this is needed at all.
     */
    private fun setHalCallState(state: Int) {
        if (profile.halCallActiveVsids.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (vsid in profile.halCallActiveVsids) {
                am.setParameters("vsid=$vsid;call_state=$state")
            }
            Log.i(TAG, "HAL call_state=$state for ${profile.halCallActiveVsids}")
        } catch (e: Exception) {
            Log.w(TAG, "setHalCallState($state) failed: ${e.message}")
        }
    }

    /**
     * Set the profile's in-call HAL parameters.  See DeviceProfile.halCallParams
     * for why a HAL without a mixer needs them.
     */
    private fun applyHalCallParams() {
        if (profile.halCallParams.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (p in profile.halCallParams) am.setParameters(p)
            Log.i(TAG, "HAL call params set: ${profile.halCallParams}")
        } catch (e: Exception) {
            Log.w(TAG, "applyHalCallParams failed: ${e.message}")
        }
    }

    /** Undo [applyHalCallParams]; a no-op for profiles that set none. */
    private fun restoreHalCallParams() {
        if (profile.halCallParamsRestore.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            for (p in profile.halCallParamsRestore) am.setParameters(p)
        } catch (e: Exception) {
            Log.w(TAG, "restoreHalCallParams failed: ${e.message}")
        }
    }

    /**
     * AudioRecord for [source].  Android 8.1 refuses the call sources
     * (VOICE_UPLINK/DOWNLINK/CALL) through the public constructor (8.0 shares
     * the framework code but is untested): the
     * AudioAttributes it builds drops a capture preset it does not know, and the
     * constructor then throws "Invalid audio source -1".  The hidden
     * (attributes, format, size, session) constructor takes the preset as given,
     * so build the attributes by hand and force the source into them.
     * Only reached when the public constructor throws.
     */
    private fun newAudioRecord(source: Int, rate: Int, bufSize: Int): AudioRecord {
        try {
            return AudioRecord(
                source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "AudioRecord source=$source rejected by the public constructor " +
                "(${e.message}); trying the hidden one")
        }
        val attrs = AudioAttributes.Builder().build()
        AudioAttributes::class.java.getDeclaredField("mSource").apply {
            isAccessible = true
            setInt(attrs, source)
        }
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val ctor = AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, AudioFormat::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        ctor.isAccessible = true
        return ctor.newInstance(attrs, fmt, bufSize, 0)
    }

    /** Mute or unmute the agent towards the caller. */
    fun setAgentMuted(on: Boolean) {
        agentMuted = on
        Log.i(TAG, if (on) "Agent MUTED towards caller" else "Agent unmuted")
        listener?.onRtpStats(if (on) "Agent muted" else "Agent unmuted")
    }

    /** Knee of [softLimit]: about 75% of full scale. */
    private val limitKnee = 24576

    /**
     * Map a boosted sample into 16 bits without a hard edge.  Linear up to
     * [limitKnee]; past it the remainder is compressed with x/(1+x) so the
     * output approaches 32767 but never jumps to it.
     */
    private fun softLimit(sample: Int): Int {
        val a = kotlin.math.abs(sample)
        if (a <= limitKnee) return sample
        val room = 32767 - limitKnee
        val over = (a - limitKnee).toDouble() / room
        val shaped = limitKnee + (room * over / (1.0 + over)).toInt()
        return if (sample < 0) -shaped else shaped
    }

    /** True while the speaker monitor (Snoop) is playing. */
    val isMonitoring: Boolean get() = monitorTrack != null

    /** Turn the speaker monitor on or off mid-call.  Safe to call repeatedly. */
    fun setMonitorEnabled(on: Boolean) {
        if (on) {
            if (monitorTrack != null) return
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    playbackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Deliberately NOT USAGE_MEDIA.  Media maps to
                            // STREAM_MUSIC, and this bridge sets
                            // incall_music_enabled=true precisely so that
                            // STREAM_MUSIC is injected into the modem uplink —
                            // so a monitor track on USAGE_MEDIA would be sent
                            // to the caller instead of the speaker, which is
                            // the opposite of what monitoring is for.
                            //
                            // And not USAGE_ASSISTANCE_SONIFICATION, which it
                            // was: that is STREAM_SYSTEM, and on MIUI a phone in
                            // vibrate or silent mode mutes STREAM_SYSTEM, so the
                            // monitor played into a muted stream — "Monitor ON"
                            // in the log and nothing on the speaker (Poco, MIUI
                            // 14, 2026-10-23).
                            //
                            // Nor USAGE_ALARM, tried next: during a call the
                            // audio policy mutes alarm/sonification streams on
                            // the speaker, so on MIUI the monitor was silent and
                            // only the call's own downlink leaked out — the
                            // caller and no agent.  VOICE_COMMUNICATION is the
                            // stream that plays on the speaker in a call; only
                            // STREAM_MUSIC goes to the uplink.  GsmCallManager
                            // unmutes it while snooping (setMonitoring).
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(playbackRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                // Force it to the loudspeaker; the call itself is elsewhere.
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { t.setPreferredDevice(it) }
                } catch (_: Exception) {}
                try {
                    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                        ?.let { GsmCallManager.setMonitoring(it, true) }
                } catch (e: Exception) {
                    Log.w(TAG, "Monitor audio: ${e.message}")
                }
                t.play()
                monitorTrack = t
                Log.i(TAG, "Monitor ON — both sides on the speaker, mic stays muted")
                listener?.onRtpStats("Monitor ON")
            } catch (e: Exception) {
                Log.w(TAG, "Monitor start failed: ${e.message}")
            }
        } else {
            monitorTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            monitorTrack = null
            try {
                (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.let { GsmCallManager.setMonitoring(it, false) }
            } catch (_: Exception) {}
            Log.i(TAG, "Monitor OFF")
            listener?.onRtpStats("Monitor OFF")
        }
    }

    /**
     * Mix the agent's frame with the most recent caller frame and play the
     * result on the speaker.
     *
     * Weighted 3:1 towards the agent rather than mixed evenly.  The caller
     * arrives from the modem downlink at close to full scale while the agent's
     * side is quieter, so an even mix buries the agent — and the agent is
     * usually the half worth listening to, since the point of monitoring is to
     * hear what it is saying.  The weights still sum to 1, so two loud sides
     * cannot clip against each other.
     */
    private fun feedMonitor(agentPcm: ByteArray) {
        val t = monitorTrack ?: return
        try {
            // Where the call itself plays on the speaker while snooping
            // (GsmCallManager.monitorCarriesCallAudio), the caller is already
            // audible — mixing them in again would play them twice, slightly
            // apart.  Then this track carries the agent alone.
            val caller = if (GsmCallManager.monitorCarriesCallAudio) null else lastCallerFrame
            if (monitorBuf.size != agentPcm.size) monitorBuf = ByteArray(agentPcm.size)
            val out = monitorBuf
            var i = 0
            while (i + 1 < agentPcm.size) {
                val a = ((agentPcm[i + 1].toInt() shl 8) or (agentPcm[i].toInt() and 0xFF)).toShort().toInt()
                val c = if (caller != null && i + 1 < caller.size) {
                    ((caller[i + 1].toInt() shl 8) or (caller[i].toInt() and 0xFF)).toShort().toInt()
                } else 0
                val mixed = if (caller == null) a else (((a * 3) + c) / 4).coerceIn(-32768, 32767)
                out[i] = (mixed and 0xFF).toByte()
                out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
            t.write(out, 0, out.size)
        } catch (e: Exception) {
            Log.w(TAG, "Monitor write failed: ${e.message}")
        }
    }

    private fun playbackLoop() {
        val track = audioTrack
        if (track == null && pcmPlayback == null) return

        if (track != null) {
            // Nothing is written to the track before the loop starts: the loop
            // feeds it continuously from its first cycle, which is what keeps an
            // underrun (and the BUFFER TIMEOUT and restart cycle that used to
            // follow one) from ever happening.  What the loop does buffer is the
            // reservoir's own target depth, and that is deliberate: see
            // JITTER_TARGET_FRAMES.
            track.play()
            // A track created by an audio policy (see [CallRedirection]) is not
            // routed to an output device at all: its output is a mix.  The
            // framework answers that question with null, or declines it, so the
            // route is reported without letting a diagnostic take the call down.
            val routed = try { "routedTo=${track.routedDevice?.type}" } catch (_: Throwable) { "routedTo=n/a" }
            Log.i(TAG, "Playback started (rate=$playbackRate usage=$playbackUsageName " +
                    "deepBuffer=true reservoir=${JITTER_TARGET_FRAMES * 20}ms " +
                    "slip=${if (slipOn) "on" else "off"} gate=${if (gateOn) "on" else "off"} " +
                    "stage=$playStage) $routed")

            // From here the track is fed by [PlaybackWriter], not by a bare
            // AudioTrack.write(): it owns the cycle's clock and keeps whatever the
            // track will not take, instead of returning a 30 ms deadline as "no
            // opinion" and letting the rest of the frame go.  `stage=0` keeps the
            // pacing and drops like build 470 did; `stage=-1` means the block
            // above logged the truth and [writePlaybackFrame] still does the work.
            if (playStage >= 0) {
                playedFramesAtLastReport = -1L
                playWriter = PlaybackWriter(
                    sink = PlaybackWriter.Sink { bytes, off, len ->
                        track.write(bytes, off, len, AudioTrack.WRITE_NON_BLOCKING)
                    },
                    // One 20 ms frame as it is actually written: mono samples,
                    // doubled again when the track is stereo — the same size as
                    // [silenceFrame] below, which is what the loop writes while the
                    // reservoir is still filling.
                    frameBytes = playbackRate / 50 * 2 * playbackChannels,
                    queueFrames = playStage,
                    alive = { running.get() },
                )
            }

            // Set incall_music_enabled=true AFTER AudioTrack.play(): the Qualcomm
            // HAL on MSM8930 starts the incall-music usecase only once there is an
            // active STREAM_MUSIC output.
            //
            // Skipped where the profile already set it before the track existed.
            // Toggling it again here reconfigures the voice path and tears down the
            // in-call record session the HAL has just started — measured on SM6150:
            // VOICE_CALL delivered real audio (rawRMS=208) until this second toggle
            // fired 8ms later, after which capture went to zero.
            if (!profile.incallMusicBeforeTrack) {
                enableIncallMusic()
            }
            // Set mixer controls via root — also handles Voice Tx Mute=0.
            // No separate ensureVoiceTxOpen() call here: the mixer thread below
            // already issues that command, and waiting for su -c was blocking
            // the playback thread for ~100ms.
            enableIncallMusicViaMixer()
        }

        // 20ms of digital silence.  Written only while the reservoir is being
        // filled, so the AudioTrack never runs dry — a dry track is what used to
        // end in BUFFER TIMEOUT and a disable/restart cycle.
        val silenceFrame = ByteArray(playbackRate * 2 * playbackChannels / 50)

        // The sample reservoir: this is where received audio is buffered now,
        // rather than as packets in [jitterBuffer].  [PlaybackSlip] explains why
        // the packet had to stop being the unit of time; the loop below is the
        // part that uses it.
        val frameSamples = playbackRate / 50
        val slip = PlaybackSlip(frameSamples)
        playbackReservoir = slip
        val frame = ByteArray(frameSamples * 2)

        // Track silence→speech transitions for fade-in.
        // Modem DSP AGC/DTX settles during silence; abrupt speech onset
        // gets over-amplified causing distortion at the start of each phrase.
        // A short fade-in smooths the transition.
        var wasPlayingSilence = true

        // Whether the reservoir has reached [JITTER_TARGET_FRAMES] yet.  It has
        // to be full before it can absorb anything.
        var primed = false

        while (running.get()) {
            try {
                // 1. Everything that has arrived is decoded into the reservoir,
                //    in arrival order.  Decoding stays on this thread: the socket
                //    thread is shared with SIP.
                //
                //    The gate is pumped first: its patience for a missing packet
                //    is measured on this thread's clock, because a held packet has
                //    to reach the reservoir when the far end stalls, not only when
                //    the next packet turns up.
                if (gateOn) rtpGate.pump { offerPlayback(it.payload) }
                while (true) {
                    val encoded = jitterBuffer.poll() ?: break
                    // Decode based on codec.  G.722 outputs 16 kHz natively;
                    // PCMA always decodes to 8 kHz (playbackRate==8000).
                    val pcm = when (payloadType) {
                        RtpPacket.PT_G722 -> g722Decoder.decodeToBytes(encoded)
                        RtpPacket.PT_PCMA -> {
                            if (playbackRate == 8000) PcmaCodec.decode8k(encoded)
                            else PcmaCodec.decode(encoded)
                        }
                        else -> g722Decoder.decodeToBytes(encoded)
                    }
                    slip.appendLe(pcm)
                }

                // How much time is buffered, counting what is still queued as
                // packets as well as what is already samples.
                var depthFrames = (slip.depth + jitterBuffer.size * frameSamples) / frameSamples

                // 2. Latency ceiling.  The slip trims drift gently, and only
                //    gently; a burst far beyond what it can absorb has to be cut
                //    out or it parks its latency in the queue for the rest of the
                //    call.  Only the excess above target goes, and [dropTo] ramps
                //    the seam instead of leaving a hard 20ms hole with a click at
                //    each end.  See JITTER_HIGH_WATER.
                if (depthFrames > JITTER_HIGH_WATER) {
                    val dropped = slip.dropTo(JITTER_TARGET_FRAMES * frameSamples)
                    if (dropped > 0) {
                        trimmedFrames += (dropped + frameSamples - 1) / frameSamples
                        depthFrames = slip.depth / frameSamples
                    }
                }

                // 3. Prefill.  Nothing is played until the reservoir holds its
                //    target depth, and the gate closes again every time the
                //    reservoir runs dry.  Playing straight out of the first
                //    arrival is what made every later late packet cost a whole
                //    frame of silence.
                if (!primed) {
                    if (depthFrames < JITTER_TARGET_FRAMES) {
                        writePlaybackOut(track, silenceFrame)
                        underruns++
                        playbackRms = 0
                        currentPlaybackActive = false
                        wasPlayingSilence = true
                        continue
                    }
                    primed = true
                }

                // 4. Exactly one frame out, read at the fractional rate the depth
                //    asks for: 1.0 exactly when the reservoir is on target, and
                //    then every sample passes through untouched.
                //
                //    With the slip switched off the rate is pinned at 1.0: the
                //    reservoir, its prefill and its ceiling all still work, and only
                //    the interpolation is taken out.  That is the A/B a real call
                //    needs, and it is reversible with one `settings put global`.
                val step = if (slipOn) slip.stepFor(depthFrames, JITTER_TARGET_FRAMES) else 1.0
                val padded = slip.drainLe(step, frame)
                if (padded > 0) {
                    // Ran dry.  Ramp the last real samples down into the gap
                    // rather than leaving a step where the audio stopped, count
                    // it as the audible gap it is, and refill before playing on.
                    applyFadeOutLe(frame, frameSamples - padded)
                    underruns++
                    primed = false
                }

                // Fade-in after silence: prevents modem DSP AGC spike that
                // causes distorted/harsh beginning of each agent phrase.
                // 5ms ramp (40 samples @ 8kHz) is enough to smooth the onset.
                //
                // It is applied to what is about to be written rather than to the
                // packet it arrived in: with the reservoir in between, a phrase
                // can start anywhere inside a frame, and the fade belongs at the
                // seam, not at a packet boundary.
                if (wasPlayingSilence) {
                    if (profile.playbackFadeIn) applyFadeIn(frame)
                }

                // Measure RMS BEFORE gain for the echo gate.  The echo gate
                // threshold was calibrated to raw codec output levels.  If
                // playbackGain > 1, measuring after gain would lower the
                // effective threshold, over-suppressing caller speech.
                val rawRms = pcmRms(frame)
                currentPlaybackActive = rawRms > echoGateThreshold

                // Software gain: boost PCM before writing to AudioTrack.
                // This increases the digital level injected via incall_music
                // without changing STREAM_MUSIC volume (which clips at >14%).
                val gain = playbackGain
                if (gain != 1.0) {
                    for (i in 0 until frame.size / 2) {
                        val lo = frame[i * 2].toInt() and 0xFF
                        val hi = frame[i * 2 + 1].toInt()
                        val sample = ((((hi shl 8) or lo) * gain).toInt())
                        // Soft-limit, not a hard clamp: a boosted peak used to
                        // be cut flat at full scale, heard as crackle on every
                        // loud syllable (playRMS 25198 on a live call, MIUI,
                        // 2026-10-23 — gain x4).  Below the knee nothing
                        // changes; above it the excess is compressed smoothly
                        // towards full scale.
                        val clamped = softLimit(sample)
                        frame[i * 2] = (clamped and 0xFF).toByte()
                        frame[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }

                if (agentMuted) {
                    // Zero before both the modem and the monitor, so someone
                    // listening in hears the mute take effect rather than
                    // audio the caller is no longer getting.
                    java.util.Arrays.fill(frame, 0)
                }
                playbackRms = if (playbackGain > 1) pcmRms(frame) else rawRms
                if (playbackRms > PLAYBACK_WINDOW_LOUD_FLOOR) {
                    windowLoudFrames++
                    if (playbackRms > windowMaxRms) windowMaxRms = playbackRms
                }
                feedMonitor(frame)
                playDump?.let { try { it.write(frame) } catch (_: Exception) {} }
                val out = if (playbackChannels == 2) monoToStereo(frame) else frame
                writePlaybackOut(track, out)
                // A frame that ended in a gap has to be followed by a fade-in,
                // whichever direction the seam is on.
                wasPlayingSilence = padded > 0
                playbackFrames++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
        playbackReservoir = null
        // Withdraw the writer with the loop that owns it: its counters stay
        // readable until the next call replaces it, which is what lets the last
        // stats line of a call still be about the call that just ended.
        //
        // close() first, so the frames still staged at hang-up are counted as
        // lost rather than vanishing between the last stats line and the next
        // call.  They are not flushed: the call is over, and waiting for a route
        // that refused them all call would delay teardown.
        playWriter?.close()
        playWriter = null
    }

    /**
     * Mirror of [applyFadeIn]: ramp the last `fadeMs` of the first [realSamples]
     * samples down to zero.
     *
     * Used where the reservoir ran dry and the rest of the frame is invented
     * silence.  Without it the frame stops at whatever amplitude it happened to
     * be at, which is a step — one click per gap, which is what a listener hears
     * as static.  Ramping costs [realSamples] downwards from a whole syllable's
     * worth of 5ms and buys a seam the ear reads as a dip instead.
     */
    private fun applyFadeOutLe(pcm: ByteArray, realSamples: Int, fadeMs: Int = 5) {
        val count = (playbackRate * fadeMs / 1000).coerceAtMost(realSamples)
        if (count <= 0) return
        val from = realSamples - count
        for (i in 0 until count) {
            val at = (from + i) * 2
            val lo = pcm[at].toInt() and 0xFF
            val hi = pcm[at + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (count - i) / count).toInt().coerceIn(-32768, 32767)
            pcm[at] = (faded and 0xFF).toByte()
            pcm[at + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Apply a 5ms linear fade-in to the start of a PCM buffer.
     * Smooths the silence→speech transition that otherwise causes
     * the modem's uplink AGC/DTX to over-amplify the first samples.
     */
    private fun applyFadeIn(pcm: ByteArray, fadeMs: Int = 5) {
        val fadeSamples = playbackRate * fadeMs / 1000
        val totalSamples = pcm.size / 2
        val count = fadeSamples.coerceAtMost(totalSamples)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (i + 1) / count).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (faded and 0xFF).toByte()
            pcm[i * 2 + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Upsample 8 kHz PCM16 to 16 kHz using 4-tap Catmull-Rom interpolation.
     * Each input sample produces two output samples: the original sample
     * and a midpoint computed as (-s[i-1] + 9*s[i] + 9*s[i+1] - s[i+2]) / 16.
     *
     * This provides ~35 dB spectral image rejection vs ~6 dB for simple
     * linear interpolation, suppressing artifacts in the 4-8 kHz band that
     * G.722's upper sub-band would otherwise encode as spurious content.
     */
    private fun upsample8kTo16k(input: ByteArray): ByteArray {
        val n = input.size / 2
        if (upsampleIn.size != n) {
            upsampleIn = IntArray(n)
            upsampleOut = ByteArray(n * 4) // 2x samples, 2 bytes each
        }
        val output = upsampleOut

        // Read all input samples into an array for random access
        val s = upsampleIn
        for (i in 0 until n) {
            val lo = input[i * 2].toInt() and 0xFF
            val hi = input[i * 2 + 1].toInt()
            s[i] = (hi shl 8) or lo
        }

        for (i in 0 until n) {
            // Even output: original sample (pass-through)
            val even = s[i]

            // Odd output: 4-tap Catmull-Rom midpoint interpolation
            val sm1 = if (i > 0) s[i - 1] else s[0]
            val s0 = s[i]
            val s1 = if (i + 1 < n) s[i + 1] else s[n - 1]
            val s2 = if (i + 2 < n) s[i + 2] else s[n - 1]
            val mid = ((-sm1 + 9 * s0 + 9 * s1 - s2 + 8) shr 4).coerceIn(-32768, 32767)

            output[i * 4] = (even and 0xFF).toByte()
            output[i * 4 + 1] = ((even shr 8) and 0xFF).toByte()
            output[i * 4 + 2] = (mid and 0xFF).toByte()
            output[i * 4 + 3] = ((mid shr 8) and 0xFF).toByte()
        }
        return output
    }

    /** Compute RMS level of PCM16 little-endian audio buffer (0-32767) */
    private fun pcmRms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0
        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample
        }
        return Math.sqrt(sumSquares.toDouble() / sampleCount).toInt()
    }

    /**
     * Set incall_music_enabled=true via AudioManager.
     * Must be called AFTER AudioTrack.play() so the HAL has an active
     * STREAM_MUSIC output to route through the incall-music usecase.
     *
     * Also re-enforces stream volumes here as a secondary safeguard.
     * GsmCallManager sets volumes in configureAudioBridge(), but Android's
     * AudioPolicyManager can reset them when the speaker route change
     * completes.  By the time we get here (after AudioTrack.play()),
     * the route change is long finished, so our volume sticks.
     *
     * On Samsung Exynos (ABOX HAL), the audio_hw_proxy should handle
     * incall_music_enabled internally by routing SIFS→NSRC→voice TX.
     * We also try Samsung-specific parameter names as fallbacks in case
     * the standard parameter is not implemented in this LineageOS build.
     */
    private fun enableIncallMusic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                // Force a false→true transition to start the incall-music
                // usecase in the HAL.  configureAudioBridge() no longer
                // primes true (it caused problems: the earpiece→speaker
                // route change would tear down the voice path and lose the
                // incall-music state, then the second true here was a no-op).
                //
                // By this point:
                //  1. AudioTrack.play() has started (active STREAM_MUSIC output)
                //  2. Speaker route is settled (configureAudioBridge ran 1+ sec ago)
                //  3. restoreAudio from previous call set false (clean slate)
                //
                // The explicit false first ensures the HAL processes it as a
                // genuine state transition, even if some stale true leaked.
                // 50ms gap lets the HAL fully tear down before re-creating.
                val param = profile.incallMusicParam
                if (param.isNotEmpty()) {
                    it.setParameters("${param}=false")
                    Thread.sleep(50)
                    it.setParameters("${param}=true")
                }

                // Samsung Exynos: additional HAL params for incall music injection.
                // Only when incallMusicParam is configured (empty = skip to avoid
                // breaking VOICE_CALL capture).
                // Samsung Exynos: additional HAL params for incall music.
                // These are "best effort" — on Exynos 9820, no visible mixer
                // effect is observed, but they may help on other Exynos devices.
                if (profile.name.contains("Exynos") && param.isNotEmpty()) {
                    it.setParameters("g_call_path=on")
                    it.setParameters("abox_incall_music=on")
                    it.setParameters("incall_music=1")
                }

                GsmCallManager.enforceVolumes(it)

                val msg = "incall_music: param=${param.ifEmpty { "NONE" }}, mode=${it.mode}" +
                    if (param.isNotEmpty() && profile.name.contains("Exynos")) " +g_call_path +abox_incall_music +incall_music=1" else ""
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set incall_music_enabled: ${e.message}")
            listener?.onRtpStats("incall_music FAILED: ${e.message}")
        }
    }

    /**
     * Fallback: use root (Magisk) to set incall_music mixer controls
     * directly via tinymix.  Device-specific commands from the profile.
     *
     * The bare 'tinymix' in profile commands is resolved to the
     * discovered full path via [DeviceProfile.resolveCmd].
     */
    private fun enableIncallMusicViaMixer() {
        val mixerCmd = profile.mixerIncallMusicCmd
        if (mixerCmd.isEmpty()) {
            Log.i(TAG, "Mixer: no incall_music commands for ${profile.name}")
            return
        }
        val resolvedMixerCmd = DeviceProfile.resolveCmd(mixerCmd)
        if (resolvedMixerCmd.isEmpty()) {
            val msg = "Mixer: tinymix not found — cannot set incall_music mixer"
            Log.e(TAG, msg)
            listener?.onRtpStats(msg)
            return
        }
        Thread({
            try {
                // Just the routing commands.  This used to append a readback of
                // four 'ABOX ...' controls, which exist only on Exynos — on
                // every call, on a Qualcomm device, that was four more root
                // round-trips during audio setup to print "Invalid mixer ctl".
                val output = RootShell.execForOutput(resolvedMixerCmd, timeoutMs = 8000)
                if (output.isNotBlank()) Log.i(TAG, "Mixer incall_music: $output")
            } catch (e: Exception) {
                Log.w(TAG, "Mixer fallback failed: ${e.message}")
                listener?.onRtpStats("Mixer incall_music FAILED: ${e.message}")
            }
        }, "RTP-Mixer").start()
    }

    /**
     * Re-assert RECORD_AUDIO appops via root.  Android's AppOpsService
     * re-revokes this permission for background apps when the screen turns
     * off, killing VOICE_CALL capture (rawCapRMS drops to ~6).  Called
     * at 3s and then every 5s from timeoutLoop to keep capture alive.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode.  Without --uid, the command "succeeds" (exit=0) but
     * AudioFlinger still denies with "Request denied by app op: 27".
     */
    private fun reAssertAppOps() {
        try {
            val pkg = context.packageName
            val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            // Ask before acting.  The sequence below is eight root commands,
            // two of them killing PermissionController, and pm/appops/cmd each
            // fork an app_process; running it unconditionally every 15s cost
            // hundreds of process launches across a single call to re-grant a
            // permission that was already granted.  One `appops get` is cheap,
            // and the expensive path now only runs when something really has
            // revoked it — which is the situation it was written for.
            val probe = RootShell.execForOutput(
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            if (probe.contains("allow", ignoreCase = true)) {
                Log.d(TAG, "appops RECORD_AUDIO still allow — nothing to do")
                return
            }
            Log.w(TAG, "appops RECORD_AUDIO not allowed [$probe] — re-granting")
            val t0 = System.currentTimeMillis()
            // Use execForOutput to capture stderr/stdout from appops commands.
            // Previous approach hid all errors and put killall last (exit=1 always).
            // Now: appops get --uid is the LAST command so exit code is meaningful,
            // and all errors are captured via 2>&1.
            // AUTO_REVOKE_PERMISSIONS_IF_UNUSED: Android 11+ (API 30)
            // appops --uid flag: Android 10+ (API 29)
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops re-assert: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                // Fallback: try cmd appops (different IPC path to AppOpsService)
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops re-assert failed: ${e.message}")
        }
    }

    /**
     * Re-toggle incall_music_enabled false→true as a safety net.
     * Called ~3s after RTP start when the speaker route change is
     * guaranteed to be complete.  Handles edge cases where the initial
     * toggle in enableIncallMusic() fired before the route settled.
     */
    private fun reToggleIncallMusic() {
        val param = profile.incallMusicParam
        if (param.isEmpty()) return
        if (profile.incallMusicBeforeTrack) {
            // The toggle is what kills in-call capture on this HAL; the
            // pre-track set has already done the job.
            return
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setParameters("${param}=false")
                Thread.sleep(50)
                it.setParameters("${param}=true")
                GsmCallManager.enforceVolumes(it)
                Log.i(TAG, "${param} re-toggled (3s safety), mode=${it.mode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${param} re-toggle failed: ${e.message}")
        }
    }

    /**
     * Log diagnostic info about capture capability:
     * Phase 1: NSRC routing + bridge state
     * Phase 2: mixer_paths.xml incall_music voice path definitions
     * Phase 3: /proc/asound capture/playback PCM status
     * Phase 6: Delayed NSRC re-check (t+5s)
     * Phase 7: ALSA capture PCM probe (tinycap, if available)
     */
    /**
     * Whether to run the full capture diagnostic sweep at call setup.
     *
     * That sweep greps every mixer_paths XML, walks all of /proc/asound,
     * sleeps five seconds and re-checks, then tinycaps every running PCM and
     * pipes each through od|grep — a 30s root budget, on every single call.
     * It is what established the routing on this SoC and on the Exynos that
     * turned out to have no digital path at all; with that settled it is pure
     * cost on the call path.  Left switchable for the next unknown device:
     *
     *   adb shell su -c "am broadcast ..."  — or simply set the pref:
     *   getSharedPreferences("gateway", 0).edit().putBoolean("audio_diag", true)
     */
    private fun audioDiagnosticsEnabled(): Boolean = try {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getBoolean("audio_diag", false)
    } catch (_: Exception) { false }

    /**
     * Reads one of this gateway's own `Settings.Global` switches — the same
     * mechanism [CallRedirection] uses for `callagent_uplink_via`, so one
     * `settings put global` takes a mechanism out of the next call and a
     * `settings get global` says what the last one ran with.
     *
     * Global rather than the app's preferences because it has to outlive an app
     * update and a reboot, and because it is read from a shell over a USB cable.
     * Reads need no permission; writing needs root, which is the right place for
     * a switch that changes the call path.
     */
    private fun globalFlag(key: String, default: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, key, default)
    } catch (_: Throwable) {
        default
    }

    private fun logCaptureDiagnostics(record: AudioRecord) {
        try {
            // Check CAPTURE_AUDIO_OUTPUT (system permission, not runtime)
            val hasCaptureOutput = context.checkCallingOrSelfPermission(
                "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput " +
                "session=${record.audioSessionId} state=${record.state} " +
                "recState=${record.recordingState} source=$audioSourceName")
            listener?.onRtpStats("Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput")

            // Comprehensive ABOX routing dump via root
            val tinymix = DeviceProfile.tinymixBin
            if (tinymix.isNotEmpty()) {
                Thread({
                    try {
                        // Phase 1: NSRC routing + bridge state
                        val routingCmd = buildString {
                            append("echo '=== NSRC routing ==='; ")
                            for (i in 0..2) {
                                append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                            }
                            append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                        }
                        val routing = RootShell.execForOutput(routingCmd, timeoutMs = 8000)
                        for (line in routing.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 2: mixer_paths.xml — voice call & incall_music paths
                        val mixerPathsCmd = buildString {
                            append("echo '=== mixer_paths voice/incall ==='; ")
                            // Search for incall_music path definitions
                            append("for f in /vendor/etc/mixer_paths*.xml /vendor/etc/audio/mixer_paths*.xml; do ")
                            append("  if [ -f \"\$f\" ]; then ")
                            append("    echo \"--- \$f ---\"; ")
                            // Grep for incall_music path (if it exists, shows what controls to set)
                            append("    grep -B2 -A15 'incall.music\\|incallmusic' \"\$f\" 2>/dev/null | head -40; ")
                            // Also show voice_call path for comparison
                            append("    echo '--- voice_call path ---'; ")
                            append("    grep -B1 -A10 'name=\"voice-call\"\\|name=\"voice_call\"' \"\$f\" 2>/dev/null | head -30; ")
                            // Search for any path that mentions UAIF (modem TX)
                            append("    echo '--- UAIF paths ---'; ")
                            append("    grep -i 'uaif' \"\$f\" 2>/dev/null | head -20; ")
                            append("  fi; done")
                        }
                        val mixerPaths = RootShell.execForOutput(mixerPathsCmd, timeoutMs = 5000)
                        for (line in mixerPaths.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 3: Active capture/playback PCMs + hw_params (all cards)
                        val pcmCmd = buildString {
                            append("echo '=== capture PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*c/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("echo \"\$card/\$pcm: \$s\"; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"  hw: \$(cat \${d}/hw_params 2>/dev/null | head -5 | tr '\\n' ' ')\"; ")
                            append("name=\$(cat \${d%/sub0}/info 2>/dev/null | grep '^name:' | head -1); ")
                            append("[ -n \"\$name\" ] && echo \"  \$name\"; ")
                            append("fi; done; ")
                            // Also dump playback PCMs (all cards)
                            append("echo '=== playback PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*p/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"\$card/\$pcm: \$s  hw: \$(cat \${d}/hw_params 2>/dev/null | head -3 | tr '\\n' ' ')\"; ")
                            append("fi; done")
                        }
                        val pcmResult = RootShell.execForOutput(pcmCmd, timeoutMs = 3000)
                        for (line in pcmResult.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 4-5 removed in v2.8.43 — routing map fully
                        // established (Madera codec, SLIMTX, HPOUT, DSP, ASRC).

                        // Phase 6: Delayed re-check (5s) — see if routing changes
                        // after enableIncallMusic/enableIncallMusicViaMixer run.
                        Thread.sleep(5000)
                        if (running.get()) {
                            val recheck = buildString {
                                append("echo '=== NSRC re-check (t+5s) ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val recheckResult = RootShell.execForOutput(recheck, timeoutMs = 8000)
                            for (line in recheckResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 7: Probe ALSA capture PCMs for non-zero audio data.
                        // All AudioRecord sources return silence (rawCapRMS=0) on this
                        // device.  This probe reads raw bytes from each RUNNING capture
                        // PCM across ALL cards to find which device has modem downlink
                        // audio.  Card 1 (aboxvdma) may carry modem voice data.
                        if (running.get()) {
                            val probeCmd = buildString {
                                append("echo '=== ALSA capture PCM probe (all cards) ==='; ")
                                append("if [ ! -x ${RootPcmCapture.TINYCAP} ]; then ")
                                append("  echo 'tinycap not found'; ")
                                append("else ")
                                // Iterate all capture PCMs across all cards
                                append("for d in /proc/asound/card*/pcm*c; do ")
                                append("  [ -d \"\$d/sub0\" ] || continue; ")
                                append("  s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                                append("  card=\${d#/proc/asound/}; card=\${card%%/*}; ")
                                append("  cardnum=\${card#card}; ")
                                append("  pcm=\${d##*/}; devnum=\${pcm#pcm}; devnum=\${devnum%c}; ")
                                append("  name=\$(cat \$d/info 2>/dev/null | grep '^name:' | head -1 | cut -d: -f2-); ")
                                append("  echo \"\$card/\$pcm:\$name status=\$s\"; ")
                                append("  if echo \"\$s\" | grep -q RUNNING; then ")
                                // Parse actual hw_params for this PCM
                                append("    ch=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^channels:' | awk '{print \$2}'); ")
                                append("    rate=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^rate:' | awk '{print \$2}'); ")
                                append("    fmt=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^format:' | awk '{print \$2}'); ")
                                append("    bits=16; echo \"\$fmt\" | grep -q S32 && bits=32; ")
                                append("    echo \"  hw: fmt=\$fmt ch=\$ch rate=\$rate bits=\$bits\"; ")
                                // Capture 1 second of raw audio with actual params
                                append("    timeout 2 ${RootPcmCapture.TINYCAP} /data/local/tmp/probe_\${cardnum}_\${devnum}.raw ")
                                append("-D \$cardnum -d \$devnum -c \${ch:-2} -r \${rate:-48000} -b \${bits} -p 480 -n 4 2>&1 | head -2; ")
                                append("    f=/data/local/tmp/probe_\${cardnum}_\${devnum}.raw; ")
                                append("    if [ -f \"\$f\" ] && [ -s \"\$f\" ]; then ")
                                append("      sz=\$(wc -c < \"\$f\"); ")
                                append("      nz=\$(od -An -tx1 \"\$f\" | tr ' ' '\\n' | grep -cv '^00\$\\|^\$'); ")
                                append("      echo \"  probe: \${sz}B, \${nz} non-zero bytes\"; ")
                                append("      rm -f \"\$f\"; ")
                                append("    else ")
                                append("      echo '  probe: no data captured'; ")
                                append("    fi; ")
                                append("  fi; ")
                                append("done; ")
                                append("fi")
                            }
                            val probeResult = RootShell.execForOutput(probeCmd, timeoutMs = 30000)
                            for (line in probeResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Diag routing check failed: ${e.message}")
                    }
                }, "CaptureDiag").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture diagnostics failed: ${e.message}")
        }
    }

    /**
     * Read CPU/memory/thread stats for diagnostics.
     * - System load from /proc/loadavg (1/5/15 min averages; >2.0 = overloaded on dual-core)
     * - Process CPU% from /proc/self/stat utime+stime delta over sample interval
     * - JVM heap usage and thread count
     */
    private fun getCpuStats(): String {
        return try {
            val sb = StringBuilder()

            // /proc/loadavg is blocked by SELinux (proc_loadavg) on priv_app.
            // Process CPU% — read utime(14) + stime(15) from /proc/self/stat
            // These are in clock ticks (typically 100 Hz on ARM).
            try {
                val statFields = java.io.File("/proc/self/stat").readText().trim()
                    .substringAfter(") ")  // skip past "(comm) "
                    .split(" ")
                // Fields after ") ": index 0=state, ..., 11=utime, 12=stime
                val utime = statFields.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = statFields.getOrNull(12)?.toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val nowMs = System.currentTimeMillis()

                if (lastCpuSampleMs > 0) {
                    val tickDelta = totalTicks - lastCpuTicks
                    val msDelta = nowMs - lastCpuSampleMs
                    if (msDelta > 0) {
                        // Convert ticks to ms (100 ticks/sec = 10ms/tick)
                        val cpuMs = tickDelta * 10
                        val cpuPct = (cpuMs * 100) / msDelta
                        sb.append(" cpu=${cpuPct}%")
                    }
                }
                lastCpuTicks = totalTicks
                lastCpuSampleMs = nowMs
            } catch (_: Exception) {}

            // Thread count and JVM heap
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val threads = Thread.activeCount()
            sb.append(" mem=${usedMb}/${maxMb}M thr=$threads")

            sb.toString()
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "RtpSession"

        /** Per-thread wait in [stop] before the audio devices are released
         *  anyway.  A capture read is one 20ms frame and the playback poll is
         *  18ms, so a healthy loop is gone well inside this. */
        private const val JOIN_TIMEOUT_MS = 400L
        /** Installed by the Magisk module, next to tinymix. */
        private const val TINYPCM = "/data/local/tmp/tinypcm"
        /** One raw PCM path per device: a session being replaced must finish
         *  its teardown before the next one routes. */
        private val ROOT_PCM_LOCK = Any()

        /** Launches of tinycap per call before the AudioRecord sources get
         *  a turn — see [captureInitAndLoop]. */
        private const val ROOT_CAPTURE_ATTEMPTS = 3

        /** Packet queue ceiling, in 20ms frames (240ms). */
        private const val JITTER_CAPACITY = 12
        /** Reservoir depth the playback loop aims at, in 20ms frames (60ms).
         *
         *  This is the elasticity that absorbs a late arrival, so it has to be
         *  sitting there before it is needed: the loop prefills to it, holds it,
         *  and returns to it whenever the reservoir runs dry.  It is deliberately
         *  less latency than the old design actually carried — that one ran
         *  pinned at 140ms of queue and still glitched. */
        private const val JITTER_TARGET_FRAMES = 3
        /** Reservoir depth above which the excess is shed in one go, seam ramped
         *  ([PlaybackSlip.dropTo]), instead of one whole packet at a time
         *  (140ms).  The slip is capped at ±0.6% because more than that is heard
         *  as pitch, so a burst far beyond it has to be cut out: keeping it would
         *  park its latency in the queue for the rest of the call. */
        private const val JITTER_HIGH_WATER = 7

        /** Registry key: `0` to bypass the RTP timeline gate ([RtpSequencer]) and
         *  take audio packets in arrival order again — the on-air A/B for the
         *  repetition report of 2026-11-06.  Default on. */
        private const val SETTING_RTP_GATE = "callagent_rtp_gate"

        /** Registry key: `0` to drain the reservoir at exactly 1:1 instead of at
         *  the fractional rate [PlaybackSlip.stepFor] asks for, leaving the
         *  interpolation out of the call while the reservoir, its prefill and its
         *  ceiling all stay.  Default on. */
        private const val SETTING_PLAYBACK_SLIP = "callagent_playback_slip"

        /** Registry key: how the playback loop feeds its track — frames of room
         *  kept in front of the sink before a refusal turns into a loss.  `0` is
         *  build 470's contract (a refusal discards, but paced and counted), `-1`
         *  is build 470 exactly as shipped (unpaced), `1..10` is that many frames
         *  of staging, up to [PlaybackWriter.MAX_QUEUE_FRAMES].  `-2`, or no value
         *  at all, means "choose by route": staging on `callagent_uplink_via=0`,
         *  where the write is measured to jam, build 470's own write on the
         *  acoustic routes, where it is not.  See the [playStage] note.
         *
         *  This is the switch that makes the 2026-11-07 loss measurable on one
         *  call instead of arguable: see [PlaybackWriter] for the two numbers and
         *  `gateway-test/gw-uplink-hardening-470.md` for how to use it. */
        private const val SETTING_PLAY_STAGE = "callagent_play_stage"

        /** [SETTING_PLAY_STAGE] value meaning "whatever the route this call took
         *  calls for".  Also the value a device with no setting written reads,
         *  since [globalFlag] hands back its default and that default is this.
         *  Kept away from `-1`, which means something else. */
        private const val PLAY_STAGE_FOLLOW_ROUTE = -2

        /** Frame level above which a playback frame is counted as loud for the
         *  per-window witness (`playWinLoud`/`playWinMax`).
         *
         *  8000 RMS: the test tone written at amp 12000 arrives through A-law at
         *  about 11800, while ordinary speech sits in the thousands and only
         *  peaks past this.  The count, times the 20 ms frame length, is the
         *  length of what was spoken in that window — the number that shows
         *  whether a press lasted the 200 ms it was asked for.  Best-effort by
         *  design: the increment is not synchronised against the stats thread's
         *  reset, so a lost frame is possible and changes nothing that matters. */
        private const val PLAYBACK_WINDOW_LOUD_FLOOR = 8000
    }
}

/**
 * G.711 A-law (PCMA) codec — fallback if G.722 is not negotiated.
 * Narrowband (8 kHz), adequate for basic voice.
 */
object PcmaCodec {
    private val ALAW_ENCODE_TABLE = intArrayOf(
        1, 1, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7
    )

    /**
     * G.711 A-law, as in the ITU reference encoder.
     *
     * Below 256 the segment is always 0 and the mantissa is the sample's
     * bits 4..7.  The segment table used to be applied there too, so every
     * quiet sample (16..255, which is every zero crossing of speech) was
     * coded as a loud one: 150 came out as 2240.  GSM-to-SIP audio sounded
     * like a radio at the edge of range while the other direction, which
     * only decodes, was clean.  Found and fixed by Evgenii Shapovalov.
     */
    fun encodeSample(pcm: Short): Byte {
        var linear = pcm.toInt()
        val mask: Int
        if (linear >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            linear = -linear - 1
        }
        val seg = if (linear < 256) 0 else ALAW_ENCODE_TABLE[(linear shr 8) and 0x7F]
        val mantissa = if (seg == 0) (linear shr 4) and 0x0F else (linear shr (seg + 3)) and 0x0F
        return (((seg shl 4) or mantissa) xor mask).toByte()
    }

    fun decodeSample(alaw: Byte): Short {
        var value = (alaw.toInt() and 0xFF) xor 0xD5
        val sign = value and 0x80
        val exponent = (value shr 4) and 7
        var mantissa = value and 0x0F

        mantissa = (mantissa shl 4) + 8
        if (exponent > 0) mantissa = (mantissa + 256) shl (exponent - 1)

        return if (sign != 0) (-mantissa).toShort() else mantissa.toShort()
    }

    /** Encode 8kHz PCM16 to A-law directly (no sample rate conversion). */
    fun encode8k(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /** Encode 16kHz PCM16 to A-law with 2:1 averaging downsample.
     *  Averages each pair of adjacent samples before encoding, acting as
     *  a simple low-pass filter that prevents aliasing artifacts from
     *  high frequencies folding back into the 4kHz output band. */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val outSize = sampleCount / 2
        val out = ByteArray(outSize)
        var outIdx = 0
        for (i in 0 until sampleCount step 2) {
            val lo0 = pcm[i * 2].toInt() and 0xFF
            val hi0 = pcm[i * 2 + 1].toInt()
            val s0 = (hi0 shl 8) or lo0
            val s1 = if (i + 1 < sampleCount) {
                val lo1 = pcm[(i + 1) * 2].toInt() and 0xFF
                val hi1 = pcm[(i + 1) * 2 + 1].toInt()
                (hi1 shl 8) or lo1
            } else s0
            val avg = ((s0 + s1) / 2).toShort()
            out[outIdx++] = encodeSample(avg)
            if (outIdx >= outSize) break
        }
        return out
    }

    /** Decode A-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 2)
        var outIdx = 0
        for (byte in alaw) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode A-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling.
     *  Each input sample produces two output samples: the original and a
     *  midpoint interpolated toward the next sample.  Eliminates the
     *  aliasing/zipper noise from the previous zero-order hold approach. */
    fun decode(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 4)
        var outIdx = 0
        for (i in alaw.indices) {
            val s0 = decodeSample(alaw[i]).toInt()
            val s1 = if (i + 1 < alaw.size) decodeSample(alaw[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = ((s0 shr 8) and 0xFF).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = ((mid shr 8) and 0xFF).toByte()
        }
        return out
    }
}
