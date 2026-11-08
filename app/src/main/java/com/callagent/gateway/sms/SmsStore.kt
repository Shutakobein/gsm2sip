package com.callagent.gateway.sms

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One received SMS, on its way to the SIP server.
 *
 * [id] is generated on arrival and travels with the message as X-SMS-Id.  It
 * is the server's idempotency key: a MESSAGE whose response is lost gets sent
 * again, and only the id tells the server the second copy is the same SMS.
 */
data class PendingSms(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val receivedAt: Long,
    val parts: Int,
    val subId: Int,
    val slot: Int,
    val carrier: String,
    val attempts: Int = 0
)

/**
 * Durable queue of received SMS that have not been handed to the server yet.
 *
 * An SMS arrives whether or not SIP is registered — during a reconnect, in the
 * seconds after a reboot, while the modem has no data — and the message is the
 * only copy.  It is written to disk before any send is attempted, and removed
 * only once the server has said yes.
 *
 * Everything here is synchronous and runs on whichever caller thread reaches it:
 * the SMS broadcast's main thread when a message arrives, the flush thread when
 * one is forwarded.  The queue is small and the write is one short JSON blob, so
 * `commit()` is used deliberately — the message has to be on disk before the
 * broadcast finishes, because the process that handled it may be the last one
 * that ever holds it.
 *
 * A message the server refuses over and over (a sender name the server's parser
 * cannot take, a body it rejects) stays here and is retried, because one of the
 * two reasons it fails is usually temporary.  It is not kept for ever: after
 * [MAX_AGE_MS] or [MAX_ATTEMPTS] it moves to a small dead-letter list, which
 * both stops the retry and leaves the evidence behind for whoever looks at the
 * logs.  The limits are deliberately wide — see their comments.
 */
object SmsStore {
    private const val TAG = "SmsStore"

    /**
     * Names already on disk.  A gateway upgrade must find the messages the
     * previous build queued, so these are not free choices: the preference file
     * is `sms_queue.xml`, the entry list is key `pending`, and each row says
     * `ts` for its timestamp and `sub` for the subscription.  Changing any of
     * them silently orphans every queued SMS, which is the one thing this class
     * exists to prevent.  The reader still accepts the longer names as a fallback.
     */
    private const val PREFS = "sms_queue"
    private const val KEY = "pending"

    /** Retired messages, kept only so a human can find out what was lost. */
    private const val KEY_DEAD = "dead"
    private const val MAX_DEAD = 20

    private const val MAX_ENTRIES = 500

    /** A week of failing, and the message is too old to be worth forwarding:
     *  a bank code or a one-time password is worthless the next day. */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Wide on purpose.  At the retry spacing the service uses (30 s) this is
     *  about a day of trying, which is well past the point a real
     *  misconfiguration shows itself, while leaving a message whose problem was
     *  temporary plenty of room to come good. */
    private const val MAX_ATTEMPTS = 3000

    fun newId(): String = UUID.randomUUID().toString()

    fun add(context: Context, msg: PendingSms) {
        write(context, read(context) + msg)
        Log.i(TAG, "SMS queued: ${msg.id} from ${msg.from} (${msg.text.length} chars)")
    }

    fun pending(context: Context): List<PendingSms> = read(context)

    fun remove(context: Context, id: String) {
        val current = read(context)
        val kept = current.filter { it.id != id }
        if (kept.size != current.size) write(context, kept)
    }

    /**
     * Records that a send did not work and says what is left of the message.
     *
     * Returns the entry with its new attempt count, or null when this attempt
     * ended its run: the message has been moved out of the queue and into
     * [retired].  The caller does not need to know which of the two it is to
     * keep going — it only needs the count to decide whether to say anything.
     */
    fun markAttempt(context: Context, id: String): PendingSms? {
        val current = read(context)
        var updated: PendingSms? = null
        val next = current.map {
            if (it.id != id) it
            else it.copy(attempts = it.attempts + 1).also { entry -> updated = entry }
        }
        val entry = updated ?: return null
        if (entry.attempts >= MAX_ATTEMPTS ||
            System.currentTimeMillis() - entry.receivedAt > MAX_AGE_MS
        ) {
            write(context, next.filter { it.id != id })
            retire(context, entry)
            return null
        }
        write(context, next)
        return entry
    }

    /** Messages that were given up on, newest failure first. */
    fun retired(context: Context): List<PendingSms> = read(context, KEY_DEAD)

    fun clearRetired(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DEAD).apply()
    }

    private fun retire(context: Context, entry: PendingSms) {
        val dead = (read(context, KEY_DEAD) + entry).let {
            if (it.size <= MAX_DEAD) it else it.takeLast(MAX_DEAD)
        }
        write(context, dead, KEY_DEAD)
        Log.e(
            TAG,
            "SMS retired: ${entry.id} from ${entry.from} after ${entry.attempts} attempts, " +
                "${(System.currentTimeMillis() - entry.receivedAt) / 3_600_000} h old"
        )
    }

    /**
     * Store a queue, dropping something if the queue has grown past its bound.
     *
     * What gets dropped is the entry that has failed the most times: it is the
     * one that has already had its chance and is the least likely to ever go
     * out, whereas dropping the oldest unconditionally (as this used to do)
     * threw away the freshest thing in the queue — a message that had not been
     * tried yet — while keeping a poison one for ever.
     */
    private fun write(context: Context, messages: List<PendingSms>, key: String = KEY) {
        var entries = messages
        if (key == KEY && entries.size > MAX_ENTRIES) {
            val victim = entries.filter { it.attempts > 0 }
                .maxByOrNull { it.attempts }
                ?: entries.first()
            entries = entries.filter { it !== victim }
            Log.e(TAG, "SMS queue full: dropped ${victim.id} after ${victim.attempts} attempts")
        }
        val array = JSONArray()
        for (m in entries) {
            array.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("from", m.from)
                    put("to", m.to)
                    put("text", m.text)
                    put("ts", m.receivedAt)
                    put("parts", m.parts)
                    put("sub", m.subId)
                    put("slot", m.slot)
                    put("carrier", m.carrier)
                    put("attempts", m.attempts)
                }
            )
        }
        prefs(context).edit().putString(key, array.toString()).commit()
    }

    private fun read(context: Context): List<PendingSms> = read(context, KEY)

    private fun read(context: Context, key: String): List<PendingSms> {
        val raw = prefs(context).getString(key, "[]") ?: return emptyList()
        val rows = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.opt(it) as? JSONObject }
        } catch (e: Exception) {
            // A preference file interrupted mid-write, or a hand-edited one,
            // used to throw here: on the flush thread that ended the drain and
            // every message in the queue was stranded behind the broken blob.
            // Losing one unreadable row is bad; losing the whole queue is worse.
            Log.e(TAG, "Unreadable SMS queue ($key): ${e.message} — salvaging what parses")
            JsonArrayRows.parse(raw)
        }
        return rows.mapNotNull(::fromJson).filter { it.id.isNotEmpty() }
    }

    private fun fromJson(o: JSONObject): PendingSms? {
        // optX rather than getX: a row written by an older build, or one that
        // survived a torn write with a field missing, used to throw and take the
        // whole queue down with it.  Absent fields are defaults, not a fatal bug.
        val text = o.optString("text", "")
        // The names actually on disk are the short ones; the long spellings are
        // accepted as well so a row can never be read as "empty" and discarded.
        val receivedAt = firstLong(o, "ts", "receivedAt")
        return if (receivedAt == 0L && text.isEmpty()) null else PendingSms(
            id = o.optString("id", ""),
            from = o.optString("from", ""),
            to = o.optString("to", ""),
            text = text,
            receivedAt = receivedAt,
            parts = o.optInt("parts", 1),
            subId = firstInt(o, "sub", "subId"),
            slot = o.optInt("slot", -1),
            carrier = o.optString("carrier", ""),
            attempts = o.optInt("attempts", 0)
        )
    }

    /** First key present, in order; -1 when none of them is. */
    private fun firstInt(o: JSONObject, vararg keys: String): Int {
        for (k in keys) if (o.has(k)) return o.optInt(k, -1)
        return -1
    }

    private fun firstLong(o: JSONObject, vararg keys: String): Long {
        for (k in keys) if (o.has(k)) return o.optLong(k, 0L)
        return 0L
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
