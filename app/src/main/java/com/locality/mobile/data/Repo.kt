package com.locality.mobile.data

import com.locality.mobile.data.api.GalleryItemDto
import com.locality.mobile.data.api.HomeDto
import com.locality.mobile.data.api.ItemDetailDto
import com.locality.mobile.data.api.LedgerEntryBody
import com.locality.mobile.data.api.MediaItemDto
import com.locality.mobile.data.api.CollectionDetailDto
import com.locality.mobile.data.api.CollectionSummaryDto
import com.locality.mobile.data.api.PersonPageDto
import com.locality.mobile.data.api.PrepareDto
import com.locality.mobile.data.api.ProgressBatchBody
import com.locality.mobile.data.api.StateDto
import com.locality.mobile.data.api.TrackPrefsBody
import com.locality.mobile.data.api.WatchedBody
import com.locality.mobile.data.db.CloudProgressRow
import com.locality.mobile.data.db.LocalityDb
import com.locality.mobile.data.db.MetaCacheRow
import com.locality.mobile.data.db.PendingProgressRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import retrofit2.HttpException
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

sealed class ConnectionState {
    data object NotConfigured : ConnectionState()
    data object Checking : ConnectionState()
    data class Connected(val state: StateDto) : ConnectionState()
    data class Offline(val message: String) : ConnectionState()
}

/**
 * Network-first with cache fallback: every successful fetch is stored as JSON
 * in Room, and the cache is served whenever the server is unreachable — so the
 * library stays browsable offline (spec: downloaded content usable regardless
 * of connection state). Refreshes happen only on explicit user action.
 */
class Repo(private val client: ApiClient, private val db: LocalityDb) {
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    val connection: StateFlow<ConnectionState> = _connection

    /**
     * Set by AppContainer. Asks the cloud account for the PC's current address
     * when the saved one stops answering; returns true if we should try again.
     */
    var relocate: (suspend () -> Boolean)? = null

    val config: ServerConfig? get() = client.config
    fun imageUrl(rel: String?): String? = client.imageUrl(rel)

    /** Ping /api/state; updates [connection] and opportunistically flushes queued progress. */
    suspend fun checkConnection(): ConnectionState {
        val api = client.api()
        if (api == null) {
            _connection.value = ConnectionState.NotConfigured
            return _connection.value
        }
        _connection.value = ConnectionState.Checking
        return try {
            val state = api.state()
            _connection.value = ConnectionState.Connected(state)
            runCatching { flushPendingProgress() }
            _connection.value
        } catch (e: Exception) {
            // Two failures are worth a second look rather than a red banner:
            // the address may have changed (we left the house and the LAN IP is
            // gone) or the PC may have rotated its token. Both are published to
            // the account, so re-resolve and try once more.
            val worthRelocating = e is IOException || (e is HttpException && e.code() == 401)
            if (worthRelocating && relocate?.invoke() == true) {
                val retried = runCatching { client.api()?.state() }.getOrNull()
                if (retried != null) {
                    _connection.value = ConnectionState.Connected(retried)
                    runCatching { flushPendingProgress() }
                    return _connection.value
                }
            }
            _connection.value = ConnectionState.Offline(friendly(e))
            _connection.value
        }
    }

    private fun friendly(e: Exception): String = when {
        e is HttpException && e.code() == 401 -> "Wrong access token. Check Remote access on the PC."
        else -> "Server unreachable. Is Locality running with Remote access on?"
    }

    // ---- cached fetches ----------------------------------------------------

    private suspend fun <T> cached(key: String, refresh: Boolean, serializer: KSerializer<T>, fetch: suspend () -> T): Result<T> {
        val cacheRow = if (refresh) null else db.metaCache().get(key)
        if (cacheRow != null) {
            runCatching { AppJson.decodeFromString(serializer, cacheRow.json) }.getOrNull()?.let { return Result.success(it) }
        }
        val api = client.api() ?: return cacheOnly(key, serializer, "No server configured")
        return try {
            val fresh = fetch()
            db.metaCache().put(MetaCacheRow(key, AppJson.encodeToString(serializer, fresh), System.currentTimeMillis()))
            if (_connection.value !is ConnectionState.Connected) runCatching { checkConnection() }
            Result.success(fresh)
        } catch (e: Exception) {
            // Only genuine transport failures mean "not connected" — an HTTP
            // error (404 on one route, TMDB hiccup, …) proves the server IS
            // reachable and must not flip the global banner.
            if (e is IOException) _connection.value = ConnectionState.Offline(friendly(e))
            cacheOnly(key, serializer, e.message ?: "offline")
        }
    }

    private suspend fun <T> cacheOnly(key: String, serializer: KSerializer<T>, error: String): Result<T> {
        val row = db.metaCache().get(key) ?: return Result.failure(Exception(error))
        return runCatching { AppJson.decodeFromString(serializer, row.json) }
    }

    suspend fun serverState(refresh: Boolean = false): Result<StateDto> =
        cached("state", refresh, StateDto.serializer()) { client.api()!!.state() }

    suspend fun home(refresh: Boolean = false): Result<HomeDto> =
        cached("home", refresh, HomeDto.serializer()) { client.api()!!.home() }

    suspend fun libraryItems(libraryId: Long, refresh: Boolean = false): Result<List<MediaItemDto>> =
        cached("library/$libraryId", refresh, ListSerializer(MediaItemDto.serializer())) { client.api()!!.libraryItems(libraryId) }

    suspend fun galleryItems(libraryId: Long, refresh: Boolean = false): Result<List<GalleryItemDto>> =
        cached("gallery/$libraryId", refresh, ListSerializer(GalleryItemDto.serializer())) { client.api()!!.galleryItems(libraryId) }

    fun galleryThumbUrl(id: Long): String? = client.config?.galleryThumbUrl(id)
    fun galleryFullUrl(id: Long): String? = client.config?.galleryFullUrl(id)

    suspend fun itemDetail(itemId: Long, refresh: Boolean = false): Result<ItemDetailDto> =
        cached("item/$itemId", refresh, ItemDetailDto.serializer()) { client.api()!!.itemDetail(itemId) }

    suspend fun similar(itemId: Long): Result<List<MediaItemDto>> =
        cached("similar/$itemId", false, ListSerializer(MediaItemDto.serializer())) { client.api()!!.similar(itemId) }

    suspend fun person(tmdbId: Long): Result<PersonPageDto> =
        cached("person/$tmdbId", false, PersonPageDto.serializer()) { client.api()!!.person(tmdbId) }

    suspend fun collections(refresh: Boolean = false): Result<List<CollectionSummaryDto>> =
        cached("collections", refresh, ListSerializer(CollectionSummaryDto.serializer())) { client.api()!!.collections() }

    suspend fun collection(key: String, refresh: Boolean = false): Result<CollectionDetailDto> =
        cached("collection/$key", refresh, CollectionDetailDto.serializer()) { client.api()!!.collection(key) }

    /** Playback prep is never served stale — it must come from the live server. */
    suspend fun prepare(fileId: Long): Result<PrepareDto> {
        val api = client.api() ?: return Result.failure(Exception("No server configured"))
        return runCatching { api.prepare(fileId) }
    }

    /**
     * Someone else's library is read-only to us: we browse, play and download
     * from it, but nothing of ours is written there — otherwise our viewing
     * would land in their Continue Watching and mark their episodes watched.
     * Our own progress still lives locally and syncs through our account.
     */
    val canWriteToServer: Boolean get() = client.config?.writable != false

    suspend fun setItemWatched(itemId: Long, watched: Boolean): Result<Unit> {
        if (!canWriteToServer) return Result.success(Unit)
        val api = client.api() ?: return Result.failure(Exception("No server configured"))
        return runCatching { api.setItemWatched(itemId, WatchedBody(watched)); Unit }
    }

    suspend fun setEpisodeWatched(episodeId: Long, watched: Boolean): Result<Unit> {
        if (!canWriteToServer) return Result.success(Unit)
        val api = client.api() ?: return Result.failure(Exception("No server configured"))
        return runCatching { api.setEpisodeWatched(episodeId, WatchedBody(watched)); Unit }
    }

    /** Remember the audio/subtitle language for a show/movie (best-effort; skipped
     *  offline). Fields are independent: pass only the one that changed. */
    suspend fun setTrackPrefs(itemId: Long, audioLang: String? = null, subLang: String? = null): Result<Unit> {
        if (!canWriteToServer) return Result.success(Unit) // local pref still applies
        val api = client.api() ?: return Result.failure(Exception("No server configured"))
        return runCatching { api.setTrackPrefs(itemId, TrackPrefsBody(audioLang, subLang)); Unit }
    }

    // ---- watch progress ------------------------------------------------------
    //
    // Single path for both streamed and downloaded playback: record locally
    // (fingerprint-keyed, ISO timestamp) and push the whole queue whenever the
    // server answers. The server merges newest-wins, so replays are harmless.

    suspend fun recordProgress(
        fingerprint: String,
        filename: String,
        sizeBytes: Long,
        positionSeconds: Double,
        durationSeconds: Double,
        watched: Boolean? = null,
    ) {
        if (fingerprint.isEmpty()) return
        val existing = db.pendingProgress().get(fingerprint)
        val effectiveWatched = watched
            ?: (durationSeconds > 0 && positionSeconds / durationSeconds >= 0.9)
            || (existing?.watched ?: false)
        db.pendingProgress().upsert(
            PendingProgressRow(
                fingerprint = fingerprint,
                filename = filename,
                sizeBytes = sizeBytes,
                durationSeconds = durationSeconds,
                positionSeconds = positionSeconds,
                watched = effectiveWatched,
                lastUpdated = Instant.now().toString(),
            )
        )
        // Local mirror of what this phone knows, independent of the server
        // outbox above: it survives the flush that empties pending_progress and
        // is what resumes a downloaded episode with nothing in reach.
        db.cloudProgress().upsertAll(
            listOf(
                CloudProgressRow(
                    fingerprint = fingerprint,
                    filename = filename,
                    sizeBytes = sizeBytes,
                    positionSeconds = positionSeconds,
                    durationSeconds = durationSeconds,
                    watched = effectiveWatched,
                    lastUpdated = Instant.now().toString(),
                )
            )
        )
    }

    /** Push everything queued; delete rows the server accepted. Safe to call anytime. */
    suspend fun flushPendingProgress(): Boolean {
        // Hold the queue rather than pushing our viewing into a friend's ledger;
        // it drains to our own server (and to our account) later.
        if (!canWriteToServer) return false
        val api = client.api() ?: return false
        val pending = db.pendingProgress().all()
        if (pending.isEmpty()) return true
        return try {
            api.postProgressBatch(
                ProgressBatchBody(
                    pending.map {
                        LedgerEntryBody(
                            fingerprint = it.fingerprint,
                            filename = it.filename,
                            sizeBytes = it.sizeBytes,
                            durationSeconds = it.durationSeconds,
                            positionSeconds = it.positionSeconds,
                            watched = it.watched,
                            lastUpdated = it.lastUpdated,
                        )
                    }
                )
            )
            db.pendingProgress().delete(pending.map { it.fingerprint })
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Same recipe as the desktop's fingerprint.ts: fp_ + sha1(lowercased filename|size).take(20). */
        fun fingerprintOf(filename: String, sizeBytes: Long): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("${filename.lowercase()}|$sizeBytes".toByteArray(Charsets.UTF_8))
            return "fp_" + digest.joinToString("") { "%02x".format(it) }.take(20)
        }
    }
}
