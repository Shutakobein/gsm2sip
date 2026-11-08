package com.callagent.gateway.gsm

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether a tracked GSM call counts as "live" when the two ways of knowing
 * disagree.
 *
 * This is the second half of the busy-tone failure reported from the field on
 * 2026-11-06.  [GsmCallManager.hasLiveCall] used to ask one thing: the state
 * carried by the [Call] object.  That state is a copy of what Telecom last
 * delivered, and nothing in Android guarantees a final DISCONNECTED reaches us:
 * if the session ends, another dialer takes the call, or the radio releases it
 * quietly, the copy is frozen at ACTIVE.  A bridge stuck in BRIDGED over that
 * object has no way to notice, and the answer to every later caller of the SIM
 * is busy until the process restarts.
 *
 * `InCallService.getCalls()` is Telecom's own list.  Used as a veto — it may
 * say "no call", it never gets to say "a call we are not tracking is our call" —
 * it is the one signal that cannot go stale.
 */
class CallLivenessTest {

    private data class Row(
        val name: String,
        val trackedState: Int?,
        val callbackState: Int = Call.STATE_NEW,
        val telecomStates: List<Int>? = null,
        val want: Boolean,
    )

    private val rows = listOf(
        // The field failure: the object is frozen, Telecom has nothing.
        Row(
            "object says ACTIVE, Telecom holds no call at all",
            Call.STATE_ACTIVE, callbackState = Call.STATE_ACTIVE,
            telecomStates = emptyList(), want = false,
        ),
        Row(
            "object says ACTIVE, Telecom holds only a finished call",
            Call.STATE_ACTIVE, callbackState = Call.STATE_ACTIVE,
            telecomStates = listOf(Call.STATE_DISCONNECTED), want = false,
        ),
        Row(
            "object says RINGING, Telecom holds no call",
            Call.STATE_RINGING, telecomStates = emptyList(), want = false,
        ),
        // Both agree: live.
        Row(
            "object says ACTIVE, Telecom says ACTIVE",
            Call.STATE_ACTIVE, callbackState = Call.STATE_ACTIVE,
            telecomStates = listOf(Call.STATE_ACTIVE), want = true,
        ),
        Row(
            "object says RINGING, Telecom says RINGING",
            Call.STATE_RINGING, callbackState = Call.STATE_RINGING,
            telecomStates = listOf(Call.STATE_RINGING), want = true,
        ),
        // No InCallService bound: the object is all there is, so it is believed.
        Row(
            "no service bound, object says ACTIVE",
            Call.STATE_ACTIVE, callbackState = Call.STATE_ACTIVE,
            telecomStates = null, want = true,
        ),
        Row(
            "no service bound, call object unreadable",
            trackedState = null, telecomStates = null, want = true,
        ),
        Row(
            "call object unreadable, Telecom holds no call",
            trackedState = null, telecomStates = emptyList(), want = false,
        ),
        // The object is asked even when Telecom has a live call, because "a call
        // exists" is not "the call we are bridging exists".
        Row(
            "object says DISCONNECTED while another call is up",
            Call.STATE_DISCONNECTED, callbackState = Call.STATE_DISCONNECTED,
            telecomStates = listOf(Call.STATE_RINGING), want = false,
        ),
        Row(
            "object says DISCONNECTED, Telecom agrees",
            Call.STATE_DISCONNECTED, callbackState = Call.STATE_DISCONNECTED,
            telecomStates = listOf(Call.STATE_DISCONNECTED), want = false,
        ),
        // A call seen as NEW whose state never moved is not a call in progress.
        Row(
            "object still NEW, no callback ever came",
            Call.STATE_NEW, callbackState = Call.STATE_NEW,
            telecomStates = listOf(Call.STATE_NEW), want = false,
        ),
        Row(
            "object still NEW, but a callback reported DIALING",
            Call.STATE_NEW, callbackState = Call.STATE_DIALING,
            telecomStates = listOf(Call.STATE_NEW), want = true,
        ),
        Row(
            "object still NEW, last callback said it ended",
            Call.STATE_NEW, callbackState = Call.STATE_DISCONNECTED,
            telecomStates = listOf(Call.STATE_NEW), want = false,
        ),
        // Telecom holding a call that we cannot read is the one case where an
        // unreadable object is treated as live — a wrong "live" is repaired by
        // the wedge path, a wrong "dead" drops a call that is really up.
        Row(
            "call object unreadable, Telecom holds it",
            trackedState = null, telecomStates = listOf(Call.STATE_ACTIVE), want = true,
        ),
    )

    @Test
    fun table() {
        for (r in rows) {
            assertEquals(
                r.name,
                r.want,
                GsmCallManager.isCallLive(r.trackedState, r.callbackState, r.telecomStates),
            )
        }
    }

    /** A live call is only ever denied by an empty Telecom list.
     *
     *  Anything else in Telecom's list must not change the answer the object
     *  gives, or the veto becomes a second opinion that can also cut a real
     *  call off mid-sentence. */
    @Test
    fun telecomOnlyEverVetoes() {
        for (tracked in listOf(
            Call.STATE_NEW, Call.STATE_DIALING, Call.STATE_RINGING, Call.STATE_ACTIVE,
            Call.STATE_CONNECTING, Call.STATE_HOLDING, Call.STATE_DISCONNECTING,
        )) {
            val unknown = GsmCallManager.isCallLive(tracked, Call.STATE_ACTIVE, null)
            val confirmed = GsmCallManager.isCallLive(tracked, Call.STATE_ACTIVE, listOf(Call.STATE_ACTIVE))
            assertEquals("$tracked confirmed by Telecom", unknown, confirmed)
            assertEquals("$tracked denied by an empty list", false,
                GsmCallManager.isCallLive(tracked, Call.STATE_ACTIVE, emptyList()))
        }
    }
}
