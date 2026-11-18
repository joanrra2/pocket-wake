package io.github.joanrra2.pocketwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BoardTest {

    @Test fun `parses the board's stats payload`() {
        val s = BoardStats.parse("""{"blocked":1834,"allowed":4127,"percent":30.7,"domains":139651,"clients":6}""", at = 5)
        assertNotNull(s)
        s!!
        assertEquals(1834L, s.blocked)
        assertEquals(4127L, s.allowed)
        assertEquals(5961L, s.total)
        assertEquals(30.7, s.percent, 1e-9)
        assertEquals(139_651L, s.domains)
        assertEquals(6L, s.clients)
        assertEquals(5L, s.at)
    }

    @Test fun `stats tolerate spaces, missing optional fields and zero totals`() {
        val s = BoardStats.parse("""{ "blocked" : 1, "allowed": 3 }""", at = 0)!!
        assertEquals(25.0, s.percent, 1e-9)
        assertEquals(0L, s.domains)
        assertEquals(0.0, BoardStats.parse("""{"blocked":0,"allowed":0}""", 0)!!.percent, 1e-9)
    }

    @Test fun `stats without counters are ignored`() {
        assertNull(BoardStats.parse("""{"percent":3.5}""", 0))
        assertNull(BoardStats.parse("garbage", 0))
        assertNull(BoardStats.parse("", 0))
    }

    @Test fun `parses blocking payloads`() {
        assertEquals(true, Blocking.parse("ON"))
        assertEquals(true, Blocking.parse(" on\n"))
        assertEquals(false, Blocking.parse("OFF"))
        assertEquals(false, Blocking.parse("0"))
        assertNull(Blocking.parse(""))
        assertNull(Blocking.parse("paused"))
    }

    @Test fun `switch shows the request until the board confirms it`() {
        var b = Blocking().onReported(true)
        assertTrue(b.known)
        b = b.onRequested(false, now = 1_000)
        assertTrue(b.pending)
        assertEquals(false, b.shown)
        assertEquals(true, b.on)
        // A periodic refresh with the old value doesn't end the wait.
        b = b.onReported(true)
        assertTrue(b.pending)
        b = b.onReported(false)
        assertFalse(b.pending)
        assertEquals(false, b.on)
        assertNull(b.problem)
    }

    @Test fun `unconfirmed change times out with a hint about the broker rules`() {
        val b = Blocking(on = true).onRequested(false, now = 0)
        assertSame(b, b.onTick(BLOCKING_CONFIRM_MS - 1, "sinkhole/blocking/set"))
        val t = b.onTick(BLOCKING_CONFIRM_MS, "sinkhole/blocking/set")
        assertFalse(t.pending)
        assertEquals(true, t.shown)
        assertTrue(t.problem!!.contains("sinkhole/blocking/set"))
        // The next request clears the problem.
        assertNull(t.onRequested(true, 0).problem)
    }

    @Test fun `failed publish restores the board's value`() {
        val b = Blocking(on = false).onRequested(true, 0).onFailed("Not connected to the broker")
        assertEquals(false, b.shown)
        assertEquals("Not connected to the broker", b.problem)
    }

    @Test fun `formats counts and percentages`() {
        assertEquals("139,651", Format.count(139_651, Locale.US))
        assertEquals("139.651", Format.count(139_651, Locale.forLanguageTag("es-ES")))
        assertEquals("30.7 %", Format.percent(30.66, Locale.US))
    }

    @Test fun `topics for blocking and stats`() {
        assertEquals("sinkhole/blocking", Topics.blockingTopic("sinkhole"))
        assertEquals("sinkhole/blocking/set", Topics.blockingSetTopic("sinkhole"))
        assertEquals("home/sinkhole/stats", Topics.statsTopic("home/sinkhole"))
        assertEquals(
            listOf("sinkhole/status", "sinkhole/wol/+/state", "sinkhole/blocking", "sinkhole/stats"),
            Topics.filters("sinkhole"),
        )
    }
}
