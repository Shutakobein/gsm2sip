package com.callagent.gateway.gsm

import com.callagent.gateway.gsm.GsmCallManager.RxMixerOp
import com.callagent.gateway.gsm.GsmCallManager.RxSilence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether a bridge call is allowed to silence the handset's own earpiece.
 *
 * The field complaint of 2026-11-06 was "robotic audio repetition" heard by the
 * remote party.  On the Exynos 990 the candidate control is *not* the
 * microphone — the physical mic is the uplink carrier on that route, so muting
 * it would take the injected agent leg away with it (the same trap the v2.8.50
 * framework-mute note records).  What can be muted without touching the uplink
 * is the call's downlink at the DSP, so the caller's own voice stops leaving the
 * handset and being picked up again.
 *
 * Whether that helps is a question for the far end's ear, not for a meter, so
 * the control is behind an operator-written switch, default off, and this table
 * is what keeps "off" and "never write anything it cannot undo" guaranteed.
 */
class RxSilenceDecisionTest {

    private val op = RxMixerOp("x tinymix -D 0 'ABOX Speech Rx Mute' 1", "y")

    @Test
    fun nothingIsTouchedUnlessTheSwitchIsOn() {
        assertEquals(RxSilence.LEAVE_ALONE,
            GsmCallManager.decideRxSilence(on = false, op, appliedOutstanding = false))
    }

    /** A device whose profile has no such control — every device but this one —
     *  must not be written to even if the switch happens to be set. */
    @Test
    fun aDeviceWithNoControlIsNeverWrittenTo() {
        for (on in booleanArrayOf(false, true))
            for (applied in booleanArrayOf(false, true))
                assertEquals("on=$on applied=$applied", RxSilence.LEAVE_ALONE,
                    GsmCallManager.decideRxSilence(on, null, applied))
    }

    @Test
    fun switchOnAppliesTheMute() {
        for (applied in booleanArrayOf(false, true))
            assertEquals(RxSilence.APPLY,
                GsmCallManager.decideRxSilence(on = true, op, applied))
    }

    /** The switch turned off while a mute from an earlier call is still
     *  outstanding: the next call has to give the earpiece back, or ordinary
     *  phone use is left muted by a test that has already ended. */
    @Test
    fun aMuteLeftBehindFromAnEarlierCallIsGivenBack() {
        assertEquals(RxSilence.REVERT,
            GsmCallManager.decideRxSilence(on = false, op, appliedOutstanding = true))
    }
}
