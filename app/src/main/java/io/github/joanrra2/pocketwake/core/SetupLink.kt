package io.github.joanrra2.pocketwake.core

import java.net.URLDecoder

/**
 * The broker settings a Pocket AdBlock board shows as a QR code (Wake-on-LAN page).
 *
 * The code is a link to the web app, so an iPhone camera opens it directly:
 * `https://joanrra2.github.io/pocket-wake/#setup?v=1&host=…&port=8883&tls=1&wsport=8084&wspath=%2Fmqtt&prefix=sinkhole&user=…`
 *
 * It never carries a password: that is typed on the phone.
 */
data class SetupLink(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val prefix: String,
    val username: String?,
    val wsPort: Int?,
    val wsPath: String?,
) {
    /** The profile with these settings; the password is kept only if the broker and user are the same. */
    fun applyTo(p: Profile): Profile {
        val user = username ?: p.username
        val samePlace = p.host.equals(host, ignoreCase = true) && p.port == port && p.username == user
        return p.copy(
            host = host,
            port = port,
            tls = tls,
            prefix = prefix,
            username = user,
            password = if (samePlace) p.password else "",
        )
    }

    companion object {
        const val WEB_APP = "https://joanrra2.github.io/pocket-wake/"
        private const val MARKER = "#setup?"

        sealed interface Result {
            data class Ok(val link: SetupLink) : Result
            data class Error(val message: String) : Result
        }

        fun parse(text: String): Result {
            val t = text.trim()
            val at = t.indexOf(MARKER)
            if (at < 0) return Result.Error("This QR code isn't a Pocket Wake setup code")
            val params = t.substring(at + MARKER.length).split('&').filter { it.isNotEmpty() }.associate {
                val eq = it.indexOf('=')
                val k = if (eq < 0) it else it.substring(0, eq)
                val v = if (eq < 0) "" else it.substring(eq + 1)
                decode(k) to decode(v)
            }
            val version = params["v"]?.toIntOrNull() ?: 1
            if (version > 1) return Result.Error("This setup code is newer than the app. Update Pocket Wake")

            val host = params["host"].orEmpty().trim()
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) {
                return Result.Error("The setup code has no valid broker address")
            }
            val tls = params["tls"] != "0"
            val port = params["port"]?.toIntOrNull()
                ?: if (tls) Profile.DEFAULT_TLS_PORT else Profile.DEFAULT_PLAIN_PORT
            if (port !in 1..65535) return Result.Error("The setup code has an invalid port")
            val prefix = Topics.normalizePrefix(params["prefix"] ?: Topics.DEFAULT_PREFIX)
            Topics.prefixError(prefix)?.let { return Result.Error("The setup code has an invalid prefix: $it") }
            val wsPort = params["wsport"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            return Result.Ok(
                SetupLink(
                    host = host,
                    port = port,
                    tls = tls,
                    prefix = prefix,
                    username = params["user"]?.trim()?.takeIf { it.isNotEmpty() },
                    wsPort = wsPort,
                    wsPath = params["wspath"]?.takeIf { it.startsWith("/") },
                ),
            )
        }

        private fun decode(s: String): String =
            // The String-charset overload: decode(String, Charset) needs API 33 (minSdk is 26).
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
