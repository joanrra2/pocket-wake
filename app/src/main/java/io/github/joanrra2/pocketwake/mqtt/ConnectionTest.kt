package io.github.joanrra2.pocketwake.mqtt

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3SubAckException
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAckReturnCode
import io.github.joanrra2.pocketwake.core.Blocking
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
    /** Retained `<prefix>/status`; null = nothing seen (wrong prefix, or board never connected). */
    val board: Presence? = null,
    /** Hosts with a retained state, name -> presence. */
    val hosts: Map<String, Presence> = emptyMap(),
    /** Topic filters the broker refused for this login (SUBACK failure). */
    val denied: List<String> = emptyList(),
    /** Retained `<prefix>/blocking`; null = not seen. */
    val blocking: Boolean? = null,
) {
    /** Refused filters the app needs for wake and PC states. */
    fun deniedRequired(prefix: String): List<String> =
        denied.filter { it == Topics.statusTopic(prefix) || it == Topics.stateFilter(prefix) }

    /** Refused filters only the ad-blocking card uses. */
    fun deniedOptional(prefix: String): List<String> =
        denied.filter { it == Topics.blockingTopic(prefix) || it == Topics.statsTopic(prefix) }

    /** True when the login may not read the board's wake results (firmware 0.6+). */
    fun deniedResults(prefix: String): Boolean = Topics.resultFilter(prefix) in denied
}

object ConnectionTest {
    /** Connects once with [profile], listens briefly for retained board and PC states, disconnects. */
    suspend fun run(profile: Profile, clientId: String, listenMillis: Long = 2500): TestReport =
        run(profile, clientId, listenMillis, plainTcpForTests = false)

    /** [plainTcpForTests] only for the opt-in AccessRulesTest; see [buildClient]. */
    internal suspend fun run(
        profile: Profile,
        clientId: String,
        listenMillis: Long,
        plainTcpForTests: Boolean,
    ): TestReport = withContext(Dispatchers.IO) {
        val p = profile.normalized()
        val client = buildClient(p, "$clientId-test", plainTcpForTests).buildAsync()
        val board = arrayOfNulls<Presence>(1)
        val hosts = ConcurrentHashMap<String, Presence>()
        val blocking = arrayOfNulls<Boolean>(1)
        client.publishes(MqttGlobalPublishFilter.ALL) { m ->
            val payload = String(m.payloadAsBytes, Charsets.UTF_8)
            if (m.topic.toString() == Topics.blockingTopic(p.prefix)) {
                Blocking.parse(payload)?.let { blocking[0] = it }
                return@publishes
            }
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
            // One SUBSCRIBE per filter, so a refusal (SUBACK 0x80) points at its topic. HiveMQ
            // throws when every code in a SUBACK is a failure, which here means this filter.
            val denied = Topics.filters(p.prefix).filter { f ->
                try {
                    val ack = client.subscribeWith().topicFilter(f).qos(MqttQos.AT_LEAST_ONCE).send().await()
                    ack.returnCodes.any { it == Mqtt3SubAckReturnCode.FAILURE }
                } catch (_: Mqtt3SubAckException) {
                    true
                }
            }
            delay(listenMillis)
            TestReport(
                connectMillis = ms,
                board = board[0]?.takeIf { it != Presence.CLEARED },
                hosts = hosts.toMap(),
                denied = denied,
                blocking = blocking[0],
            )
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            TestReport(error = ConnectionErrors.describe(t, p).message)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
