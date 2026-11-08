package com.callagent.gateway.rtp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.callagent.gateway.UplinkRouteSetting
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * The call audio of the Exynos 990 (S20+), reached through the framework's
 * call-redirection AudioPolicy instead of through the HAL or the ABOX DSP.
 *
 * On the devices that work through the HAL, one of our own streams is what the
 * HAL routes into and out of the modem: an AudioRecord on a telephony source
 * gives the caller, an AudioTrack on the incall_music output gives the agent.
 * The Exynos 990 HAL on this firmware offers neither — no telephony mixPort, no
 * incall_music, no in-call capture — which is why the ABOX route (see
 * [com.callagent.gateway.DeviceProfile.rootPcm]) had to reach inside the DSP.
 * That route needs a kernel module that panics this particular kernel, so the
 * call audio has to come from a different place entirely.
 *
 * It is still there, just not where the HAL looks for it.  Measured on this
 * device while a call was up: the call's media is carried by two ordinary
 * AudioFlinger clients —
 *
 *   downlink  a *playback* client, usage VOICE_COMMUNICATION, 8 kHz mono PCM16
 *   uplink    a *record* client, capture preset VOICE_COMMUNICATION, 8 kHz mono
 *             (`com.android.phone` — the modem's IMS media path reading its side
 *             of the call off the audio stack)
 *
 * An AudioPolicy with two mixes can take both of those over:
 *
 *   MIX_ROLE_PLAYERS  + RULE_MATCH_ATTRIBUTE_USAGE(VOICE_COMMUNICATION), with
 *     AudioMixingRule.setVoiceCommunicationCaptureAllowed(true) on the rule,
 *     pulls that playback client into a mix of our own.  `createAudioRecordSink`
 *     then hands us an AudioRecord whose input *is* the mix: the caller,
 *     digitally, with nothing playing on the handset.
 *
 *   MIX_ROLE_INJECTOR + RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET(VOICE_COMMUNICATION)
 *     makes a record client opened on that preset read from a virtual source of
 *     ours instead of the mic, and `createAudioTrackSource` hands us the
 *     AudioTrack that is that source.
 *
 * The downlink half of that is what the app runs on, and it is what makes the
 * caller audible to the SIP side.  The uplink half is not, and the claim this
 * file used to make for it — that keys pressed into the injector were heard by
 * the far end — was never earned.  Two presses put on the line with the injector
 * live were answered by nothing at the far end, against a paired call that made
 * no press at all and behaved identically, and the witness taken while such a
 * call was up says why:
 *
 *   - `dumpsys media.audio_policy` mid-call lists this HAL's whole output
 *     inventory — earpiece, speaker, Bluetooth SCO and A2DP, HDMI, hearing aid,
 *     remote submix, USB, wired — and it carries no
 *     `AUDIO_DEVICE_OUT_TELEPHONY_TX`.  The audio stack here has no endpoint that
 *     means "into the modem's transmit path", so an injected stream has nowhere
 *     to be routed to.
 *   - mid-call, our own injector track is routed to "Remote Submix Out", and the
 *     clients reading a remote-submix input are our own sink plus one record
 *     client from `com.android.ons` (uid 1001) opened on the VC preset, which our
 *     injector mix had taken over — and which read flat digital silence, -72 dB
 *     for its whole life, during a call that was only being listened to.
 *
 * What carries the caller was written here as a DSP claim until 2026-11-07; the
 * witnesses taken that day say something else, and it is what the downlink runs on:
 * on this build the transmit path of the call is *inside* the audio stack.  One
 * record client owns it - uid 1001 / pid 7981, the IMS media process, on
 * AUDIO_SOURCE_VOICE_COMMUNICATION, 8 kHz mono PCM16, with the Acoustic Echo
 * Canceler and Noise Suppression chains attached to its session - and there is no
 * AudioFlinger output thread anywhere pointing at a voice-call-up device: the modem
 * is not handed a stream, it reads that record client.
 *
 * That one stream changes its input device when the setting below changes, three
 * dumps per call agreeing (gate+4.0, +16.6, +18.5 s of a live call):
 *
 *   uplink_via=1  input device 0x80000004 BUILTIN_MIC - and the HAL still prints its
 *                 microphone signal-power history for it, which never once fell
 *                 below -54 dBFS while the call was up, i.e. the mic floor was in
 *                 the caller's uplink the whole time.
 *   uplink_via=0  input device 0x80000100 IN_REMOTE_SUBMIX - and the HAL prints no
 *                 microphone history for it at all ("Signal power history: (none)").
 *
 * So the microphone is not bolted below the audio stack: it is the input device of a
 * stream inside the stack.  That is why the acoustic route carries speech at all, and
 * why cutting the DMICs or the VTS uplink at the DSP would take this route's own
 * speech with it.  What is still NOT settled is whether audio pushed by the injector
 * mix really reaches that stream: in the uplink_via=0 call our own remote-submix tap
 * showed no trace of the 300 ms digit that was injected (852/1336 Hz pair: 1.7 at the
 * press window, nothing anywhere), so treat 0 as unproven for carrying speech.  See
 * `gateway-test/gw-mic-isolation-470.md` for the runs, and leave the default alone
 * until a far end that can hear has judged it.
 *
 * `Settings.Global/callagent_uplink_via` says which of those to use, so that the
 * question is settled by a call and not by an argument:
 *
 *   0 (default) the injector mix, as above.
 *   1 an ordinary in-call AudioTrack on USAGE_VOICE_COMMUNICATION_SIGNALLING —
 *     the usage Android reserves for tones that belong inside a call.
 *   2 the same, on USAGE_NOTIFICATION_COMMUNICATION_REQUEST.
 *
 * 1 and 2 deliberately stay off VOICE_COMMUNICATION: that is the usage our own
 * players mix takes over, so a stream carrying it would be captured into the
 * bridge instead of being played out of the handset.  With either one set, only
 * the downlink mix is registered at all.
 *
 * Marking the *criteria* with AudioAttributes.Builder.setForCallRedirection() is
 * what makes this the call-redirection policy Android has a name for: it is what
 * `AudioMixingRule.isForCallRedirection()` reports, what the permission gate
 * (`CALL_AUDIO_INTERCEPTION`) checks, and what makes the framework treat the sink
 * and the source as call audio rather than as ordinary media.
 *
 * Every class below `android.media.audiopolicy` is @hide/@SystemApi: `javap`
 * against the SDK's android.jar reports `android.media.AudioPolicy`, `AudioMix`,
 * `AudioMixingRule`, `AudioMixPlaybackAttributes` and `AudioMixCaptureAttributes`
 * as *not present* at all.  So there is nothing to compile against, and a stub
 * jar that did carry them would only prove the stub has them.  Reflection is the
 * only honest route: the shape below was read off the device and each member is
 * resolved by name, falling back to the declared (non-public) form, because
 * several of them are package-private on this build.
 *
 * Both directions were proven on this device with the stand-alone harness before
 * anything was put into the app: the caller's announcement came out of the sink
 * at the real 8 kHz rate, and keys pressed into the injector were heard by the
 * far end.  What this class adds is the app's own permission set, since a normal
 * process — even a privileged one — is not root.
 *
 * Measured again inside the app, 2026-11-06, and two of those statements have to
 * be narrowed:
 *
 *   - The injector presses in this app register the mix, create the source and
 *     play, but the injector track is routed to "Remote Submix Out" and the only
 *     client that ever reads a remote-submix input is one of ours, so no press
 *     through it has ever been heard by a far end.  The harness result above was
 *     the stand-alone policy process, not this app.
 *   - Every press run up to and including the first acoustic one put *more* tone
 *     on the wire than it was asked for: the host tool's press state machine
 *     indexed a ten-frame list with a wrapping counter and a guard that stayed
 *     true, so it emitted dual tone until the call ended instead of for the 200 ms
 *     it was told to.  A 9333 announcement hearing twenty seconds of 697+1209 Hz
 *     has no reason to answer, so the "far end did not react" verdicts from those
 *     runs say nothing about the audio path and have been withdrawn.  The witness
 *     that exposed it is the AudioFlinger power history and this app's own stats
 *     line: both kept reporting press level long after the press was over.
 *
 * What the first acoustic run did prove, independently of that bug, is the route:
 * our track (`Usg=3`, 8 kHz mono) sits on the primary mixer with
 * `0x1 AUDIO_DEVICE_OUT_EARPIECE` as its output device, and the call's own record
 * client (`AudioIn_36`, preset VOICE_COMMUNICATION, `0x80000004 BUILTIN_MIC`, AEC
 * reference `0x1` EARPIECE) rises about 14 dB over its noise floor for exactly as
 * long as the tone is played.  Earpiece to microphone to uplink capture is real.
 *
 * The second run, same day, with the press length fixed (measured: 8 tone frames
 * = 160 ms out of the track, 200 ms of it at the microphone, everything else back
 * at the -53 dB floor), got no reaction from the far end either.  The dump says
 * why that was the expected outcome, and it is not a fault in the route: the
 * record session that carries the uplink to the modem (`AudioIn_36`/session 361,
 * client uid 1001/pid 8009) has two preproc effects registered *and enabled*,
 * both at 8000 Hz mono —
 *
 *   Effect 43 `Acoustic Echo Canceler` (AOSP), Enabled=y
 *   Effect 51 `Noise Suppression`      (AOSP), Enabled=y
 *
 * and that record thread's reference output device is `0x1 AUDIO_DEVICE_OUT_EARPIECE`
 * — the device our track is routed to.  A 160 ms stationary dual tone, +13 dB over
 * the microphone floor, played on the echo canceller's own reference and then
 * cleaned by a noise suppressor, is precisely the signal those two effects exist
 * to remove.  Whether they did remove it is not readable from this side: the
 * power history of `AudioIn_36` shows the tone (that witness sits before the
 * effects), the far end does not, and only the far end is a verdict.
 *
 * Whether the canceller removed it was then measured directly, with a second
 * capture client (`callagent_uplink_witness=1`, builds 464 and 465).  The
 * framework gave that client its own processing chain - its session 537 carries
 * an Acoustic Echo Canceler and a Noise Suppression as well - and attached it to
 * the call's own record thread.  It was never handed a single frame:
 *
 *   UPLINK_WITNESS open ... state=1 recording=1 routed=BUILTIN_MIC id=10
 *   UPLINK_WITNESS not fed: reads=50 all returned 0 recorded_frames=0
 *   UPLINK_WITNESS closed windows=0 reads=1846 zero=1846 negative=0 recorded_frames=0
 *
 * So while a call owns the microphone path, this firmware will not share it with
 * a second preset-VOICE_COMMUNICATION capture client, and what those effects
 * deliver is not readable from the application side at all.  The instrument had
 * to be rewritten (non-blocking reads plus that `not fed` line) to report even
 * that: with blocking reads the client simply sits inside `read()` and the
 * witness logs nothing, which is the empty answer build 463 gave.
 *
 * A third press with a correct-length digit (build 465, digit 8 at gate+15.5 s,
 * 160 ms of tone measured out of the track and 150 ms of it on the uplink
 * capture: -52.6 then -39.4/-40.8/-40.5 then back to -52.9 dB) produced no
 * reaction from the far end either, against the same press-free control, in the
 * window gate+16.1 .. gate+21.1 s.
 *
 * So the acoustic route ends here in software, with the reason measured: there is
 * no playback device on this handset that the canceller does not reference while
 * the built-in microphone stays the input.  Playing on the loudspeaker would move
 * the tone outside the canceller's reference, but it is audible, it is the one
 * route that can flip the `Left/Right AMP Enable Switch` controls, and it is out
 * of bounds for a test at night; a headset moves both the reference and the input
 * and cancels just as well; `REMOTE_SUBMIX` is inaudible by construction; and the
 * HAL inventory during a call offers only EARPIECE, SPEAKER and REMOTE_SUBMIX.
 * What is left is a coupler that reaches the modem below the audio stack, which is
 * the kernel route this device cannot run.
 *
 * One variant is recorded but deliberately not tried: splitting the digit into
 * several short bursts, on the theory that the first tens of milliseconds of each
 * burst survive the canceller before its adaptive filter converges.  It is not
 * worth an air test, because a DTMF detector wants a contiguous tone (Q.23: 40 ms
 * minimum, +-25 ms tolerance) and the cancellation edge is shorter than that, so
 * a burst train reads either as one broken digit or as noise; and pressing the
 * same key several times in one call is a different input sequence from the
 * caller's point of view.
 */
class CallRedirection private constructor(
    private val am: AudioManager,
    private val policy: Any,
    private val dlMix: Any,
    private val ulMix: Any?,
    /** Set instead of [ulMix] when the agent's side is meant to be heard through
     *  the handset rather than injected: the usage of the AudioTrack to play it
     *  on.  Null for the injector route. */
    private val acousticUsage: Int?,
) {

    /** The AudioRecord carrying the caller: the players mix's sink. */
    lateinit var sink: AudioRecord
        private set

    /** The AudioTrack carrying the agent: the injector mix's virtual source. */
    lateinit var injector: AudioTrack
        private set

    /** True when the injector came up; without it the caller hears nothing. */
    val hasInjector: Boolean get() = ::injector.isInitialized

    /** True when the injector mix was registered, so a source can be created.
     *  This is the question `start()` has to ask, and `hasInjector` is the wrong
     *  form of it: that one only says the track exists, and before `start()` has
     *  run, it never can. Asking it there is what made every call one-way — the
     *  downlink came up, the injector was never created, and the mix that had
     *  been registered for it sat unused. */
    private val wantsInjector: Boolean get() = ulMix != null

    /**
     * Opens both directions: registers the policy, then asks it for the sink and
     * the source.  Returns null when the registration itself is refused — a
     * permission problem, or a framework that will not take the mix — in which
     * case nothing at all has been opened and there is nothing to undo.
     *
     * [rate] is the geometry the mix is created with.  Measured 8000: both
     * clients of the call are 8 kHz mono, which is the geometry the call's own
     * clients use (see the class note), so nothing has to be resampled on the way.
     */
    fun start(rate: Int): Boolean {
        return try {
            val polC = cls("AudioPolicy")
            val mixC = cls("AudioMix")
            sink = create(polC, "createAudioRecordSink", mixC, dlMix) as AudioRecord
            Log.i(TAG, "POLICY sink init=${sink.state}(1=INITIALIZED) rate=${sink.sampleRate} " +
                "ch=${sink.channelCount}")
            if (wantsInjector) {
                // Not `as AudioTrack`: the framework answers null when it refuses
                // the mix, and a cast on null would take the whole session down
                // for what is really a caller-only call.
                val src = create(polC, "createAudioTrackSource", mixC, ulMix) as? AudioTrack
                if (src == null) {
                    Log.e(TAG, "POLICY createAudioTrackSource refused the injector mix: " +
                        "the caller will not hear the agent")
                } else {
                    injector = src
                    uplinkRoute = "injector"
                    Log.i(TAG, "POLICY injector state=${src.state}(1=INITIALIZED) " +
                        "rate=${src.sampleRate} ch=${src.channelCount} " +
                        "buf=${src.bufferSizeInFrames}f")
                }
            } else if (acousticUsage != null) {
                val trk = newAcousticTrack(rate, acousticUsage)
                if (trk == null) {
                    Log.e(TAG, "UPLINK acoustic track refused: the caller will not hear the agent")
                } else {
                    injector = trk
                    uplinkRoute = "acoustic-usage-$acousticUsage"
                }
            } else {
                Log.w(TAG, "POLICY no injector mix registered: this call will be caller-only")
            }
            sink.startRecording()
            if (hasInjector) injector.play()
            Log.i(TAG, "POLICY running recstate=${sink.recordingState}(3=STARTED) " +
                "playstate=${if (hasInjector) injector.playState else 0}(3=STARTED) " +
                "injector=${hasInjector} uplink=${uplinkRoute}")
            if (acousticUsage != null) Log.i(TAG, "UPLINK out devices now: ${outputDevices()}")
            sink.state == AudioRecord.STATE_INITIALIZED &&
                sink.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (t: Throwable) {
            Log.e(TAG, "POLICY streams failed: ${root(t)}")
            false
        }
    }

    /** Where the agent's side is going on this call, for the witness lines. */
    var uplinkRoute: String = "none"
        private set

    /** The routing witness, kept so it can be withdrawn in [close]. */
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    /** A record client on the same preset the call uses for its uplink, opened only
     *  when `callagent_uplink_witness=1`, purely to read out what a record client
     *  actually receives during the call.
     *
     *  Why this exists: the dump of the acoustic run shows the call's own uplink
     *  record session (`AudioIn_36`, session 361, uid 1001/pid 8009) carries two
     *  enabled preproc effects — an Acoustic Echo Canceler and a Noise Suppression,
     *  both 8 kHz mono — and that thread's echo reference is
     *  `0x1 AUDIO_DEVICE_OUT_EARPIECE`, which is exactly where our acoustic track is
     *  routed.  The raw microphone level rises about 14 dB for the length of the
     *  tone, but that witness sits *before* the effects; what the modem is handed
     *  afterwards is not readable from the dump.  A record client of our own, on the
     *  same preset and therefore with the same processing, is the missing half: if
     *  it reports the tone, the canceller is not removing it and the far end's
     *  silence is the far end's own; if it does not, the acoustic route is dead on
     *  this firmware and the far-end verdict was already telling the truth.
     *
     *  Off by default because it is a second in-call capture client, which the
     *  framework could answer by re-routing; the routing lines above are the
     *  guard that would show that happening. */
    private var witness: AudioRecord? = null
    private var witnessThread: Thread? = null

    /**
     * The acoustic route's playback stream: an ordinary AudioTrack, so the sound
     * leaves the handset and the call's own microphone has to hear it.  Same
     * geometry as everything else on this path — 8 kHz mono PCM16 — so the frames
     * the bridge writes go out unaltered.
     *
     * Refusal is reported as null, not thrown: an unusable knob value should cost
     * the caller's side of the bridge nothing.
     */
    private fun newAcousticTrack(rate: Int, usage: Int): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // A quarter of a second: the bridge feeds this at its own pace and a
            // longer buffer only adds delay between a press and the tone.
            .setBufferSizeInBytes(if (min > rate / 2) min else rate / 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // The stream the call is on is the one whose volume decides how loudly the
        // handset plays this, and therefore whether the call's microphone can hear
        // it at all.  Read, never set.
        val vol = try { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) } catch (_: Throwable) { -1 }
        val max = try { am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) } catch (_: Throwable) { -1 }
        Log.i(TAG, "UPLINK acoustic track usage=$usage state=${trk.state}(1=INITIALIZED) " +
            "rate=${trk.sampleRate} ch=${trk.channelCount} buf=${trk.bufferSizeInFrames}f " +
            "minbuf=$min voice-call-volume=$vol/$max")
        watchRouting(trk)
        trk
    } catch (t: Throwable) {
        Log.e(TAG, "UPLINK acoustic track failed: ${root(t)}")
        null
    }

    /** Asks the framework to say which device this stream lands on.  The track is
     *  opened before the dial, so the route it gets once the call is up is exactly
     *  the question — earpiece and speaker are different answers, and only one of
     *  them is audible to the call's microphone.
     *
     *  This one is ordinary public API (`AudioRouting.OnRoutingChangedListener`,
     *  and `AudioTrack` implements [AudioRouting]).  The first attempt here looked
     *  the interface up as `AudioManager$OnRoutingChangedListener`, which does not
     *  exist on any SDK, and the whole witness came back as a ClassNotFoundException
     *  on build 461.  The AudioFlinger dump of that run answered the routing
     *  question anyway — our track on `AudioOut_D` with `0x1 AUDIO_DEVICE_OUT_
     *  EARPIECE` — so this is a cheap fix, not a new dependency. */
    private fun watchRouting(track: AudioTrack) {
        try {
            val listener = object : AudioRouting.OnRoutingChangedListener {
                override fun onRoutingChanged(routing: AudioRouting) {
                    val routed = try {
                        routing.routedDevice?.let { "${deviceName(it.type)} id=${it.id}" } ?: "none"
                    } catch (t: Throwable) {
                        "unknown: ${root(t)}"
                    }
                    Log.i(TAG, "UPLINK routing now: track_routed=$routed global=${outputDevices()}")
                }
            }
            // Held: the framework keeps only a weak reference to these, and a
            // collected listener is a silent witness rather than a loud one.
            routingListener = listener
            track.addOnRoutingChangedListener(listener, null)
            val at = try {
                track.routedDevice?.let { "${deviceName(it.type)} id=${it.id}" } ?: "none"
            } catch (t: Throwable) {
                "unknown: ${root(t)}"
            }
            Log.i(TAG, "UPLINK routing watch armed: track_routed=$at global=${outputDevices()}")
        } catch (t: Throwable) {
            Log.w(TAG, "UPLINK routing watch unavailable: ${root(t)}")
        }
    }

    /** Opens the uplink witness described on [witness] if the knob asks for it.
     *  Safe to call on every route: it returns without opening anything unless
     *  `Settings.Global/callagent_uplink_witness` is non-zero. */
    fun startUplinkWitness(context: Context, rate: Int) {
        val want = settingValue(context, UPLINK_WITNESS_SETTING)?.toIntOrNull() ?: 0
        Log.i(TAG, "UPLINK_WITNESS_SETTING $UPLINK_WITNESS_SETTING=$want (1=listen to the in-call uplink)")
        if (want == 0) return
        if (acousticUsage == null) {
            // On the injector route the capture-preset mix we registered has already
            // taken over every record client on this preset, ours included: the
            // witness would read our own virtual source and report silence back to
            // us.  There is nothing to measure there.
            Log.w(TAG, "UPLINK_WITNESS not started: the injector mix would capture " +
                "this witness too, so it could only read its own virtual source")
            return
        }
        try {
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                if (min > 0) min else 4096
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                // -3 = ERROR_BAD_VALUE from getMinBufferSize, i.e. this geometry is
                // not offered for this source; the HAL refusing in-call capture
                // shows up here too.  Either way the call is unaffected.
                Log.w(TAG, "UPLINK_WITNESS refused: state=${rec.state}, minbuf=$min")
                rec.release()
                return
            }
            rec.startRecording()
            val routed = try {
                rec.routedDevice?.let { "${deviceName(it.type)} id=${it.id}" } ?: "none"
            } catch (t: Throwable) {
                "unknown: ${root(t)}"
            }
            // `recordingState` (getRecordingState) is the form this SDK carries;
            // recordingStatus() is not in the android.jar we compile against.
            Log.i(TAG, "UPLINK_WITNESS open rate=$rate source=VOICE_COMMUNICATION state=1 " +
                "recording=${rec.recordingState} routed=$routed")
            witness = rec
            val t = Thread { witnessLoop(rec) }
            witnessThread = t
            t.isDaemon = true
            t.start()
        } catch (t: Throwable) {
            Log.w(TAG, "UPLINK_WITNESS failed: ${root(t)}")
        }
    }

    /** One 500 ms line per window: what a record client on the call's own preset is
     *  being handed, in int16 RMS and peak.
     *
     *  Read it as a floor and an event, not as a level.  AudioFlinger's own history
     *  for the same path sits at -53 dBFS of noise floor and put the tone at
     *  -40 dBFS, which on this scale is roughly 90 and 330; a window during the
     *  press that is not clearly above the surrounding windows means the
     *  canceller and the noise suppresser removed the tone before any client could
     *  read it.
     *
     *  What it measured on this firmware (build 464, 2026-11-06, one call): the
     *  client opened, reported `routed=BUILTIN_MIC`, was attached to the call's own
     *  record thread in the AudioFlinger dump — in state `s`, standby — and delivered
     *  nothing: zero windows, no error.  The in-call microphone path is not shared
     *  with a second VOICE_COMMUNICATION capture client, so there is no way from
     *  here to read the uplink *after* the canceller and the noise suppresser.  The
     *  loop below is written for that answer explicitly (non-blocking reads and a
     *  `not fed` line) rather than leaving it as an empty range again. */
    private fun witnessLoop(rec: AudioRecord) {
        val frame = ShortArray(160)                     // 20 ms at 8 kHz
        var filled = 0
        var winRms = 0.0
        var winPeak = 0
        var windows = 0
        var floor = Int.MAX_VALUE
        var top = 0
        var reads = 0
        var zeros = 0
        var negatives = 0
        var framesRead = 0
        var reported = false
        while (witnessThread === Thread.currentThread()) {
            // Non-blocking on purpose.  With the in-call capture path already owned
            // by the telephony client, the blocking form of read() simply never
            // returns and the witness says nothing at all (build 463/464 measured
            // exactly that: the client is attached to the call's own record thread
            // and sits in `s` - standby - in the AudioFlinger dump, and the loop
            // ended with windows=0 and no error to show for it).  Non-blocking says
            // plainly whether the platform is handing this client any frames.
            val n = try {
                rec.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
            } catch (t: Throwable) {
                Log.w(TAG, "UPLINK_WITNESS read threw: ${root(t)}")
                return
            }
            reads++
            if (n < 0) {
                negatives++
                if (!reported) {
                    // -2 INVALID_OPERATION (not recording), -11 INVALID_USER,
                    // -34 NOT_INITIALIZED.  Worth one line, not a stream.
                    Log.w(TAG, "UPLINK_WITNESS first read returned $n")
                    reported = true
                }
                sleepQuietly(20)
                continue
            }
            if (n == 0) {
                zeros++
                if (zeros == WITNESS_NOT_FED_READS && !reported) {
                    // ~1 s of reads with no frames.  That is the answer this
                    // instrument was built to get on this firmware: a second
                    // VOICE_COMMUNICATION capture client during a call is not fed,
                    // so the post-echo-canceller uplink cannot be read out here.
                    Log.w(TAG, "UPLINK_WITNESS not fed: reads=$reads all returned 0 " +
                        "recorded_frames=0 (the in-call mic path stays with the " +
                        "telephony client; nothing for this witness to measure)")
                    reported = true
                }
                sleepQuietly(10)
                continue
            }
            if (!reported) {
                Log.i(TAG, "UPLINK_WITNESS first read returned $n frames")
                reported = true
            }
            framesRead += n
            var sum = 0L
            var peak = 0
            for (i in 0 until n) {
                val s = frame[i].toInt()
                sum += s.toLong() * s
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
            }
            val rms = kotlin.math.sqrt(sum.toDouble() / n)
            if (rms > winRms) winRms = rms
            if (peak > winPeak) winPeak = peak
            if (++filled >= WITNESS_WINDOW_FRAMES) {
                val r = winRms.toInt()
                windows++
                if (r < floor) floor = r
                if (r > top) top = r
                Log.i(TAG, "UPLINK_WITNESS t=${windows * WITNESS_WINDOW_FRAMES * 20}ms " +
                    "rms=$r peak=$winPeak frames=$filled")
                filled = 0; winRms = 0.0; winPeak = 0
            }
        }
        Log.i(TAG, "UPLINK_WITNESS closed windows=$windows reads=$reads zero=$zeros " +
            "negative=$negatives recorded_frames=$framesRead " +
            "floor_rms=${if (floor == Int.MAX_VALUE) "n/a" else floor} top_rms=$top")
    }

    /** Witness pacing only; never on the bridge's audio threads. */
    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** What the framework has routed playback to right now, as a witness for the
     *  acoustic route: earpiece and speaker are different routes and the call's
     *  microphone only hears one of them. */
    private fun outputDevices(): String = try {
        val l = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (l == null || l.isEmpty()) "none"
        else l.joinToString(", ") { "${deviceName(it.type)}(id=${it.id})" }
    } catch (t: Throwable) {
        "unavailable: ${root(t)}"
    }

    /** AudioDevice type names, read off android.jar rather than remembered: an
     *  earlier note here had 15 as Bluetooth SCO, and 15 is the built-in
     *  microphone.  Only the names that can matter on this handset are spelled
     *  out; anything else prints as its number. */
    private fun deviceName(type: Int): String = when (type) {
        1 -> "EARPIECE"           // TYPE_BUILTIN_EARPIECE
        2 -> "SPEAKER"            // TYPE_BUILTIN_SPEAKER
        7 -> "BLUETOOTH_SCO"     // TYPE_BLUETOOTH_SCO
        15 -> "BUILTIN_MIC"      // TYPE_BUILTIN_MIC
        18 -> "TELEPHONY"        // TYPE_TELEPHONY
        25 -> "REMOTE_SUBMIX"    // TYPE_REMOTE_SUBMIX
        else -> "type=$type"
    }

    /** Hands the call back: releases our streams, then withdraws the mixes.
     *  Waits for the framework to report no registered mixes, so the next call
     *  does not register a second pair on top of a half-dead one. */
    fun close() {
        witnessThread = null
        witness?.let { w ->
            try {
                w.stop()
            } catch (_: Exception) {}
            try {
                w.release()
            } catch (_: Exception) {}
        }
        witness = null
        if (::sink.isInitialized) try {
            sink.stop()
            sink.release()
        } catch (_: Exception) {}
        if (::injector.isInitialized) try {
            injector.stop()
            injector.release()
        } catch (_: Exception) {}
        routingListener = null
        try {
            val un = method(am.javaClass, "unregisterAudioPolicyAsync", cls("AudioPolicy"))
            un.invoke(am, policy)
            var waited = 0
            while (registeredCount() > 0 && waited < UNREGISTER_WAIT_MS) {
                Thread.sleep(100)
                waited += 100
            }
            Log.i(TAG, "POLICY unregistered (mixes left=${registeredCount()}, waited=${waited}ms)")
        } catch (t: Throwable) {
            Log.w(TAG, "POLICY unregister failed: ${root(t)}")
        }
    }

    /** What the framework itself says about the mixes we registered — the only
     *  witness that has ever counted here, never a level meter. */
    fun registeredMixes(): String = try {
        val l = AudioManager::class.java.getMethod("getRegisteredPolicyMixes")
            .invoke(am) as? List<*>
        if (l == null || l.isEmpty()) "none"
        else l.joinToString("; ") { mix ->
            try {
                val mc = cls("AudioMix")
                "type=${method(mc, "getMixType").invoke(mix)} " +
                    "route=0x" + Integer.toHexString(method(mc, "getRouteFlags").invoke(mix) as Int) +
                    " state=${method(mc, "getMixState").invoke(mix)} " +
                    "fmt=${method(mc, "getFormat").invoke(mix)}"
            } catch (t: Throwable) {
                "?${root(t)}"
            }
        }
    } catch (t: Throwable) {
        "unavailable: ${root(t)}"
    }

    private fun registeredCount(): Int = try {
        (AudioManager::class.java.getMethod("getRegisteredPolicyMixes").invoke(am) as? List<*>)?.size ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun create(polC: Class<*>, name: String, mixC: Class<*>, mix: Any?): Any? =
        method(polC, name, mixC).invoke(policy, mix)

    companion object {
        private const val TAG = "CallRedirection"
        private const val UNREGISTER_WAIT_MS = 2000

        /** The package every one of these lives in.  None of it is in android.jar. */
        private const val PKG = "android.media.audiopolicy."

        /** Registry key: set to "0" to open the hidden surface for this process. */
        private const val POLICY_SETTING = "hidden_api_policy"

        /** Registry key: which route the agent's side takes.  See the class note.
         *
         *  `internal`, not private, because [RtpSession] has to read the same key to
         *  decide how to feed its track on the route this call actually took; one
         *  string shared here is the only way the two cannot drift apart. */
        internal const val UPLINK_SETTING = "callagent_uplink_via"

        /** Registry key: set to "1" to have this process open its own record client
         *  on the call's capture preset and log what it reads.  Diagnostic only,
         *  off by default; see [startUplinkWitness]. */
        private const val UPLINK_WITNESS_SETTING = "callagent_uplink_witness"

        /** Record frames per witness line: 25 x 20 ms = 500 ms. */
        private const val WITNESS_WINDOW_FRAMES = 25

        /** Reads with no frames before the witness declares the path unshared.
         *  With the 10 ms pacing below this is a little over half a second. */
        private const val WITNESS_NOT_FED_READS = 50

        /**
         * The usage an acoustic uplink track is played on, for a [via] setting, or
         * null when that setting asks for the injector mix.
         *
         * Neither of these is USAGE_VOICE_COMMUNICATION: this app's own players
         * mix takes over every playback client carrying that usage, so a stream on
         * it would be captured into the bridge instead of being played out of the
         * handset.  SIGNALLING is the usage Android reserves for tones that belong
         * to a call and routes with the call; NOTIFICATION_COMMUNICATION_REQUEST
         * is the fallback in case the framework treats the first as call audio and
         * sends it into the call.
         */
        private fun acousticUsageOf(via: Int): Int? = when (via) {
            1 -> AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            // 7, from android.jar.  This said 11 while it was being written against
            // the numbers from memory, and a usage that does not exist is a stream
            // the framework has no policy for.
            2 -> AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST
            else -> null
        }

        /** Reads the uplink route for this call and logs it: the one number that
         *  decides what the next call is measuring. */
        private fun uplinkVia(context: Context): Int {
            val v = settingValue(context, UPLINK_SETTING)?.toIntOrNull() ?: 0
            Log.i(TAG, "UPLINK_SETTING $UPLINK_SETTING=$v " +
                "(0=injector mix, 1=in-call SIGNALLING track, 2=notification-comms track)")
            // Read-only report back to the object that owns the key: it says once,
            // in logcat, when a call resolved to a route this app did not apply.
            // Nothing is written from here — the route is fixed by the time this
            // runs, and a root command on the call path costs answer latency.
            UplinkRouteSetting.noteObserved(v)
            return v
        }

        /** Reads `settings get global <key>` through the framework, no shell. */
        private fun settingValue(context: Context, key: String): String? = try {
            val global = Class.forName("android.provider.Settings\$Global")
            val getInt = global.getMethod(
                "getInt", android.content.ContentResolver::class.java,
                String::class.java, Int::class.javaPrimitiveType
            )
            val v = getInt.invoke(null, context.contentResolver, key, -1) as Int
            if (v < 0) null else v.toString()
        } catch (_: Throwable) {
            null
        }

        /** Exemptions for this process, so the @hide classes above resolve.
         *
         *  An app process is not exempt from hidden-API enforcement, and there is
         *  no public surface to exempt here: the API is the point.  The documented
         *  way for a privileged app to open it is
         *  `settings put global hidden_api_policy 0`, which is what the Magisk
         *  module does for this device; this only reports what it finds, and says
         *  plainly when the door is shut rather than letting the call die on a
         *  ClassNotFoundException three levels down. */
        fun hiddenApiOpen(context: Context): Boolean {
            val value = settingValue(context, POLICY_SETTING)
            Log.i(TAG, "POLICY_SETTING $POLICY_SETTING=${value ?: "unset(enforcement on by default)"}")
            return value == "0"
        }

        /**
         * Reports whether the shipped framework carries the surface this needs,
         * without registering or opening anything — the same check the stand-alone
         * harness used before any call was placed.
         */
        fun probe(): Boolean {
            return try {
                val ruleC = cls("AudioMixingRule")
                cls("AudioMix")
                cls("AudioPolicy")
                cls("AudioPolicy\$Builder")
                cls("AudioMix\$Builder")
                cls("AudioMixingRule\$Builder")
                // The two members that decide whether this device can do the job
                // at all, and the two that make the mixes what they are.
                method(ruleC, "setVoiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType!!)
                method(cls("AudioPolicy"), "createAudioRecordSink", cls("AudioMix"))
                method(cls("AudioPolicy"), "createAudioTrackSource", cls("AudioMix"))
                AudioAttributes.Builder::class.java.getMethod("setForCallRedirection")
                Log.i(TAG, "POLICY_SURFACE present: rule=${ruleC.name} " +
                    "role_players=${constInt(ruleC, "MIX_ROLE_PLAYERS")} " +
                    "role_injector=${constInt(ruleC, "MIX_ROLE_INJECTOR")} " +
                    "match_usage=${constInt(ruleC, "RULE_MATCH_ATTRIBUTE_USAGE")} " +
                    "match_preset=${constInt(ruleC, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET")} " +
                    "route_loopback=${constInt(cls("AudioMix"), "ROUTE_FLAG_LOOP_BACK")}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "POLICY_SURFACE unavailable: ${root(t)}")
                false
            }
        }

        /**
         * Builds the policy with both mixes and registers it.  Returns null, and
         * nothing half-open behind it, if any step is refused.
         */
        fun open(context: Context, rate: Int, wantInjector: Boolean = true): CallRedirection? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                Log.e(TAG, "POLICY no AudioManager")
                return null
            }
            val via = uplinkVia(context)
            val acoustic = acousticUsageOf(via)
            var policy: Any? = null
            var dlMix: Any? = null
            var ulMix: Any? = null
            try {
                // On the acoustic route the injector mix is not registered at all:
                // leaving it registered hands every record client on the
                // VOICE_COMMUNICATION preset a virtual source of ours, which is
                // how the call's own uplink ends up reading digital silence.
                val mixes = buildMixes(rate, wantInjector && acoustic == null)
                dlMix = mixes[0]
                ulMix = mixes[1]

                val mixC = cls("AudioMix")
                val polB = cls("AudioPolicy\$Builder")
                val pb = constructor(polB, Context::class.java).newInstance(context)
                val addMix = method(polB, "addMix", mixC)
                addMix.invoke(pb, dlMix)
                if (ulMix != null) addMix.invoke(pb, ulMix)
                policy = method(polB, "build").invoke(pb)

                val reg = method(am.javaClass, "registerAudioPolicy", cls("AudioPolicy"))
                val status = reg.invoke(am, policy) as Int
                Log.i(TAG, "POLICY register status=$status mixes=${if (ulMix != null) "2" else "1"} " +
                    "rate=$rate injector=$wantInjector uplink_via=$via")
                if (status != 0) {
                    // AudioPolicy.PLAYBACK_ATTR_* aside, the registration codes are
                    // the AudioService ones: -1 ERROR, -2 bad transmission,
                    // -3 already connected, -20 dead policy.  Printed, not guessed at.
                    Log.e(TAG, "POLICY register refused status=$status " +
                        "(0=OK, -1=ERROR, -2=BAD_TRANSMISSION_FORMAT_FOR_CLIENT, " +
                        "-3=ALREADY_CONNECTED, -20=DEAD_POLICY)")
                    return null
                }

                val gw = CallRedirection(am, policy, dlMix!!, ulMix, acoustic)
                Log.i(TAG, "POLICY registered mixes: ${gw.registeredMixes()}")
                return gw
            } catch (t: Throwable) {
                Log.e(TAG, "POLICY open failed: ${root(t)}")
                // Undo whatever did get registered, and nothing else.
                if (policy != null) try {
                    method(am.javaClass, "unregisterAudioPolicyAsync", cls("AudioPolicy")).invoke(am, policy)
                } catch (_: Throwable) {}
                return null
            }
        }

        /**
         * The two mixes, exactly as the harness built and registered them on this
         * device.  Each value here is one that was read out of the shipped
         * framework (`constInt`), not a number copied out of documentation.
         */
        private fun buildMixes(rate: Int, wantInjector: Boolean): Array<Any?> {
            val ruleC = cls("AudioMixingRule")
            val ruleB = cls("AudioMixingRule\$Builder")
            val players = constInt(ruleC, "MIX_ROLE_PLAYERS")
            val injector = constInt(ruleC, "MIX_ROLE_INJECTOR")
            val byUsage = constInt(ruleC, "RULE_MATCH_ATTRIBUTE_USAGE")
            val byPreset = constInt(ruleC, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET")
            val loopBack = constInt(cls("AudioMix"), "ROUTE_FLAG_LOOP_BACK")

            // Downlink: take over the call's own playback client.
            val b1 = ruleB.getConstructor().newInstance()
            method(ruleB, "setTargetMixRole", Int::class.javaPrimitiveType!!).invoke(b1, players)
            method(ruleB, "addMixRule", Int::class.javaPrimitiveType!!, Object::class.java)
                .invoke(b1, byUsage, callAttributes(usage = AudioAttributes.USAGE_VOICE_COMMUNICATION))
            val dlRule = method(ruleB, "build").invoke(b1)
            // Without this the framework refuses to capture a voice-communication
            // playback client at all, whatever the rule matched.
            method(ruleC, "setVoiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType!!)
                .invoke(dlRule, true)

            // Uplink: become what the call's record client reads.
            val b2 = ruleB.getConstructor().newInstance()
            method(ruleB, "setTargetMixRole", Int::class.javaPrimitiveType!!).invoke(b2, injector)
            method(ruleB, "addMixRule", Int::class.javaPrimitiveType!!, Object::class.java)
                .invoke(b2, byPreset, callAttributes(preset = MediaRecorder.AudioSource.VOICE_COMMUNICATION))
            val ulRule = method(ruleB, "build").invoke(b2)

            // A mix needs an OUTPUT channel mask: it describes the stream the mix
            // produces, not what a record client asks for.
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val dl = buildMix(dlRule, fmt, loopBack)
            val ul = if (wantInjector) buildMix(ulRule, fmt, loopBack) else null
            return arrayOf(dl, ul)
        }

        /**
         * Criteria with the call-redirection flag on them.  It does not change
         * the usage or preset that gets matched — it is what tells the framework
         * this is call audio being intercepted, which is what its own permission
         * gate looks at.
         */
        private fun callAttributes(usage: Int? = null, preset: Int? = null): AudioAttributes {
            val b = AudioAttributes.Builder()
            if (usage != null) b.setUsage(usage)
            if (preset != null) {
                // setInternalCapturePreset is only public from API 29 and this app
                // still runs on 26, so it is taken reflectively — the same way the
                // harness took it.  Nothing else about the attributes changes.
                try {
                    AudioAttributes.Builder::class.java
                        .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                        .invoke(b, preset)
                } catch (t: Throwable) {
                    Log.w(TAG, "CAPTURE_PRESET setter not applied: ${root(t)}")
                }
            }
            try {
                AudioAttributes.Builder::class.java.getMethod("setForCallRedirection").invoke(b)
            } catch (t: Throwable) {
                // Older builds have no such setter.  The mix still matches, but it
                // is not marked as call redirection, and the framework's own gate
                // then treats it as ordinary capture — worth seeing in the log.
                Log.w(TAG, "CALL_REDIR_FLAG setter not applied: ${root(t)}")
            }
            return b.build()
        }

        /**
         * AudioMix.Builder carries the rule, the geometry and the route flags.
         * `ROUTE_FLAG_LOOP_BACK` is the flag the proven harness used: it is what
         * lets a mix that takes a playback client hand that stream to a sink of
         * ours, and a mix that replaces a record source take its input from a
         * track of ours.
         */
        private fun buildMix(rule: Any, fmt: AudioFormat, routeFlags: Int): Any {
            val mixC = cls("AudioMix")
            val ruleC = cls("AudioMixingRule")
            val mixB = cls("AudioMix\$Builder")
            val b: Any = try {
                constructor(mixB, ruleC).newInstance(rule)
            } catch (_: NoSuchMethodException) {
                val alt = constructor(mixB).newInstance()
                method(mixB, "setMixingRule", ruleC).invoke(alt, rule)
                alt
            }
            method(mixB, "setFormat", AudioFormat::class.java).invoke(b, fmt)
            method(mixB, "setRouteFlags", Int::class.javaPrimitiveType!!).invoke(b, routeFlags)
            return method(mixB, "build").invoke(b)
        }

        private fun cls(simple: String): Class<*> = Class.forName(PKG + simple)

        /** Public first, then the declared non-public form: several of these
         *  members are package-private on this build. */
        private fun method(c: Class<*>, name: String, vararg params: Class<*>): Method = try {
            c.getMethod(name, *params)
        } catch (_: NoSuchMethodException) {
            c.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        }

        private fun constructor(c: Class<*>, vararg params: Class<*>): Constructor<*> = try {
            c.getConstructor(*params)
        } catch (_: NoSuchMethodException) {
            c.getDeclaredConstructor(*params).also { it.isAccessible = true }
        }

        private fun constInt(c: Class<*>, name: String): Int = c.getField(name).getInt(null)

        /** The cause, not the InvocationTargetException wrapping it: that wrapper
         *  says nothing about why the framework actually said no. */
        private fun root(t: Throwable): String {
            var c: Throwable = t
            while (c.cause != null && c.cause !== c) c = c.cause!!
            val why = if (c === t) "" else " <- ${c.javaClass.simpleName}: ${c.message}"
            return "${t.javaClass.simpleName}: ${t.message}$why"
        }
    }
}
