package com.locality.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.locality.mobile.LocalityApp
import com.locality.mobile.data.ConnectionState
import com.locality.mobile.data.Repo
import com.locality.mobile.data.ServerConfigStore
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: ServerConfigStore,
    repo: Repo,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit = {},
) {
    val connection by repo.connection.collectAsState()
    val savedServers by store.servers.collectAsState(initial = emptyList())
    val activeId by store.activeId.collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val settings = (LocalContext.current.applicationContext as LocalityApp).container.settings
    val wifiOnly by settings.wifiOnly.collectAsState(initial = true)
    val smartEnabled by settings.smartEnabled.collectAsState(initial = false)
    val smartBudget by settings.smartBudgetGb.collectAsState(initial = 5)

    if (editing) {
        val cfg = repo.config
        ConnectScreen(
            store = store,
            repo = repo,
            initialHost = cfg?.host ?: "",
            initialPort = (cfg?.port ?: 3777).toString(),
            initialToken = cfg?.token ?: "",
            onConnected = { editing = false },
            onSkip = { editing = false },
        )
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Server", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            val cfg = repo.config
            Text(
                if (cfg == null) "Not configured" else cfg.baseUrl.trimEnd('/'),
                color = Palette.Body,
            )
            Text(
                when (val c = connection) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Offline -> c.message
                    is ConnectionState.Checking -> "Checking…"
                    is ConnectionState.NotConfigured -> "Enter the PC's address to connect"
                },
                color = when (connection) {
                    is ConnectionState.Connected -> Palette.Ok
                    is ConnectionState.Offline -> Palette.Warn
                    else -> Palette.Muted
                },
                style = MaterialTheme.typography.labelLarge,
            )
            if (cfg?.writable == false) {
                Text(
                    "Someone else's library — nothing you watch is written to it.",
                    color = Palette.Hint,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // Saved servers: your own PC plus any friend's library you've opened,
            // so switching back doesn't mean signing in again.
            if (savedServers.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("Switch server", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                savedServers.forEach { s ->
                    val isActive = s.id == activeId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isActive) {
                                scope.launch {
                                    store.setActive(s.id)
                                    repo.checkConnection()
                                }
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name.ifEmpty { s.host },
                                color = if (isActive) Palette.Accent else Palette.Body,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                (if (s.owned) "" else "read-only · ") + s.host,
                                color = Palette.Muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (isActive) {
                            Text("active", color = Palette.Ok, style = MaterialTheme.typography.labelSmall)
                        } else {
                            Text(
                                "remove",
                                color = Palette.Danger,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable { scope.launch { store.remove(s.id) } }
                                    .padding(start = 12.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { scope.launch { repo.checkConnection() } }) {
                Text("Test connection", color = Palette.Accent)
            }
            OutlinedButton(onClick = { editing = true }) {
                Text("Change server / token", color = Palette.Accent)
            }
            OutlinedButton(onClick = onOpenAccount) {
                Text("Account, sharing & watch party", color = Palette.Accent)
            }
            Spacer(Modifier.height(24.dp))
            Text("Watch progress", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Progress recorded offline syncs back automatically whenever the desktop is reachable.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    syncMessage = if (repo.flushPendingProgress()) "Synced." else "Server unreachable. Will retry later."
                }
            }) {
                Text("Sync now", color = Palette.Accent)
            }
            syncMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(24.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Download over Wi-Fi only", color = Palette.Body)
                    Text(
                        "Pause transfers on mobile data; resume on Wi-Fi.",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { scope.launch { settings.setWifiOnly(it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Accent,
                        checkedTrackColor = Palette.Accent.copy(alpha = 0.4f),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Smart downloads", color = Palette.Body)
                    Text(
                        "Auto-download what you're watching and your watchlist over Wi-Fi, up to the budget below.",
                        color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = smartEnabled,
                    onCheckedChange = { scope.launch { settings.setSmartEnabled(it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Accent,
                        checkedTrackColor = Palette.Accent.copy(alpha = 0.4f),
                    ),
                )
            }
            if (smartEnabled) {
                Spacer(Modifier.height(12.dp))
                // Local drag state so we only persist when the user lets go.
                var sliderValue by remember(smartBudget) { mutableStateOf(smartBudget.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Storage budget", color = Palette.Body, modifier = Modifier.weight(1f))
                    Text("${sliderValue.toInt()} GB", color = Palette.Heading)
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { scope.launch { settings.setSmartBudgetGb(sliderValue.toInt()) } },
                    valueRange = 1f..100f,
                    steps = 98, // whole-GB stops between 1 and 100
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Accent,
                        activeTrackColor = Palette.Accent,
                        inactiveTrackColor = Palette.Track,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB. " +
                    "Metadata also from TVmaze and AniList; subtitles from OpenSubtitles.com.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Locality is free software under the GNU GPL v3.",
                color = Palette.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
