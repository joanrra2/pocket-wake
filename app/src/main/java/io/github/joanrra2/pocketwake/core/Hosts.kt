package io.github.joanrra2.pocketwake.core

/** How long to wait for `online` after a wake before giving up. */
const val WAKE_TIMEOUT_MS = 3 * 60 * 1000L

sealed interface WakeStatus {
    data object Idle : WakeStatus

    /** Publishing the wake request to the broker. */
    data class Sending(val startedAt: Long) : WakeStatus

    /** The broker has the request; waiting for the state topic to turn `online`. */
    data class Waiting(val startedAt: Long) : WakeStatus

    data class Succeeded(val startedAt: Long, val onlineAt: Long) : WakeStatus {
        val seconds: Long get() = ((onlineAt - startedAt) / 1000).coerceAtLeast(0)
    }

    /** Sent to a PC that has no IP on the board, so it can never report `online`. */
    data class SentUnverified(val at: Long) : WakeStatus

    /** The PC was already reported online when the request went out. */
    data class AlreadyOnline(val at: Long) : WakeStatus

    data class TimedOut(val startedAt: Long) : WakeStatus

    data class Failed(val at: Long, val reason: String) : WakeStatus

    val isBusy: Boolean get() = this is Sending || this is Waiting
}

/** Last completed wake, kept across app restarts. [onlineAfterSeconds] is null when not confirmed. */
data class LastWake(val at: Long, val onlineAfterSeconds: Long?)

data class Host(
    /** Topic level, case-sensitive. */
    val name: String,
    val presence: Presence = Presence.UNKNOWN,
    /**
     * When the current [presence] began, if this app saw the change happen live. Retained
     * messages carry no timestamp, so a state learned on connect has no "since".
     */
    val presenceSince: Long? = null,
    /** True once the board has published a state for this host (it has an IP on the board). */
    val reportsState: Boolean = false,
    /** Added by hand in the app. */
    val manual: Boolean = false,
    val wake: WakeStatus = WakeStatus.Idle,
    val lastWake: LastWake? = null,
)

/**
 * Immutable set of hosts plus the rules that change it. Every function returns a new book;
 * the ViewModel keeps the current one in a StateFlow.
 */
data class HostBook(
    val hosts: Map<String, Host> = emptyMap(),
    /**
     * Last wakes saved in earlier sessions, including PCs not discovered yet in this one
     * (discovered PCs only appear after connecting). Applied whenever such a PC appears.
     */
    val savedLastWakes: Map<String, LastWake> = emptyMap(),
) {

    /** Hosts sorted by name (case-insensitive, then exact) so cards never jump around. */
    val sorted: List<Host>
        get() = hosts.values.sortedWith(compareBy<Host>({ it.name.lowercase() }, { it.name }))

    operator fun get(name: String): Host? = hosts[name]

    val anyBusy: Boolean get() = hosts.values.any { it.wake.isBusy }

    private fun put(host: Host): HostBook {
        val h = if (host.lastWake == null) savedLastWakes[host.name]?.let { host.copy(lastWake = it) } ?: host else host
        return copy(hosts = hosts + (h.name to h))
    }
    private fun edit(name: String, f: (Host) -> Host) = hosts[name]?.let { put(f(it)) } ?: this

    fun withManual(names: Collection<String>): HostBook =
        names.fold(this) { book, n -> book.addManual(n) }

    fun withLastWakes(last: Map<String, LastWake>): HostBook = copy(
        hosts = hosts.mapValues { (n, h) -> last[n]?.let { h.copy(lastWake = it) } ?: h },
        savedLastWakes = savedLastWakes + last,
    )

    /** Saved values plus the current ones, so PCs not shown right now keep theirs. */
    fun lastWakes(): Map<String, LastWake> =
        savedLastWakes + hosts.values.mapNotNull { h -> h.lastWake?.let { h.name to it } }.toMap()

    fun addManual(name: String): HostBook =
        put(hosts[name]?.copy(manual = true) ?: Host(name = name, manual = true))

    /** Clears the manual flag; the card disappears unless the board still reports the host. */
    fun removeManual(name: String): HostBook {
        val h = hosts[name] ?: return this
        return if (h.reportsState) put(h.copy(manual = false)) else copy(hosts = hosts - name)
    }

    /** Drops everything learned from the broker (after switching broker or prefix). */
    fun forgetDiscovered(): HostBook = copy(
        hosts = hosts.filterValues { it.manual }.mapValues { (_, h) ->
            h.copy(presence = Presence.UNKNOWN, presenceSince = null, reportsState = false, wake = WakeStatus.Idle)
        }
    )

    /**
     * Applies a `<prefix>/wol/<name>/state` message. [live] is false for retained messages
     * delivered on subscribe (MQTT sets the retain flag only for those).
     */
    fun onState(name: String, presence: Presence, now: Long, live: Boolean = true): HostBook {
        val old = hosts[name]
        if (presence == Presence.CLEARED || presence == Presence.UNKNOWN) {
            // Retained state deleted: the board no longer tracks this host.
            if (old == null) return this
            return if (old.manual) {
                put(old.copy(presence = Presence.UNKNOWN, presenceSince = null, reportsState = false))
            } else {
                copy(hosts = hosts - name)
            }
        }
        val base = old ?: Host(name = name)
        val since = when {
            base.presence == presence -> base.presenceSince
            live -> now
            else -> null
        }
        var wake = base.wake
        var last = base.lastWake
        if (presence == Presence.ONLINE) {
            val started = when (wake) {
                is WakeStatus.Waiting -> wake.startedAt
                is WakeStatus.Sending -> wake.startedAt
                is WakeStatus.SentUnverified -> wake.at
                // A late boot still counts, within twice the timeout.
                is WakeStatus.TimedOut -> wake.startedAt.takeIf { now - it < 2 * WAKE_TIMEOUT_MS }
                else -> null
            }
            if (started != null) {
                val ok = WakeStatus.Succeeded(started, now)
                wake = ok
                last = LastWake(started, ok.seconds)
            }
        }
        return put(base.copy(presence = presence, presenceSince = since, reportsState = true, wake = wake, lastWake = last))
    }

    fun onWakeRequested(name: String, now: Long): HostBook =
        put((hosts[name] ?: Host(name = name, manual = true)).copy(wake = WakeStatus.Sending(now)))

    /** The broker accepted the wake request. */
    fun onWakePublished(name: String, now: Long): HostBook = edit(name) { h ->
        // An `online` may already have arrived while publishing; keep that result.
        val started = (h.wake as? WakeStatus.Sending)?.startedAt ?: return@edit h
        when {
            h.presence == Presence.ONLINE ->
                h.copy(wake = WakeStatus.AlreadyOnline(now), lastWake = LastWake(started, null))
            !h.reportsState ->
                h.copy(wake = WakeStatus.SentUnverified(started), lastWake = LastWake(started, null))
            else -> h.copy(wake = WakeStatus.Waiting(started))
        }
    }

    fun onWakeFailed(name: String, reason: String, now: Long): HostBook =
        edit(name) { it.copy(wake = WakeStatus.Failed(now, reason)) }

    /** Times out waits older than [WAKE_TIMEOUT_MS]. Returns this same instance when nothing changed. */
    fun onTick(now: Long): HostBook {
        val expired = hosts.values.filter { h ->
            val w = h.wake
            w is WakeStatus.Waiting && now - w.startedAt >= WAKE_TIMEOUT_MS
        }
        if (expired.isEmpty()) return this
        return expired.fold(this) { book, h ->
            val started = (h.wake as WakeStatus.Waiting).startedAt
            book.put(h.copy(wake = WakeStatus.TimedOut(started), lastWake = LastWake(started, null)))
        }
    }

    fun dismissWake(name: String): HostBook = edit(name) { it.copy(wake = WakeStatus.Idle) }
}
