package io.github.joanrra2.pocketwake.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import io.github.joanrra2.pocketwake.core.HostBook
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.Topics
import io.github.joanrra2.pocketwake.core.WakeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test of the real MQTT code (HiveMQ client, TLS, topics, wake flow) against a
 * public test broker, with a second client playing the board. Opt-in, because it needs
 * the internet:  POCKETWAKE_IT=1 ./gradlew testDebugUnitTest --tests '*BrokerIntegrationTest*'
 *
 * Uses a random prefix and made-up host names; retained messages are cleared afterwards.
 */
class BrokerIntegrationTest {
    private val host = System.getenv("POCKETWAKE_IT_HOST") ?: "broker.emqx.io"
    private val prefix = "pocketwake-it-" + UUID.randomUUID().toString().take(8)
    private val pc = "DESKTOP-Test"

    @Before fun optIn() = assumeTrue(System.getenv("POCKETWAKE_IT") == "1")

    private fun profile(port: Int = 8883, tls: Boolean = true, h: String = host) =
        Profile(host = h, port = port, tls = tls, prefix = prefix)

    @Test fun `wake flow against a fake board over TLS`() = runBlocking {
        // Fake board: status online, PC offline, and wakes the PC 2 s after a set message.
        val board = MqttClient.builder().useMqttVersion3().identifier("$prefix-board")
            .serverHost(host).serverPort(8883).sslWithDefaultConfig().buildAsync()
        board.connect().await()
        suspend fun retain(topic: String, payload: String) = board.publishWith().topic(topic)
            .payload(payload.toByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).send().await()
        retain(Topics.statusTopic(prefix), "online")
        retain(Topics.stateTopic(prefix, pc), "offline")
        val setPayload = AtomicReference<String?>(null)
        board.subscribeWith().topicFilter("$prefix/wol/+/set").callback { m ->
            setPayload.set(String(m.payloadAsBytes))
            Thread {
                Thread.sleep(2000)
                board.publishWith().topic(Topics.stateTopic(prefix, pc)).payload("online".toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
            }.start()
        }.send().await()

        try {
            // Connection test sees the board and the PC.
            val report = ConnectionTest.run(profile(), "$prefix-app", listenMillis = 2000)
            assertNull(report.error)
            assertEquals(Presence.ONLINE, report.board)
            assertEquals(mapOf(pc to Presence.OFFLINE), report.hosts)

            // Long-lived session drives the HostBook the way the ViewModel does.
            val book = AtomicReference(HostBook())
            val boardSeen = AtomicReference<Presence?>(null)
            val session = BrokerSession(
                profile = profile(),
                clientId = "$prefix-app",
                filters = listOf(Topics.statusTopic(prefix), Topics.stateFilter(prefix)),
                onMessage = { m ->
                    val p = Topics.parsePresence(m.payload) ?: return@BrokerSession
                    if (m.topic == Topics.statusTopic(prefix)) boardSeen.set(p)
                    Topics.hostFromStateTopic(prefix, m.topic)?.let { n ->
                        book.updateAndGet { it.onState(n, p, System.currentTimeMillis(), live = !m.retained) }
                    }
                },
            )
            session.start()
            withTimeout(20_000) { session.state.first { it is Connection.Connected } }
            withTimeout(10_000) { while (book.get()[pc] == null || boardSeen.get() == null) kotlinx.coroutines.delay(50) }
            assertEquals(Presence.OFFLINE, book.get()[pc]!!.presence)
            assertNull("retained state has no since", book.get()[pc]!!.presenceSince)

            book.updateAndGet { it.onWakeRequested(pc, System.currentTimeMillis()) }
            session.publish(Topics.setTopic(prefix, pc), "1")
            book.updateAndGet { it.onWakePublished(pc, System.currentTimeMillis()) }
            assertTrue(book.get()[pc]!!.wake is WakeStatus.Waiting)

            withTimeout(15_000) { while (book.get()[pc]!!.wake !is WakeStatus.Succeeded) kotlinx.coroutines.delay(50) }
            val ok = book.get()[pc]!!.wake as WakeStatus.Succeeded
            assertTrue("online after ${ok.seconds} s", ok.seconds in 1..10)
            assertEquals("1", setPayload.get())
            assertNotNull("live change has a since", book.get()[pc]!!.presenceSince)
            session.stop()
        } finally {
            // Clear retained messages so nothing is left on the public broker.
            retain(Topics.statusTopic(prefix), "")
            retain(Topics.stateTopic(prefix, pc), "")
            board.disconnect().await()
        }
    }

    @Test fun `certificate for another name is rejected`() = runBlocking {
        // Connect by IP address: the certificate is valid but doesn't name the IP.
        val ip = java.net.InetAddress.getByName(host).hostAddress!!
        val r = ConnectionTest.run(profile(h = ip), "$prefix-ip", listenMillis = 0)
        assertNotNull(r.error)
        assertTrue(r.error, r.error!!.contains("certificate", ignoreCase = true))
    }

    @Test fun `tls to a plain port explains the mismatch`(): Unit = runBlocking {
        val r = ConnectionTest.run(profile(port = 1883, tls = true), "$prefix-plain", listenMillis = 0)
        assertTrue(r.error, r.error!!.contains("TLS port"))
    }

    @Test fun `unknown host is reported`() = runBlocking {
        val r = ConnectionTest.run(profile(h = "no-such-host.invalid"), "$prefix-dns", listenMillis = 0)
        assertTrue(r.error, r.error!!.startsWith("Can't find"))
    }
}
