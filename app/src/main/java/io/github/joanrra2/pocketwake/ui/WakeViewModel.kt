package io.github.joanrra2.pocketwake.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.joanrra2.pocketwake.core.HostBook
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.Topics
import io.github.joanrra2.pocketwake.data.SettingsRepository
import io.github.joanrra2.pocketwake.mqtt.BrokerSession
import io.github.joanrra2.pocketwake.mqtt.Connection
import io.github.joanrra2.pocketwake.mqtt.ConnectionTest
import io.github.joanrra2.pocketwake.mqtt.Message
import io.github.joanrra2.pocketwake.mqtt.TestReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var foreground = false

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
            filters = listOf(Topics.statusTopic(prefix), Topics.stateFilter(prefix)),
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

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                var timedOut = false
                _ui.update { s ->
                    val book = s.book.onTick(now)
                    timedOut = book !== s.book
                    s.copy(book = book, now = now)
                }
                if (timedOut) persistLastWakes()
                delay(if (_ui.value.book.anyBusy) 1_000 else 15_000)
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
        _ui.update {
            it.copy(
                profile = n,
                passwordLost = false,
                book = if (brokerChanged) it.book.forgetDiscovered() else it.book,
                board = if (brokerChanged) BoardState() else it.board,
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
}
