package io.github.joanrra2.pocketwake.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.joanrra2.pocketwake.BuildConfigInfo
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.core.SetupLink
import io.github.joanrra2.pocketwake.core.Topics
import io.github.joanrra2.pocketwake.mqtt.TestReport
import io.github.joanrra2.pocketwake.scan.ScanScreen
import io.github.joanrra2.pocketwake.ui.components.Mono
import io.github.joanrra2.pocketwake.ui.components.QrScanIcon
import io.github.joanrra2.pocketwake.ui.components.SectionTitle
import io.github.joanrra2.pocketwake.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: UiState,
    onSave: (Profile) -> Unit,
    onTest: (Profile) -> Unit,
    onBack: (() -> Unit)?,
    incomingLink: SetupLink? = null,
    onLinkUsed: () -> Unit = {},
) {
    val saved = ui.profile
    var host by rememberSaveable { mutableStateOf(saved.host) }
    var port by rememberSaveable { mutableStateOf(saved.port.toString()) }
    var tls by rememberSaveable { mutableStateOf(saved.tls) }
    var username by rememberSaveable { mutableStateOf(saved.username) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    var prefix by rememberSaveable { mutableStateOf(saved.prefix) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var triedSave by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }

    // From the QR scanner, or a setup link sent by the Pocket AdBlock app.
    fun applyLink(link: SetupLink) {
        val p = link.applyTo(
            Profile(host = host, port = port.toIntOrNull() ?: -1, tls = tls, username = username, password = password, prefix = prefix),
        )
        host = p.host
        port = p.port.toString()
        tls = p.tls
        username = p.username
        password = p.password
        prefix = p.prefix
        scanned = when {
            p.username.isEmpty() -> "Filled in from the board. Enter the phone's username and password, then Test connection."
            p.password.isEmpty() -> "Filled in from the board. Enter the password for ${p.username}, then Test connection."
            else -> "Filled in from the board. Test the connection, then Save."
        }
    }

    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            scanning = false
            applyLink(it)
            onLinkUsed()
        }
    }

    if (scanning) {
        ScanScreen(
            onResult = { link ->
                applyLink(link)
                scanning = false
            },
            onClose = { scanning = false },
        )
        return
    }

    val draft = Profile(
        host = host,
        port = port.toIntOrNull() ?: -1,
        tls = tls,
        username = username,
        password = password,
        prefix = prefix,
    )
    val errors = draft.errors()
    fun err(f: Profile.Field) = if (triedSave) errors[f] else null
    val shownPrefix = Topics.normalizePrefix(prefix).ifEmpty { "<prefix>" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Broker settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            ScanCard(note = scanned, onScan = { scanning = true })
            SectionTitle("Broker")
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Address") },
                placeholder = { Text("abc123.ala.eu-central-1.emqxsl.com") },
                singleLine = true,
                isError = err(Profile.Field.HOST) != null,
                supportingText = err(Profile.Field.HOST)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = err(Profile.Field.PORT) != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("TLS", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (tls) "Encrypted, certificate verified" else "Not encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tls) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(
                    checked = tls,
                    onCheckedChange = { on ->
                        tls = on
                        port.toIntOrNull()?.let { port = Profile.portAfterTlsToggle(it, on).toString() }
                    },
                )
            }
            Text(
                "EMQX Cloud: use the MQTT over TLS port (8883). The certificate is checked against the authorities Android trusts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            SectionTitle("Login")
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                isError = err(Profile.Field.USERNAME) != null,
                supportingText = err(Profile.Field.USERNAME)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.passwordLost) {
                Text(
                    "The saved password couldn't be decrypted on this phone. Enter it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Use the phone's own broker login, not the board's. The password is encrypted with a key kept in the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Topics")
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text("Topic prefix") },
                placeholder = { Text(Topics.DEFAULT_PREFIX) },
                singleLine = true,
                isError = err(Profile.Field.PREFIX) != null,
                supportingText = { Text(err(Profile.Field.PREFIX) ?: "Same as the MQTT prefix in the board's web UI") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono("wake    $shownPrefix/wol/<PC>/set")
                    Mono("state   $shownPrefix/wol/+/state")
                    Mono("board   $shownPrefix/status")
                    Mono("result  $shownPrefix/wol/+/result  (optional)")
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        triedSave = true
                        if (errors.isEmpty()) onTest(draft)
                    },
                    enabled = !ui.testing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (ui.testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing…")
                    } else {
                        Text("Test connection")
                    }
                }
                Button(
                    onClick = {
                        triedSave = true
                        if (errors.isEmpty()) onSave(draft)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            ui.testReport?.let { TestReportCard(it, Topics.normalizePrefix(prefix)) }

            SectionTitle("About")
            Text(
                "Pocket Wake ${BuildConfigInfo.versionName(androidx.compose.ui.platform.LocalContext.current)} · for the Pocket AdBlock board's Wake-on-LAN MQTT bridge.\n" +
                    "Connects only while the app is open. No analytics, no ads; nothing leaves the phone except the MQTT connection to your broker.\n" +
                    "Setup guide and bug reports: github.com/joanrra2/pocket-wake",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanCard(note: String?, onScan: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(QrScanIcon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Set up from the board", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    note ?: "Board web page → Wake-on-LAN → Phone app shows a QR code with these settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onScan) { Text(if (note == null) "Scan" else "Scan again") }
        }
    }
}

@Composable
private fun TestReportCard(r: TestReport, prefix: String) {
    val status = LocalStatusColors.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r.error != null) {
                ReportLine(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "Couldn't connect", r.error)
                return@Column
            }
            ReportLine(
                Icons.Filled.CheckCircle,
                status.online,
                "Connected in ${r.connectMillis} ms",
                if (r.tls) "TLS certificate verified, login accepted" else "Login accepted (not encrypted)",
            )
            when (r.board) {
                Presence.ONLINE -> ReportLine(Icons.Filled.CheckCircle, status.online, "Board online", "$prefix/status = online")
                Presence.OFFLINE -> ReportLine(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "Board offline", "Its last will says offline. Is it powered and on Wi-Fi?")
                else -> ReportLine(Icons.Filled.Warning, status.warning, "No board status", "Nothing on $prefix/status. Check the prefix and that the board's MQTT bridge is on.")
            }
            if (r.hosts.isEmpty()) {
                ReportLine(Icons.Filled.Info, MaterialTheme.colorScheme.primary, "No PCs reported", "PCs without an IP on the board can be added by name.")
            } else {
                val list = r.hosts.entries.sortedBy { it.key.lowercase() }
                    .joinToString(", ") { (n, p) -> n + if (p == Presence.ONLINE) " (online)" else " (offline)" }
                ReportLine(Icons.Filled.CheckCircle, status.online, "Found ${r.hosts.size} PC" + if (r.hosts.size == 1) "" else "s", list)
            }
            r.deniedRequired(prefix).takeIf { it.isNotEmpty() }?.let { d ->
                ReportLine(
                    Icons.Filled.Warning,
                    MaterialTheme.colorScheme.error,
                    "The broker won't let this login read " + d.joinToString(" and "),
                    "Add a Client Authorization rule for this login that allows Subscribe on it.",
                )
            }
            when {
                r.blocking != null -> ReportLine(
                    Icons.Filled.CheckCircle,
                    status.online,
                    "Ad blocking " + if (r.blocking) "on" else "off",
                    "The home screen shows a switch and the board's stats.",
                )
                r.deniedOptional(prefix).isNotEmpty() -> ReportLine(
                    Icons.Filled.Info,
                    MaterialTheme.colorScheme.primary,
                    "Ad-blocking switch not allowed (optional)",
                    "To see and switch ad blocking here, allow this login Subscribe on $prefix/blocking and $prefix/stats, and Publish on $prefix/blocking/set.",
                )
            }
            if (r.deniedResults(prefix)) {
                ReportLine(
                    Icons.Filled.Info,
                    MaterialTheme.colorScheme.primary,
                    "Wake results not allowed (optional)",
                    "With board firmware 0.6 or later, allow this login Subscribe on ${Topics.resultFilter(prefix)} to get the board's own wake results and a Cancel button.",
                )
            }
        }
    }
}

@Composable
private fun ReportLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
