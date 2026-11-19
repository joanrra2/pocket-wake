package io.github.joanrra2.pocketwake.core

/** How long to wait for `online` after a wake before giving up (the board's default). */
const val WAKE_TIMEOUT_MS = 3 * 60 * 1000L

/**
 * Safety limit while the board reports the wake itself (firmware 0.6+): the board's own
 * wait is per PC, up to 60 minutes, and it publishes the result when it ends.
 */
const val BOARD_WAKE_LIMIT_MS = 61 * 60 * 1000L

/**
 * How long Cancel waits for the board's "cancelled" before stopping the wait here anyway
 * (the board publishes nothing when it had already stopped waiting).
 */
const val CANCEL_CONFIRM_MS = 10_000L

/**
 * `<prefix>/wol/<name>/result` (firmware 0.6+, retained): what the board says about the
 * last wake of a PC.
 */
sealed interface BoardResult {
    data object Waking : BoardResult
    data class Online(val seconds: Long) : BoardResult
    /** "no answer after 3 min", "no answer after 10 min"... (the PC's own wait). */
    data class NoAnswer(val text: String) : BoardResult
    data object Cancelled : BoardResult
    data object AlreadyOnline : BoardResult
    /** "magic packet sent" (PC without an IP on the board) or anything newer. */
    data class Other(val text: String) : BoardResult

    companion object {
        private val ONLINE = Regex("""online after (\d+) s""")

        /** Null for an empty (cleared) payload. */
        fun parse(payload: String): BoardResult? {
            val t = payload.trim().take(64)
            val l = t.lowercase()
            return when {
                t.isEmpty() -> null
                l == "waking" -> Waking
                l == "cancelled" || l == "canceled" -> Cancelled
                l == "already online" -> AlreadyOnline
                l.startsWith("no answer") -> NoAnswer(t)
                else -> ONLINE.matchEntire(l)?.let { Online(it.groupValues[1].toLong()) } ?: Other(t)
            }
        }
    }
}

sealed interface WakeStatus {
    data object Idle : WakeStatus

    /** Publishing the wake request to the broker. */
    data class Sending(val startedAt: Long) : WakeStatus

    /**
     * The broker has the request; waiting for the state topic to turn `online`.
     * [board]: the board said it's waking this PC (firmware 0.6+), so it publishes the
     * result, keeps its own per-PC wait and understands "cancel".
     * [cancelAt]: when Cancel was sent, 0 = not cancelling.
     */
    data class Waiting(
        val startedAt: Long,
        val board: Boolean = false,
        val cancelAt: Long = 0,
    ) : WakeStatus {
        val cancelling: Boolean get() = cancelAt > 0

        /** Only boards that said "waking" understand "cancel"; older ones would wake again. */
        val canCancel: Boolean get() = board && !cancelling
    }

    data class Succeeded(val startedAt: Long, val onlineAt: Long) : WakeStatus {
        val seconds: Long get() = ((onlineAt - startedAt) / 1000).coerceAtLeast(0)
    }

    /** Sent to a PC that has no IP on the board, so it can never report `online`. */
    data class SentUnverified(val at: Long) : WakeStatus

    /** The PC was already reported online when the request went out. */
    /** [sent]: false when the board itself found the PC online and sent nothing (firmware 0.6+). */
    data class AlreadyOnline(val at: Long, val sent: Boolean = true) : WakeStatus

    /** [text]: the board's own result ("no answer after 10 min"), when it sent one. */
    data class TimedOut(val startedAt: Long, val text: String? = null) : WakeStatus

    /** Stopped with Cancel (here or anywhere else). */
    data class Cancelled(val at: Long) : WakeStatus

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

    /**
     * Applies `<prefix>/wol/<name>/result` (firmware 0.6+).
     *
     * A live "waking" turns a wait into one the board tracks, or shows a wake started
     * elsewhere (Home Assistant, another phone). A retained "waking" only confirms a wait
     * already running here: a board that restarted mid-wake leaves "waking" behind for good.
     * Final results end a wait; a retained one only ends a wait the board already confirmed,
     * since an older retained result may be left over from a previous wake.
     */
    fun onResult(name: String, result: BoardResult, now: Long, live: Boolean = true): HostBook {
        val h = hosts[name]
        val w = h?.wake
        val waiting = w as? WakeStatus.Waiting
        val started = when (w) {
            is WakeStatus.Sending -> w.startedAt
            is WakeStatus.Waiting -> w.startedAt
            else -> null
        }
        if (result == BoardResult.Waking) {
            val wake = when {
                waiting != null -> waiting.copy(board = true)
                // A "waking" that beats the publish acknowledgement; onWakePublished only
                // acts on Sending, so this stays.
                w is WakeStatus.Sending -> WakeStatus.Waiting(w.startedAt, board = true)
                live -> WakeStatus.Waiting(now, board = true)
                else -> return this
            }
            return put((h ?: Host(name = name)).copy(wake = wake))
        }
        if (h == null || started == null || !(live || waiting?.board == true)) return this
        return when (result) {
            is BoardResult.Online -> put(
                h.copy(
                    wake = WakeStatus.Succeeded(started, started + result.seconds * 1000),
                    lastWake = LastWake(started, result.seconds),
                )
            )
            is BoardResult.NoAnswer ->
                put(h.copy(wake = WakeStatus.TimedOut(started, result.text), lastWake = LastWake(started, null)))
            BoardResult.Cancelled -> put(h.copy(wake = WakeStatus.Cancelled(now)))
            // The board marks the PC online without publishing its state topic, so do it here.
            BoardResult.AlreadyOnline -> put(
                h.copy(
                    presence = Presence.ONLINE,
                    presenceSince = if (h.presence == Presence.ONLINE) h.presenceSince else now,
                    wake = WakeStatus.AlreadyOnline(now, sent = false),
                    lastWake = LastWake(started, null),
                )
            )
            else -> this
        }
    }

    /** Cancel was sent; the board answers with "cancelled". */
    fun onCancelRequested(name: String, now: Long): HostBook = edit(name) { h ->
        val w = h.wake as? WakeStatus.Waiting ?: return@edit h
        h.copy(wake = w.copy(cancelAt = now))
    }

    /** The cancel didn't reach the broker; the wait goes on. */
    fun onCancelFailed(name: String): HostBook = edit(name) { h ->
        val w = h.wake as? WakeStatus.Waiting ?: return@edit h
        h.copy(wake = w.copy(cancelAt = 0))
    }

    /**
     * Times out waits older than [WAKE_TIMEOUT_MS], or [BOARD_WAKE_LIMIT_MS] when the board
     * reports the result itself, and ends a Cancel the board didn't confirm within
     * [CANCEL_CONFIRM_MS]. Returns this same instance when nothing changed.
     */
    fun onTick(now: Long): HostBook {
        var book = this
        for (h in hosts.values) {
            val w = h.wake as? WakeStatus.Waiting ?: continue
            val limit = if (w.board) BOARD_WAKE_LIMIT_MS else WAKE_TIMEOUT_MS
            book = when {
                w.cancelling && now - w.cancelAt >= CANCEL_CONFIRM_MS -> book.put(h.copy(wake = WakeStatus.Cancelled(now)))
                now - w.startedAt >= limit ->
                    book.put(
                        h.copy(
                            wake = WakeStatus.TimedOut(w.startedAt, if (w.board) "No result from the board" else null),
                            lastWake = LastWake(w.startedAt, null),
                        )
                    )
                else -> book
            }
        }
        return book
    }

    fun dismissWake(name: String): HostBook = edit(name) { it.copy(wake = WakeStatus.Idle) }
}
