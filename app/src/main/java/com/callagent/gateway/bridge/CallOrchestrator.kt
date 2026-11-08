package com.callagent.gateway.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.RootShell
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.CallRedirection
import com.callagent.gateway.rtp.SrtpContext
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.rtp.DtmfDetector
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    private var activeSipCall: SipCall? = null
    private var activeGsmCall: Call? = null
    /** The call-redirection policy armed for the GSM leg this flow is about to
     *  dial.  Only the SIP-initiated outbound flow sets it, and only before
     *  [GsmCallManager.makeCall] — see [preArmCallRedirect] for why the order is
     *  the whole point.  Owned here until [startRtp] hands it to the RTP
     *  session, which then owns it; [closePendingCallRedirect] is what withdraws
     *  it if no bridge ever came up. */
    private var pendingCallRedirect: CallRedirection? = null
    /** The UDP socket our own SDP names.
     *
     *  Bound by [openRtpSocket] *before* the SDP that advertises it goes out, and
     *  kept open until [startRtp] hands it to the RTP session, which then owns it.
     *  Every path that ends a bridge without a session having taken it must call
     *  [closeMediaSocket].  See [openRtpSocket] for why probing a port and
     *  closing it again is not the same thing. */
    private var mediaSocket: DatagramSocket? = null
    /** Guards [mediaSocket] on its own.  tearDown() is @Synchronized on this
     *  object and blocks for seconds inside session.stop(); sharing that monitor
     *  with the socket helpers would delay them by the same amount, and the
     *  socket has to be releasable at any moment. */
    private val mediaLock = Any()
    /** False while the previous RTP/HAL session is still being released. */
    @Volatile private var audioReady = true

    private data class PendingIncoming(val call: Call, val number: String)
    @Volatile private var pendingIncoming: PendingIncoming? = null

    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** One thread for every deferred bridge action, instead of a fresh Thread
     *  per dial, per INVITE and per retry. */
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bridge-timers").apply { isDaemon = true }
        }

    /**
     * Telecom normally delivers a state callback when the remote GSM party
     * hangs up.  On some Android/RadioInterface combinations it updates the
     * Call object first and delivers onCallRemoved later (or skips the state
     * callback altogether).  Polling the tracked Call is a cheap safety net
     * which prevents the SIP leg from being left up after the cellular leg is
     * gone.
     */
    @Volatile private var gsmStateWatchdog: ScheduledFuture<*>? = null

    private fun ensureGsmStateWatchdog() {
        if (gsmStateWatchdog?.isCancelled == false) return
        val gen = generation
        gsmStateWatchdog = timers.scheduleAtFixedRate(watchdog@{
            if (generation != gen || bridgeState == BridgeState.IDLE ||
                bridgeState == BridgeState.TEARING_DOWN) {
                gsmStateWatchdog?.cancel(false)
                gsmStateWatchdog = null
                return@watchdog
            }

            val call = activeGsmCall ?: return@watchdog
            val state = try {
                call.state
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read GSM call state: ${e.message}")
                return@watchdog
            }
            if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
                Log.i(TAG, "GSM watchdog detected call end (state=$state)")
                tearDown("GSM call disconnected")
                return@watchdog
            }

            // The state above is a copy Telecom delivered to us and keeps.  If
            // the telephony stack stopped talking to this Call object — the
            // session ended, another dialer took the call, the radio released
            // it without a final callback — that copy is frozen at ACTIVE and
            // this watchdog would trust it for ever, with the bridge stuck in
            // BRIDGED and every later caller told "busy".  Telecom's own list
            // is the one that cannot go stale.
            val telecom = GsmCallManager.telecomCallStates()
            if (telecom != null && telecom.none { it != Call.STATE_DISCONNECTED }) {
                Log.w(TAG, "GSM watchdog: Telecom holds no call (tracked object says state=$state) " +
                        "— the cellular leg ended without telling us")
                tearDown("GSM call gone from Telecom")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    /** Bumped every time the bridge returns to IDLE.
     *
     *  Timers used to check nothing but [bridgeState], which is shared by every
     *  call there has ever been.  A dial that failed at t+5s left its 45s
     *  timeout running; a different call answered at t+20s was still setting up
     *  when that timer fired, saw a non-IDLE state and tore the new call down
     *  as "GSM dial timeout".  Each timer now captures the generation it was
     *  scheduled in and does nothing if the bridge has moved on since. */
    @Volatile private var generation = 0L

    /** Run [action] after [delayMs], unless the bridge has moved on. */
    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        timers.schedule({
            if (generation != gen) return@schedule
            try { action() } catch (e: Exception) {
                Log.w(TAG, "Timer action failed: ${e.message}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Keep a cellular caller ringing while the previous Qualcomm audio output
     * finishes shutting down. Starting a new incall_music track before the
     * old one reaches standby caused intermittent SIP->GSM one-way calls on
     * Pixel 4a.
     */
    private fun waitForAudioReady(
        call: Call,
        number: String,
        waitingSince: Long = System.currentTimeMillis()
    ) {
        val pending = pendingIncoming
        if (pending?.call !== call || activeGsmCall !== call ||
            bridgeState != BridgeState.GSM_RINGING) {
            return
        }

        val state = try { call.state } catch (e: Exception) {
            Log.w(TAG, "Unable to read waiting GSM call state: ${e.message}")
            return
        }
        if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
            pendingIncoming = null
            tearDown("Waiting GSM call disconnected")
            return
        }

        if (audioReady) {
            pendingIncoming = null
            Log.i(TAG, "Audio HAL ready — starting SIP flow for waiting GSM call from $number")
            Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()
            return
        }

        val waitedMs = System.currentTimeMillis() - waitingSince
        if (waitedMs >= AUDIO_READY_WAIT_TIMEOUT_MS) {
            pendingIncoming = null
            Log.w(TAG, "Audio HAL did not become ready after ${waitedMs}ms — rejecting waiting GSM call")
            tearDown("Audio HAL not ready")
            return
        }

        schedule(AUDIO_READY_POLL_MS) {
            waitForAudioReady(call, number, waitingSince)
        }
    }

    /**
     * Probe the actual AudioFlinger state instead of blindly sleeping for the
     * whole Pixel guard window.  The Qualcomm output is reusable when its
     * TELEPHONY_TX thread is in standby and no longer blocked in a write.
     * If the dump is unavailable (for example, root is temporarily denied),
     * keep waiting and use the configured maximum as the safe fallback.
     */
    private fun waitForAudioHalDrain(
        startedAt: Long = System.currentTimeMillis()
    ) {
        if (activeRtpSession != null) return

        val dump = try {
            RootShell.execForOutput(
                "dumpsys media.audio_flinger 2>/dev/null",
                timeoutMs = AUDIO_HAL_PROBE_TIMEOUT_MS
            )
        } catch (e: Exception) {
            Log.w(TAG, "Audio HAL readiness probe failed: ${e.message}")
            ""
        }

        val ready = if (dump.isNotBlank()) {
            val blocks = dump.split(Regex("(?=\\n\\s*Output devices:)"))
                .filter { it.contains("AUDIO_DEVICE_OUT_TELEPHONY_TX") }
            // No TELEPHONY_TX output means the old output is already gone.
            // If one exists, require it to be fully standby and not blocked.
            blocks.isEmpty() || blocks.all {
                it.contains("Blocked in write: no") &&
                    Regex("Hal stream dump:\\s*Standby: yes").containsMatchIn(it)
            }
        } else {
            false
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val maxWait = GsmCallManager.profile.audioStopSettleDelayMs
        if (ready) {
            audioReady = true
            Log.i(TAG, "Audio HAL ready after ${elapsed}ms (AudioFlinger standby)")
            return
        }
        if (elapsed >= maxWait) {
            // Do not strand a real incoming call forever if dumpsys cannot
            // report readiness. The maximum remains the safety fallback.
            audioReady = true
            Log.w(TAG, "Audio HAL readiness not confirmed after ${elapsed}ms — releasing wait by timeout")
            return
        }

        schedule(AUDIO_READY_POLL_MS) {
            waitForAudioHalDrain(startedAt)
        }
    }

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
        TEARING_DOWN         // Hanging up
    }

    /** How an incoming GSM call is met while the bridge is not IDLE.
     *  Produced by [decideIncoming], and tested there as a table.
     *
     *  [RESET_AND_REJECT] is the half-way answer: a leg really is up, so this
     *  caller is told busy, but the bridge is incomplete (the other leg is gone),
     *  so it is cleared on the way — a caller is allowed to hear busy once, and
     *  not for as long as the process lives. */
    internal enum class IncomingAction {
        ACCEPT, RESET_AND_ACCEPT, RESET_AND_REJECT, REJECT_BUSY, IGNORE_DUPLICATE
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
        timers.shutdownNow()
    }

    /**
     * The number this gateway answers on, sent as the SIP destination.
     *
     * The server maps an incoming number to an assistant the same way it does
     * a Fritz!Box DID, so it needs the MSISDN of our SIM here.  Addressing our
     * own SIP account instead makes the platform see account N calling account
     * N, match it as a local peer and ring us straight back — the INVITE comes
     * back, the orchestrator rejects it as busy, and the GSM leg is never
     * answered.
     *
     * Asks the platform first; many carriers do not publish the number on the
     * SIM, in which case the configured value is used.
     */
    private fun inboundSipDestination(gsmCall: Call? = null): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val subscription = gsmCall?.details?.accountHandle?.let { account ->
            try {
                val subId = com.callagent.gateway.gsm.GsmCallManager.subscriptionIdOf(context, account)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val info = context.getSystemService(SubscriptionManager::class.java)
                        ?.getActiveSubscriptionInfo(subId)
                    if (info != null && info.simSlotIndex >= 0) {
                        Log.i(TAG, "Incoming call on SIM slot ${info.simSlotIndex} (subscription $subId)")
                        subId to info.simSlotIndex
                    } else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve incoming call SIM: ${e.message}")
                null
            }
        }

        if (subscription != null) {
            val (subId, slot) = subscription
            val configured = prefs.getString("own_number_slot_$slot", "")?.trim().orEmpty()
            if (configured.isNotEmpty()) {
                val intl = toInternational(configured, subId)
                Log.i(TAG, "Own number for SIM slot $slot from settings: $configured → $intl")
                return intl
            }
            simNumber(subId)?.let {
                val intl = toInternational(it, subId)
                Log.i(TAG, "Own number for subscription $subId from SIM: $it → $intl")
                return intl
            }
            Log.w(TAG, "No number configured for incoming SIM slot $slot; using legacy fallback")
        } else if (gsmCall != null) {
            Log.w(TAG, "Incoming call SIM unresolved; using legacy fallback")
        }

        val configured = prefs.getString("own_number", DEFAULT_OWN_NUMBER)?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val intl = toInternational(configured)
            Log.i(TAG, "Own number from settings: $configured → $intl")
            return intl
        }
        simNumber(SubscriptionManager.getDefaultSubscriptionId())?.let {
            val intl = toInternational(it)
            Log.i(TAG, "Own number from default SIM: $it → $intl")
            return intl
        }
        Log.w(TAG, "No own number known — addressing our own extension, which loops back")
        return sipClient.username
    }

    /**
     * The destination always goes out in international form.  What we have to
     * start from varies: the platform may hand back E.164, the settings field
     * may hold a national number ("015112345678"), and either may use a 00
     * prefix.  PhoneNumberUtils resolves the national case against the SIM's
     * country rather than us guessing a dialling code.
     *
     * The caller number in From is deliberately NOT put through this — that one
     * is passed on exactly as the carrier delivered it.
     */
    private fun toInternational(number: String, subId: Int? = null): String {
        val trimmed = number.trim().filterNot { it == ' ' || it == '-' || it == '/' }
        if (trimmed.startsWith("+")) return trimmed
        if (trimmed.startsWith("00")) return "+" + trimmed.substring(2)
        // Android/carrier settings often store a US number as 11 digits
        // without the leading '+'.  Keep it E.164 or Asterisk will treat the
        // Request-URI as a different, unknown DID.
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("1")) return "+$digits"
        val iso = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            (if (subId != null) tm.createForSubscriptionId(subId) else tm)
                .simCountryIso?.uppercase()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "SIM country unavailable: ${e.message}")
            null
        }
        if (iso != null) {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)?.let { return it }
            if (iso == "US" && digits.length == 10) return "+1$digits"
        }
        Log.w(TAG, "Cannot make '$trimmed' international (SIM country=$iso) — sending as is")
        return trimmed
    }

    /** The SIM's own number, when the carrier publishes it — many do not. */
    @SuppressLint("MissingPermission")
    private fun simNumber(subId: Int): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getPhoneNumber(subId)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .createForSubscriptionId(subId).line1Number
        }
        number?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "SIM number unavailable: ${e.message}")
        null
    }

    /** Cut the agent's audio to the caller, leaving the call itself up. */
    fun setAgentMuted(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            listener?.onError("No active call to mute")
            return
        }
        session.setAgentMuted(on)
    }

    /** Listen in on the active call through the phone's speaker.
     *
     *  Plays both sides mixed; the microphone stays muted, so nothing the room
     *  says reaches either the caller or the agent. */
    /** Whether the active call is on Snoop right now (manual or Call monitor). */
    val isMonitoring: Boolean get() = activeRtpSession?.isMonitoring == true

    fun setMonitorEnabled(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            Log.w(TAG, "Monitor requested with no active call")
            listener?.onError("No active call to monitor")
            return
        }
        session.setMonitorEnabled(on)
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        // MMI/USSD is not a call — it never produces a Telecom Connection, so
        // arming the bridge for it would strand us in GSM_DIALING until the
        // stale-state timeout.
        if (GsmCallManager.isMmiCode(number)) {
            Log.i(TAG, "MMI code $number — sending as USSD, not bridging")
            GsmCallManager.sendMmi(context, number) { result ->
                Log.i(TAG, "MMI result: $result")
                listener?.onStateChanged(bridgeState, "MMI: $result")
            }
            return
        }
        if (bridgeState != BridgeState.IDLE) {
            // Check for stale state: if bridge has been non-IDLE for too long
            // without reaching BRIDGED, force a reset.  This happens on cold boot
            // when InCallService isn't bound yet and call events never arrive.
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        diallerInitiated = true
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.makeCall(context, number)

        // Timeout: if GSM doesn't go active within 45s, tear down.
        // On cold boot, InCallService may not be bound, so call events
        // never arrive and the bridge gets stuck in GSM_DIALING.
        schedule(GSM_DIAL_TIMEOUT_MS) {
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            }
        }
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}, outbound_cid=${call.callerNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call 486")
            call.reject(486, "Busy Here")
            sipClient.removeCall(call.callId)
            return
        }

        val gsmDest = outboundDestination(call)
        if (gsmDest != null) {
            // OUTBOUND flow: Asterisk wants us to dial a GSM number
            handleOutboundFlow(call, gsmDest)
        } else {
            // Nothing to dial: no X-GSM-Forward, and no number in the
            // Request-URI either.  Answering used to look harmless, but a 200
            // tells the server the call is up and leaves it bridged to
            // silence for as long as it cares to wait.  Say we cannot take it.
            Log.w(TAG, "SIP INVITE names no GSM destination " +
                "(uri=${call.originalInvite?.requestUri}) — rejecting 488")
            listener?.onError("INVITE with no GSM destination — rejected")
            call.reject(488, "Not Acceptable Here")
            sipClient.removeCall(call.callId)
        }
    }

    /**
     * The number an INVITE asks us to dial.
     *
     * X-GSM-Forward first, then the user part of the Request-URI — the same
     * order the SMS path resolves a recipient in, so that
     * Dial(SIP/<peer>/+49...) addresses a call the way it already addresses a
     * message, and a dialplan does not have to know that calls are the
     * exception.
     *
     * The Request-URI carries the account name whenever the server addresses
     * the peer rather than a number, and our own MSISDN when it routes the
     * SIM's DID back to us.  Dialling either would be a loop, so both are
     * ruled out before what is left is treated as a destination.
     */
    private fun outboundDestination(call: SipCall): String? {
        call.gsmForwardNumber?.trim()?.ifEmpty { null }?.let { return it }

        val invite = call.originalInvite ?: return null
        val user = invite.requestUri
            ?.let { invite.extractUser(it) }
            ?.trim()?.ifEmpty { null } ?: return null

        if (user.equals(sipClient.username, ignoreCase = true)) return null
        if (digitsOf(user) == digitsOf(inboundSipDestination())) return null
        if (!looksDialable(user)) return null

        Log.i(TAG, "No X-GSM-Forward — destination taken from the Request-URI")
        return user
    }

    /**
     * Strict on purpose: a Request-URI user is a number only when that is all
     * it is.  An account name made of digits is already ruled out above; one
     * with a letter in it was never a number to begin with.
     */
    private fun looksDialable(user: String): Boolean =
        user.all { it.isDigit() || it in "+-.()" } &&
            digitsOf(user).length >= MIN_DIALABLE_DIGITS

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again —
        // but only after a transient failure.  A deliberate answer from the far
        // side (busy, declined, unavailable) must end the GSM call: retrying it
        // re-rang the softphone after the user had rejected the call.
        val status = call.finalStatus
        val transient = status == 0 || status == 408 || status == 500 || status == 503 || status == 504
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && transient && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            schedule(1000) {
                if (bridgeState != BridgeState.SIP_CALLING &&
                    bridgeState != BridgeState.SIP_RINGING) return@schedule
                activeGsmCall?.let { handleInboundFlow(it) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }
            return
        }

        if (status >= 300) Log.i(TAG, "SIP side answered $status — not retrying, ending the GSM call")
        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    @Synchronized
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        // onCallAdded() and the first STATE_RINGING callback can describe the
        // same Telecom call. Do not reject the call as a duplicate.
        val gsmLive = GsmCallManager.hasLiveCall()
        val sipLive = activeSipCall?.let { it.state != SipCall.State.TERMINATED } == true
        val ageMs = System.currentTimeMillis() - lastStateChangeTime
        val action = decideIncoming(bridgeState, activeGsmCall === call, gsmLive, sipLive, ageMs)
        if (action == IncomingAction.REJECT_BUSY || action == IncomingAction.RESET_AND_REJECT) {
            // Everything busy is decided from these numbers, so a logcat taken
            // on site is enough to say which of them was wrong.  The phone
            // number is deliberately not in it.
            Log.w(TAG, "Incoming GSM call met with $action — state=$bridgeState, " +
                    "gsm=${if (gsmLive) "live" else "gone"}, sip=${if (sipLive) "live" else "gone"}, " +
                    "${ageMs / 1000}s in this state, telecom calls=${GsmCallManager.telecomCallStates()}")
        }
        when (action) {
            IncomingAction.IGNORE_DUPLICATE -> {
                Log.d(TAG, "Ignoring duplicate ringing callback for the same GSM call (state=$bridgeState)")
                return
            }

            IncomingAction.REJECT_BUSY -> {
                // Only a genuinely concurrent call gets this far: both legs of a
                // bridge, or one leg inside the timeout.  Busy is what callers
                // reported hearing on calls that were not concurrent at all, so
                // the reason is said out loud.
                GsmCallManager.rejectCall(call)
                return
            }

            // BRIDGED with one leg already dead: this caller hears busy, and the
            // dead bridge is taken away so the next one does not.
            IncomingAction.RESET_AND_REJECT -> {
                forceReset(
                    "bridge claims BRIDGED with gsm=${if (gsmLive) "live" else "gone"} and " +
                        "sip=${if (sipLive) "live" else "gone"} — clearing it, and this caller is " +
                        "told busy because a line is still up"
                )
                GsmCallManager.rejectCall(call)
                return
            }

            // Non-IDLE with nothing live behind it is a wedged bridge, not a
            // busy one.  Clearing it costs nothing that was still working, and
            // not clearing it told every later caller they had reached a busy
            // number — permanently, until the process restarted.
            IncomingAction.RESET_AND_ACCEPT -> forceReset(
                "bridge wedged in $bridgeState for ${ageMs / 1000}s with gsm=${if (gsmLive) "live" else "gone"} " +
                    "and sip=${if (sipLive) "live" else "gone"} — accepting this call instead of rejecting it"
            )

            IncomingAction.ACCEPT -> Unit
        }

        sipCallRetries = 0
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        lastStateChangeTime = System.currentTimeMillis()
        ensureGsmStateWatchdog()
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        if (!audioReady) {
            pendingIncoming = PendingIncoming(call, number)
            listener?.onStateChanged(bridgeState, "Waiting for audio HAL")
            Log.i(TAG, "GSM ringing from $number — audio HAL is not ready, holding SIP flow")
            waitForAudioReady(call, number)
            return
        }

        // Don't answer GSM yet — place SIP call to Asterisk first.
        // When the agent answers on SIP, we'll answer GSM so the caller
        // hears the agent immediately with no dead air.
        // The caller hears normal ringing in the meantime.
        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        activeGsmCall = call
        ensureGsmStateWatchdog()

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        startRtp(localPort, addr, port, pt)
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    // DIALLER flow: GSM active → place SIP call to Asterisk (like inbound)
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    Thread({
            // Anything thrown here used to kill the whole process, taking the
            // registration down with the one call (S4 Mini, 2026-10-23: a
            // RejectedExecutionException from a SIP client whose executor
            // had been shut down).  Fail the call, keep the gateway.
            try {
                handleInboundFlow(call)
            } catch (t: Throwable) {
                Log.e(TAG, "Inbound flow failed: $t", t)
                tearDown("inbound flow failed: ${t.javaClass.simpleName}")
            }
        }, "SIP-OutCall").start()
                } else {
                    // SIP-initiated OUTBOUND flow: GSM destination answered → start audio bridge
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                    // Answer the SIP call off the main thread
                    Thread({
                        activeSipCall?.let { sipCall ->
                            try {
                                // The socket this call's SDP will name, bound now and
                                // held: the peer starts sending as soon as it reads
                                // our 200 OK, so the port has to be listening before
                                // that 200 OK is even written.
                                val sock = openRtpSocket()
                                val rtpPort = sock.localPort
                                sipCall.listener = this
                                sipCall.accept(rtpPort)

                                val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                                val port = sipCall.remoteRtpPort
                                val pt = sipCall.negotiatedPayloadType
                                if (port > 0) {
                                    startRtp(rtpPort, addr, port, pt)
                                } else {
                                    closeMediaSocket("peer SDP carried no media port")
                                }
                            } catch (t: Throwable) {
                                // An uncaught throw here takes the process, and the
                                // registration with it.  Fail this call instead.
                                Log.e(TAG, "Outbound bridge failed: $t", t)
                                closeMediaSocket("outbound bridge failed")
                                tearDown("outbound bridge failed: ${t.javaClass.simpleName}")
                            }
                        }
                        Log.i(TAG, "Outbound bridge established")
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
            ensureGsmStateWatchdog()
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        } else if (state == Call.STATE_DISCONNECTING && bridgeState != BridgeState.IDLE) {
            // Telecom can remain in DISCONNECTING for a while after the radio
            // has already released the call.  End the SIP leg immediately.
            tearDown("GSM call disconnecting")
        }
    }

    /**
     * The SIP status that says why a GSM leg never connected.
     *
     * A provider that cannot complete a call answers the INVITE with a final
     * response the dialplan can branch on — busy, declined, unobtainable.
     * We used to send BYE instead, which is not a valid way to end an INVITE
     * that was never answered: the server replies 481 and then waits out its
     * own timer, so a busy number, a declined call and a dead SIM all looked
     * alike and all looked like a timeout.
     */
    /**
     * Why a GSM call we placed failed, as the SIP final response the server
     * gets.  Android folds unreachable / invalid / out-of-service / switched
     * off / congestion into code ERROR; the public reason string still names
     * the telephony cause ("BUSY", "UNOBTAINABLE_NUMBER", …), so it is used
     * first.  Before, ERROR became 500, which Asterisk treats like an
     * unreachable channel (CHANUNAVAIL) — a wrong or switched-off number
     * looked to the server like the gateway itself had failed.
     */
    private fun sipStatusFor(cause: DisconnectCause?): Pair<Int, String> {
        val r = cause?.reason.orEmpty().uppercase()
        Log.i(TAG, "GSM disconnect: code=${cause?.code} reason=${cause?.reason} label=${cause?.label}")
        fun has(vararg k: String) = k.any { r.contains(it) }
        return when {
            cause?.code == DisconnectCause.BUSY || has("USER_BUSY", "BUSY") -> 486 to "Busy Here"
            cause?.code == DisconnectCause.REJECTED || has("CALL_REJECTED", "INCOMING_REJECTED") -> 603 to "Decline"
            has("UNOBTAINABLE_NUMBER", "UNASSIGNED_NUMBER", "NO_ROUTE_TO_DESTINATION") -> 404 to "Number Not In Service"
            has("INVALID_NUMBER", "INVALID_NUMBER_FORMAT") -> 484 to "Address Incomplete"
            cause?.code == DisconnectCause.RESTRICTED || has("CALL_BARRED", "FDN_BLOCKED", "RESTRICTED") -> 403 to "Call Barred"
            has("NUMBER_UNREACHABLE", "POWER_OFF", "NO_ANSWER", "TIMED_OUT", "NO_USER_RESPONDING") ||
                cause?.code == DisconnectCause.MISSED -> 480 to "Not Reachable"
            has("OUT_OF_SERVICE", "EMERGENCY_ONLY", "RADIO_OFF", "ICC_ERROR", "CONGESTION",
                "OUT_OF_NETWORK", "NOT_VALID") ||
                cause?.code == DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> 503 to "Service Unavailable"
            cause?.code == DisconnectCause.CANCELED || cause?.code == DisconnectCause.LOCAL -> 487 to "Request Terminated"
            // ERROR without a recognisable reason, REMOTE: not reachable.
            else -> 480 to "Temporarily Unavailable"
        }
    }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call == activeGsmCall ||
            (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    /** Telecom has unbound the InCallService.
     *
     *  What usually causes that is the dialer role changing — being made, or
     *  unmade, the default dialer.  Whatever GSM leg an open bridge was holding
     *  can no longer be observed, answered or disconnected, so the bridge would
     *  keep a state that nothing can move and meet every later caller with a
     *  busy signal.  Closing it here is the difference between a role change and
     *  a gateway that has to be restarted. */
    override fun onInCallServiceGone() {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        forceReset("InCallService unbound while the bridge was in $bridgeState")
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onDtmf(call: SipCall, digit: Char, durationMs: Int) {
        if (call !== activeSipCall) {
            Log.w(TAG, "INFO DTMF '$digit' for a call that is not bridged — ignored")
            return
        }
        GsmCallManager.dtmfRelay.pulse(digit, durationMs)
    }

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually)
            // before SIP was ready.  Start RTP now.
            val localRtpPort = call.localRtpPort
            startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
            Log.i(TAG, "Inbound bridge established — GSM was already active (codec=$codecName)")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call) {
        val (callerNumber, callerName) = GsmCallManager.callerOf(gsmCall)
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber ($callerName)")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val sock = openRtpSocket()
        val rtpPort = sock.localPort
        val sipCall = sipClient.makeCall(
            targetExtension = inboundSipDestination(gsmCall),
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerName
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        // Timeout: if the server has not even said "ringing" within 30 s, give
        // up.  Once it rings (180), the server's own dial timeout decides; ours
        // only stops a call that rings for ever.  Cutting at 30 s regardless
        // ended calls the softphone was still ringing for — a push-woken
        // phone got about 20 s to answer.
        val sentAt = System.currentTimeMillis()
        fun check() {
            if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING) return
            if (activeSipCall !== sipCall) return
            val elapsed = System.currentTimeMillis() - sentAt
            val ringing = sipCall.state == SipCall.State.RINGING
            if (ringing && elapsed < SIP_RING_MAX_MS) {
                schedule(5_000) { check() }
                return
            }
            Log.w(TAG, "SIP call timeout — ${if (ringing) "rang ${elapsed / 1000}s" else "no ringing from the server in ${elapsed / 1000}s"}")
            tearDown(if (ringing) "No answer" else "Asterisk not answering")
        }
        schedule(SIP_CALL_TIMEOUT_MS) { check() }
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")

        // Send 180 Ringing to SIP caller while GSM dials
        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        // Dial via the SIM whose configured own number matches the SIP
        // Outbound CID.  On a dual-SIM device omitting the account leaves
        // Telecom in SELECT_PHONE_ACCOUNT, which a headless gateway cannot
        // answer.  A single-SIM gateway dials on its SIM whatever the CID is,
        // or without one — makeCall decides.
        //
        // And before the dial, on the Exynos 990: arm the call-redirection
        // route.  AudioFlinger decides whether a client belongs to a policy mix
        // when the client is *created*, and the two clients that carry a call on
        // this device are created by the modem's media path the moment the call
        // starts.  Mixes registered once the call is ACTIVE own nothing: the
        // sink reads digital silence and the injector has no record client to
        // feed.  Measured on air with build 459 (2026-11-05): one call ran with
        // capRMS=0 for its whole duration and an AudioTrack write timeout on
        // every frame written, while the call before it captured the caller
        // properly — the only difference was whether the call's two clients
        // happened to be created after the registration.  The stand-alone
        // harness that proved both directions on this device registered the
        // policy and then dialled, in that order, so what is being copied here
        // is the ordering, not a new mechanism.
        pendingCallRedirect = preArmCallRedirect()

        val cid = sipCall.callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (!GsmCallManager.makeCall(context, gsmDestination, cid, headless = true)) {
            Log.e(TAG, "Cannot dial GSM: SIP Outbound CID ${cid ?: "missing"} does not select a unique SIM")
            closePendingCallRedirect()
            tearDown("No matching SIM for Outbound CID", 503 to "No Matching SIM")
        }
    }

    /**
     * Registers the call-redirection policy and opens its two streams while no
     * call is up, which is what has to precede the dial.  The mix's sink reads
     * silence until the call's own playback client is matched into it, and the
     * injector reaches nobody: neither stream touches the handset, so arming
     * early puts no sound anywhere and changes nothing a caller can hear.
     *
     * Returns null when the profile has no such route (every device but the
     * Exynos 990) or when the framework refuses the registration.  That is not
     * fatal: with nothing handed over, [RtpSession.startCallRedirect] still
     * registers the policy itself when the GSM leg answers — the ordering that
     * worked on some calls, and the one this replaces.
     */
    private fun preArmCallRedirect(): CallRedirection? {
        val route = GsmCallManager.profile.callRedirect ?: return null
        if (!CallRedirection.hiddenApiOpen(context)) {
            Log.w(TAG, "Pre-arm skipped: hidden-API enforcement is on in this process")
            return null
        }
        if (!CallRedirection.probe()) return null
        val gw = CallRedirection.open(context, route.mixRate, route.wantInjector) ?: return null
        if (!gw.start(route.mixRate)) {
            Log.w(TAG, "Pre-arm: policy streams did not come up; withdrawing before the dial")
            gw.close()
            return null
        }
        Log.i(TAG, "Pre-armed call redirect before the dial: rate=${route.mixRate} " +
            "injector=${gw.hasInjector} uplink=${gw.uplinkRoute} mixes=[${gw.registeredMixes()}]")
        return gw
    }

    /** Withdraws a policy armed for a call that never reached a bridge: the dial
     *  was refused, the SIP caller cancelled, or the GSM leg never answered.
     *  Leaving the mixes registered would hand the next call streams belonging
     *  to a call that no longer exists. */
    private fun closePendingCallRedirect() {
        pendingCallRedirect?.let { armed ->
            pendingCallRedirect = null
            armed.close()
            Log.i(TAG, "Pre-armed call redirect withdrawn: no bridge adopted it")
        }
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_G722) {
        // Not @Synchronized, deliberately: tearDown() is, and it is reached
        // from the Telecom callback on the main thread, so sharing a monitor
        // with this method — which blocks for as long as AudioRecord takes to
        // come up — would hold the UI thread for seconds.  The generation
        // counter gives the same protection without the lock.
        val gen = generation
        // Re-assert RECORD_AUDIO appops SYNCHRONOUSLY before AudioRecord
        // creation.  Must complete before RtpSession.start() so AudioFlinger
        // sees "allow" when the record thread begins reading.  Running async
        // caused a race: AudioRecord started reading silence (denied) before
        // the appops command finished.  RtpSession also periodically re-asserts
        // appops in its timeoutLoop for screen-off resilience.
        forceAllowRecordAudio()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down before RTP setup — not starting")
            return
        }

        activeRtpSession?.let { oldSession ->
            audioReady = false
            oldSession.stop()
            // This path is normally only reached after a duplicate/late RTP
            // callback, but it must obey the same HAL handoff rule as teardown.
            val deadline = System.currentTimeMillis() + RTP_STOP_WAIT_TIMEOUT_MS
            while (!audioReady && generation == gen &&
                System.currentTimeMillis() < deadline) {
                try { Thread.sleep(AUDIO_READY_POLL_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val session = RtpSession(context, localPort, remoteAddr, remotePort, payloadType,
            takeMediaSocket(localPort))

        // DTMF from the SIP side → the GSM call, so IVR menus work.  Accept
        // the payload type the server named in its SDP as well as our own 101.
        val dtmfPt = activeSipCall?.telephoneEventPt ?: RtpPacket.PT_TELEPHONE_EVENT
        session.telephoneEventPts = setOf(dtmfPt, RtpPacket.PT_TELEPHONE_EVENT)
        session.dtmfDetector = DtmfDetector(
            onBegin = { digit -> GsmCallManager.dtmfRelay.begin(digit) },
            onEnd = { digit, _ -> GsmCallManager.dtmfRelay.end(digit) }
        )

        // Attach the negotiated SRTP keys, if this call has any.  Done before
        // start() so no packet is ever sent or accepted unprotected on a call
        // that agreed to be protected.
        activeSipCall?.let { call ->
            val local = call.localSrtpKeys
            val remote = call.remoteSrtpKeys
            if (local != null && remote != null) {
                session.srtpSend = SrtpContext(local)
                session.srtpRecv = SrtpContext(remote)
                Log.i(TAG, "SRTP enabled for this call (${local.suite.sdpName})")
            }
        }
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                if (activeRtpSession === session) {
                    activeRtpSession = null
                }
                Log.i(TAG, "RTP session stopped")

                // Java/native stream release is not the same as Qualcomm HAL
                // readiness on Pixel 4a. The old deep-buffer output has been
                // observed blocked in AudioFlinger for ~10 seconds after this
                // callback. Keep the next GSM call ringing until that drain
                // window has elapsed, otherwise it can reuse stale TELEPHONY_TX.
                val settleMs = GsmCallManager.profile.audioStopSettleDelayMs
                if (settleMs <= 0) {
                    audioReady = true
                    return
                }
                audioReady = false
                Log.i(TAG, "Checking Qualcomm audio HAL readiness (max wait ${settleMs}ms)")
                waitForAudioHalDrain()
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        // Hand over the policy that was registered before this call was dialled,
        // and give up ownership with it: from here the session's own stop() path
        // withdraws it, and tearDown() must not, because the session may be
        // mid-teardown at this very moment.
        pendingCallRedirect?.let { armed ->
            pendingCallRedirect = null
            session.preArmedRedirect = armed
        }
        // Published before start() so a teardown arriving mid-setup can find
        // and stop it rather than leaving an orphaned session holding the
        // audio devices and the RTP socket.
        activeRtpSession = session
        session.start()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down during RTP start — stopping orphaned session")
            session.stop()
            if (activeRtpSession === session) activeRtpSession = null
        } else if (context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                .getBoolean("call_monitor", false)) {
            // Settings → Call monitor: every call starts on Snoop.
            Log.i(TAG, "Call monitor on — snooping this call")
            session.setMonitorEnabled(true)
        }
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String, sipStatus: Pair<Int, String>? = null) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        gsmStateWatchdog?.cancel(false)
        gsmStateWatchdog = null
        diallerInitiated = false
        Log.i(TAG, "Tearing down bridge: $reason")
        GsmCallManager.dtmfRelay.cancel()

        try {
            activeRtpSession?.let {
                audioReady = false
                it.stop()
            }
            activeRtpSession = null

            // A policy armed before the dial that no session ever adopted (this
            // teardown arrived while the GSM leg was still dialling) is ours to
            // withdraw.  Once a session has taken it, pendingCallRedirect is
            // already null and that session's stop() path closes it.
            closePendingCallRedirect()

            // A media socket the bridge opened for an SDP but no RTP session ever
            // took (call failed while GSM was dialling, peer never answered, peer
            // SDP carried no media port) is ours to release.  Left open it holds a
            // port no dialog uses any more, and SO_REUSEADDR elsewhere would call
            // that port free again.
            closeMediaSocket("bridge torn down")

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) {
                        // An INVITE we accepted is ended with BYE; one we never
                        // answered has to be turned down with a final response
                        // instead, which is also the only place the server ever
                        // learns why the GSM leg did not come up.
                        if (it.direction == SipCall.Direction.INBOUND &&
                            it.state != SipCall.State.ANSWERED) {
                            val (code, phrase) = sipStatus ?: (480 to "Temporarily Unavailable")
                            it.reject(code, phrase)
                        } else {
                            it.hangup()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending SIP call: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            // In the outbound flow Telecom can create the Call object and
            // publish it to GsmCallManager just before the InCallService
            // state callback reaches this class.  Prefer our tracked object,
            // but fall back to the manager's object so a SIP CANCEL during GSM
            // ringing cannot leave the cellular leg ringing indefinitely.
            val gsmCallToDisconnect = activeGsmCall ?: GsmCallManager.activeCall
            gsmCallToDisconnect?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingIncoming = null
            pendingRtpAddr = null
            // Cleared with the rest: an old local port left here is a port some
            // later flow might start RTP on that no SDP of ours ever named.
            pendingLocalRtpPort = 0
            pendingRtpPort = 0
            pendingPayloadType = 0
        } finally {
            bridgeState = BridgeState.IDLE
            generation++
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    /**
     * Bind a free even UDP port for RTP and **keep the socket**.
     *
     * The old version bound a probe socket, closed it and returned the number.
     * That number then went into the SDP while the RTP session went off to bind
     * the port again, up to a second later.  Two ways that loses, both measured:
     *
     *  - Between the close and the real bind, nothing at all is listening on the
     *    port the peer has just been told about.  A conforming peer starts
     *    sending the moment it reads the 200 OK.  Air measurement with build 466
     *    (2026-11-06, out\uac-ac6): the host wrote 703 frames to the advertised
     *    192.168.0.6:33642 and the session logged rx=0 for the entire call.
     *  - The probe asked for the port with SO_REUSEADDR, which on Linux succeeds
     *    even when one of our own sockets still holds it, so it does not prove
     *    the port is free — and two sockets on one port means the kernel picks
     *    one of them to deliver to.
     *
     * Holding the socket instead fixes both: the port is exclusively ours (no
     * SO_REUSEADDR here) from the instant the SDP leaves until the session takes
     * it over, and the port the SDP names is read back from the socket itself,
     * so it cannot be a number nothing owns.
     */
    private fun openRtpSocket(): DatagramSocket {
        // One bridge at a time: if a previous flow left a socket behind, that
        // flow is over and its port is not ours to keep.
        closeMediaSocket("replaced by a new bridge")
        val span = (RTP_PORT_MAX - RTP_PORT_MIN) / 2
        val start = (Math.random() * span).toInt()
        var lastError: Exception? = null
        for (i in 0 until span) {
            val port = RTP_PORT_MIN + ((start + i) % span) * 2
            try {
                val sock = DatagramSocket(null).apply {
                    // Deliberately NOT reuseAddress: exclusivity is the point, and
                    // a probe that accepts a port someone already holds is the
                    // other half of the bug above.
                    bind(InetSocketAddress(port))
                }
                synchronized(mediaLock) { mediaSocket = sock }
                // What the kernel bound, not what we asked for: an SDP must name
                // the socket's own port.
                Log.i(TAG, "RTP socket held on port ${sock.localPort} for this call's SDP " +
                        "(bound on ${sock.localSocketAddress})")
                return sock
            } catch (e: Exception) {
                lastError = e
                continue
            }
        }
        throw RuntimeException("No free RTP port available (${lastError?.message})")
    }

    /** Releases the held media socket if no RTP session has taken it. */
    private fun closeMediaSocket(why: String) {
        val sock: DatagramSocket? = synchronized(mediaLock) {
            val s = mediaSocket
            mediaSocket = null
            s
        }
        if (sock == null) return
        try { sock.close() } catch (_: Exception) {}
        Log.i(TAG, "RTP socket on port ${sock.localPort} closed: $why")
    }

    /**
     * The media socket for the port this call's SDP named, or null if the socket
     * is gone (a re-INVITE moved the port, or the flow that opened it ended).
     * Taking it clears the field: from here the RTP session owns the socket and
     * its stop() path closes it.
     */
    private fun takeMediaSocket(expectedPort: Int): DatagramSocket? {
        val held = synchronized(mediaLock) {
            val s = mediaSocket
            if (s != null && s.localPort == expectedPort && !s.isClosed) {
                mediaSocket = null
                s
            } else null
        }
        if (held != null) {
            Log.i(TAG, "Handing the RTP socket on port ${held.localPort} to the session that " +
                    "carries the call whose SDP named it")
            return held
        }
        val current = synchronized(mediaLock) { mediaSocket }
        if (current != null) {
            // A port nobody's SDP advertises any more (a re-INVITE moved it) must
            // not stay held: it is one more port the peer will never be told
            // about, and the probe would later call it free.
            closeMediaSocket("not the SDP port $expectedPort (this one is on ${current.localPort})")
        } else {
            Log.w(TAG, "No held RTP socket for SDP port $expectedPort — the session has to bind it itself")
        }
        return null
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService revokes RECORD_AUDIO (app op 27) for
     * foreground services when the screen is off.  This must be
     * re-asserted before EVERY call, not just at startup.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = context.packageName
            val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            // This sits directly between the call being answered and the first
            // frame of audio, so ask before acting: one `appops get` costs a
            // single root round-trip, where the grant sequence below is eight
            // commands and forks an app_process for each of pm/appops/cmd.
            // In the steady state the permission is already allowed and this
            // returns immediately.
            val probe = RootShell.execForOutput(
                "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
            )
            if (probe.contains("allow", ignoreCase = true)) {
                Log.i(TAG, "appops RECORD_AUDIO already allow — skipping grant")
                return
            }
            Log.w(TAG, "appops RECORD_AUDIO not allowed [$probe] — granting")
            val t0 = System.currentTimeMillis()
            // Capture all output (2>&1) for diagnosis.  appops get is LAST
            // so exit code reflects verification, not a stray killall.
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
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
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
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        gsmStateWatchdog?.cancel(false)
        gsmStateWatchdog = null
        try {
            activeRtpSession?.let {
                audioReady = false
                it.stop()
            }
        } catch (_: Exception) {}
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        pendingIncoming = null
        pendingRtpAddr = null
        pendingLocalRtpPort = 0
        // Same reason as in tearDown(), stated once: a recovery path that clears
        // the dialog without releasing what it opened leaves a port bound that no
        // dialog advertises, and an AudioPolicy mix registered over nothing.
        closePendingCallRedirect()
        closeMediaSocket("bridge force-reset")
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        generation++
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L

        /** Whether an incoming GSM call is accepted, taken over after a reset,
         *  met with busy, or ignored as a second report of the call already
         *  being handled.
         *
         *  The code this replaces asked one question — is the bridge IDLE — and
         *  answered busy to everything else.  Any bridge whose teardown never
         *  finished therefore told every caller of this SIM "the line is busy"
         *  for as long as it lasted, which is exactly what was reported from the
         *  field on 2026-11-06 just after the app was made the default dialer.
         *
         *  What makes a non-IDLE bridge real is one of two live legs: a GSM call
         *  Telecom has not finished, or a SIP dialog that has not terminated.
         *  With neither, the state is left over from something that already
         *  ended.  A state that has not reached BRIDGED within
         *  [STALE_STATE_TIMEOUT_MS] is left over too — every legitimate
         *  non-BRIDGED state is resolved by a timeout inside 70 s (see
         *  [SIP_RING_MAX_MS], the longest honest wait in the machine).
         *
         *  A BRIDGED call is never reset on age alone: two sides are talking and
         *  the third caller really is hearing a busy line.  But BRIDGED is a
         *  claim of *two* live legs, and when only one of them is still there the
         *  state is not a call — the cellular side is connected to nothing, or
         *  the SIP dialog is ringing into a call that has already ended.  Such a
         *  bridge is cleared, and the caller waiting in front of it is met with
         *  [IncomingAction.RESET_AND_REJECT]: busy this time, because a line
         *  genuinely is up, but never busy again afterwards. */
        internal fun decideIncoming(
            state: BridgeState,
            sameCallAsTracked: Boolean,
            liveGsm: Boolean,
            liveSip: Boolean,
            ageMs: Long,
            staleMs: Long = STALE_STATE_TIMEOUT_MS,
        ): IncomingAction = when {
            state == BridgeState.IDLE -> IncomingAction.ACCEPT
            sameCallAsTracked -> IncomingAction.IGNORE_DUPLICATE
            !liveGsm && !liveSip -> IncomingAction.RESET_AND_ACCEPT
            state == BridgeState.BRIDGED && !(liveGsm && liveSip) ->
                IncomingAction.RESET_AND_REJECT
            state != BridgeState.BRIDGED && ageMs > staleMs -> IncomingAction.RESET_AND_ACCEPT
            else -> IncomingAction.REJECT_BUSY
        }

        /** Safety net once the server reports ringing (its own dial timeout normally ends it first). */
        private const val SIP_RING_MAX_MS = 70_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
        private const val AUDIO_READY_POLL_MS = 100L
        private const val AUDIO_READY_WAIT_TIMEOUT_MS = 30_000L
        private const val RTP_STOP_WAIT_TIMEOUT_MS = 10_000L
        private const val AUDIO_HAL_PROBE_TIMEOUT_MS = 1_500L

        private const val RTP_PORT_MIN = 30000
        private const val RTP_PORT_MAX = 40000

        /** Placeholder shown in settings until a real MSISDN is entered. */
        private const val DEFAULT_OWN_NUMBER = "+49123123123123"

        /** Shortest Request-URI user we will believe is a number to dial. */
        private const val MIN_DIALABLE_DIGITS = 3
    }
}
