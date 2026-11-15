package io.github.joanrra2.pocketwake

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.joanrra2.pocketwake.ui.HomeScreen
import io.github.joanrra2.pocketwake.ui.SettingsScreen
import io.github.joanrra2.pocketwake.ui.WakeViewModel
import io.github.joanrra2.pocketwake.ui.theme.PocketWakeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketWakeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    PocketWakeApp()
                }
            }
        }
    }
}

@Composable
private fun PocketWakeApp(vm: WakeViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(!ui.profile.isConfigured) }
    val configured = ui.profile.isConfigured

    BackHandler(enabled = settingsOpen && configured) { settingsOpen = false }

    AnimatedContent(
        targetState = settingsOpen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(
                ui = ui,
                onSave = { vm.saveProfile(it); settingsOpen = false },
                onTest = vm::testConnection,
                onBack = if (configured) ({ settingsOpen = false; vm.clearTestReport() }) else null,
            )
        } else {
            HomeScreen(
                ui = ui,
                onWake = vm::wake,
                onDismissWake = vm::dismissWake,
                onAddHost = vm::addHost,
                onRemoveHost = vm::removeHost,
                onRetry = vm::retry,
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
