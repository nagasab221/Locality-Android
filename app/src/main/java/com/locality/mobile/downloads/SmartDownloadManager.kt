package com.locality.mobile.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.locality.mobile.data.ApiClient
import com.locality.mobile.data.SettingsStore
import com.locality.mobile.data.api.HomeDto
import com.locality.mobile.data.api.ItemDetailDto
import com.locality.mobile.data.db.LocalityDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Netflix/YouTube-style Smart Downloads: when enabled and on Wi-Fi, keeps a
 * rotating, budget-capped set of downloads based on what the user is watching.
 *
 * Priority: the season/movie you're mid-way through (Continue Watching), then
 * your watchlist, then recent unwatched titles. When the disk budget is reached
 * it evicts the oldest auto-download that's no longer wanted. Manual downloads
 * are never touched (the auto-created bundle ids are tracked in SettingsStore,
 * not the DB, to avoid a destructive Room migration).
 */
class SmartDownloadManager(
    private val context: Context,
    private val client: ApiClient,
    private val db: LocalityDb,
    private val downloads: DownloadCoordinator,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Fire-and-forget; safe to call often (guarded + serialized internally). */
    fun trigger() {
        scope.launch { runCatching { maybeRun() } }
    }

    private data class Desired(val itemId: Long, val season: Int?, val estBytes: Long) {
        val key get() = "$itemId/${season ?: -1}"
    }

    suspend fun maybeRun() {
        if (!settings.smartEnabled.first()) return
        if (!isUnmetered()) return
        val api = client.api() ?: return
        mutex.withLock {
            val home = runCatching { api.home() }.getOrNull() ?: return
            val budget = settings.smartBudgetGb.first().toLong() * 1_000_000_000L

            suspend fun bytesOf(bundleId: Long): Long =
                db.downloads().filesFor(bundleId).sumOf { it.size }

            val desired = buildDesired(home).filter { it.estBytes in 1..budget }
            val desiredKeys = desired.map { it.key }.toSet()

            val smartIds = settings.snapshotSmartBundleIds().toMutableSet()
            val bundles = db.downloads().allBundles()
            val smartBundles = bundles.filter { it.id.toString() in smartIds }
            var used = smartBundles.sumOf { bytesOf(it.id) }

            // Anything part-watched on THIS device. `desired` comes from the
            // server's Continue Watching, which can't know about progress that
            // hasn't synced yet — without this, watching offline over the budget
            // could delete the very episode you're in the middle of.
            val watchingOffline = db.pendingProgress().all()
                .filter { !it.watched && it.positionSeconds > 30 }
                .map { it.fingerprint }
                .toSet()

            // Evict oldest auto-downloads no longer wanted, to free space.
            for (b in smartBundles.sortedBy { it.createdAt }) {
                if (used <= budget) break
                if ("${b.itemId}/${b.season ?: -1}" in desiredKeys) continue
                if (db.downloads().filesFor(b.id).any { it.fingerprint in watchingOffline }) continue
                downloads.deleteBundle(b.id)
                smartIds.remove(b.id.toString())
                used -= bytesOf(b.id)
            }

            // Add wanted bundles that aren't present yet, until the budget is reached.
            for (d in desired) {
                if (downloads.hasBundle(d.itemId, d.season)) continue
                if (used + d.estBytes > budget) continue
                downloads.queueDownload(d.itemId, d.season).getOrNull()?.let { newId ->
                    smartIds.add(newId.toString())
                    used += d.estBytes
                }
            }
            settings.setSmartBundleIds(smartIds)
        }
    }

    private suspend fun buildDesired(home: HomeDto): List<Desired> {
        val api = client.api() ?: return emptyList()
        val out = LinkedHashMap<String, Desired>() // ordered by priority, deduped by key

        suspend fun consider(itemId: Long, preferInProgressSeason: Boolean) {
            val detail = runCatching { api.itemDetail(itemId) }.getOrNull() ?: return
            val d = if (detail.type == "movie") {
                Desired(itemId, null, detail.files.sumOf { it.size })
            } else {
                val season = pickSeason(detail, preferInProgressSeason) ?: return
                val bytes = detail.seasons.firstOrNull { it.season == season }
                    ?.episodes?.sumOf { it.file?.size ?: 0L } ?: 0L
                Desired(itemId, season, bytes)
            }
            out.putIfAbsent(d.key, d)
        }

        for (card in home.continueWatching) consider(card.itemId, preferInProgressSeason = true)
        for (item in home.watchlist) consider(item.id, preferInProgressSeason = false)
        for (row in home.recentlyAdded) for (item in row.items) consider(item.id, preferInProgressSeason = false)
        return out.values.toList()
    }

    /** Season with an unwatched episode you're likely to want next, else season 1. */
    private fun pickSeason(detail: ItemDetailDto, preferInProgress: Boolean): Int? {
        val seasons = detail.seasons.filter { s -> s.episodes.any { it.file != null } }
        if (seasons.isEmpty()) return null
        if (preferInProgress) {
            for (s in seasons) {
                if (s.episodes.any { it.file != null && it.progress?.watched != true }) return s.season
            }
        }
        return seasons.first().season
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
