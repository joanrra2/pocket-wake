package io.github.joanrra2.pocketwake.core

import io.github.joanrra2.pocketwake.mqtt.BrokerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ProfileAndFormatTest {

    @Test fun `default profile is not configured`() {
        assertFalse(Profile().isConfigured)
        assertTrue(Profile().errors().containsKey(Profile.Field.HOST))
    }

    @Test fun `valid profile`() {
        val p = Profile(host = "abc.emqxsl.com", username = "phone", password = "x")
        assertTrue(p.isConfigured)
        assertEquals(8883, p.port)
        assertTrue(p.tls)
        assertEquals("sinkhole", p.prefix)
    }

    @Test fun `rejects urls and bad ports`() {
        assertTrue(Profile(host = "mqtts://abc.com").errors().containsKey(Profile.Field.HOST))
        assertTrue(Profile(host = "abc.com/mqtt").errors().containsKey(Profile.Field.HOST))
        assertTrue(Profile(host = "abc.com", port = 0).errors().containsKey(Profile.Field.PORT))
        assertTrue(Profile(host = "abc.com", password = "x").errors().containsKey(Profile.Field.USERNAME))
    }

    @Test fun `normalizes before saving`() {
        val p = Profile(host = " abc.com ", username = " phone ", prefix = "/sinkhole/").normalized()
        assertEquals("abc.com", p.host)
        assertEquals("phone", p.username)
        assertEquals("sinkhole", p.prefix)
    }

    @Test fun `tls toggle swaps default ports only`() {
        assertEquals(1883, Profile.portAfterTlsToggle(8883, tls = false))
        assertEquals(8883, Profile.portAfterTlsToggle(1883, tls = true))
        assertEquals(9001, Profile.portAfterTlsToggle(9001, tls = false))
    }

    @Test fun `formats durations`() {
        assertEquals("0 s", Format.seconds(0))
        assertEquals("23 s", Format.seconds(23))
        assertEquals("1 min 5 s", Format.seconds(65))
        assertEquals("3 min", Format.seconds(180))
    }

    @Test fun `formats relative times`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", Format.ago(now, now - 10_000))
        assertEquals("5 min ago", Format.ago(now, now - 5 * 60_000))
        assertEquals("3 h ago", Format.ago(now, now - 3 * 3_600_000))
        assertEquals("yesterday", Format.ago(now, now - 30 * 3_600_000L))
        assertEquals("4 days ago", Format.ago(now, now - 4 * 86_400_000L))
    }

    @Test fun `formats clock times`() {
        // 2023-11-14 22:13:20 UTC
        val now = 1_700_000_000_000L
        assertEquals("22:13", Format.clock(now, now, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("yesterday 22:13", Format.clock(now, now - 86_400_000L, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("12 Nov 22:13", Format.clock(now, now - 2 * 86_400_000L, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test fun `reconnect backoff caps at 30 s`() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), (1..7).map { BrokerSession.backoffSeconds(it) })
    }
}
