package io.github.joanrra2.pocketwake.core

/**
 * Topic layout of the Pocket AdBlock MQTT bridge (components/wol/mqtt_bridge.cpp):
 *
 *   <prefix>/status              online / offline (retained, last will)
 *   <prefix>/wol/<name>/state    online / offline (retained, only hosts with an IP)
 *   <prefix>/wol/<name>/set      any payload wakes <name> (check, wake, verify)
 *   <prefix>/blocking            ON / OFF (retained), ad blocking
 *   <prefix>/blocking/set        ON / OFF turns ad blocking on or off
 *   <prefix>/stats               JSON every 60 s: blocked, allowed, percent, domains, clients
 *
 * Host names are case-sensitive and used verbatim as one topic level.
 */
object Topics {
    const val DEFAULT_PREFIX = "sinkhole"
    const val MAX_NAME_LENGTH = 64

    fun normalizePrefix(raw: String): String = raw.trim().trim('/')

    /** Returns an error message, or null when [prefix] (already normalized) is usable. */
    fun prefixError(prefix: String): String? = when {
        prefix.isEmpty() -> "Enter the topic prefix set on the board"
        prefix.any { it == '+' || it == '#' } -> "The prefix can't contain + or #"
        prefix.contains("//") -> "The prefix can't contain empty levels (//)"
        prefix.any { it.code < 0x20 } -> "Invalid character"
        else -> null
    }

    /** Returns an error message, or null when [name] can be used as a host topic level. */
    fun nameError(name: String): String? = when {
        name.isEmpty() -> "Enter the PC name exactly as on the board"
        name.isBlank() || name != name.trim() -> "Remove leading or trailing spaces"
        name.any { it == '/' || it == '+' || it == '#' } -> "Names can't contain /, + or #"
        name.any { it.code < 0x20 } -> "Invalid character"
        name.length > MAX_NAME_LENGTH -> "At most $MAX_NAME_LENGTH characters"
        else -> null
    }

    fun statusTopic(prefix: String) = "$prefix/status"
    fun stateFilter(prefix: String) = "$prefix/wol/+/state"
    fun stateTopic(prefix: String, name: String) = "$prefix/wol/$name/state"
    fun setTopic(prefix: String, name: String) = "$prefix/wol/$name/set"
    fun blockingTopic(prefix: String) = "$prefix/blocking"
    fun blockingSetTopic(prefix: String) = "$prefix/blocking/set"
    fun statsTopic(prefix: String) = "$prefix/stats"

    /** Everything the app reads. Wake and blocking topics are only written. */
    fun filters(prefix: String) = listOf(statusTopic(prefix), stateFilter(prefix), blockingTopic(prefix), statsTopic(prefix))

    /** Host name from `<prefix>/wol/<name>/state`, or null when [topic] is something else. */
    fun hostFromStateTopic(prefix: String, topic: String): String? {
        val head = "$prefix/wol/"
        val tail = "/state"
        if (!topic.startsWith(head) || !topic.endsWith(tail)) return null
        if (topic.length <= head.length + tail.length) return null
        val name = topic.substring(head.length, topic.length - tail.length)
        return name.takeIf { it.isNotEmpty() && '/' !in it }
    }

    /**
     * Parses a state or status payload. An empty payload means the retained message was
     * cleared ([Presence.CLEARED]); anything unrecognised returns null and is ignored.
     */
    fun parsePresence(payload: String): Presence? = when (payload.trim().lowercase()) {
        "" -> Presence.CLEARED
        "online", "on", "up", "1", "true" -> Presence.ONLINE
        "offline", "off", "down", "0", "false" -> Presence.OFFLINE
        else -> null
    }
}

enum class Presence { ONLINE, OFFLINE, UNKNOWN, CLEARED }
