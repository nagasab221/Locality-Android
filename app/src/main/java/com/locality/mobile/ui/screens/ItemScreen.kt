package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.EpisodeDto
import com.locality.mobile.data.api.ItemDetailDto
import com.locality.mobile.data.api.MediaItemDto
import com.locality.mobile.downloads.DownloadCoordinator
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import com.locality.mobile.party.PartyMode
import com.locality.mobile.party.PartyService
import com.locality.mobile.ui.components.MediaItemRow
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.SectionRow
import com.locality.mobile.ui.components.formatDuration
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/** A file someone tapped "Watch together" on, waiting for a mode choice. */
private data class PartyTarget(val fileId: Long, val fingerprint: String, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(
    repo: Repo,
    downloads: DownloadCoordinator,
    itemId: Long,
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlayFile: (Long) -> Unit,
    party: PartyService? = null,
    onPartyStarted: () -> Unit = {},
) {
    var partyTarget by remember { mutableStateOf<PartyTarget?>(null) }
    var partyMessage by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ItemDetailDto?>(null) }
    var similar by remember { mutableStateOf<List<MediaItemDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load(refresh: Boolean) {
        repo.itemDetail(itemId, refresh).fold(
            onSuccess = {
                detail = it
                error = null
                if (selectedSeason == null) selectedSeason = it.seasons.firstOrNull()?.season
            },
            onFailure = { error = it.message },
        )
        repo.similar(itemId).onSuccess { similar = it }
    }
    LaunchedEffect(itemId) { load(refresh = false) }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = Palette.Heading),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Body) }
                },
                title = {},
                actions = {
                    IconButton(onClick = { scope.launch { load(refresh = true) } }) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = Palette.Body)
                    }
                },
            )
        },
    ) { _ ->
        val d = detail
        when {
            d == null && error != null -> CenteredMessage(error ?: "")
            d == null -> CenteredLoading()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { Hero(d, repo) }
                if (d.type == "movie" && party != null && d.files.isNotEmpty()) {
                    item {
                        val f = d.files.first()
                        OutlinedButton(
                            onClick = {
                                partyTarget = PartyTarget(
                                    f.id,
                                    f.fingerprint,
                                    d.title + (d.year?.let { y -> " ($y)" } ?: ""),
                                )
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Icon(Icons.Filled.Groups, null, tint = Palette.Accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Watch together", color = Palette.Accent)
                        }
                    }
                }
                item {
                    ActionBar(
                        d = d,
                        repo = repo,
                        downloads = downloads,
                        snackbar = snackbar,
                        onPlayFile = onPlayFile,
                        onWatchedChanged = { scope.launch { load(refresh = true) } },
                    )
                }
                d.collectionName?.let { name ->
                    item {
                        Text(
                            "Part of: $name",
                            color = Palette.Hint,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { d.collectionId?.let { onOpenCollection("tmdb:$it") } },
                        )
                    }
                }
                if (d.type == "show" && d.seasons.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            items(d.seasons.map { it.season }) { s ->
                                FilterChip(
                                    selected = selectedSeason == s,
                                    onClick = { selectedSeason = s },
                                    label = { Text(d.seasons.first { it.season == s }.name ?: "Season $s") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Palette.Accent,
                                        selectedLabelColor = Palette.AccentText,
                                        containerColor = Palette.Raised,
                                        labelColor = Palette.Body,
                                    ),
                                )
                            }
                        }
                    }
                    val season = d.seasons.firstOrNull { it.season == selectedSeason }
                    if (season != null) {
                        item {
                            SeasonDownloadRow(d, season.season, downloads, snackbar)
                        }
                        items(season.episodes, key = { it.id }) { ep ->
                            EpisodeRow(
                                ep,
                                repo,
                                onPlay = { ep.file?.let { onPlayFile(it.id) } },
                                onWatchTogether = if (party != null && ep.file != null) {
                                    {
                                        partyTarget = PartyTarget(
                                            fileId = ep.file.id,
                                            fingerprint = ep.file.fingerprint,
                                            title = "${d.title} · S%02dE%02d".format(ep.season, ep.episode) +
                                                (ep.title?.let { t -> " · $t" } ?: ""),
                                        )
                                    }
                                } else null,
                            ) { watched ->
                                scope.launch {
                                    repo.setEpisodeWatched(ep.id, watched)
                                    load(refresh = true)
                                }
                            }
                        }
                    }
                }
                if (d.cast.isNotEmpty()) {
                    item {
                        SectionRow("Cast") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(d.cast.take(15)) { p ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(84.dp)
                                            .clickable(enabled = p.id != null) { p.id?.let(onOpenPerson) },
                                    ) {
                                        Box(
                                            Modifier
                                                .size(64.dp)
                                                .clip(CircleShape)
                                                .background(Palette.Raised),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                p.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Palette.Hint,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(p.name, style = MaterialTheme.typography.labelSmall, color = Palette.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        p.character?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (similar.isNotEmpty()) {
                    item {
                        SectionRow("More Like This") {
                            MediaItemRow(similar, repo::imageUrl) { onOpenItem(it.id) }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        // "Watch together" on a specific episode (or the movie): pick how the
        // others get the video, then the party is live and invitable.
        val target = partyTarget
        if (target != null && party != null) {
            AlertDialog(
                onDismissRequest = { partyTarget = null },
                containerColor = Palette.Surface,
                title = { Text("Watch together", color = Palette.Heading) },
                text = {
                    Column {
                        Text(target.title, color = Palette.Body, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Everyone plays their own copy, or they stream yours. Invite people from " +
                                "the Party tab once it's started.",
                            color = Palette.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = target
                        partyTarget = null
                        scope.launch {
                            val r = party.host(t.fileId, t.fingerprint, t.title, PartyMode.Local)
                            partyMessage = r.exceptionOrNull()?.message
                            if (r.isSuccess) onPartyStarted()
                        }
                    }) { Text("Their own copy", color = Palette.Accent) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val t = target
                        partyTarget = null
                        scope.launch {
                            val r = party.host(t.fileId, t.fingerprint, t.title, PartyMode.Stream)
                            partyMessage = r.exceptionOrNull()?.message
                            if (r.isSuccess) onPartyStarted()
                        }
                    }) { Text("Stream mine", color = Palette.Accent) }
                },
            )
        }

        partyMessage?.let { msg ->
            LaunchedEffect(msg) {
                snackbar.showSnackbar(msg)
                partyMessage = null
            }
        }
    }
}

@Composable
private fun Hero(d: ItemDetailDto, repo: Repo) {
    Box(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = repo.imageUrl(d.backdrop ?: d.poster),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Bg)))
        )
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = repo.imageUrl(d.poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(d.title, style = MaterialTheme.typography.headlineMedium)
                val meta = buildList {
                    d.year?.let { add(it.toString()) }
                    d.runtime?.let { add("${it} min") }
                    d.rating?.let { add("★ %.1f".format(it)) }
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
                if (d.genres.isNotEmpty()) {
                    Text(d.genres.take(3).joinToString(", "), color = Palette.Hint, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    d: ItemDetailDto,
    repo: Repo,
    downloads: DownloadCoordinator,
    snackbar: SnackbarHostState,
    onPlayFile: (Long) -> Unit,
    onWatchedChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (!d.overview.isNullOrBlank()) {
            Text(d.overview, color = Palette.Body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            if (d.type == "movie" && d.files.isNotEmpty()) {
                val file = d.files.first()
                val resume = file.progress != null && !file.progress.watched && file.progress.positionSeconds > 30
                Button(
                    onClick = { onPlayFile(file.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (resume) "Resume" else "Play")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        downloads.queueDownload(d.id, null).fold(
                            onSuccess = { snackbar.showSnackbar("Download queued") },
                            onFailure = { snackbar.showSnackbar("Download failed: ${it.message}") },
                        )
                    }
                }) {
                    Icon(Icons.Filled.Download, null, tint = Palette.Accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Download", color = Palette.Accent)
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val target = !(d.watched ?: (d.episodeCount != null && d.episodeCount == d.watchedCount && d.episodeCount > 0))
                    repo.setItemWatched(d.id, target)
                    onWatchedChanged()
                }
            }) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Hint)
                Spacer(Modifier.width(6.dp))
                Text("Watched", color = Palette.Hint)
            }
        }
        if (d.type == "movie" && d.files.isNotEmpty()) {
            val p = d.files.first().progress
            if (p != null && p.durationSeconds > 0 && !p.watched && p.positionSeconds > 30) {
                LinearProgressIndicator(
                    progress = { (p.positionSeconds / p.durationSeconds).toFloat() },
                    color = Palette.Accent,
                    trackColor = Palette.Track,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

@Composable
private fun SeasonDownloadRow(d: ItemDetailDto, season: Int, downloads: DownloadCoordinator, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var queued by remember(d.id, season) { mutableStateOf(false) }
    LaunchedEffect(d.id, season) {
        // reflected as "Downloaded" if a bundle already exists for this season
        queued = downloads.hasBundle(d.id, season)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Spacer(Modifier.weight(1f))
        if (queued) {
            Icon(Icons.Filled.DownloadDone, null, tint = Palette.Ok)
            Spacer(Modifier.width(6.dp))
            Text("In Downloads", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        } else {
            OutlinedButton(onClick = {
                scope.launch {
                    downloads.queueDownload(d.id, season).fold(
                        onSuccess = { queued = true; snackbar.showSnackbar("Season $season queued") },
                        onFailure = { snackbar.showSnackbar("Download failed: ${it.message}") },
                    )
                }
            }) {
                Icon(Icons.Filled.Download, null, tint = Palette.Accent)
                Spacer(Modifier.width(6.dp))
                Text("Download season", color = Palette.Accent)
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: EpisodeDto,
    repo: Repo,
    onPlay: () -> Unit,
    onWatchTogether: (() -> Unit)? = null,
    onToggleWatched: (Boolean) -> Unit,
) {
    val watched = ep.progress?.watched == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ep.file != null, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Raised)
        ) {
            AsyncImage(
                model = repo.imageUrl(ep.still),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val p = ep.progress
            if (p != null && p.durationSeconds > 0 && !p.watched && p.positionSeconds > 30) {
                LinearProgressIndicator(
                    progress = { (p.positionSeconds / p.durationSeconds).toFloat() },
                    color = Palette.Accent,
                    trackColor = Palette.Track,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
            if (ep.file == null) {
                Box(Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Text("missing", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                "E${ep.episode}  ${ep.title ?: ""}".trim(),
                style = MaterialTheme.typography.titleSmall,
                color = if (watched) Palette.Muted else Palette.Heading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ep.runtime?.let {
                Text(formatDuration(it * 60), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
        if (onWatchTogether != null && ep.file != null) {
            IconButton(onClick = onWatchTogether) {
                Icon(Icons.Filled.Groups, "Watch this episode together", tint = Palette.Muted)
            }
        }
        IconButton(onClick = { onToggleWatched(!watched) }) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = if (watched) "Mark unwatched" else "Mark watched",
                tint = if (watched) Palette.Accent else Palette.Track,
            )
        }
    }
}
