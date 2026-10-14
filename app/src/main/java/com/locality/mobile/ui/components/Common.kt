package com.locality.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.locality.mobile.data.api.MediaItemDto
import com.locality.mobile.ui.theme.Palette

/** Poster with title underneath — the unit of every grid and row, desktop-style. */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    subtitle: String? = null,
    watched: Boolean = false,
    progress: Float? = null,
    width: androidx.compose.ui.unit.Dp = 116.dp,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Palette.CardBorder, RoundedCornerShape(8.dp))
                .background(Palette.Raised)
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Watched",
                    tint = Palette.Accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            if (progress != null && progress > 0f && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = Palette.Accent,
                    trackColor = Palette.Track,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Palette.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MediaItemCard(item: MediaItemDto, imageUrl: (String?) -> String?, onClick: (MediaItemDto) -> Unit) {
    val sub = buildString {
        item.year?.let { append(it) }
        if (item.type == "show" && item.episodeCount != null) {
            if (isNotEmpty()) append(" · ")
            append("${item.watchedCount ?: 0}/${item.episodeCount}")
        }
    }.ifEmpty { null }
    val showWatched = when (item.type) {
        "movie" -> item.watched == true
        else -> item.episodeCount != null && item.episodeCount > 0 && item.watchedCount == item.episodeCount
    }
    PosterCard(
        title = item.title,
        posterUrl = imageUrl(item.poster),
        subtitle = sub,
        watched = showWatched,
        onClick = { onClick(item) },
    )
}

/** Horizontal poster row with a section heading — Continue Watching, More Like This, etc. */
@Composable
fun SectionRow(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
fun MediaItemRow(items: List<MediaItemDto>, imageUrl: (String?) -> String?, onClick: (MediaItemDto) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { MediaItemCard(it, imageUrl, onClick) }
    }
}

@Composable
fun NotConnectedBanner(message: String, onRetry: () -> Unit, onDownloads: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Raised)
            .border(1.dp, Palette.CardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Palette.Warn)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Not connected", style = MaterialTheme.typography.titleSmall, color = Palette.Heading)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Text(
            "Retry",
            color = Palette.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(onClick = onRetry).padding(8.dp),
        )
        Text(
            "Downloads",
            color = Palette.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(onClick = onDownloads).padding(8.dp),
        )
    }
}

/**
 * What Home and Libraries show with no server connected.
 *
 * An account alone is a perfectly good reason to be here — parties and
 * downloads both work without a library — so this explains the state and
 * offers the ways forward instead of looking like a failure.
 */
@Composable
fun NoServerState(
    onConnect: () -> Unit,
    onDownloads: () -> Unit,
    onParty: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No server connected", style = MaterialTheme.typography.headlineSmall, color = Palette.Heading)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your PC to browse its library. You can still join a watch party " +
                "or play anything you've downloaded without one.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
        ) { Text("Connect a server") }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onParty) { Text("Watch together", color = Palette.Hint) }
            TextButton(onClick = onDownloads) { Text("Downloads", color = Palette.Hint) }
        }
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Accent, trackColor = Palette.Track)
    }
}

@Composable
fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
