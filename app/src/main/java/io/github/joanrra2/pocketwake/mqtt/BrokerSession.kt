package io.github.joanrra2.pocketwake.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttClientTransportConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import io.github.joanrra2.pocketwake.core.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data class Connected(val since: Long) : Connection
    /** Lost or failed for a reason that may pass; reconnecting automatically. */
    data class Retrying(val reason: String, val attempt: Int, val nextInSeconds: Long) : Connection
    /** Won't retry (wrong password, untrusted certificate...). Needs a settings change or a manual retry. */
    data class Failed(val reason: String) : Connection
}

/** One incoming message. */
data class Message(val topic: String, val payload: String, val retained: Boolean)

/**
 * Builds a HiveMQ MQTT 3.1.1 client for [profile].
 *
 * TLS uses the default config on purpose: the platform's trust store (Android's system
 * CAs; the EMQX Cloud chain ends at DigiCert Global Root G2), SNI from the host name, and
 * HTTPS-style hostname verification in the TLS engine (HiveMQ falls back to the platform
 * HostnameVerifier where the engine can't do it). No certificate pinning or trust-all.
 *
 * The app always connects with TLS. [plainTcpForTests] exists only for the opt-in
 * AccessRulesTest against a local broker; no app code passes it.
 */
internal fun buildClient(
    profile: Profile,
    clientId: String,
    plainTcpForTests: Boolean = false,
): com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder {
    val transport = MqttClientTransportConfig.builder()
        .serverHost(profile.host)
        .serverPort(profile.port)
        .socketConnectTimeout(10, TimeUnit.SECONDS)
        .mqttConnectTimeout(10, TimeUnit.SECONDS)
        .let { b -> if (plainTcpForTests) b else b.sslConfig(MqttClientSslConfig.builder().build()) }
        .build()
    return MqttClient.builder()
        .useMqttVersion3()
        .identifier(clientId)
        .transportConfig(transport)
}

internal fun Mqtt3AsyncClient.connectWith(profile: Profile) =
    connectWith()
        .cleanSession(true)
        .keepAlive(30)
        .let { b ->
            if (profile.username.isNotEmpty()) {
                b.simpleAuth()
                    .username(profile.username)
                    .apply { if (profile.password.isNotEmpty()) password(profile.password.toByteArray()) }
                    .applySimpleAuth()
            } else b
        }
        .send()

/**
 * A long-lived connection used while the app is in the foreground. Reconnects with
 * backoff on network problems, stops on fatal ones, and re-subscribes to [filters] after
 * every (re)connect.
 */
class BrokerSession(
    private val profile: Profile,
    clientId: String,
    private val filters: List<String>,
    private val onMessage: (Message) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<Connection>(Connection.Idle)
    val state: StateFlow<Connection> = _state.asStateFlow()

    @Volatile private var stopped = false

    private val client: Mqtt3AsyncClient = buildClient(profile, clientId)
        .addConnectedListener {
            _state.value = Connection.Connected(clock())
            subscribeAll()
        }
        .addDisconnectedListener { ctx ->
            if (stopped || ctx.source == MqttDisconnectSource.USER) {
                _state.value = Connection.Idle
                return@addDisconnectedListener
            }
            val problem = ConnectionErrors.describe(ctx.cause, profile)
            if (problem.fatal) {
                _state.value = Connection.Failed(problem.message)
            } else {
                val attempt = ctx.reconnector.attempts + 1
                val delay = backoffSeconds(attempt)
                _state.value = Connection.Retrying(problem.message, attempt, delay)
                ctx.reconnector
                    .reconnect(true)
                    .resubscribeIfSessionExpired(false) // we subscribe ourselves on connect
                    .republishIfSessionExpired(false)
                    .delay(delay, TimeUnit.SECONDS)
            }
        }
        .buildAsync()
        .also { c ->
            c.publishes(MqttGlobalPublishFilter.ALL) { p ->
                onMessage(Message(p.topic.toString(), String(p.payloadAsBytes, Charsets.UTF_8), p.isRetain))
            }
        }

    fun start() {
        stopped = false
        _state.value = Connection.Connecting
        // Failures are reported to the disconnected listener, which decides about retrying.
        client.connectWith(profile)
    }

    fun stop() {
        stopped = true
        _state.value = Connection.Idle
        client.disconnect()
    }

    private fun subscribeAll() {
        filters.forEach { f -> client.subscribeWith().topicFilter(f).qos(MqttQos.AT_LEAST_ONCE).send() }
    }

    /** Publishes a non-retained QoS 1 message and waits for the broker's acknowledgement. */
    suspend fun publish(topic: String, payload: String) {
        check(_state.value is Connection.Connected) { "Not connected to the broker" }
        withTimeout(10_000) {
            client.publishWith()
                .topic(topic)
                .payload(payload.toByteArray())
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(false)
                .send()
                .await()
        }
    }

    companion object {
        /** 1, 2, 4, 8, 16, 30, 30... seconds. */
        fun backoffSeconds(attempt: Int): Long = minOf(30L, 1L shl (attempt - 1).coerceIn(0, 5))
    }
}
