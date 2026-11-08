package com.callagent.gateway.bridge

import com.callagent.gateway.bridge.CallOrchestrator.BridgeState
import com.callagent.gateway.bridge.CallOrchestrator.IncomingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an incoming GSM call is met with while the bridge is not IDLE.
 *
 * This table is the whole of finding 2 as reported from the field on
 * 2026-11-06: "when gsm2sip app is set as default dialer it says line is busy
 * on the other end when they call s20+".  Setting the dialer role unbinds and
 * rebinds the InCallService, so a bridge that was open at that moment keeps its
 * state while losing every way of learning that its GSM leg ended — and the old
 * code answered "is the bridge IDLE?" and nothing else, so that state told every
 * later caller a busy tone, for as long as the process lived.
 *
 * The rule being held to: busy is only said when a leg is demonstrably up.
 */
class IncomingCallDecisionTest {

    /** One row of the table, named the way a person would name the situation. */
    private data class Row(
        val name: String,
        val state: BridgeState,
        val sameCall: Boolean = false,
        val liveGsm: Boolean = false,
        val liveSip: Boolean = false,
        val ageMs: Long = 1_000L,
        val want: IncomingAction,
    )

    private val rows = listOf(
        // The two honest answers.
        Row("nothing in progress", BridgeState.IDLE, want = IncomingAction.ACCEPT),
        Row(
            "on the air: caller on GSM and agent on SIP",
            BridgeState.BRIDGED, liveGsm = true, liveSip = true,
            ageMs = 120_000, want = IncomingAction.REJECT_BUSY,
        ),
        // The field failure, in each of its shapes.  A wedged bridge has no live
        // leg, whatever state name it is wearing.
        Row(
            "wedged after the dialer role changed, mid-dial",
            BridgeState.GSM_DIALING, want = IncomingAction.RESET_AND_ACCEPT,
        ),
        Row(
            "wedged mid-SIP, agent never answered",
            BridgeState.SIP_CALLING, liveGsm = false, liveSip = false,
            ageMs = 20_000, want = IncomingAction.RESET_AND_ACCEPT,
        ),
        Row(
            "wedged in teardown — the cleanup never finished",
            BridgeState.TEARING_DOWN, want = IncomingAction.RESET_AND_ACCEPT,
        ),
        Row(
            "wedged while still BRIDGED, both legs gone",
            BridgeState.BRIDGED, liveGsm = false, liveSip = false,
            ageMs = 300_000, want = IncomingAction.RESET_AND_ACCEPT,
        ),
        // One leg up is still busy: the gateway serves one call at a time.
        Row(
            "GSM leg up, SIP leg died",
            BridgeState.SIP_RINGING, liveGsm = true, liveSip = false,
            want = IncomingAction.REJECT_BUSY,
        ),
        Row(
            "SIP leg up, its GSM leg already gone",
            BridgeState.SIP_CALLING, liveGsm = false, liveSip = true,
            want = IncomingAction.REJECT_BUSY,
        ),
        // A state that is neither live nor finished is abandoned by age, so a
        // wedged bridge cannot outlive the timeout even if a leg reports "live"
        // from a Call object that can no longer be read.  Not BRIDGED: two sides
        // that are talking are not abandoned for being old.
        Row(
            "one leg claims to be live, 100 s in a non-bridged state",
            BridgeState.GSM_ANSWERED, liveGsm = true, liveSip = false,
            ageMs = 100_000, want = IncomingAction.RESET_AND_ACCEPT,
        ),
        Row(
            "bridged and old — that is just a long call",
            BridgeState.BRIDGED, liveGsm = true, liveSip = true,
            ageMs = 900_000, want = IncomingAction.REJECT_BUSY,
        ),
        // BRIDGED claims two live legs.  With one of them gone the "call" is a
        // cellular side connected to nothing, or a SIP dialog ringing at nobody,
        // and leaving it up is what makes the busy tone permanent.  Busy once,
        // wedge cleared.
        Row(
            "bridged with the SIP leg dead — a deaf call, and a wedge",
            BridgeState.BRIDGED, liveGsm = true, liveSip = false,
            ageMs = 30_000, want = IncomingAction.RESET_AND_REJECT,
        ),
        Row(
            "bridged with the GSM leg dead — Telecom let go without saying",
            BridgeState.BRIDGED, liveGsm = false, liveSip = true,
            ageMs = 30_000, want = IncomingAction.RESET_AND_REJECT,
        ),
        // Not a new caller at all: Telecom reports ringing twice for one call.
        Row(
            "same call, second callback",
            BridgeState.GSM_RINGING, sameCall = true, liveGsm = true,
            want = IncomingAction.IGNORE_DUPLICATE,
        ),
        Row(
            "IDLE but the same call object again",
            BridgeState.IDLE, sameCall = true, want = IncomingAction.ACCEPT,
        ),
    )

    @Test
    fun table() {
        for (r in rows) {
            val got = CallOrchestrator.decideIncoming(r.state, r.sameCall, r.liveGsm, r.liveSip, r.ageMs)
            assertEquals(r.name, r.want, got)
        }
    }

    /** Nothing that is not IDLE, and has no live leg behind it, may ever be
     *  answered with busy — that is the permanent busy tone. */
    @Test
    fun wedgedIsNeverBusy() {
        for (state in BridgeState.entries) {
            val got = CallOrchestrator.decideIncoming(state, false, liveGsm = false, liveSip = false, ageMs = 5_000L)
            if (state == BridgeState.IDLE) assertEquals(IncomingAction.ACCEPT, got)
            else assertEquals("$state with no live leg", IncomingAction.RESET_AND_ACCEPT, got)
        }
    }

    /** The age rule is a fallback, not the primary one: inside the timeout, a
     *  live leg still means busy. */
    @Test
    fun liveLegWinsOverAgeUntilTheTimeout() {
        for (age in longArrayOf(0, 1_000, 59_000)) {
            assertEquals(
                "$age ms in SIP_CALLING with GSM up",
                IncomingAction.REJECT_BUSY,
                CallOrchestrator.decideIncoming(BridgeState.SIP_CALLING, false, true, false, age),
            )
        }
        assertEquals(
            "past the timeout",
            IncomingAction.RESET_AND_ACCEPT,
            CallOrchestrator.decideIncoming(BridgeState.SIP_CALLING, false, true, false, 61_000L),
        )
    }

    /** A busy answer must never be reachable from a state that is both old and
     *  has lost its SIP leg, because that is the shape of a failed SIP call
     *  whose GSM cleanup did not run. */
    @Test
    fun staleWithNoSipLegAlwaysRecovers() {
        for (state in BridgeState.entries) {
            if (state == BridgeState.IDLE) continue         // nothing to recover
            val want = if (state == BridgeState.BRIDGED)
                IncomingAction.RESET_AND_REJECT             // a line is up, so: busy, and cleared
            else
                IncomingAction.RESET_AND_ACCEPT
            val got = CallOrchestrator.decideIncoming(state, false, liveGsm = true, liveSip = false, ageMs = 300_000L)
            assertEquals("$state at 300 s", want, got)
        }
    }

    /** The shape of the complaint, told as two callers in a row.
     *
     *  A bridge that claims BRIDGED over a leg that has already ended answers
     *  "busy" for as long as it lasts, which — with nothing left to end it — is
     *  until the process restarts.  Every combination that produces a busy
     *  answer here also clears the state that produced it, so the answer holds
     *  for one caller and not for the queue behind them. */
    @Test
    fun busyIsNeverRepeated() {
        for (state in BridgeState.entries) {
            for (liveGsm in booleanArrayOf(false, true)) {
                for (liveSip in booleanArrayOf(false, true)) {
                    for (age in longArrayOf(0, 30_000, 300_000)) {
                        val first = CallOrchestrator.decideIncoming(state, false, liveGsm, liveSip, age)
                        if (first != IncomingAction.RESET_AND_REJECT) continue
                        // forceReset hangs up whatever was left, so the next
                        // caller arrives to an idle bridge and nothing live.
                        val second = CallOrchestrator.decideIncoming(
                            BridgeState.IDLE, false, liveGsm = false, liveSip = false, ageMs = 0
                        )
                        assertEquals(
                            "$state/${liveGsm}/${liveSip}/${age}ms cleared the wedge",
                            IncomingAction.ACCEPT, second,
                        )
                    }
                }
            }
        }
    }

    /** What "busy" is allowed to rest on.
     *
     *  Either both legs of a bridged call, or one leg that is still young enough
     *  to be believed.  Anything else and the answer is a recovery, because a
     *  busy tone is what the caller hears when the gateway has wedged, and they
     *  have no way to tell the two apart. */
    @Test
    fun busyNeedsAHonestBridge() {
        for (state in BridgeState.entries) {
            for (liveGsm in booleanArrayOf(false, true)) {
                for (liveSip in booleanArrayOf(false, true)) {
                    val got = CallOrchestrator.decideIncoming(state, false, liveGsm, liveSip, ageMs = 1_000L)
                    if (got != IncomingAction.REJECT_BUSY) continue
                    assertTrue(
                        "$state/${liveGsm}/${liveSip} should not be busy",
                        liveGsm || liveSip,
                    )
                    if (state == BridgeState.BRIDGED)
                        assertTrue("BRIDGED busy needs both legs", liveGsm && liveSip)
                }
            }
        }
    }
}
