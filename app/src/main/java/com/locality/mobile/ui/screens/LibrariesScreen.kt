package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.LibraryDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.NoServerState
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/** The Libraries tab: every library on the desktop (Movies, Series, …) plus Collections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    repo: Repo,
    onOpenLibrary: (Long, String, String) -> Unit,
    onOpenCollections: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenParty: () -> Unit = {},
) {
    var libraries by remember { mutableStateOf<List<LibraryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        repo.serverState(refresh).fold(
            onSuccess = { libraries = it.libraries; error = null },
            onFailure = { error = it.message },
        )
    }
    LaunchedEffect(Unit) { load(refresh = false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                title = { Text("Libraries", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { scope.launch { load(refresh = true) } }) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Muted)
                    }
                },
            )
        },
    ) { padding ->
        val libs = libraries
        when {
            repo.config == null -> NoServerState(
                onConnect = onOpenSettings,
                onDownloads = onOpenDownloads,
                onParty = onOpenParty,
            )
            libs == null && error != null -> CenteredMessage(error ?: "")
            libs == null -> CenteredLoading()
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(libs, key = { it.id }) { lib ->
                    LibraryCard(
                        icon = if (lib.type == "tv") Icons.Filled.Tv else Icons.Filled.Movie,
                        title = lib.name,
                        subtitle = if (lib.type == "tv") "Series" else "Movies",
                        onClick = { onOpenLibrary(lib.id, lib.name, lib.type) },
                    )
                }
                item(key = "collections") {
                    LibraryCard(
                        icon = Icons.Filled.Collections,
                        title = "Collections",
                        subtitle = "Box sets & custom collections",
                        onClick = onOpenCollections,
                    )
                }
                if (libs.isEmpty()) {
                    item {
                        CenteredMessage("No libraries yet. Add one on the desktop, then refresh.")
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Raised)
            .border(1.dp, Palette.CardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Hov),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Palette.Accent)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}
