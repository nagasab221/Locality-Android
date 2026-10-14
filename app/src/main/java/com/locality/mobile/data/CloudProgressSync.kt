package com.locality.mobile.data

import com.locality.mobile.data.db.CloudProgressRow
import com.locality.mobile.data.db.LocalityDb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
private data class RemoteProgress(
    val fingerprint: String = "",
    val filename: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    val watched: Boolean = false,
    @SerialName("last_updated") val lastUpdated: String = "",
)

@Serializable
private data class ProgressWrite(
    @SerialName("user_id") val userId: String,
    val fingerprint: String,
    val filename: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("position_seconds") val positionSeconds: Double,
    val watched: Boolean,
    @SerialName("last_updated") val lastUpdated: String,
)

/**
 * Watch progress straight to the account, alongside the existing server sync.
 *
 * Why both: the server path only works when a Locality server is in reach. This
 * one carries progress from episodes watched on mobile data — or fully offline
 * from downloads, once any network comes back — into the same `watch_progress`
 * table the desktop syncs (electron/server/services/cloudSync.ts), so the PC
 * picks it up on its next pull.
 *
 * Merge rule is the same everywhere in Locality: per fingerprint, newest
 * `last_updated` wins. That makes double-writing from phone and server safe.
 */
class CloudProgressSync(private val cloud: CloudAuth, private val db: LocalityDb) {

    /** Timestamps compare as ISO strings; Postgres hands back a different shape. */
    private fun normalize(ts: String): String = runCatching {
        java.time.Instant.parse(ts).toString()
    }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(ts).toInstant().toString() }.getOrDefault(ts)
    }

    private fun newer(a: String, b: String): Boolean = runCatching {
        java.time.Instant.parse(normalize(a)) > java.time.Instant.parse(normalize(b))
    }.getOrDefault(a > b)

    /** Pull the account's rows, then push anything this phone holds newer. */
    suspend fun sync(): Result<Unit> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Signed out"))
        return try {
            val remote = AppJson.decodeFromString(
                ListSerializer(RemoteProgress.serializer()),
                cloud.rest(
                    "watch_progress?select=fingerprint,filename,size_bytes,duration_seconds," +
                        "position_seconds,watched,last_updated"
                ),
            )
            val remoteByFp = remote.associateBy { it.fingerprint }

            // --- pull: fold newer remote rows into the local mirror ---
            val incoming = remote.mapNotNull { r ->
                if (r.fingerprint.isEmpty()) return@mapNotNull null
                val local = db.cloudProgress().get(r.fingerprint)
                if (local != null && !newer(r.lastUpdated, local.lastUpdated)) null
                else CloudProgressRow(
                    fingerprint = r.fingerprint,
                    filename = r.filename,
                    sizeBytes = r.sizeBytes,
                    positionSeconds = r.positionSeconds,
                    durationSeconds = r.durationSeconds,
                    watched = r.watched,
                    lastUpdated = normalize(r.lastUpdated),
                )
            }
            if (incoming.isNotEmpty()) db.cloudProgress().upsertAll(incoming)

            // --- push: everything we hold newer than the cloud ---
            //
            // Deliberately sourced from the local mirror, NOT the pending queue:
            // that queue is the SERVER's outbox and is emptied the moment a
            // server accepts it, which would otherwise lose the row before the
            // account ever saw it.
            val outgoing = db.cloudProgress().all().filter { p ->
                val r = remoteByFp[p.fingerprint]
                r == null || newer(p.lastUpdated, r.lastUpdated)
            }.map { p ->
                ProgressWrite(
                    userId = uid,
                    fingerprint = p.fingerprint,
                    filename = p.filename,
                    sizeBytes = p.sizeBytes,
                    durationSeconds = p.durationSeconds,
                    positionSeconds = p.positionSeconds,
                    watched = p.watched,
                    lastUpdated = p.lastUpdated,
                )
            }
            if (outgoing.isNotEmpty()) {
                // Chunked upsert; the conflict target matches the table's composite PK.
                outgoing.chunked(500).forEach { chunk ->
                    cloud.rest(
                        "watch_progress?on_conflict=user_id,fingerprint",
                        "POST",
                        AppJson.encodeToString(ListSerializer(ProgressWrite.serializer()), chunk),
                        prefer = "resolution=merge-duplicates,return=minimal",
                    )
                }
                // Mirror locally too, so a resume right after this doesn't re-pull.
                db.cloudProgress().upsertAll(
                    outgoing.map {
                        CloudProgressRow(
                            it.fingerprint,
                            it.filename,
                            it.sizeBytes,
                            it.positionSeconds,
                            it.durationSeconds,
                            it.watched,
                            it.lastUpdated,
                        )
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
