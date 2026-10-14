package com.locality.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.CollectionDetailDto
import com.locality.mobile.data.api.CollectionSummaryDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.MediaItemCard
import com.locality.mobile.ui.components.PosterCard
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(repo: Repo, onBack: () -> Unit, onOpenCollection: (String) -> Unit) {
    var list by remember { mutableStateOf<List<CollectionSummaryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        repo.collections(refresh).fold(onSuccess = { list = it; error = null }, onFailure = { error = it.message })
    }
    LaunchedEffect(Unit) { load(false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text("Collections") },
                actions = {
                    IconButton(onClick = { scope.launch { load(true) } }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Muted) }
                },
            )
        },
    ) { padding ->
        val l = list
        when {
            l == null && error != null -> CenteredMessage(error ?: "")
            l == null -> CenteredLoading()
            l.isEmpty() -> CenteredMessage("No collections yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(l, key = { it.key }) { c ->
                    PosterCard(
                        title = c.name ?: "Collection",
                        posterUrl = repo.imageUrl(c.poster),
                        subtitle = "${c.count} titles",
                        onClick = { onOpenCollection(c.key) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(repo: Repo, collectionKey: String, onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    var detail by remember { mutableStateOf<CollectionDetailDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(collectionKey) {
        repo.collection(collectionKey).fold(onSuccess = { detail = it }, onFailure = { error = it.message })
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text(detail?.name ?: "Collection") },
            )
        },
    ) { padding ->
        val d = detail
        when {
            d == null && error != null -> CenteredMessage(error ?: "")
            d == null -> CenteredLoading()
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                d.total?.let { total ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text("You own ${d.items.size} of $total", color = Palette.Muted)
                    }
                }
                items(d.items, key = { it.id }) { item ->
                    MediaItemCard(item, repo::imageUrl) { onOpenItem(it.id) }
                }
            }
        }
    }
}
