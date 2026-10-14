package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.locality.mobile.data.AppJson
import com.locality.mobile.data.api.ExportManifestDto
import com.locality.mobile.data.db.DownloadBundle
import com.locality.mobile.data.db.DownloadFileRow
import com.locality.mobile.data.db.LocalityDb
import com.locality.mobile.downloads.DownloadCoordinator
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.formatBytes
import com.locality.mobile.ui.theme.Palette
import java.io.File

/**
 * The only place downloaded media is visible or manageable (app-private
 * storage, invisible to MediaStore/Gallery). Fully usable with no server.
 *
 * Everything shown here comes from the bundle's stored manifest + cached
 * artwork, so downloads look like real media (poster, title, year, overview,
 * episode stills) rather than a list of filenames — even offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    db: LocalityDb,
    downloads: DownloadCoordinator,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onPlayLocal: (Long) -> Unit,
) {
    val bundles by db.downloads().bundles().collectAsState(initial = emptyList())
    val allFiles by db.downloads().allFilesFlow().collectAsState(initial = emptyList())

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text("Downloads") },
                actions = {
                    if (allFiles.any { it.status != DownloadFileRow.STATUS_DONE }) {
                        IconButton(onClick = { downloads.startService() }) {
                            Icon(Icons.Filled.Refresh, "Resume downloads", tint = Palette.Accent)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (bundles.isEmpty()) {
            CenteredMessage("Nothing downloaded yet.\nUse “Download” on a movie or season while connected.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                bundles.forEach { bundle ->
                    val files = allFiles.filter { it.bundleId == bundle.id }
                    item(key = "b${bundle.id}") {
                        BundleHeader(bundle, files, downloads, imageUrl, onPlayLocal)
                    }
                    // Movies are a single file already represented by the header.
                    if (bundle.type != "movie") {
                        files.forEach { f ->
                            item(key = "f${f.fileId}") {
                                EpisodeRow(bundle, f, imageUrl, onPlay = { onPlayLocal(f.fileId) })
                            }
                        }
                    }
                    item(key = "sp${bundle.id}") { Spacer(Modifier.height(14.dp)) }
                }
            }
        }
    }
}

/** Decode the stored manifest once per bundle (source of the rich metadata). */
@Composable
private fun rememberManifest(bundle: DownloadBundle): ExportManifestDto? =
    remember(bundle.id, bundle.manifestJson) {
        runCatching { AppJson.decodeFromString(ExportManifestDto.serializer(), bundle.manifestJson) }.getOrNull()
    }

/**
 * Prefer the downloaded image; if it's missing (a small-asset fetch that
 * didn't land), fall back to the server URL so art still shows when connected.
 */
private fun artModel(localPath: String?, rel: String?, imageUrl: (String?) -> String?): Any? {
    if (localPath != null) {
        val f = File(localPath)
        if (f.length() > 0) return f // 0-byte = a failed fetch; fall through to the network
    }
    return imageUrl(rel)
}

@Composable
private fun BundleHeader(
    bundle: DownloadBundle,
    files: List<DownloadFileRow>,
    downloads: DownloadCoordinator,
    imageUrl: (String?) -> String?,
    onPlayLocal: (Long) -> Unit,
) {
    val manifest = rememberManifest(bundle)
    val item = manifest?.item
    val posterModel = artModel(bundle.posterFile, item?.poster, imageUrl)
    val onDisk = files.sumOf { f -> File(f.localPath).takeIf { it.exists() }?.length() ?: 0L }
    val movieFile = files.firstOrNull().takeIf { bundle.type == "movie" }
    // Non-null only when the movie is fully downloaded, so it both gates the UI
    // and smart-casts for the click handler below.
    val playableMovie = movieFile?.takeIf { it.status == DownloadFileRow.STATUS_DONE }
    val playable = playableMovie != null

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (playableMovie != null) Modifier.clickable { onPlayLocal(playableMovie.fileId) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Raised),
                contentAlignment = Alignment.Center,
            ) {
                if (posterModel != null) {
                    AsyncImage(
                        model = posterModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        bundle.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Palette.Muted,
                    )
                }
            }

            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    bundle.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Heading,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val bits = buildList {
                    bundle.year?.let { add(it.toString()) }
                    bundle.season?.let { add("Season $it") }
                    if (bundle.type != "movie") add("${files.size} episodes")
                    item?.genres?.take(2)?.forEach { add(it) }
                }
                if (bits.isNotEmpty()) {
                    Text(
                        bits.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val done = files.count { it.status == DownloadFileRow.STATUS_DONE }
                Text(
                    "${formatBytes(onDisk)} · $done/${files.size} downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Muted,
                )
                item?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (playable) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Palette.Accent)
            }
            IconButton(onClick = { downloads.deleteBundleAsync(bundle.id) }) {
                Icon(Icons.Filled.Delete, "Delete download", tint = Palette.Danger)
            }
        }

        // A movie has no episode rows, so its transfer state lives here.
        if (movieFile != null && movieFile.status != DownloadFileRow.STATUS_DONE) {
            TransferState(movieFile, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun EpisodeRow(bundle: DownloadBundle, f: DownloadFileRow, imageUrl: (String?) -> String?, onPlay: () -> Unit) {
    val manifest = rememberManifest(bundle)
    val ep = remember(manifest, f.fileId) { manifest?.episodes?.firstOrNull { it.file.id == f.fileId } }
    val playable = f.status == DownloadFileRow.STATUS_DONE
    val stillModel = artModel(f.stillFile, ep?.still, imageUrl)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        // Episode still — downloaded with the bundle, so this works offline.
        Box(
            Modifier
                .width(76.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.Raised),
            contentAlignment = Alignment.Center,
        ) {
            if (stillModel != null) {
                AsyncImage(
                    model = stillModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (playable) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Palette.Accent)
            }
        }

        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            val num = if (f.season != null && f.episode != null) "S%02dE%02d".format(f.season, f.episode) else null
            val title = f.epTitle ?: ep?.title
            Text(
                listOfNotNull(num, title).joinToString("  ·  ").ifEmpty { f.filename },
                style = MaterialTheme.typography.bodyMedium,
                color = if (playable) Palette.Body else Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (f.status) {
                DownloadFileRow.STATUS_DONE -> {
                    val meta = buildList {
                        ep?.runtime?.let { add("$it min") }
                        add(formatBytes(f.size))
                    }
                    Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
                else -> TransferState(f, Modifier)
            }
        }
    }
}

/** Progress bar / failure text for a file that isn't finished yet. */
@Composable
private fun TransferState(f: DownloadFileRow, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (f.status == DownloadFileRow.STATUS_FAILED) {
            Text(
                "Failed: ${f.error ?: "error"}. Tap the refresh button to retry.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Danger,
            )
        } else {
            LinearProgressIndicator(
                progress = { if (f.size > 0) (f.downloadedBytes.toFloat() / f.size) else 0f },
                color = Palette.Accent,
                trackColor = Palette.Track,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Text(
                "${formatBytes(f.downloadedBytes)} / ${formatBytes(f.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Muted,
            )
        }
    }
}
