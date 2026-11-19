package io.github.joanrra2.pocketwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `<prefix>/wol/<name>/result` and Cancel (board firmware 0.6+). */
class WakeResultTest {
    private val t0 = 1_700_000_000_000L
    private val pc = "OFFICE-PC"

    private fun waiting(): HostBook =
        HostBook().onState(pc, Presence.OFFLINE, t0, live = false)
            .onWakeRequested(pc, t0)
            .onWakePublished(pc, t0 + 200)

    private fun boardWaiting(): HostBook = waiting().onResult(pc, BoardResult.Waking, t0 + 1_000)

    @Test fun `parses the board's results`() {
        assertEquals(BoardResult.Waking, BoardResult.parse("waking"))
        assertEquals(BoardResult.Online(41), BoardResult.parse("online after 41 s"))
        assertEquals(BoardResult.NoAnswer("no answer after 10 min"), BoardResult.parse("no answer after 10 min"))
        assertEquals(BoardResult.Cancelled, BoardResult.parse("cancelled"))
        assertEquals(BoardResult.AlreadyOnline, BoardResult.parse("Already online\n"))
        assertEquals(BoardResult.Other("magic packet sent"), BoardResult.parse("magic packet sent"))
        assertNull(BoardResult.parse(""))
    }

    @Test fun `result topics`() {
        assertEquals("sinkhole/wol/+/result", Topics.resultFilter("sinkhole"))
        assertEquals(pc, Topics.hostFromResultTopic("sinkhole", "sinkhole/wol/$pc/result"))
        assertNull(Topics.hostFromResultTopic("sinkhole", "sinkhole/wol/$pc/state"))
        assertNull(Topics.hostFromStateTopic("sinkhole", "sinkhole/wol/$pc/result"))
        assertTrue("sinkhole/wol/+/result" in Topics.filters("sinkhole"))
        assertTrue("sinkhole/wol/+/result" in Topics.optionalFilters("sinkhole"))
    }

    @Test fun `waking makes the board track the wait and allows cancel`() {
        val plain = waiting()[pc]!!.wake as WakeStatus.Waiting
        assertFalse(plain.board)
        assertFalse(plain.canCancel)
        val w = boardWaiting()[pc]!!.wake as WakeStatus.Waiting
        assertTrue(w.board)
        assertTrue(w.canCancel)
        assertEquals(t0, w.startedAt)
    }

    @Test fun `waking that beats the publish ack is kept`() {
        var b = HostBook().onState(pc, Presence.OFFLINE, t0, live = false).onWakeRequested(pc, t0)
        b = b.onResult(pc, BoardResult.Waking, t0 + 100)
        b = b.onWakePublished(pc, t0 + 300)
        assertTrue((b[pc]!!.wake as WakeStatus.Waiting).board)
    }

    @Test fun `board-tracked waits outlast the 3-minute default`() {
        val b = boardWaiting()
        assertSame(b, b.onTick(t0 + WAKE_TIMEOUT_MS + 1))
        val late = b.onTick(t0 + BOARD_WAKE_LIMIT_MS)
        assertEquals("No result from the board", (late[pc]!!.wake as WakeStatus.TimedOut).text)
        // Without the board's "waking", the 3-minute default still applies.
        assertTrue(waiting().onTick(t0 + WAKE_TIMEOUT_MS)[pc]!!.wake is WakeStatus.TimedOut)
    }

    @Test fun `board results end the wait`() {
        val ok = boardWaiting().onResult(pc, BoardResult.Online(41), t0 + 42_000)[pc]!!
        assertEquals(41L, (ok.wake as WakeStatus.Succeeded).seconds)
        assertEquals(LastWake(t0, 41), ok.lastWake)

        val none = boardWaiting().onResult(pc, BoardResult.NoAnswer("no answer after 10 min"), t0 + 600_000)[pc]!!
        assertEquals("no answer after 10 min", (none.wake as WakeStatus.TimedOut).text)
        assertEquals(LastWake(t0, null), none.lastWake)

        val up = boardWaiting().onResult(pc, BoardResult.AlreadyOnline, t0 + 1_500)[pc]!!
        assertEquals(WakeStatus.AlreadyOnline(t0 + 1_500, sent = false), up.wake)
        // The board doesn't publish the state for this case.
        assertEquals(Presence.ONLINE, up.presence)
        assertEquals(t0 + 1_500, up.presenceSince)
    }

    @Test fun `cancel waits for the board, or gives up after a while`() {
        var b = boardWaiting().onCancelRequested(pc, t0 + 5_000)
        val w = b[pc]!!.wake as WakeStatus.Waiting
        assertTrue(w.cancelling)
        assertFalse(w.canCancel)
        assertTrue(b.onResult(pc, BoardResult.Cancelled, t0 + 6_000)[pc]!!.wake is WakeStatus.Cancelled)
        // No answer (the board had already stopped): the wait ends here anyway.
        assertSame(b, b.onTick(t0 + 5_000 + CANCEL_CONFIRM_MS - 1))
        assertTrue(b.onTick(t0 + 5_000 + CANCEL_CONFIRM_MS)[pc]!!.wake is WakeStatus.Cancelled)
        // A failed publish puts the Cancel button back.
        b = b.onCancelFailed(pc)
        assertTrue((b[pc]!!.wake as WakeStatus.Waiting).canCancel)
    }

    @Test fun `stale retained results don't touch a wait the board hasn't confirmed`() {
        val b = waiting()
        assertSame(b, b.onResult(pc, BoardResult.Online(41), t0 + 500, live = false))
        assertSame(b, b.onResult(pc, BoardResult.Cancelled, t0 + 500, live = false))
        // Once the board confirmed this wait, a retained result after a reconnect is current.
        val c = boardWaiting().onResult(pc, BoardResult.Online(30), t0 + 60_000, live = false)
        assertTrue(c[pc]!!.wake is WakeStatus.Succeeded)
    }

    @Test fun `retained results on connect never start or show anything`() {
        val idle = HostBook().onState(pc, Presence.OFFLINE, t0, live = false)
        // A board that restarted mid-wake leaves "waking" retained for good.
        assertSame(idle, idle.onResult(pc, BoardResult.Waking, t0, live = false))
        assertSame(idle, idle.onResult(pc, BoardResult.Online(12), t0, live = false))
        assertSame(idle, idle.onResult("UNKNOWN", BoardResult.Waking, t0, live = false))
    }

    @Test fun `a live wake from elsewhere shows as waiting`() {
        val b = HostBook().onState(pc, Presence.OFFLINE, t0, live = false).onResult(pc, BoardResult.Waking, t0 + 1_000)
        val w = b[pc]!!.wake as WakeStatus.Waiting
        assertTrue(w.board)
        assertEquals(t0 + 1_000, w.startedAt)
        assertEquals(41L, (b.onResult(pc, BoardResult.Online(41), t0 + 42_000)[pc]!!.wake as WakeStatus.Succeeded).seconds)
    }

    @Test fun `results for PCs that aren't waking are ignored`() {
        val idle = HostBook().onState(pc, Presence.ONLINE, t0, live = false)
        assertSame(idle, idle.onResult(pc, BoardResult.Online(12), t0))
        assertSame(idle, idle.onResult(pc, BoardResult.Other("magic packet sent"), t0))
        assertSame(idle, idle.onResult("NOPE", BoardResult.Cancelled, t0))
    }

    @Test fun `watch-only PCs get no wake, and any wait ends at once`() {
        assertEquals(BoardResult.WatchOnly, BoardResult.parse("watch only"))
        val idle = HostBook().onState(pc, Presence.ONLINE, t0, live = false)
        // Retained on connect: marks the PC, shows nothing.
        val watched = idle.onResult(pc, BoardResult.WatchOnly, t0 + 100, live = false)
        assertTrue(watched[pc]!!.watchOnly)
        assertEquals(WakeStatus.Idle, watched[pc]!!.wake)
        // Wake (a shortcut, or a tap before the result arrived) publishes nothing.
        assertEquals(WakeStatus.WatchOnly(t0 + 200), watched.onWakeRequested(pc, t0 + 200)[pc]!!.wake)
        // A wait already running ends when the board answers "watch only".
        val ended = waiting().onResult(pc, BoardResult.WatchOnly, t0 + 900)[pc]!!
        assertEquals(WakeStatus.WatchOnly(t0 + 900), ended.wake)
        assertTrue(ended.watchOnly)
    }

    @Test fun `watch-only arrives before the state, and clears when the board gets a MAC`() {
        var b = HostBook().onResult(pc, BoardResult.WatchOnly, t0, live = false)
        b = b.onState(pc, Presence.OFFLINE, t0, live = false)
        assertTrue(b[pc]!!.watchOnly)
        assertTrue(b[pc]!!.reportsState)
        // Empty retained result: the MAC was added.
        assertFalse(b.onResultCleared(pc)[pc]!!.watchOnly)
        val plain = HostBook().onState(pc, Presence.OFFLINE, t0, live = false)
        assertSame(plain, plain.onResultCleared(pc))
        assertSame(plain, plain.onResultCleared("NOPE"))
        // Any other result also means it can be woken again.
        val woken = b.onResult(pc, BoardResult.Waking, t0 + 1_000)[pc]!!
        assertFalse(woken.watchOnly)
        assertTrue(woken.wake is WakeStatus.Waiting)
        assertFalse(b.addManual(pc).forgetDiscovered()[pc]!!.watchOnly)
    }
}
