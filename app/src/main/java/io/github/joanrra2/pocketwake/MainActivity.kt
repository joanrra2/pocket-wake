package io.github.joanrra2.pocketwake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.joanrra2.pocketwake.core.SetupLink
import io.github.joanrra2.pocketwake.shortcut.WakeShortcuts
import io.github.joanrra2.pocketwake.ui.HomeScreen
import io.github.joanrra2.pocketwake.ui.SettingsScreen
import io.github.joanrra2.pocketwake.ui.WakeViewModel
import io.github.joanrra2.pocketwake.ui.theme.PocketWakeTheme

class MainActivity : ComponentActivity() {
    // A setup link that arrived by intent (from the Pocket AdBlock app or a tapped link),
    // waiting to be shown in Broker settings.
    private var incomingLink by mutableStateOf<SetupLink?>(null)

    // A wake asked for by another app or a pocketwake:// link, waiting for the user's OK.
    private var askWake by mutableStateOf<String?>(null)

    private val vm: WakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            PocketWakeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    PocketWakeApp(vm = vm, incomingLink = incomingLink, onLinkUsed = { incomingLink = null })
                    askWake?.let { name ->
                        AlertDialog(
                            onDismissRequest = { askWake = null },
                            title = { Text("Wake $name?") },
                            text = { Text("Another app or a link asked Pocket Wake to wake this PC.") },
                            confirmButton = { TextButton(onClick = { askWake = null; vm.wakeWhenReady(name) }) { Text("Wake") } },
                            dismissButton = { TextButton(onClick = { askWake = null }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val wake = WakeShortcuts.parse(intent, vm.shortcutToken)
        when {
            wake == null -> readSetupLink(intent)
            !vm.ui.value.profile.isConfigured -> Toast.makeText(this, "Set up the broker first", Toast.LENGTH_LONG).show()
            wake.trusted -> vm.wakeWhenReady(wake.name)
            else -> askWake = wake.name
        }
    }

    private fun readSetupLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW && it.scheme == "https" }?.dataString ?: return
        when (val r = SetupLink.parse(data)) {
            is SetupLink.Companion.Result.Ok -> incomingLink = r.link
            is SetupLink.Companion.Result.Error -> Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun PocketWakeApp(
    vm: WakeViewModel,
    incomingLink: SetupLink?,
    onLinkUsed: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(!ui.profile.isConfigured) }
    val configured = ui.profile.isConfigured

    // A setup link opens Broker settings with the board's values filled in.
    LaunchedEffect(incomingLink) { if (incomingLink != null) settingsOpen = true }

    BackHandler(enabled = settingsOpen && configured) { settingsOpen = false }

    AnimatedContent(
        targetState = settingsOpen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(
                ui = ui,
                incomingLink = incomingLink,
                onLinkUsed = onLinkUsed,
                onSave = { vm.saveProfile(it); settingsOpen = false },
                onTest = vm::testConnection,
                onBack = if (configured) ({ settingsOpen = false; vm.clearTestReport() }) else null,
            )
        } else {
            HomeScreen(
                ui = ui,
                onWake = vm::wake,
                onDismissWake = vm::dismissWake,
                onCancelWake = vm::cancelWake,
                onAddHost = vm::addHost,
                onRemoveHost = vm::removeHost,
                onRetry = vm::retry,
                onSetBlocking = vm::setBlocking,
                onCancelQueuedWake = vm::cancelQueuedWake,
                onPin = vm::pinShortcut,
                onOpenSettings = { settingsOpen = true },
            )
        }
    }
}

/** Version name without enabling BuildConfig generation. */
object BuildConfigInfo {
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
