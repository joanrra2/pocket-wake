package io.github.joanrra2.pocketwake.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.joanrra2.pocketwake.core.Format
import io.github.joanrra2.pocketwake.core.Host
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Topics
import io.github.joanrra2.pocketwake.core.WAKE_TIMEOUT_MS
import io.github.joanrra2.pocketwake.core.WakeStatus
import io.github.joanrra2.pocketwake.mqtt.Connection
import io.github.joanrra2.pocketwake.shortcut.WakeShortcuts
import io.github.joanrra2.pocketwake.ui.components.BetaPill
import io.github.joanrra2.pocketwake.ui.components.InfoRow
import io.github.joanrra2.pocketwake.ui.components.Mono
import io.github.joanrra2.pocketwake.ui.components.PowerAvatar
import io.github.joanrra2.pocketwake.ui.components.SectionTitle
import io.github.joanrra2.pocketwake.ui.components.StatusDot
import io.github.joanrra2.pocketwake.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ui: UiState,
    onWake: (String) -> Unit,
    onDismissWake: (String) -> Unit,
    onAddHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetBlocking: (Boolean) -> Unit = {},
    onCancelQueuedWake: () -> Unit = {},
    onPin: (String) -> Boolean = { false },
    onCancelWake: (String) -> Unit = {},
) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    val configured = ui.profile.isConfigured
    val hosts = ui.book.sorted

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pocket Wake", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(8.dp))
                            BetaPill()
                        }
                        if (configured) {
                            Text(
                                ui.profile.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Broker settings") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (configured) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add PC") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!configured) {
                item { SetupCard(onOpenSettings) }
                return@LazyColumn
            }
            item { ConnectionCard(ui, onRetry, onOpenSettings) }
            ui.queuedWake?.let { name -> item { QueuedWakeCard(name, onCancelQueuedWake) } }
            if (ui.blocking.known || ui.stats != null) {
                item { BoardCard(ui, onSetBlocking) }
            }
            item {
                val online = hosts.count { it.presence == Presence.ONLINE }
                val reporting = hosts.count { it.reportsState }
                SectionTitle(
                    "Computers",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    top = 8.dp,
                    trailing = {
                        if (hosts.isNotEmpty()) {
                            Text(
                                when {
                                    ui.connection !is Connection.Connected -> "${hosts.size} PCs"
                                    reporting > 0 -> "$online of $reporting online"
                                    else -> "${hosts.size} added"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
            if (hosts.isEmpty()) {
                item { EmptyHostsCard(ui.connection is Connection.Connected, onAdd = { adding = true }) }
            }
            items(hosts, key = { it.name }) { host ->
                HostCard(
                    host = host,
                    now = ui.now,
                    prefix = ui.profile.prefix,
                    connected = ui.connection is Connection.Connected,
                    onWake = { onWake(host.name) },
                    onDismiss = { onDismissWake(host.name) },
                    onCancel = { onCancelWake(host.name) },
                    onRemove = { onRemoveHost(host.name) },
                    onPin = { onPin(host.name) },
                    snackbar = snackbar,
                )
            }
            if (hosts.isNotEmpty()) {
                item {
                    Text(
                        "PCs with an IP on the board appear automatically. Names are case-sensitive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        AddHostDialog(
            existing = ui.book.hosts.keys,
            onDismiss = { adding = false },
            onAdd = { onAddHost(it); adding = false },
        )
    }
}

// --- connection / board status ----------------------------------------------------------

@Composable
private fun ConnectionCard(ui: UiState, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val status = LocalStatusColors.current
    val c = ui.connection
    val (dot, title, detail) = when (c) {
        is Connection.Connected -> Triple(status.online, "Connected", "since ${Format.clock(ui.now, c.since)}")
        Connection.Connecting -> Triple(MaterialTheme.colorScheme.primary, "Connecting…", null)
        Connection.Idle -> Triple(status.offline, "Not connected", null)
        is Connection.Retrying -> Triple(status.warning, "Reconnecting…", c.reason)
        is Connection.Failed -> Triple(MaterialTheme.colorScheme.error, "Can't connect", c.reason)
    }
    val failed = c is Connection.Failed
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot, size = 12.dp, pulse = c is Connection.Connecting || c is Connection.Retrying)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (c is Connection.Failed || c is Connection.Retrying || c is Connection.Idle) {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry now") }
                }
            }
            if (failed) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Retry") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))

            val board = ui.board
            val connected = c is Connection.Connected
            val (boardDot, boardText) = when {
                !connected -> status.offline to "Unknown until connected"
                board.presence == Presence.ONLINE ->
                    status.online to ("Online" + (board.since?.let { " · since ${Format.clock(ui.now, it)}" } ?: ""))
                board.presence == Presence.OFFLINE ->
                    MaterialTheme.colorScheme.error to ("Offline" + (board.since?.let { " · since ${Format.clock(ui.now, it)}" } ?: " (last will)"))
                else -> status.warning to "No status under ${Topics.statusTopic(ui.profile.prefix)}"
            }
            InfoRow("Board", leading = { StatusDot(boardDot, size = 8.dp) }, value = boardText)
            InfoRow("Broker", value = ui.profile.transportLabel)
            InfoRow("Prefix", value = ui.profile.prefix)
            ui.lastMessageAt?.let { InfoRow("Last update", value = Format.ago(ui.now, it)) }
            if (connected && board.presence == Presence.OFFLINE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The board is offline, so wake requests wait until it reconnects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// --- queued wake (opened from a shortcut) ---------------------------------------------------

@Composable
private fun QueuedWakeCard(name: String, onCancel: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Waking $name as soon as the broker connects…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// --- ad blocking and stats ---------------------------------------------------------------

@Composable
private fun BoardCard(ui: UiState, onSetBlocking: (Boolean) -> Unit) {
    val b = ui.blocking
    val connected = ui.connection is Connection.Connected
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val subtitle = when {
        b.shown == null -> "State unknown"
        !connected -> if (b.shown == true) "On when last seen" else "Off when last seen"
        b.pending -> "Waiting for the board…"
        b.shown == true -> "On · ads and trackers are blocked"
        else -> "Off · devices using the board see ads"
    }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ad blocking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (b.shown == false && connected) LocalStatusColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (b.pending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                if (b.known) {
                    Switch(
                        checked = b.shown == true,
                        onCheckedChange = { on -> if (on) onSetBlocking(true) else confirmOff = true },
                        enabled = connected && !b.pending,
                        modifier = Modifier.semantics { contentDescription = "Ad blocking" },
                    )
                }
            }
            b.problem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val st = ui.stats
            if (st != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
                InfoRow("Blocked", value = "${Format.count(st.blocked)} of ${Format.count(st.total)} · ${Format.percent(st.percent)}")
                InfoRow("Blocklist", value = "${Format.count(st.domains)} domains")
                InfoRow("Devices", value = Format.count(st.clients))
                st.uptimeAt(ui.now)?.let { InfoRow("Up for", value = Format.uptime(it)) }
                st.restart?.let { why ->
                    InfoRow(
                        "Last restart",
                        leading = if (st.unexpectedRestart) ({ StatusDot(LocalStatusColors.current.warning, size = 8.dp) }) else null,
                        value = Format.sentence(why),
                    )
                }
                Text(
                    "Since the board restarted · updated ${Format.ago(ui.now, st.at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Blocking stats arrive within a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off ad blocking?") },
            text = { Text("Every device that uses the board will see ads and trackers until you turn it back on, here or in the board's web UI.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; onSetBlocking(false) }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

// --- host card ----------------------------------------------------------------------------

@Composable
private fun HostCard(
    host: Host,
    now: Long,
    prefix: String,
    connected: Boolean,
    onWake: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onPin: () -> Boolean,
    snackbar: SnackbarHostState,
) {
    val status = LocalStatusColors.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val canPin = remember { WakeShortcuts.canPin(context) }
    var menu by remember { mutableStateOf(false) }
    val busy = host.wake.isBusy
    val online = host.presence == Presence.ONLINE

    val dotColor by animateColorAsState(
        when {
            busy -> MaterialTheme.colorScheme.primary
            online -> status.online
            else -> status.offline
        },
        label = "dot",
    )
    val avatarBg by animateColorAsState(
        when {
            online -> status.onlineContainer
            busy -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "avatarBg",
    )
    val avatarFg = when {
        online -> status.online
        busy -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PowerAvatar(container = avatarBg, glyph = avatarFg, dot = dotColor, pulse = busy)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        presenceLine(host, now, connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (online) status.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!busy) {
                    val label = when (host.wake) {
                        is WakeStatus.TimedOut, is WakeStatus.Failed -> "Retry"
                        else -> "Wake"
                    }
                    if (online) {
                        FilledTonalButton(onClick = onWake, enabled = connected) { Text(label) }
                    } else {
                        Button(onClick = onWake, enabled = connected) { Text(label) }
                    }
                }
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${host.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy wake topic") },
                            onClick = {
                                menu = false
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("topic", Topics.setTopic(prefix, host.name))))
                                    snackbar.showSnackbar("Copied ${Topics.setTopic(prefix, host.name)}")
                                }
                            },
                        )
                        if (canPin) {
                            DropdownMenuItem(
                                text = { Text("Add to home screen") },
                                onClick = {
                                    menu = false
                                    if (!onPin()) scope.launch { snackbar.showSnackbar("Your launcher can't add shortcuts") }
                                },
                            )
                        }
                        if (host.manual) {
                            DropdownMenuItem(
                                text = { Text(if (host.reportsState) "Unpin (keep while the board reports it)" else "Remove") },
                                onClick = { menu = false; onRemove() },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        host.reportsState && host.manual -> "Reported by the board · added by you"
                                        host.reportsState -> "Reported by the board"
                                        else -> "Added by you · no IP on the board"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = { menu = false },
                            enabled = false,
                        )
                    }
                }
            }
            WakeSection(host, now, onDismiss, onCancel)
            host.lastWake?.takeIf { !busy && host.wake == WakeStatus.Idle }?.let { lw ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "Last woken ${Format.ago(now, lw.at)}" + (lw.onlineAfterSeconds?.let { " · online after ${Format.seconds(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 62.dp),
                )
            }
        }
    }
}

private fun presenceLine(host: Host, now: Long, connected: Boolean): String = when {
    !connected && host.presence == Presence.ONLINE -> "Online when last seen"
    !connected && host.presence == Presence.OFFLINE -> "Offline when last seen"
    host.presence == Presence.ONLINE -> "Online" + (host.presenceSince?.let { " · since ${Format.clock(now, it)}" } ?: "")
    host.presence == Presence.OFFLINE -> "Offline" + (host.presenceSince?.let { " · since ${Format.clock(now, it)}" } ?: "")
    !connected -> "Status unknown"
    host.manual && !host.reportsState -> "No status from the board · wake only"
    else -> "Waiting for status…"
}

@Composable
private fun WakeSection(host: Host, now: Long, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val status = LocalStatusColors.current
    AnimatedContent(
        targetState = host.wake,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "wake",
    ) { w ->
        when (w) {
            WakeStatus.Idle -> Spacer(Modifier.height(0.dp))
            is WakeStatus.Sending, is WakeStatus.Waiting -> {
                val started = if (w is WakeStatus.Sending) w.startedAt else (w as WakeStatus.Waiting).startedAt
                val elapsed = ((now - started) / 1000).coerceAtLeast(0)
                // Once the board says "waking" it keeps the PC's own wait (up to 60 min), so
                // there's no fixed maximum to show.
                val board = (w as? WakeStatus.Waiting)?.board == true
                Column(Modifier.padding(start = 62.dp, end = 8.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (w is WakeStatus.Sending) "Sending wake request…" else "Waking… ${Format.seconds(elapsed)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!board) {
                            Text("max 3 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (board) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { (elapsed * 1000f / WAKE_TIMEOUT_MS).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (w is WakeStatus.Waiting) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The board sent the magic packet and is waiting for the PC to answer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (w.board) {
                            TextButton(
                                onClick = onCancel,
                                enabled = w.canCancel,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text(if (w.cancelling) "Cancelling…" else "Cancel") }
                        }
                    }
                }
            }
            is WakeStatus.Succeeded -> ResultBanner(
                icon = { Icon(Icons.Filled.CheckCircle, null, tint = status.online) },
                container = status.onlineContainer,
                content = status.onOnlineContainer,
                title = "Online after ${Format.seconds(w.seconds)}",
                body = "Woken at ${Format.clock(now, w.startedAt)}",
                onDismiss = onDismiss,
            )
            is WakeStatus.AlreadyOnline -> ResultBanner(
                icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                title = "Already online",
                body = if (w.sent) "The wake request was sent anyway." else "The board checked it and sent nothing.",
                onDismiss = onDismiss,
            )
            is WakeStatus.SentUnverified -> ResultBanner(
                icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                title = "Magic packet sent",
                body = "This PC has no IP on the board, so it can't confirm when it's up.",
                onDismiss = onDismiss,
            )
            is WakeStatus.TimedOut -> ResultBanner(
                icon = { Icon(Icons.Filled.Warning, null, tint = status.warning) },
                container = status.warningContainer,
                content = status.onWarningContainer,
                title = w.text?.let(Format::sentence) ?: "No answer after 3 min",
                body = "Check that Wake-on-LAN is enabled in the PC's BIOS and network adapter, and that it's on wired power.",
                onDismiss = onDismiss,
            )
            is WakeStatus.Cancelled -> ResultBanner(
                icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                title = "Cancelled",
                body = "The board stopped waiting. If the PC still starts, its dot turns green.",
                onDismiss = onDismiss,
            )
            is WakeStatus.Failed -> ResultBanner(
                icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                title = "Couldn't send",
                body = w.reason,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ResultBanner(
    icon: @Composable () -> Unit,
    container: Color,
    content: Color,
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 62.dp, end = 8.dp, top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", Modifier.size(18.dp)) }
        }
    }
}

// --- empty states ---------------------------------------------------------------------------

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp)) {
            PowerAvatar(
                container = MaterialTheme.colorScheme.primary,
                glyph = MaterialTheme.colorScheme.onPrimary,
                dot = LocalStatusColors.current.offline,
                pulse = false,
            )
            Spacer(Modifier.height(16.dp))
            Text("Wake your PCs from anywhere", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pocket Wake talks to your Pocket AdBlock board through its MQTT broker. " +
                    "Enter the broker address and the phone's login to get started.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step("1", "Turn on the MQTT bridge in the board's web UI")
                Step("2", "Create a separate login for the phone on your broker")
                Step("3", "Enter it here and tap Test connection")
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenSettings) { Text("Set up broker") }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(n, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyHostsCard(connected: Boolean, onAdd: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp)) {
            Text(if (connected) "No PCs reported yet" else "No PCs yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "PCs that have an IP on the board show up here by themselves, with a live online dot. " +
                    "For PCs without an IP, add them by name, exactly as in the board's Wake-on-LAN list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a PC by name")
            }
        }
    }
}

@Composable
private fun AddHostDialog(existing: Set<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val error = Topics.nameError(name) ?: if (name in existing) "Already in the list" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a PC") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; touched = true },
                    label = { Text("Name on the board") },
                    placeholder = { Text("PC-NAME") },
                    singleLine = true,
                    isError = touched && error != null,
                    supportingText = { Text(if (touched && error != null) error else "Case-sensitive, as in the board's Wake-on-LAN list") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name.isNotEmpty() && error == null) {
                    Mono("Wake topic: …/wol/$name/set")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name) }, enabled = error == null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
