package com.locality.mobile.player

import android.net.Uri
import androidx.media3.common.MediaItem
import com.locality.mobile.LocalityApp
import com.locality.mobile.data.AppJson
import com.locality.mobile.data.api.ExportManifestDto
import com.locality.mobile.data.db.DownloadFileRow
import com.locality.mobile.downloads.LocalSubtitle
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

data class NextInfo(val fileId: Long, val label: String)

/** One shape for both playback paths — streamed from the LAN server or a downloaded file. */
data class PlaybackSource(
    val fileId: Long,
    /** Show/movie id, used to remember the audio+subtitle choice per title. */
    val itemId: Long?,
    val title: String,
    val mediaItem: MediaItem,
    /** Rendered by our own overlay (offset/position control) — not by Media3. */
    val subs: List<SubRef>,
    val fingerprint: String,
    val filename: String,
    val sizeBytes: Long,
    val introStart: Double?,
    val introEnd: Double?,
    val creditsStart: Double?,
    val next: NextInfo?,
    val startPositionMs: Long,
    val autoplayNext: Boolean,
    val upNextSeconds: Int,
    val skipIntro: Boolean,
    val isLocal: Boolean,
    /** Server-remembered track languages (fallback when the phone has no local
     *  choice yet); subLang may be the sentinel "off". */
    val prefAudioLang: String? = null,
    val prefSubLang: String? = null,
)

/**
 * What to play for the party we're in.
 *
 * "local" mode resolves the party's fingerprint against this phone — a download
 * first, else the connected server's copy — so nothing streams from the host.
 * "stream" mode plays the host's file from THEIR server, which is a different
 * machine and token than the one we're configured for, so everything here is
 * addressed explicitly rather than through the shared client.
 */
suspend fun resolvePartySource(app: LocalityApp, party: ActivePartyRef): Result<PlaybackSource> {
    party.localFileId?.let { return resolveSource(app, it) }
    val host = party.hostServer
        ?: return Result.failure(Exception("You don't have this file, and the host isn't streaming it"))
    val fileId = party.hostFileId
        ?: return Result.failure(Exception("The host hasn't said which file this is"))

    val base = host.url.trimEnd('/')
    // The host's token can't ride on the shared client's interceptor, and
    // ExoPlayer's data source isn't ours to add headers to per item — the
    // server accepts ?token= for exactly this reason (players and EventSource).
    val auth = if (host.token.isEmpty()) "" else "?token=${host.token}"
    val prep = runCatching {
        app.container.client.getJson(
            "$base/api/player/prepare/$fileId",
            host.token,
            com.locality.mobile.data.api.PrepareDto.serializer(),
        )
    }.getOrNull()

    val subs = prep?.subtitles.orEmpty().map { s ->
        SubRef(
            id = s.id,
            label = s.label.ifEmpty { s.lang ?: "Subtitles" },
            url = "$base/api/subtitles/${s.id}/vtt$auth",
            lang = s.lang,
        )
    }
    return Result.success(
        PlaybackSource(
            fileId = fileId,
            itemId = null,
            title = prep?.title?.ifEmpty { party.title } ?: party.title,
            mediaItem = MediaItem.Builder().setUri(Uri.parse("$base/api/stream/$fileId$auth")).build(),
            subs = subs,
            fingerprint = party.fingerprint,
            filename = prep?.file?.filename ?: "",
            sizeBytes = prep?.file?.size ?: 0L,
            introStart = prep?.markers?.introStart,
            introEnd = prep?.markers?.introEnd,
            creditsStart = prep?.markers?.creditsStart,
            // Up Next would pull one member into the next episode alone.
            next = null,
            startPositionMs = 0L,
            autoplayNext = false,
            upNextSeconds = 10,
            skipIntro = prep?.playback?.skipIntro ?: true,
            isLocal = false,
        )
    )
}

/** The slice of an active party this file needs, kept free of the party package's types. */
data class ActivePartyRef(
    val title: String,
    val fingerprint: String,
    val hostFileId: Long?,
    val localFileId: Long?,
    val hostServer: com.locality.mobile.party.HostServer?,
)

/**
 * Downloaded copy wins when it exists (plays with the server unreachable);
 * otherwise ask the server's /player/prepare for everything.
 */
suspend fun resolveSource(app: LocalityApp, fileId: Long): Result<PlaybackSource> {
    val c = app.container
    val local = c.db.downloads().file(fileId)
    if (local != null && local.status == DownloadFileRow.STATUS_DONE && File(local.localPath).exists()) {
        return Result.success(localSource(app, local))
    }

    return c.repo.prepare(fileId).map { prep ->
        val base = c.client.config?.baseUrl?.trimEnd('/') ?: ""
        val subs = prep.subtitles.map { s ->
            SubRef(
                id = s.id,
                label = s.label.ifEmpty { s.lang ?: "Subtitles" },
                url = "$base/api/subtitles/${s.id}/vtt",
                lang = s.lang,
            )
        }
        val resumeMs = prep.progress
            ?.takeIf { !it.watched && it.positionSeconds > 30 && it.durationSeconds > 0 && it.positionSeconds / it.durationSeconds < 0.95 }
            ?.let { (it.positionSeconds * 1000).toLong() } ?: 0L
        PlaybackSource(
            fileId = fileId,
            itemId = prep.itemId,
            title = prep.title,
            mediaItem = MediaItem.Builder().setUri(Uri.parse(base + prep.streamUrl)).build(),
            subs = subs,
            fingerprint = prep.file.fingerprint,
            filename = prep.file.filename,
            sizeBytes = prep.file.size,
            introStart = prep.markers?.introStart,
            introEnd = prep.markers?.introEnd,
            creditsStart = prep.markers?.creditsStart,
            next = prep.next?.let { NextInfo(it.fileId, "S%02dE%02d · %s".format(it.season, it.episode, it.title)) },
            startPositionMs = resumeMs,
            autoplayNext = prep.playback.autoplayNext,
            upNextSeconds = prep.playback.upNextSeconds,
            skipIntro = prep.playback.skipIntro,
            isLocal = false,
            prefAudioLang = prep.playback.prefAudioLang,
            prefSubLang = prep.playback.prefSubLang,
        )
    }
}

private suspend fun localSource(app: LocalityApp, row: DownloadFileRow): PlaybackSource {
    val c = app.container
    val subs = runCatching {
        AppJson.decodeFromString(ListSerializer(LocalSubtitle.serializer()), row.subtitlesJson)
    }.getOrDefault(emptyList()).filter { File(it.localPath).exists() }.map { s ->
        SubRef(id = s.id, label = s.label.ifEmpty { s.lang ?: "Subtitles" }, path = s.localPath, lang = s.lang)
    }

    val bundle = c.db.downloads().bundle(row.bundleId)
    val title = buildString {
        append(bundle?.title ?: row.filename)
        if (row.season != null && row.episode != null) append(" · S%02dE%02d".format(row.season, row.episode))
    }

    // Next: the following downloaded-and-finished episode in the same bundle.
    val next = if (row.season != null && row.episode != null) {
        c.db.downloads().filesFor(row.bundleId)
            .filter { it.status == DownloadFileRow.STATUS_DONE && it.season != null && it.episode != null }
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .firstOrNull { (it.season!! > row.season) || (it.season == row.season && it.episode!! > row.episode) }
            ?.let { NextInfo(it.fileId, "S%02dE%02d · %s".format(it.season, it.episode, it.epTitle ?: "")) }
    } else null

    // Resume point, newest wins: this phone's own queue, then the account's copy
    // (what another device watched, pulled down by CloudProgressSync), then the
    // server-side progress captured in the manifest at download time.
    val pending = c.db.pendingProgress().get(row.fingerprint)
    val cloud = c.db.cloudProgress().get(row.fingerprint)
    val manifestProgress = bundle?.let {
        runCatching { AppJson.decodeFromString(ExportManifestDto.serializer(), it.manifestJson) }.getOrNull()
    }?.let { m ->
        (m.files + m.episodes.map { it.file }).firstOrNull { it.id == row.fileId }?.progress
    }
    // (position, watched, timestamp) triples, most trustworthy timestamp wins.
    val candidates = listOfNotNull(
        pending?.let { Triple(it.positionSeconds, it.watched, it.lastUpdated) },
        cloud?.let { Triple(it.positionSeconds, it.watched, it.lastUpdated) },
        manifestProgress?.let { Triple(it.positionSeconds, it.watched, it.lastUpdated) },
    )
    val best = candidates.maxByOrNull {
        runCatching { java.time.Instant.parse(it.third).toEpochMilli() }.getOrDefault(0L)
    }
    val resumeSec = if (best != null && !best.second) best.first else 0.0
    val resumeMs = if (resumeSec > 30) (resumeSec * 1000).toLong() else 0L

    return PlaybackSource(
        fileId = row.fileId,
        itemId = bundle?.itemId,
        title = title,
        mediaItem = MediaItem.Builder().setUri(Uri.fromFile(File(row.localPath))).build(),
        subs = subs,
        fingerprint = row.fingerprint,
        filename = row.filename,
        sizeBytes = row.size,
        introStart = row.introStart,
        introEnd = row.introEnd,
        creditsStart = row.creditsStart,
        next = next,
        startPositionMs = resumeMs,
        autoplayNext = true,
        upNextSeconds = 10,
        skipIntro = true,
        isLocal = true,
    )
}
