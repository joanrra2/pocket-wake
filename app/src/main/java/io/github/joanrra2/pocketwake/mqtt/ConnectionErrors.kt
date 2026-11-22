package io.github.joanrra2.pocketwake.mqtt

import com.hivemq.client.mqtt.exceptions.ConnectionClosedException
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import io.github.joanrra2.pocketwake.core.Profile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** A connection problem in words a user can act on. [fatal] = retrying won't help. */
data class ConnectionProblem(val message: String, val fatal: Boolean)

object ConnectionErrors {

    fun describe(error: Throwable?, profile: Profile): ConnectionProblem {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
        fun <T : Throwable> find(type: Class<T>): T? = chain.firstNotNullOfOrNull { if (type.isInstance(it)) type.cast(it) else null }
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }.lowercase()

        find(Mqtt3ConnAckException::class.java)?.let { e ->
            return when (e.mqttMessage.returnCode) {
                Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD ->
                    ConnectionProblem("Wrong username or password", fatal = true)
                Mqtt3ConnAckReturnCode.NOT_AUTHORIZED ->
                    ConnectionProblem("Login refused: check the username, password and the broker's access rules", fatal = true)
                Mqtt3ConnAckReturnCode.IDENTIFIER_REJECTED ->
                    ConnectionProblem("The broker rejected this app's client id", fatal = true)
                Mqtt3ConnAckReturnCode.UNSUPPORTED_PROTOCOL_VERSION ->
                    ConnectionProblem("The broker doesn't accept MQTT 3.1.1", fatal = true)
                Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE ->
                    ConnectionProblem("The broker is temporarily unavailable", fatal = false)
                else -> ConnectionProblem("The broker refused the connection", fatal = true)
            }
        }
        if (find(SSLPeerUnverifiedException::class.java) != null || "hostname" in text && ("verif" in text || "match" in text)) {
            return ConnectionProblem("The broker's certificate doesn't match ${profile.host}", fatal = true)
        }
        if (find(CertPathValidatorException::class.java) != null || find(CertificateException::class.java) != null ||
            "trust anchor" in text
        ) {
            return ConnectionProblem("The broker's TLS certificate isn't trusted by this phone", fatal = true)
        }
        if ("notsslrecordexception" in text || "not an ssl/tls record" in text) {
            return ConnectionProblem("Port ${profile.port} isn't a TLS port. Pocket Wake only connects with TLS: use the broker's TLS port (usually 8883)", fatal = true)
        }
        if (find(SSLHandshakeException::class.java) != null) {
            return ConnectionProblem("TLS handshake failed. Check that port ${profile.port} is the broker's TLS port (usually 8883)", fatal = true)
        }
        if (find(UnknownHostException::class.java) != null) {
            return ConnectionProblem("Can't find ${profile.host}. Check the address and your internet connection", fatal = false)
        }
        if (find(NoRouteToHostException::class.java) != null) {
            return ConnectionProblem("No route to the broker. Check your internet connection", fatal = false)
        }
        if (find(SocketTimeoutException::class.java) != null || "timeout" in text || "timed out" in text) {
            return ConnectionProblem("The broker didn't answer in time", fatal = false)
        }
        if (find(ConnectException::class.java) != null) {
            return ConnectionProblem("Can't reach ${profile.host}:${profile.port}. Check the address and port", fatal = false)
        }
        if (find(ConnectionClosedException::class.java) != null || find(ClosedChannelException::class.java) != null) {
            return ConnectionProblem(
                "The broker closed the connection. Is port ${profile.port} a TLS port? (EMQX Cloud: 8883)",
                fatal = false,
            )
        }
        val deepest = chain.lastOrNull { it.message != null }
        val msg = deepest?.message ?: chain.lastOrNull()?.javaClass?.simpleName ?: "unknown error"
        return ConnectionProblem("Connection failed: $msg", fatal = false)
    }
}
