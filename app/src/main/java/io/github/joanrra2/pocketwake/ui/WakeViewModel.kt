package io.github.joanrra2.pocketwake.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.joanrra2.pocketwake.core.BoardStats
import io.github.joanrra2.pocketwake.core.Blocking
import io.github.joanrra2.pocketwake.core.BoardResult
import io.github.joanrra2.pocketwake.core.HostBook
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.Topics
import io.github.joanrra2.pocketwake.core.WakeStatus
import io.github.joanrra2.pocketwake.data.SettingsRepository
import io.github.joanrra2.pocketwake.mqtt.BrokerSession
import io.github.joanrra2.pocketwake.mqtt.Connection
import io.github.joanrra2.pocketwake.mqtt.ConnectionTest
import io.github.joanrra2.pocketwake.mqtt.Message
import io.github.joanrra2.pocketwake.mqtt.TestReport
import io.github.joanrra2.pocketwake.shortcut.WakeShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Retained `<prefix>/status` of the board. */
data class BoardState(val presence: Presence = Presence.UNKNOWN, val since: Long? = null)

data class UiState(
    val profile: Profile = Profile(),
    val passwordLost: Boolean = false,
    val connection: Connection = Connection.Idle,
    val board: BoardState = BoardState(),
    val book: HostBook = HostBook(),
    val lastMessageAt: Long? = null,
    val now: Long = System.currentTimeMillis(),
    val testing: Boolean = false,
    val testReport: TestReport? = null,
    /** `<prefix>/blocking`, unknown until the board's retained state arrives. */
    val blocking: Blocking = Blocking(),
    /** Last `<prefix>/stats` (every 60 s). */
    val stats: BoardStats? = null,
    /** A PC to wake as soon as the broker connects (from a shortcut or another app). */
    val queuedWake: String? = null,
)

/**
 * Owns the MQTT session. Connects when the app comes to the foreground and disconnects
 * when it leaves (ProcessLifecycleOwner, so rotation doesn't reconnect). No background work.
 */
class WakeViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {

    private val repo = SettingsRepository(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var session: BrokerSession? = null
    private var sessionJob: Job? = null
    private var tickJob: Job? = null
    private var queuedJob: Job? = null
    private var foreground = false
    val shortcutToken: String get() = repo.shortcutToken()

    init {
        val profile = repo.loadProfile()
        val book = HostBook().withManual(repo.loadManualHosts()).withLastWakes(repo.loadLastWakes())
        _ui.value = UiState(profile = profile, passwordLost = repo.passwordLost(), book = book)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        connect()
        startTicker()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        disconnect()
        tickJob?.cancel()
        persistLastWakes()
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        disconnect()
    }

    // --- connection -------------------------------------------------------------------

    private fun connect() {
        disconnect()
        val profile = _ui.value.profile
        if (!profile.isConfigured) return
        val prefix = profile.prefix
        val s = BrokerSession(
            profile = profile,
            clientId = repo.clientId(),
            filters = Topics.filters(prefix),
            onMessage = { onMessage(prefix, it) },
        )
        session = s
        sessionJob = viewModelScope.launch {
            s.state.collect { c -> _ui.update { it.copy(connection = c) } }
        }
        s.start()
    }

    private fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        session?.let { s -> viewModelScope.launch(Dispatchers.IO) { runCatching { s.stop() } } }
        session = null
        _ui.update { it.copy(connection = Connection.Idle) }
    }

    /** Manual retry after a fatal error (e.g. the password was fixed on the broker side). */
    fun retry() {
        if (foreground) connect()
    }

    private fun onMessage(prefix: String, m: Message) {
        val now = System.currentTimeMillis()
        when (m.topic) {
            Topics.blockingTopic(prefix) -> {
                val on = Blocking.parse(m.payload) ?: return
                _ui.update { it.copy(blocking = it.blocking.onReported(on), lastMessageAt = now) }
                return
            }
            Topics.statsTopic(prefix) -> {
                val stats = BoardStats.parse(m.payload, now) ?: return
                _ui.update { it.copy(stats = stats, lastMessageAt = now) }
                return
            }
        }
        Topics.hostFromResultTopic(prefix, m.topic)?.let { name ->
            val result = BoardResult.parse(m.payload) ?: return
            _ui.update { it.copy(book = it.book.onResult(name, result, now, live = !m.retained), lastMessageAt = now) }
            if (result != BoardResult.Waking) persistLastWakes()
            return
        }
        val presence = Topics.parsePresence(m.payload) ?: return
        if (m.topic == Topics.statusTopic(prefix)) {
            _ui.update { s ->
                val p = if (presence == Presence.CLEARED) Presence.UNKNOWN else presence
                val since = when {
                    s.board.presence == p -> s.board.since
                    !m.retained -> now
                    else -> null
                }
                s.copy(board = BoardState(p, since), lastMessageAt = now)
            }
            return
        }
        val name = Topics.hostFromStateTopic(prefix, m.topic) ?: return
        _ui.update { it.copy(book = it.book.onState(name, presence, now, live = !m.retained), lastMessageAt = now) }
    }

    // --- wake -------------------------------------------------------------------------

    fun wake(name: String) {
        val s = session ?: return
        val prefix = _ui.value.profile.prefix
        WakeShortcuts.onWoken(getApplication(), name, repo.shortcutToken())
        _ui.update { it.copy(book = it.book.onWakeRequested(name, System.currentTimeMillis())) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.publish(Topics.setTopic(prefix, name), "1") }
                _ui.update { it.copy(book = it.book.onWakePublished(name, System.currentTimeMillis())) }
            } catch (t: Throwable) {
                val reason = if (t is IllegalStateException) t.message ?: "Not connected" else "The broker didn't accept the request"
                _ui.update { it.copy(book = it.book.onWakeFailed(name, reason, System.currentTimeMillis())) }
            }
            persistLastWakes()
        }
    }

    fun dismissWake(name: String) = _ui.update { it.copy(book = it.book.dismissWake(name)) }

    /**
     * Stops the board waiting for [name]. Only offered once the board said "waking": older
     * firmware treats any payload on the set topic, "cancel" included, as another wake.
     */
    fun cancelWake(name: String) {
        val s = session ?: return
        val w = _ui.value.book[name]?.wake as? WakeStatus.Waiting ?: return
        if (!w.canCancel) return
        val prefix = _ui.value.profile.prefix
        _ui.update { it.copy(book = it.book.onCancelRequested(name, System.currentTimeMillis())) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.publish(Topics.setTopic(prefix, name), "cancel") }
            } catch (_: Throwable) {
                _ui.update { it.copy(book = it.book.onCancelFailed(name)) }
            }
        }
    }

    /**
     * Wakes [name] once connected (the app was just opened from a shortcut). Waits briefly
     * for the retained PC states, so a PC the board reports isn't mistaken for an unknown one.
     */
    fun wakeWhenReady(name: String) {
        queuedJob?.cancel()
        _ui.update { it.copy(queuedWake = name) }
        queuedJob = viewModelScope.launch {
            val c = withTimeoutOrNull(QUEUE_TIMEOUT_MS) {
                ui.first { it.connection is Connection.Connected || it.connection is Connection.Failed }.connection
            }
            if (c !is Connection.Connected) {
                val now = System.currentTimeMillis()
                val reason = (c as? Connection.Failed)?.reason ?: "Couldn't connect to the broker in time"
                _ui.update { it.copy(queuedWake = null, book = it.book.onWakeRequested(name, now).onWakeFailed(name, reason, now)) }
                return@launch
            }
            withTimeoutOrNull(STATES_WAIT_MS) { ui.first { it.book[name]?.reportsState == true } }
            _ui.update { it.copy(queuedWake = null) }
            wake(name)
        }
    }

    fun cancelQueuedWake() {
        queuedJob?.cancel()
        _ui.update { it.copy(queuedWake = null) }
    }

    /** Asks the launcher for a "Wake <name>" home-screen icon. False when it can't. */
    fun pinShortcut(name: String): Boolean = WakeShortcuts.pin(getApplication(), name, repo.shortcutToken())

    // --- ad blocking ------------------------------------------------------------------

    fun setBlocking(on: Boolean) {
        val s = session ?: return
        val topic = Topics.blockingSetTopic(_ui.value.profile.prefix)
        _ui.update { it.copy(blocking = it.blocking.onRequested(on, System.currentTimeMillis())) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.publish(topic, if (on) "ON" else "OFF") }
            } catch (t: Throwable) {
                val reason = if (t is IllegalStateException) t.message ?: "Not connected" else "The broker didn't accept the request"
                _ui.update { it.copy(blocking = it.blocking.onFailed(reason)) }
            }
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                var timedOut = false
                _ui.update { s ->
                    val book = s.book.onTick(now)
                    timedOut = book !== s.book
                    s.copy(book = book, now = now, blocking = s.blocking.onTick(now, Topics.blockingSetTopic(s.profile.prefix)))
                }
                if (timedOut) persistLastWakes()
                val busy = _ui.value.book.anyBusy || _ui.value.blocking.pending
                delay(if (busy) 1_000 else 15_000)
            }
        }
    }

    private fun persistLastWakes() {
        val map = _ui.value.book.lastWakes()
        viewModelScope.launch(Dispatchers.IO) { repo.saveLastWakes(map) }
    }

    // --- hosts ------------------------------------------------------------------------

    fun addHost(name: String) {
        _ui.update { it.copy(book = it.book.addManual(name)) }
        saveManual()
    }

    fun removeHost(name: String) {
        _ui.update { it.copy(book = it.book.removeManual(name)) }
        saveManual()
    }

    private fun saveManual() {
        val names = _ui.value.book.hosts.values.filter { it.manual }.map { it.name }
        viewModelScope.launch(Dispatchers.IO) { repo.saveManualHosts(names) }
    }

    // --- settings ---------------------------------------------------------------------

    fun saveProfile(p: Profile) {
        val n = p.normalized()
        val old = _ui.value.profile
        repo.saveProfile(n)
        val brokerChanged = old.host != n.host || old.port != n.port || old.prefix != n.prefix
        if (brokerChanged) WakeShortcuts.clearRecent(getApplication())
        _ui.update {
            it.copy(
                profile = n,
                passwordLost = false,
                book = if (brokerChanged) it.book.forgetDiscovered() else it.book,
                board = if (brokerChanged) BoardState() else it.board,
                blocking = if (brokerChanged) Blocking() else it.blocking,
                stats = if (brokerChanged) null else it.stats,
                testReport = null,
            )
        }
        if (foreground) connect()
    }

    fun testConnection(p: Profile) {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, testReport = null) }
        viewModelScope.launch {
            val report = ConnectionTest.run(p, repo.clientId())
            _ui.update { it.copy(testing = false, testReport = report) }
        }
    }

    fun clearTestReport() = _ui.update { it.copy(testReport = null) }

    private companion object {
        const val QUEUE_TIMEOUT_MS = 30_000L
        const val STATES_WAIT_MS = 2_500L
    }
}
