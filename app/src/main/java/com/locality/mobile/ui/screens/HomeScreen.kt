package com.locality.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.ConnectionState
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.HomeDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.MediaItemRow
import com.locality.mobile.ui.components.NoServerState
import com.locality.mobile.ui.components.NotConnectedBanner
import com.locality.mobile.ui.components.PosterCard
import com.locality.mobile.ui.components.SectionRow
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: Repo,
    onOpenItem: (Long) -> Unit,
    onOpenLibrary: (Long, String, String) -> Unit,
    onOpenCollections: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenParty: () -> Unit = {},
    onPlayFile: (Long) -> Unit,
) {
    var home by remember { mutableStateOf<HomeDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val connection by repo.connection.collectAsState()
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        loading = home == null
        repo.home(refresh).fold(
            onSuccess = { home = it; error = null },
            onFailure = { error = it.message },
        )
        loading = false
    }

    LaunchedEffect(Unit) { load(refresh = false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                title = { Text("Locality", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { scope.launch { load(refresh = true); repo.checkConnection() } }) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Muted)
                    }
                    IconButton(onClick = onOpenCollections) {
                        Icon(Icons.Filled.Collections, "Collections", tint = Palette.Muted)
                    }
                },
            )
        },
    ) { padding ->
        val h = home
        when {
            // An account with no server is a normal state now, not a failure.
            repo.config == null -> NoServerState(
                onConnect = onOpenSettings,
                onDownloads = onOpenDownloads,
                onParty = onOpenParty,
            )
            loading -> CenteredLoading()
            h == null -> CenteredMessage(error ?: "Nothing here yet. Connect to the desktop and refresh.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                if (connection is ConnectionState.Offline) {
                    item {
                        NotConnectedBanner(
                            (connection as ConnectionState.Offline).message,
                            onRetry = { scope.launch { repo.checkConnection(); load(refresh = true) } },
                            onDownloads = onOpenDownloads,
                        )
                    }
                }
                if (h.continueWatching.isNotEmpty()) {
                    item {
                        SectionRow("Continue Watching") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(h.continueWatching, key = { "${it.fileId}" }) { card ->
                                    PosterCard(
                                        title = card.title,
                                        posterUrl = repo.imageUrl(card.poster),
                                        subtitle = card.subtitle,
                                        progress = if (card.duration > 0) (card.position / card.duration).toFloat() else null,
                                        onClick = { onPlayFile(card.fileId) },
                                    )
                                }
                            }
                        }
                    }
                }
                items(h.recentlyAdded, key = { it.library.id }) { row ->
                    if (row.items.isNotEmpty()) {
                        SectionRow(row.library.name) {
                            Text(
                                "Browse all →",
                                color = Palette.Hint,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .clickable { onOpenLibrary(row.library.id, row.library.name, row.library.type) },
                            )
                            Spacer(Modifier.height(6.dp))
                            MediaItemRow(row.items, repo::imageUrl) { onOpenItem(it.id) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
