package io.github.joanrra2.pocketwake.core

/**
 * One board's broker connection. The app has a single profile today; the id lets it hold
 * several boards later (see ROADMAP.md) without changing how settings are stored.
 */
data class Profile(
    val id: String = "default",
    val label: String = "Home",
    val host: String = "",
    val port: Int = DEFAULT_TLS_PORT,
    val username: String = "",
    val password: String = "",
    val prefix: String = Topics.DEFAULT_PREFIX,
) {
    val isConfigured: Boolean get() = errors().isEmpty()

    /** Field -> message for every invalid field (empty when the profile can be used). */
    fun errors(): Map<Field, String> = buildMap {
        val h = host.trim()
        when {
            h.isEmpty() -> put(Field.HOST, "Enter the broker address")
            h.contains("://") -> put(Field.HOST, "Address only, without mqtts:// or wss://")
            h.any { it.isWhitespace() || it == '/' } -> put(Field.HOST, "Address only, no spaces or paths")
        }
        if (port !in 1..65535) put(Field.PORT, "Port 1 to 65535")
        Topics.prefixError(Topics.normalizePrefix(prefix))?.let { put(Field.PREFIX, it) }
        if (password.isNotEmpty() && username.isBlank()) put(Field.USERNAME, "Enter the username for this password")
    }

    /** Copy with whitespace and slashes cleaned up, ready to save. */
    fun normalized(): Profile = copy(
        host = host.trim(),
        username = username.trim(),
        prefix = Topics.normalizePrefix(prefix),
    )

    /** "MQTT over TLS · port 8883". The app only connects with TLS. */
    val transportLabel: String get() = "MQTT over TLS · port $port"

    enum class Field { HOST, PORT, USERNAME, PASSWORD, PREFIX }

    companion object {
        const val DEFAULT_TLS_PORT = 8883
    }
}
