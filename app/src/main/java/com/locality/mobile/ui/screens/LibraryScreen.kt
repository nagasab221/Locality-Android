package com.locality.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.MediaItemDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.MediaItemCard
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repo: Repo,
    libraryId: Long,
    libraryName: String,
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    var items by remember { mutableStateOf<List<MediaItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    suspend fun load(refresh: Boolean) {
        repo.libraryItems(libraryId, refresh).fold(
            onSuccess = { items = it; error = null },
            onFailure = { error = it.message },
        )
    }
    /** Pull-to-refresh + the toolbar button share this: re-fetch from the server. */
    fun refreshNow() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                load(refresh = true)
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(libraryId) { load(refresh = false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) }
                },
                title = { Text(libraryName) },
                actions = {
                    IconButton(onClick = { refreshNow() }) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Muted)
                    }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null && error != null -> CenteredMessage(error ?: "")
            list == null -> CenteredLoading()
            else -> {
                val filtered = if (query.isBlank()) list
                else list.filter { it.title.contains(query.trim(), ignoreCase = true) }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshNow() },
                    state = pullState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = refreshing,
                            containerColor = Palette.Raised,
                            color = Palette.Accent,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search ${libraryName.lowercase()}…", color = Palette.Muted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Palette.Accent,
                                unfocusedBorderColor = Palette.InBorder,
                                cursorColor = Palette.Accent,
                                focusedContainerColor = Palette.Input,
                                unfocusedContainerColor = Palette.Input,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        )
                    }
                    items(filtered, key = { it.id }) { item ->
                        MediaItemCard(item, repo::imageUrl) { onOpenItem(it.id) }
                    }
                }
                }
            }
        }
    }
}
