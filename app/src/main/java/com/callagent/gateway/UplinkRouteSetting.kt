package com.callagent.gateway

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.callagent.gateway.rtp.CallRedirection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the uplink route knob at the value the certified GSM uplink uses, so it
 * never has to be typed into a shell again.
 *
 * The knob is `Settings.Global/callagent_uplink_via`, read once per call by
 * [CallRedirection]'s `uplinkVia()` and again by [com.callagent.gateway.rtp.RtpSession]
 * when the call's session object is built: 0 takes the agent's side of the call
 * through the AudioMix injector, 1 and 2 play it out of the handset and record it
 * back in.  The route certified on 2026-11-08 — four bridged calls in
 * `gateway-test/out/farend-own-call-20261108-163344`, baseline record in
 * `gateway-test/out/MILESTONE-471-VIA0.md` — is 0, and up to now that value was
 * applied by hand with `settings put global callagent_uplink_via 0` from ADB or
 * Termux.  This object does the same write itself, through the app's existing
 * [RootShell], as part of bringing the gateway up.
 *
 * Two facts about the key decided how it is treated here:
 *
 *  - It lives in `Settings.Global`, which is one namespace for the whole device,
 *    but nothing outside this app reads it.  The string appears in exactly two
 *    places in the shipped source — the two readers named above — and in no
 *    framework, init script or other app in this tree (checked across the whole
 *    tree, not assumed: `magisk/service.sh` writes only `hidden_api_policy`).  A
 *    cellular call this gateway is not bridging therefore cannot notice its
 *    value, so there is nothing to restore on teardown and no lifecycle to
 *    design around other than "set it and leave it", which is what was asked
 *    for.  Anyone who later gives a `callagent_` key a second consumer has to
 *    re-read this note before deciding that.
 *  - 0 is also what the code uses when the key is absent: `uplinkVia()` defaults
 *    to 0 and `RtpSession` asks for it with a default of 0.  On a clean device
 *    this write changes no behaviour.  What it buys is that the certified route
 *    is stated instead of implied, and that a value left at 1 by hand — which is
 *    how field sessions kept ending up on the acoustic route while the code was
 *    assumed to be on the injected one — gets put back.
 *
 * Where the write happens matters more than the write.  A root command blocks:
 * the first Magisk `su` of a boot costs 4+ seconds, and the 2026-11-07/08
 * campaign found commands run during call setup delaying the answer.  So this
 * never runs on an audio thread, on an RTP frame, or anywhere in call setup.  It
 * runs on the `gateway-init` thread before SIP registration makes the device
 * reachable for a call, and from the boot receiver after a restart.  The current
 * value is read through the framework first — free, no shell — and the shell is
 * only used when the value is missing or wrong, so on a healthy device a
 * bring-up costs one settings read and no root round trip at all.
 */
object UplinkRouteSetting {
    private const val TAG = "UplinkRoute"

    /** The same constant the readers use, so the writer and the reader cannot
     *  drift apart into two spellings of one knob. */
    private const val KEY = CallRedirection.UPLINK_SETTING

    /** 0 = the agent's side goes through the injector mix: the certified route. */
    private const val DESIRED = 0

    /** Root round trips.  The first one after a boot pays for Magisk's `su`. */
    private const val READ_TIMEOUT_MS = 6_000L
    private const val WRITE_TIMEOUT_MS = 10_000L

    /** Set once the value has been confirmed at [DESIRED] in this process.
     *  Bring-up runs more than once per process — START_STICKY redelivery, the
     *  Activity's auto-start, a reconnect — and a confirmed value needs no shell
     *  afterwards, which is the "not on every frame, not on every callback" rule
     *  expressed in the only place it can be enforced. */
    @Volatile private var confirmed = false

    /** Whether the "already set" line has reached the app log yet.  Logged at
     *  debug level after the first time: it is a per-bring-up fact, and repeating
     *  it buries the bring-up lines that matter. */
    @Volatile private var announced = false

    /** True once the drift note has been reported since the last successful
     *  apply, so a hand-set value cannot produce one warning line per call. */
    @Volatile private var driftReported = false

    /** One pass at a time.  Two bring-ups racing (the `START_STICKY` redelivery
     *  with a null intent plus the Activity's auto-start) would otherwise queue
     *  two identical writes into one root shell. */
    private val running = AtomicBoolean(false)

    /**
     * Reads the key the way this app's own call path reads it: through the
     *  framework, no shell, no root.  Null means the key is not present.
     *
     * Public because the on-screen diagnostics and the gateway's bring-up lines
     *  both want to print the same number the next call will use, and a second
     *  spelling of this read is how the two would disagree.
     */
    fun currentValue(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, KEY, -1).let { if (it < 0) null else it }
    } catch (t: Throwable) {
        // A settings read has no business being why a bring-up failed.
        Log.w(TAG, "$KEY read failed: ${t.message}")
        null
    }

    /**
     * Makes sure the next call reads [DESIRED], writing it through [RootShell]
     * when it does not.
     *
     * Call from a background thread: the root round trip blocks.  Never throws,
     * and [KEY] is the only setting this writes — nothing else in the settings
     * namespace is in scope here, and the mixer and ABOX state are not touched by
     * anything in this file.
     *
     * @param reason what brought the gateway up, for the log line
     * @param report app-log sink (`GatewayService::broadcastLog`), so a failure is
     *               visible to the operator without going looking in logcat
     * @return true when the key reads back as [DESIRED]
     */
    fun ensureApplied(context: Context, reason: String, report: ((String) -> Unit)? = null): Boolean {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "$KEY: apply already in progress ($reason) — skipping this pass")
            return confirmed
        }
        return try {
            val t0 = System.currentTimeMillis()
            val before = currentValue(context)

            if (before == DESIRED) {
                // The common case, and deliberately shell-free: the value the next
                // call will read is already the right one, so there is nothing to
                // write and nothing to spend.
                confirmed = true
                if (!announced) {
                    announced = true
                    val msg = "Uplink route: $KEY=$before already set " +
                        "(injector mix, verified at ${System.currentTimeMillis() - t0}ms, $reason)"
                    Log.i(TAG, msg)
                    report?.invoke(msg)
                } else {
                    Log.d(TAG, "$KEY already $before ($reason) — no write needed")
                }
                return true
            }

            Log.i(TAG, "$KEY is ${before ?: "unset"}, want $DESIRED ($reason) — " +
                "writing via root (root=${RootShell.rootState()})")
            val echoed = writeViaRoot(viaCmd = false)

            // Verified with the framework read rather than with the shell's echo,
            // because the framework read is what `uplinkVia()` and `RtpSession`
            // will do when the next RTP session is built.  A shell that reports 0
            // while this process still reads something else means the settings
            // cache has not caught up with a write made outside this process, and
            // the call would follow this process's copy, not the provider's.
            var after = currentValue(context)
            if (after != DESIRED) {
                // Same shape as the appops path in GatewayService: the `cmd` form
                // talks to the settings service directly and works when the
                // `settings` binary is missing or refused.
                Log.w(TAG, "$KEY still ${after ?: "unset"} after `settings put` — retrying as `cmd settings`")
                writeViaRoot(viaCmd = true)
                after = currentValue(context)
            }

            val elapsed = System.currentTimeMillis() - t0
            val root = RootShell.rootState()
            if (after == DESIRED) {
                confirmed = true
                driftReported = false
                val msg = "Uplink route: $KEY ${before ?: "unset"} -> $DESIRED " +
                    "(verified by read-back, ${elapsed}ms, root=$root, shell said [$echoed])"
                Log.i(TAG, msg)
                report?.invoke(msg)
                true
            } else {
                // Not fatal, and said as such: with the key absent or wrong,
                // `uplinkVia()` falls back to 0 anyway, so the injector route this
                // app was certified on is what a call still takes.  What is lost is
                // the statement of intent, and the protection against a value that
                // was left at 1 or 2 by hand — which is exactly the case that needs
                // to be visible rather than inferred.
                val msg = "WARNING: could not set $KEY=$DESIRED (root=$root, value now " +
                    "${after ?: "unset"}, ${elapsed}ms). A call still defaults to the " +
                    "injector route, but the route is no longer stated and a hand-set " +
                    "value would win — check root access in Magisk, then look at " +
                    "`$KEY`. Retried at the next gateway bring-up."
                Log.e(TAG, msg)
                report?.invoke(msg)
                false
            }
        } catch (e: Exception) {
            // RootShell already swallows its own failures; this is for anything
            // else — a dead context, a settings provider refusing to answer.
            val msg = "Uplink route check failed: ${e.message}"
            Log.w(TAG, msg, e)
            report?.invoke(msg)
            false
        } finally {
            running.set(false)
        }
    }

    /**
     * One write plus the provider's own answer to it, for the log.
     *
     * `2>&1` matters: [RootShell.execForOutput] reads only the shell's stdout, so
     * a refusal that stays on stderr comes back as empty output and looks like a
     * command that worked.
     */
    private fun writeViaRoot(viaCmd: Boolean): String {
        val put = if (viaCmd)
            "cmd settings put global $KEY $DESIRED 2>&1"
        else
            "settings put global $KEY $DESIRED 2>&1"
        val out = RootShell.execForOutput(
            "$put; settings get global $KEY 2>&1",
            timeoutMs = WRITE_TIMEOUT_MS
        )
        Log.i(TAG, "$KEY write via ${if (viaCmd) "`cmd settings`" else "`settings`"}: [$out]")
        return out
    }

    /**
     * Call-path hook: records the route a call actually resolved to.
     *
     * Read-only by construction — it starts no shell, runs no command, and cannot
     * repair anything, because the route for a call is fixed when that call's RTP
     * session is built: writing the key mid-call would change nothing about the
     * call in progress, and the write itself would put a root round trip next to
     * the audio threads, which is the failure the 2026-11-08 field rules exist to
     * prevent.  What it does is state once, in logcat, that a call read a route
     * other than the one this process applied; the repair lands at the next
     * bring-up, which is when the value is actually re-read.
     */
    fun noteObserved(observed: Int) {
        if (observed == DESIRED) {
            driftReported = false
            return
        }
        if (driftReported) return
        driftReported = true
        Log.w(TAG, "Uplink route: a call read $KEY=$observed, not $DESIRED — changed by " +
            "hand, or an apply failed. Nothing is rewritten mid-call (the route is " +
            "chosen when the call's RTP session is built), so it is re-applied at " +
            "the next gateway bring-up.")
    }
}
