package io.github.joanrra2.pocketwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TopicsTest {

    @Test fun `builds the bridge topics`() {
        assertEquals("sinkhole/status", Topics.statusTopic("sinkhole"))
        assertEquals("sinkhole/wol/+/state", Topics.stateFilter("sinkhole"))
        assertEquals("sinkhole/wol/OFFICE-PC/set", Topics.setTopic("sinkhole", "OFFICE-PC"))
        assertEquals("sinkhole/wol/OFFICE-PC/state", Topics.stateTopic("sinkhole", "OFFICE-PC"))
    }

    @Test fun `extracts host names case-sensitively`() {
        assertEquals("OFFICE-PC", Topics.hostFromStateTopic("sinkhole", "sinkhole/wol/OFFICE-PC/state"))
        assertEquals("office-pc", Topics.hostFromStateTopic("sinkhole", "sinkhole/wol/office-pc/state"))
    }

    @Test fun `works with multi-level prefixes`() {
        assertEquals("NAS", Topics.hostFromStateTopic("home/sinkhole", "home/sinkhole/wol/NAS/state"))
        assertNull(Topics.hostFromStateTopic("home/sinkhole", "sinkhole/wol/NAS/state"))
    }

    @Test fun `ignores topics that are not host states`() {
        assertNull(Topics.hostFromStateTopic("sinkhole", "sinkhole/status"))
        assertNull(Topics.hostFromStateTopic("sinkhole", "sinkhole/wol/OFFICE-PC/set"))
        assertNull(Topics.hostFromStateTopic("sinkhole", "sinkhole/wol//state"))
        assertNull(Topics.hostFromStateTopic("sinkhole", "sinkhole/wol/a/b/state"))
        assertNull(Topics.hostFromStateTopic("sinkhole", "other/wol/PC/state"))
        assertNull(Topics.hostFromStateTopic("sink", "sinkhole/wol/PC/state"))
    }

    @Test fun `parses presence payloads`() {
        assertEquals(Presence.ONLINE, Topics.parsePresence("online"))
        assertEquals(Presence.ONLINE, Topics.parsePresence(" Online\n"))
        assertEquals(Presence.OFFLINE, Topics.parsePresence("offline"))
        assertEquals(Presence.CLEARED, Topics.parsePresence(""))
        assertNull(Topics.parsePresence("rebooting"))
    }

    @Test fun `normalizes and validates prefixes`() {
        assertEquals("sinkhole", Topics.normalizePrefix("  /sinkhole/ "))
        assertNull(Topics.prefixError("home/sinkhole"))
        assertNotNull(Topics.prefixError(""))
        assertNotNull(Topics.prefixError("sink+hole"))
        assertNotNull(Topics.prefixError("sink#"))
        assertNotNull(Topics.prefixError("a//b"))
    }

    @Test fun `validates host names`() {
        assertNull(Topics.nameError("OFFICE-PC"))
        assertNull(Topics.nameError("Living room PC"))
        assertNotNull(Topics.nameError(""))
        assertNotNull(Topics.nameError(" PC"))
        assertNotNull(Topics.nameError("a/b"))
        assertNotNull(Topics.nameError("a+"))
        assertNotNull(Topics.nameError("#"))
        assertNotNull(Topics.nameError("x".repeat(Topics.MAX_NAME_LENGTH + 1)))
    }
}
