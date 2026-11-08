package com.callagent.gateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row recovery for the stored SMS blobs.
 *
 * The stores read their whole JSON array in one call, and that call throws on a
 * single damaged byte — a preference file interrupted mid-write, or a hand-edit.
 * When it threw, the caller returned an empty list, and on the SMS queue that
 * meant every unread message in the blob was gone: the gateway's only copy of
 * them.  These tests pin the salvage path that keeps the readable rows.
 *
 * Only [JsonArrayRows.objects] is exercised here: JSONObject is a stub on the
 * unit-test JVM, so parsing a recovered row cannot be checked without a device.
 */
class JsonArrayRowsTest {

    @Test
    fun wholeRowsComeBackInOrder() {
        assertEquals(
            listOf("""{"id":"a"}""", """{"id":"b"}"""),
            JsonArrayRows.objects("""[{"id":"a"},{"id":"b"}]""")
        )
    }

    @Test
    fun aMalformedRowIsStillReturnedAsASpan() {
        // objects() only knows span boundaries — it has no JSON knowledge, and
        // that is deliberate: a row that is balanced but malformed
        // ({"id":"b" broken }) still has a clear end, so it can be cut out
        // without dragging its neighbours down with it.  parse() is what drops
        // the span it cannot read; on a device that row is logged and lost,
        // while the rows around it are forwarded.
        val rows = JsonArrayRows.objects("""[{"id":"a"},{"id":"b" broken }]{"id":"c"}]""")
        assertEquals(
            listOf("""{"id":"a"}""", """{"id":"b" broken }""", """{"id":"c"}"""),
            rows
        )
    }

    @Test
    fun anUnbalancedRowIsHonestAboutItsLimits() {
        // Depth-based recovery, so a `{` that is never closed nests the row
        // behind it and both are lost together.  That is the limit of this
        // helper: it recovers what still has balanced braces, nothing more.
        // Losing the tail beats losing the queue, and the log says it happened.
        val rows = JsonArrayRows.objects("""[{"id":"a"},{"id":"b" broken {"id":"c"}]""")
        assertEquals(listOf("""{"id":"a"}"""), rows)
    }

    @Test
    fun bracesInsideATextBodyAreNotRowBoundaries() {
        // An SMS body is free text and braces are ordinary in it — a URL query
        // or a code snippet must not split the row.
        val rows = JsonArrayRows.objects("""[{"id":"a","text":"use {x} and {y}"}]""")
        assertEquals(1, rows.size)
        assertEquals("""{"id":"a","text":"use {x} and {y}"}""", rows[0])
    }

    @Test
    fun anEscapedQuoteDoesNotEndTheString() {
        // Without tracking escapes, the `"` after "say \"" would close the string
        // early and the `{` behind it would be counted as a new row start.
        val rows = JsonArrayRows.objects("""[{"id":"a","text":"say \"hello {world}\""}]""")
        assertEquals(1, rows.size)
    }

    @Test
    fun anEmptyQueueIsAnEmptyList() {
        assertTrue(JsonArrayRows.objects("[]").isEmpty())
        assertTrue(JsonArrayRows.objects("").isEmpty())
    }

    @Test
    fun aRowCutOffByATornWriteIsNotInvented() {
        // A write interrupted halfway leaves an unterminated row.  Returning it
        // would hand back a message with a body we only half have.
        assertEquals(
            listOf("""{"id":"a"}"""),
            JsonArrayRows.objects("""[{"id":"a"},{"id":"b","text":"half""")
        )
    }
}
