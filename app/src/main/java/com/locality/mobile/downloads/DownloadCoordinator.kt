package com.locality.mobile.downloads

import android.content.Context
import android.content.Intent
import com.locality.mobile.data.ApiClient
import com.locality.mobile.data.AppJson
import com.locality.mobile.data.api.ExportFileDto
import com.locality.mobile.data.api.ExportManifestDto
import com.locality.mobile.data.db.DownloadBundle
import com.locality.mobile.data.db.DownloadFileRow
import com.locality.mobile.data.db.LocalityDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File

@Serializable
data class LocalSubtitle(val id: Long, val lang: String? = null, val label: String = "", val localPath: String = "")

/**
 * Sets up download bundles (rows + small assets) and hands the heavy file
 * transfers to the foreground [DownloadService]. All content lives under
 * app-private storage — never the public Downloads folder or MediaStore.
 */
class DownloadCoordinator(
    private val context: Context,
    private val client: ApiClient,
    private val db: LocalityDb,
    private val scope: CoroutineScope,
) {
    fun bundleDir(bundleId: Long): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "bundles/$bundleId")

    suspend fun hasBundle(itemId: Long, season: Int?): Boolean =
        db.downloads().bundleFor(itemId, season) != null

    /**
     * "Download Movie" / "Download Season": fetch manifest, register rows, start
     * the service. Runs on the IO dispatcher — it's often launched from a UI
     * (Main) coroutine, and the small-asset fetches below do BLOCKING network
     * I/O, which throws NetworkOnMainThreadException on the main thread (that
     * silently killed poster/still downloads, so thumbnails never appeared).
     */
    suspend fun queueDownload(itemId: Long, season: Int?): Result<Long> = withContext(Dispatchers.IO) {
        val api = client.api() ?: return@withContext Result.failure(Exception("No server configured"))
        val manifest = try {
            api.exportManifest(itemId, season)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val existing = db.downloads().bundleFor(itemId, season)
        if (existing != null) return@withContext Result.success(existing.id) // already queued/downloaded

        val bundleId = db.downloads().insertBundle(
            DownloadBundle(
                itemId = itemId,
                type = manifest.item.type,
                title = manifest.item.title,
                year = manifest.item.year,
                season = season,
                posterFile = null,
                backdropFile = null,
                manifestJson = AppJson.encodeToString(ExportManifestDto.serializer(), manifest),
                createdAt = System.currentTimeMillis(),
            )
        )
        val dir = bundleDir(bundleId).apply { mkdirs() }

        val files: List<Pair<ExportFileDto, ExportEpisodeRef?>> =
            if (manifest.item.type == "movie") manifest.files.map { it to null }
            else manifest.episodes.map { it.file to ExportEpisodeRef(it.id, it.season, it.episode, it.title, it.still, it.markers.introStart, it.markers.introEnd, it.markers.creditsStart) }

        // Register the file rows (pending) with no small assets yet, then start
        // the transfer IMMEDIATELY — starting the foreground service must happen
        // while the app is still in the foreground (right after the tap), before
        // any slow network work, or Android blocks the service start.
        for ((f, ep) in files) {
            db.downloads().upsertFile(
                DownloadFileRow(
                    fileId = f.id,
                    bundleId = bundleId,
                    filename = f.filename,
                    size = f.size,
                    fingerprint = f.fingerprint,
                    mime = f.mime,
                    streamPath = f.streamPath,
                    localPath = File(dir, f.filename).absolutePath,
                    episodeId = ep?.id,
                    season = ep?.season,
                    episode = ep?.episode,
                    epTitle = ep?.title,
                    stillFile = null,
                    introStart = ep?.introStart,
                    introEnd = ep?.introEnd,
                    creditsStart = ep?.creditsStart,
                    subtitlesJson = "[]",
                )
            )
        }
        startService()

        // Posters, stills and subtitles fetch in the background so they never
        // delay the video transfer or the button. Column-scoped DAO updates so
        // they don't clobber the transfer service's writes to the same rows.
        scope.launch { fetchBundleArt(bundleId, dir, manifest, files) }

        Result.success(bundleId)
    }

    /** Fetch poster/backdrop/stills/subtitles after the transfer has started. */
    private suspend fun fetchBundleArt(
        bundleId: Long,
        dir: File,
        manifest: ExportManifestDto,
        files: List<Pair<ExportFileDto, ExportEpisodeRef?>>,
    ) {
        val poster = manifest.item.poster?.let { fetchSmall(client.imageUrl(it), File(dir, "poster.img")) }
        val backdrop = manifest.item.backdrop?.let { fetchSmall(client.imageUrl(it), File(dir, "backdrop.img")) }
        db.downloads().bundle(bundleId)?.let {
            db.downloads().updateBundle(it.copy(posterFile = poster?.absolutePath, backdropFile = backdrop?.absolutePath))
        }
        for ((f, ep) in files) {
            val still = ep?.still?.let { fetchSmall(client.imageUrl(it), File(dir, "still_${f.id}.img")) }
            if (still != null) db.downloads().setStill(f.id, still.absolutePath)
            val subs = f.subtitles.map { s ->
                val out = File(dir, "sub_${f.id}_${s.id}.vtt")
                fetchSmall(absolute(s.vttPath), out)
                LocalSubtitle(s.id, s.lang, s.label.ifEmpty { s.lang ?: "Subtitles" }, out.absolutePath)
            }.filter { File(it.localPath).length() > 0 }
            if (subs.isNotEmpty()) {
                db.downloads().setSubtitles(
                    f.id,
                    AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(LocalSubtitle.serializer()), subs),
                )
            }
        }
    }

    fun startService() {
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    suspend fun deleteBundle(bundleId: Long) {
        val bundle = db.downloads().bundle(bundleId) ?: return
        db.downloads().deleteFilesFor(bundleId)
        db.downloads().deleteBundle(bundle)
        bundleDir(bundleId).deleteRecursively()
    }

    fun deleteBundleAsync(bundleId: Long) {
        scope.launch { deleteBundle(bundleId) }
    }

    private fun absolute(path: String): String? {
        val base = client.config?.baseUrl ?: return null
        return base.trimEnd('/') + path
    }

    /** Best-effort download of a small asset (image/subtitle); returns the file
     *  or null. Never leaves a 0-byte file behind (that would look "downloaded"
     *  but render as a broken image and block the network fallback). */
    private fun fetchSmall(url: String?, dest: File): File? {
        if (url == null) return null
        return try {
            client.okHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.isEmpty()) return@use null
                dest.writeBytes(bytes)
                dest
            }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }
}

data class ExportEpisodeRef(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String?,
    val still: String?,
    val introStart: Double?,
    val introEnd: Double?,
    val creditsStart: Double?,
)
