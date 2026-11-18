package io.github.joanrra2.pocketwake.shortcut

import io.github.joanrra2.pocketwake.shortcut.WakeShortcuts.ACTION_WAKE
import io.github.joanrra2.pocketwake.shortcut.WakeShortcuts.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeShortcutsTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val view = "android.intent.action.VIEW"

    @Test fun `own shortcut with the install token wakes without asking`() {
        assertEquals(Request("OFFICE-PC", trusted = true), WakeShortcuts.request(ACTION_WAKE, "OFFICE-PC", token, null, token))
    }

    @Test fun `other apps get a confirmation`() {
        assertEquals(Request("OFFICE-PC", trusted = false), WakeShortcuts.request(ACTION_WAKE, "OFFICE-PC", null, null, token))
        assertEquals(Request("OFFICE-PC", trusted = false), WakeShortcuts.request(ACTION_WAKE, "OFFICE-PC", "guess", null, token))
        // An empty stored token never matches an empty extra.
        assertEquals(Request("NAS", trusted = false), WakeShortcuts.request(ACTION_WAKE, "NAS", "", null, ""))
    }

    @Test fun `links always ask, even with a token`() {
        assertEquals(Request("NAS", trusted = false), WakeShortcuts.request(view, null, token, "NAS", token))
    }

    @Test fun `invalid or missing names are ignored`() {
        assertNull(WakeShortcuts.request(ACTION_WAKE, null, token, null, token))
        assertNull(WakeShortcuts.request(ACTION_WAKE, "a/b", token, null, token))
        assertNull(WakeShortcuts.request(ACTION_WAKE, " PC", token, null, token))
        assertNull(WakeShortcuts.request(ACTION_WAKE, "#", token, null, token))
        assertNull(WakeShortcuts.request("android.intent.action.MAIN", "PC", token, null, token))
        assertNull(WakeShortcuts.request(view, null, null, null, token))
    }

    @Test fun `reads pocketwake wake links`() {
        assertEquals("OFFICE-PC", WakeShortcuts.nameFromLink("pocketwake", "wake", listOf("OFFICE-PC")))
        assertEquals("Office PC", WakeShortcuts.nameFromLink("PocketWake", "WAKE", listOf("Office PC")))
        assertNull(WakeShortcuts.nameFromLink("https", "wake", listOf("PC")))
        assertNull(WakeShortcuts.nameFromLink("pocketwake", "setup", listOf("PC")))
        assertNull(WakeShortcuts.nameFromLink("pocketwake", "wake", emptyList()))
        assertNull(WakeShortcuts.nameFromLink("pocketwake", "wake", listOf("a", "b")))
    }
}
