package io.github.joanrra2.pocketwake.shortcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.joanrra2.pocketwake.MainActivity
import io.github.joanrra2.pocketwake.R
import io.github.joanrra2.pocketwake.core.Topics

/**
 * "Wake <PC>" shortcuts: the last woken PCs in the app icon's long-press menu, and pinned
 * icons on the home screen. Both open the app, which connects and wakes the PC.
 *
 * The same request can come from outside: the [ACTION_WAKE] intent (Tasker, NFC tag apps)
 * or a `pocketwake://wake/<name>` link. Those always ask before waking; only intents that
 * carry this install's [token] (put there by the shortcuts below) wake straight away.
 */
object WakeShortcuts {
    const val ACTION_WAKE = "io.github.joanrra2.pocketwake.WAKE"
    const val EXTRA_NAME = "name"
    const val EXTRA_TOKEN = "token"
    const val SCHEME = "pocketwake"

    /** A wake asked for by intent. [trusted] = from one of this app's own shortcuts. */
    data class Request(val name: String, val trusted: Boolean)

    /** Reads a wake request from [intent], or null when it isn't one or the name is invalid. */
    fun parse(intent: Intent?, token: String): Request? {
        if (intent == null) return null
        val data = intent.data
        return request(
            action = intent.action,
            extraName = intent.getStringExtra(EXTRA_NAME),
            extraToken = intent.getStringExtra(EXTRA_TOKEN),
            linkName = data?.let { nameFromLink(it.scheme, it.host, it.pathSegments) },
            token = token,
        )
    }

    /** [parse] without Android types, for tests. */
    fun request(action: String?, extraName: String?, extraToken: String?, linkName: String?, token: String): Request? {
        val name = when (action) {
            ACTION_WAKE -> extraName
            Intent.ACTION_VIEW -> linkName
            else -> null
        } ?: return null
        if (Topics.nameError(name) != null) return null
        val trusted = action == ACTION_WAKE && token.isNotEmpty() && extraToken == token
        return Request(name, trusted)
    }

    /** `pocketwake://wake/OFFICE-PC` -> "OFFICE-PC" (path already decoded). */
    fun nameFromLink(scheme: String?, host: String?, path: List<String>): String? =
        if (scheme.equals(SCHEME, ignoreCase = true) && host.equals("wake", ignoreCase = true) && path.size == 1) path[0] else null

    fun link(name: String): Uri = Uri.Builder().scheme(SCHEME).authority("wake").appendPath(name).build()

    private fun intent(context: Context, name: String, token: String): Intent =
        Intent(ACTION_WAKE)
            .setClass(context, MainActivity::class.java)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_TOKEN, token)

    private fun info(context: Context, name: String, token: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "wake:$name")
            .setShortLabel(name)
            .setLongLabel("Wake $name")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent(context, name, token))
            .setRank(0)
            .build()

    /** Puts [name] first in the app icon's long-press menu (the launcher keeps the last few). */
    fun onWoken(context: Context, name: String, token: String) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info(context, name, token)) }
    }

    fun canPin(context: Context): Boolean = runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** Asks the launcher to add a "Wake <name>" icon. False when the launcher can't. */
    fun pin(context: Context, name: String, token: String): Boolean =
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, info(context, name, token), null) }.getOrDefault(false)

    /** After switching broker or prefix: the recent list belonged to the old one. */
    fun clearRecent(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
