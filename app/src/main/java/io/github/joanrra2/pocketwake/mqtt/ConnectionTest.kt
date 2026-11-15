package io.github.joanrra2.pocketwake.mqtt

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.Topics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Result of the settings screen's "Test connection". */
data class TestReport(
    /** Null on success. */
    val error: String? = null,
    val connectMillis: Long? = null,
    val tls: Boolean = true,
    /** Retained `<prefix>/status`; null = nothing seen (wrong prefix, or board never connected). */
    val board: Presence? = null,
    /** Hosts with a retained state, name -> presence. */
    val hosts: Map<String, Presence> = emptyMap(),
)

object ConnectionTest {
    /** Connects once with [profile], listens briefly for retained board and PC states, disconnects. */
    suspend fun run(profile: Profile, clientId: String, listenMillis: Long = 2500): TestReport = withContext(Dispatchers.IO) {
        val p = profile.normalized()
        val client = buildClient(p, "$clientId-test").buildAsync()
        val board = arrayOfNulls<Presence>(1)
        val hosts = ConcurrentHashMap<String, Presence>()
        client.publishes(MqttGlobalPublishFilter.ALL) { m ->
            val payload = String(m.payloadAsBytes, Charsets.UTF_8)
            val presence = Topics.parsePresence(payload) ?: return@publishes
            val topic = m.topic.toString()
            if (topic == Topics.statusTopic(p.prefix)) board[0] = presence
            Topics.hostFromStateTopic(p.prefix, topic)?.let { name ->
                if (presence == Presence.CLEARED) hosts.remove(name) else hosts[name] = presence
            }
        }
        val started = System.nanoTime()
        try {
            withTimeout(25_000) { client.connectWith(p).await() }
            val ms = (System.nanoTime() - started) / 1_000_000
            client.subscribeWith().topicFilter(Topics.statusTopic(p.prefix)).qos(MqttQos.AT_LEAST_ONCE).send().await()
            client.subscribeWith().topicFilter(Topics.stateFilter(p.prefix)).qos(MqttQos.AT_LEAST_ONCE).send().await()
            delay(listenMillis)
            TestReport(connectMillis = ms, tls = p.tls, board = board[0]?.takeIf { it != Presence.CLEARED }, hosts = hosts.toMap())
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            TestReport(error = ConnectionErrors.describe(t, p).message, tls = p.tls)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
