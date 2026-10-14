package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.GalleryItemDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/** Photo/clip library: a thumbnail grid and a swipeable full-screen viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    repo: Repo,
    libraryId: Long,
    libraryName: String,
    onBack: () -> Unit,
) {
    var items by remember { mutableStateOf<List<GalleryItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) } // index into items, or null
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        repo.galleryItems(libraryId, refresh).fold(
            onSuccess = { items = it; error = null },
            onFailure = { error = it.message },
        )
    }
    LaunchedEffect(libraryId) { load(false) }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text(libraryName) },
                actions = { IconButton(onClick = { scope.launch { load(true) } }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Muted) } },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null && error != null -> CenteredMessage(error ?: "")
            list == null -> CenteredLoading()
            list.isEmpty() -> CenteredMessage("No photos or videos here yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { g ->
                    val index = list.indexOf(g)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Raised)
                            .clickable { viewer = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = repo.galleryThumbUrl(g.id),
                            contentDescription = g.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (g.kind == "video") {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }

    val list = items
    val start = viewer
    if (list != null && start != null) {
        GalleryViewer(list, start, repo, onClose = { viewer = null })
    }
}

@Composable
private fun GalleryViewer(items: List<GalleryItemDto>, start: Int, repo: Repo, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(initialPage = start, pageCount = { items.size })
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val g = items[page]
                if (g.kind == "video" && pager.currentPage == page) {
                    VideoPage(repo.galleryFullUrl(g.id))
                } else {
                    AsyncImage(
                        model = if (g.kind == "video") repo.galleryThumbUrl(g.id) else repo.galleryFullUrl(g.id),
                        contentDescription = g.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

/** A single clip, played inline. Only the current page builds a player. */
@Composable
private fun VideoPage(url: String?) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            if (url != null) {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(url) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize(),
    )
}
