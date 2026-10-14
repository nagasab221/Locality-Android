package com.locality.mobile.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Cached API JSON (home, item detail, …) so browsing works offline / without refetching. */
@Entity(tableName = "meta_cache")
data class MetaCacheRow(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

/** One "Download Movie" / "Download Season" action. */
@Entity(tableName = "download_bundles")
data class DownloadBundle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val type: String, // "movie" | "show"
    val title: String,
    val year: Int?,
    val season: Int?, // null for movies / whole-show bundles
    val posterFile: String?, // local absolute path
    val backdropFile: String?,
    val manifestJson: String, // full ExportManifestDto as fetched
    val createdAt: Long,
)

/** One media file inside a bundle, downloading into app-private storage. */
@Entity(tableName = "download_files")
data class DownloadFileRow(
    @PrimaryKey val fileId: Long, // server file id
    val bundleId: Long,
    val filename: String,
    val size: Long,
    val fingerprint: String,
    val mime: String,
    val streamPath: String,
    val localPath: String,
    val downloadedBytes: Long = 0,
    val status: String = STATUS_PENDING, // pending | downloading | done | failed
    val error: String? = null,
    // episode context (nulls for movies)
    val episodeId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val epTitle: String? = null,
    val stillFile: String? = null,
    val introStart: Double? = null,
    val introEnd: Double? = null,
    val creditsStart: Double? = null,
    val subtitlesJson: String = "[]", // [{id,lang,label,localPath}]
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
    }
}

/** Watch progress recorded on the phone, queued until the server is reachable. */
@Entity(tableName = "pending_progress")
data class PendingProgressRow(
    @PrimaryKey val fingerprint: String,
    val filename: String,
    val sizeBytes: Long,
    val durationSeconds: Double,
    val positionSeconds: Double,
    val watched: Boolean,
    val lastUpdated: String, // ISO-8601, drives the server's newest-wins merge
)

@Dao
interface MetaCacheDao {
    @Query("SELECT * FROM meta_cache WHERE `key` = :key")
    suspend fun get(key: String): MetaCacheRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MetaCacheRow)
}

@Dao
interface DownloadsDao {
    @Query("SELECT * FROM download_bundles ORDER BY createdAt DESC")
    fun bundles(): Flow<List<DownloadBundle>>

    @Query("SELECT * FROM download_bundles ORDER BY createdAt ASC")
    suspend fun allBundles(): List<DownloadBundle>

    @Query("SELECT * FROM download_bundles WHERE id = :id")
    suspend fun bundle(id: Long): DownloadBundle?

    @Query("SELECT * FROM download_bundles WHERE itemId = :itemId AND ((:season IS NULL AND season IS NULL) OR season = :season)")
    suspend fun bundleFor(itemId: Long, season: Int?): DownloadBundle?

    @Insert
    suspend fun insertBundle(bundle: DownloadBundle): Long

    @Update
    suspend fun updateBundle(bundle: DownloadBundle)

    @Delete
    suspend fun deleteBundle(bundle: DownloadBundle)

    @Query("SELECT * FROM download_files WHERE bundleId = :bundleId ORDER BY season, episode")
    suspend fun filesFor(bundleId: Long): List<DownloadFileRow>

    @Query("SELECT * FROM download_files ORDER BY season, episode")
    fun allFilesFlow(): Flow<List<DownloadFileRow>>

    @Query("SELECT * FROM download_files WHERE fileId = :fileId")
    suspend fun file(fileId: Long): DownloadFileRow?

    /** Party "local" mode: our own copy of a fingerprint, if we downloaded it. */
    @Query("SELECT * FROM download_files WHERE fingerprint = :fp LIMIT 1")
    suspend fun fileByFingerprint(fp: String): DownloadFileRow?

    @Query("SELECT * FROM download_files WHERE status != 'done' ORDER BY bundleId, season, episode LIMIT 1")
    suspend fun nextIncomplete(): DownloadFileRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(row: DownloadFileRow)

    @Update
    suspend fun updateFile(row: DownloadFileRow)

    // Column-scoped updates: the art job and the transfer service both touch a
    // file row concurrently, so a read-modify-write (updateFile) would clobber.
    @Query("UPDATE download_files SET stillFile = :path WHERE fileId = :fileId")
    suspend fun setStill(fileId: Long, path: String?)

    @Query("UPDATE download_files SET subtitlesJson = :json WHERE fileId = :fileId")
    suspend fun setSubtitles(fileId: Long, json: String)

    @Query("DELETE FROM download_files WHERE bundleId = :bundleId")
    suspend fun deleteFilesFor(bundleId: Long)

    @Query("UPDATE download_files SET status = 'pending', error = NULL WHERE status = 'failed' OR status = 'downloading'")
    suspend fun resetStuck()
}

/**
 * Watch progress pulled from the account (the same `watch_progress` table the
 * desktop syncs). Lets a downloaded episode resume where another device left
 * off, with no server in reach.
 */
@Entity(tableName = "cloud_progress")
data class CloudProgressRow(
    @PrimaryKey val fingerprint: String,
    val filename: String,
    val sizeBytes: Long,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val watched: Boolean,
    val lastUpdated: String,
)

@Dao
interface CloudProgressDao {
    @Query("SELECT * FROM cloud_progress WHERE fingerprint = :fp")
    suspend fun get(fp: String): CloudProgressRow?

    @Query("SELECT * FROM cloud_progress")
    suspend fun all(): List<CloudProgressRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CloudProgressRow>)
}

@Dao
interface PendingProgressDao {
    @Query("SELECT * FROM pending_progress")
    suspend fun all(): List<PendingProgressRow>

    @Query("SELECT * FROM pending_progress WHERE fingerprint = :fp")
    suspend fun get(fp: String): PendingProgressRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingProgressRow)

    @Query("DELETE FROM pending_progress WHERE fingerprint IN (:fps)")
    suspend fun delete(fps: List<String>)
}

@Database(
    entities = [
        MetaCacheRow::class,
        DownloadBundle::class,
        DownloadFileRow::class,
        PendingProgressRow::class,
        CloudProgressRow::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LocalityDb : RoomDatabase() {
    abstract fun metaCache(): MetaCacheDao
    abstract fun downloads(): DownloadsDao
    abstract fun pendingProgress(): PendingProgressDao
    abstract fun cloudProgress(): CloudProgressDao

    companion object {
        /**
         * v1 → v2 adds cloud_progress. A real migration, NOT the destructive
         * fallback: that would drop download_bundles/download_files and orphan
         * every already-downloaded file on disk.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_progress (
                        fingerprint TEXT NOT NULL PRIMARY KEY,
                        filename TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        positionSeconds REAL NOT NULL,
                        durationSeconds REAL NOT NULL,
                        watched INTEGER NOT NULL,
                        lastUpdated TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): LocalityDb =
            Room.databaseBuilder(context, LocalityDb::class.java, "locality.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
