package io.github.joanrra2.pocketwake.core

/** How long the switch waits for the board to confirm a blocking change. */
const val BLOCKING_CONFIRM_MS = 10_000L

/**
 * `<prefix>/stats` from the board's MQTT bridge, published every 60 s (not retained).
 * Counters run since the board last restarted.
 */
data class BoardStats(
    val blocked: Long,
    val allowed: Long,
    /** Share of queries blocked, 0..100. */
    val percent: Double,
    /** Domains in the active blocklist. */
    val domains: Long,
    /** Devices that used the board's DNS recently. */
    val clients: Long,
    /** When this phone received it. */
    val at: Long,
) {
    val total: Long get() = blocked + allowed

    companion object {
        private fun num(json: String, key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)

        /** Parses `{"blocked":1,"allowed":2,"percent":33.3,"domains":4,"clients":5}`; null if blocked/allowed are missing. */
        fun parse(json: String, at: Long): BoardStats? {
            val blocked = num(json, "blocked")?.toDoubleOrNull()?.toLong() ?: return null
            val allowed = num(json, "allowed")?.toDoubleOrNull()?.toLong() ?: return null
            val total = blocked + allowed
            return BoardStats(
                blocked = blocked,
                allowed = allowed,
                percent = num(json, "percent")?.toDoubleOrNull() ?: if (total > 0) blocked * 100.0 / total else 0.0,
                domains = num(json, "domains")?.toDoubleOrNull()?.toLong() ?: 0,
                clients = num(json, "clients")?.toDoubleOrNull()?.toLong() ?: 0,
                at = at,
            )
        }
    }
}

/**
 * The board's ad-blocking switch: `<prefix>/blocking` (retained ON/OFF) and
 * `<prefix>/blocking/set`. Unknown until the board's retained state arrives, which also
 * needs the phone's broker login to be allowed to read that topic.
 */
data class Blocking(
    /** Last state the board reported; null = never seen. */
    val on: Boolean? = null,
    /** Value asked for from this phone, until the board confirms it. */
    val requested: Boolean? = null,
    val requestedAt: Long = 0,
    /** Why the last change didn't go through, until the next change. */
    val problem: String? = null,
) {
    val known: Boolean get() = on != null
    val pending: Boolean get() = requested != null

    /** What the switch shows: the requested value while waiting, else the board's. */
    val shown: Boolean? get() = requested ?: on

    fun onReported(value: Boolean): Blocking =
        if (requested == value) copy(on = value, requested = null, problem = null) else copy(on = value)

    fun onRequested(value: Boolean, now: Long): Blocking = copy(requested = value, requestedAt = now, problem = null)

    fun onFailed(reason: String): Blocking = copy(requested = null, problem = reason)

    /** Gives up waiting after [BLOCKING_CONFIRM_MS]. Returns this same instance when nothing changed. */
    fun onTick(now: Long, setTopic: String): Blocking =
        if (requested != null && now - requestedAt >= BLOCKING_CONFIRM_MS) {
            onFailed("The board didn't confirm. The broker login may not be allowed to publish $setTopic.")
        } else {
            this
        }

    companion object {
        /** "ON"/"OFF" as the board publishes them; null for anything else (ignored). */
        fun parse(payload: String): Boolean? = when (payload.trim().uppercase()) {
            "ON", "1", "TRUE" -> true
            "OFF", "0", "FALSE" -> false
            else -> null
        }
    }
}
