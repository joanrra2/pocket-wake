package io.github.joanrra2.pocketwake.core

import io.github.joanrra2.pocketwake.scan.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLinkTest {

    private fun ok(text: String) = (SetupLink.parse(text) as SetupLink.Companion.Result.Ok).link
    private fun error(text: String) = (SetupLink.parse(text) as SetupLink.Companion.Result.Error).message

    @Test fun `reads the link the board shows`() {
        val l = ok(
            "https://joanrra2.github.io/pocket-wake/#setup?v=1&host=x1.ala.eu-central-1.emqxsl.com" +
                "&port=8883&tls=1&wsport=8084&wspath=%2Fmqtt&prefix=sinkhole&user=phone",
        )
        assertEquals("x1.ala.eu-central-1.emqxsl.com", l.host)
        assertEquals(8883, l.port)
        assertTrue(l.tls)
        assertEquals("sinkhole", l.prefix)
        assertEquals("phone", l.username)
        assertEquals(8084, l.wsPort)
        assertEquals("/mqtt", l.wsPath)
    }

    @Test fun `fills defaults and decodes escapes`() {
        val l = ok("#setup?host=broker.lan&tls=0&prefix=home%2Fwake%2F&user=")
        assertEquals(1883, l.port)
        assertFalse(l.tls)
        assertEquals("home/wake", l.prefix)
        assertNull(l.username)
        assertNull(l.wsPort)
        assertEquals(8883, ok("#setup?host=b").port)
    }

    @Test fun `rejects other codes and bad values`() {
        assertEquals("This QR code isn't a Pocket Wake setup code", error("https://example.com/"))
        assertEquals("The setup code has no valid broker address", error("#setup?port=8883"))
        assertEquals("The setup code has no valid broker address", error("#setup?host=a%20b"))
        assertEquals("The setup code has an invalid port", error("#setup?host=b&port=70000"))
        assertTrue(error("#setup?host=b&prefix=a%2B").startsWith("The setup code has an invalid prefix"))
        assertEquals("This setup code is newer than the app. Update Pocket Wake", error("#setup?v=2&host=b"))
    }

    @Test fun `never takes a password from the code`() {
        val before = Profile(host = "old.example", username = "phone", password = "secret")
        val after = ok("#setup?host=new.example&user=phone&password=leak&pass=leak").applyTo(before)
        assertEquals("new.example", after.host)
        assertEquals("", after.password)
    }

    @Test fun `keeps the password when the broker and user are unchanged`() {
        val before = Profile(host = "b.example", port = 8883, username = "phone", password = "secret", prefix = "old")
        val after = ok("#setup?host=B.example&port=8883&prefix=sinkhole").applyTo(before)
        assertEquals("secret", after.password)
        assertEquals("phone", after.username)
        assertEquals("sinkhole", after.prefix)
    }

    /** Codes drawn by the board's own encoder (www/index.html), read back with the app's scanner. */
    @Test fun `reads every QR code the board draws`() {
        val lines = javaClass.getResource("/board-qr-codes.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(7, lines.size)
        val decoder = QrDecoder()
        for (line in lines) {
            val text = Regex("^\\{\"t\":\"(.*?)\",\"rows\"").find(line)!!.groupValues[1].unescapeJson()
            val rows = Regex("\"rows\":\\[(.*)]").find(line)!!.groupValues[1].split(',').map { it.trim('"') }
            val n = rows.size
            val scale = 4
            val side = (n + 8) * scale
            val luma = ByteArray(side * side) { 0xFF.toByte() }
            for (y in 0 until n) for (x in 0 until n) if (rows[y][x] == '1') {
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    luma[((y + 4) * scale + dy) * side + (x + 4) * scale + dx] = 0
                }
            }
            assertEquals("version ${(n - 17) / 4}", text, decoder.decode(luma, side, side))
        }
    }

    private fun String.unescapeJson() = Regex("\\\\u([0-9a-fA-F]{4})|\\\\(.)").replace(this) {
        it.groupValues[1].takeIf { h -> h.isNotEmpty() }?.toInt(16)?.toChar()?.toString() ?: it.groupValues[2]
    }
}
