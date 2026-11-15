package io.github.joanrra2.pocketwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBookTest {
    private val t0 = 1_700_000_000_000L

    @Test fun `retained states discover hosts without a since time`() {
        val b = HostBook()
            .onState("OFFICE-PC", Presence.OFFLINE, t0, live = false)
            .onState("NAS", Presence.ONLINE, t0, live = false)
        assertEquals(listOf("NAS", "OFFICE-PC"), b.sorted.map { it.name })
        assertEquals(Presence.OFFLINE, b["OFFICE-PC"]!!.presence)
        assertTrue(b["OFFICE-PC"]!!.reportsState)
        assertNull(b["OFFICE-PC"]!!.presenceSince)
    }

    @Test fun `live changes record since, repeats keep it`() {
        val b = HostBook()
            .onState("PC", Presence.OFFLINE, t0, live = false)
            .onState("PC", Presence.ONLINE, t0 + 5_000)
            .onState("PC", Presence.ONLINE, t0 + 9_000)
        assertEquals(t0 + 5_000, b["PC"]!!.presenceSince)
    }

    @Test fun `names are case-sensitive`() {
        val b = HostBook().onState("OFFICE-PC", Presence.ONLINE, t0).onState("office-pc", Presence.OFFLINE, t0)
        assertEquals(2, b.hosts.size)
    }

    @Test fun `wake then online reports seconds to online`() {
        var b = HostBook().onState("OFFICE-PC", Presence.OFFLINE, t0, live = false)
        b = b.onWakeRequested("OFFICE-PC", t0 + 1_000)
        assertTrue(b["OFFICE-PC"]!!.wake is WakeStatus.Sending)
        b = b.onWakePublished("OFFICE-PC", t0 + 1_200)
        assertEquals(WakeStatus.Waiting(t0 + 1_000), b["OFFICE-PC"]!!.wake)
        assertTrue(b.anyBusy)
        b = b.onState("OFFICE-PC", Presence.ONLINE, t0 + 24_500)
        val w = b["OFFICE-PC"]!!.wake as WakeStatus.Succeeded
        assertEquals(23, w.seconds)
        assertEquals(LastWake(t0 + 1_000, 23), b["OFFICE-PC"]!!.lastWake)
        assertFalse(b.anyBusy)
    }

    @Test fun `online arriving before the publish ack still counts`() {
        var b = HostBook().onState("PC", Presence.OFFLINE, t0, live = false)
        b = b.onWakeRequested("PC", t0).onState("PC", Presence.ONLINE, t0 + 3_000).onWakePublished("PC", t0 + 3_100)
        assertTrue(b["PC"]!!.wake is WakeStatus.Succeeded)
    }

    @Test fun `offline messages while waiting keep waiting`() {
        var b = HostBook().onState("PC", Presence.OFFLINE, t0, live = false)
        b = b.onWakeRequested("PC", t0).onWakePublished("PC", t0).onState("PC", Presence.OFFLINE, t0 + 10_000)
        assertTrue(b["PC"]!!.wake is WakeStatus.Waiting)
    }

    @Test fun `times out after three minutes`() {
        var b = HostBook().onState("PC", Presence.OFFLINE, t0, live = false)
        b = b.onWakeRequested("PC", t0).onWakePublished("PC", t0)
        assertSame(b, b.onTick(t0 + WAKE_TIMEOUT_MS - 1))
        b = b.onTick(t0 + WAKE_TIMEOUT_MS)
        assertEquals(WakeStatus.TimedOut(t0), b["PC"]!!.wake)
        assertEquals(LastWake(t0, null), b["PC"]!!.lastWake)
        // A late boot within twice the timeout still turns into a success.
        b = b.onState("PC", Presence.ONLINE, t0 + WAKE_TIMEOUT_MS + 30_000)
        assertTrue(b["PC"]!!.wake is WakeStatus.Succeeded)
    }

    @Test fun `waking an online host says already online`() {
        var b = HostBook().onState("PC", Presence.ONLINE, t0, live = false)
        b = b.onWakeRequested("PC", t0 + 1).onWakePublished("PC", t0 + 2)
        assertTrue(b["PC"]!!.wake is WakeStatus.AlreadyOnline)
    }

    @Test fun `manual host without state gets sent-unverified`() {
        var b = HostBook().addManual("OLD-PC")
        assertTrue(b["OLD-PC"]!!.manual)
        assertFalse(b["OLD-PC"]!!.reportsState)
        b = b.onWakeRequested("OLD-PC", t0).onWakePublished("OLD-PC", t0 + 100)
        assertEquals(WakeStatus.SentUnverified(t0), b["OLD-PC"]!!.wake)
    }

    @Test fun `publish failure is reported`() {
        val b = HostBook().addManual("PC").onWakeRequested("PC", t0).onWakeFailed("PC", "Not connected", t0 + 1)
        assertEquals(WakeStatus.Failed(t0 + 1, "Not connected"), b["PC"]!!.wake)
    }

    @Test fun `cleared retained state removes discovered hosts but keeps manual ones`() {
        var b = HostBook().onState("A", Presence.ONLINE, t0, live = false).addManual("B").onState("B", Presence.ONLINE, t0)
        b = b.onState("A", Presence.CLEARED, t0).onState("B", Presence.CLEARED, t0)
        assertNull(b["A"])
        assertEquals(Presence.UNKNOWN, b["B"]!!.presence)
        assertFalse(b["B"]!!.reportsState)
    }

    @Test fun `removing a manual host keeps it while the board reports it`() {
        var b = HostBook().addManual("A").addManual("B").onState("B", Presence.OFFLINE, t0)
        b = b.removeManual("A").removeManual("B")
        assertNull(b["A"])
        assertFalse(b["B"]!!.manual)
    }

    @Test fun `forgetting discovered hosts keeps manual ones reset`() {
        val b = HostBook()
            .onState("A", Presence.ONLINE, t0)
            .addManual("B").onState("B", Presence.ONLINE, t0)
            .forgetDiscovered()
        assertEquals(setOf("B"), b.hosts.keys)
        assertEquals(Presence.UNKNOWN, b["B"]!!.presence)
    }

    @Test fun `last wakes round trip`() {
        val last = mapOf("PC" to LastWake(t0, 20))
        val b = HostBook().addManual("PC").withLastWakes(last)
        assertEquals(last, b.lastWakes())
    }

    @Test fun `sorting is case-insensitive and stable`() {
        val b = HostBook().addManual("beta").addManual("Alpha").addManual("alpha")
        assertEquals(listOf("Alpha", "alpha", "beta"), b.sorted.map { it.name })
    }

    @Test fun `saved last wakes reach PCs discovered later and are never dropped`() {
        val saved = mapOf("NAS" to LastWake(t0, 41), "GONE" to LastWake(t0, 9))
        var b = HostBook().withLastWakes(saved)
        assertTrue(b.hosts.isEmpty())
        b = b.onState("NAS", Presence.ONLINE, t0 + 1, live = false)
        assertEquals(LastWake(t0, 41), b["NAS"]!!.lastWake)
        // A newer wake replaces the saved one; PCs not shown keep theirs.
        b = b.onState("NAS", Presence.OFFLINE, t0 + 2)
            .onWakeRequested("NAS", t0 + 10).onWakePublished("NAS", t0 + 11)
            .onState("NAS", Presence.ONLINE, t0 + 20_010)
        assertEquals(LastWake(t0 + 10, 20), b["NAS"]!!.lastWake)
        assertEquals(mapOf("NAS" to LastWake(t0 + 10, 20), "GONE" to LastWake(t0, 9)), b.lastWakes())
    }
}
