package io.github.joanrra2.pocketwake.data

import android.content.Context
import androidx.core.content.edit
import io.github.joanrra2.pocketwake.core.LastWake
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.Topics
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * App settings in private SharedPreferences. The password is stored only as Keystore
 * ciphertext (see [SecretBox]); everything else is not secret.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketwake", Context.MODE_PRIVATE)
    private val secrets = SecretBox()

    fun loadProfile(): Profile = Profile(
        host = prefs.getString(K_HOST, "") ?: "",
        port = prefs.getInt(K_PORT, Profile.DEFAULT_TLS_PORT),
        tls = prefs.getBoolean(K_TLS, true),
        username = prefs.getString(K_USER, "") ?: "",
        password = prefs.getString(K_PASS, null)?.let { secrets.decrypt(it) } ?: "",
        prefix = prefs.getString(K_PREFIX, Topics.DEFAULT_PREFIX) ?: Topics.DEFAULT_PREFIX,
    )

    /** True when a password was saved but can no longer be decrypted. */
    fun passwordLost(): Boolean = prefs.getString(K_PASS, null)?.let { secrets.decrypt(it) == null } ?: false

    fun saveProfile(p: Profile) {
        val n = p.normalized()
        prefs.edit {
            putString(K_HOST, n.host)
            putInt(K_PORT, n.port)
            putBoolean(K_TLS, n.tls)
            putString(K_USER, n.username)
            if (n.password.isEmpty()) remove(K_PASS) else putString(K_PASS, secrets.encrypt(n.password))
            putString(K_PREFIX, n.prefix)
        }
    }

    fun loadManualHosts(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(K_MANUAL, "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun saveManualHosts(names: Collection<String>) {
        prefs.edit { putString(K_MANUAL, JSONArray(names.sorted()).toString()) }
    }

    fun loadLastWakes(): Map<String, LastWake> = runCatching {
        val o = JSONObject(prefs.getString(K_LAST_WAKES, "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            LastWake(e.getLong("at"), if (e.has("s")) e.getLong("s") else null)
        }
    }.getOrDefault(emptyMap())

    fun saveLastWakes(map: Map<String, LastWake>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            o.put(k, JSONObject().put("at", v.at).apply { v.onlineAfterSeconds?.let { put("s", it) } })
        }
        prefs.edit { putString(K_LAST_WAKES, o.toString()) }
    }

    /** Stable random MQTT client id for this install, e.g. "pocketwake-3f9a1c2e". */
    fun clientId(): String = prefs.getString(K_CLIENT_ID, null) ?: run {
        val id = "pocketwake-" + UUID.randomUUID().toString().take(8)
        prefs.edit { putString(K_CLIENT_ID, id) }
        id
    }

    private companion object {
        const val K_HOST = "broker.host"
        const val K_PORT = "broker.port"
        const val K_TLS = "broker.tls"
        const val K_USER = "broker.user"
        const val K_PASS = "broker.pass.enc"
        const val K_PREFIX = "topic.prefix"
        const val K_MANUAL = "hosts.manual"
        const val K_LAST_WAKES = "hosts.lastWakes"
        const val K_CLIENT_ID = "mqtt.clientId"
    }
}
