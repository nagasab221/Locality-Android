package com.locality.mobile.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.locality.mobile.LocalityApp
import com.locality.mobile.data.AppJson
import com.locality.mobile.data.db.DownloadFileRow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * Foreground service scoped to the transfer itself: starts when a bundle is
 * queued, walks the pending-file queue one at a time, and stops itself the
 * moment nothing is left (spec: no long-lived background service).
 *
 * Resume: the server supports HTTP Range, so an interrupted file continues
 * from `downloadedBytes` instead of restarting.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Preparing download…", 0, 0)
        if (!running) {
            running = true
            scope.launch { drainQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun drainQueue() {
        val app = application as LocalityApp
        val dao = app.container.db.downloads()
        clearWaiting()
        try {
            while (true) {
                if (!canDownloadNow()) {
                    // Wi-Fi-only and we're on cellular: stop and let the app's
                    // network monitor restart us when an unmetered network returns.
                    notifyWaitingForWifi()
                    break
                }
                val next = dao.nextIncomplete() ?: break
                downloadOne(next)
            }
        } finally {
            stopSelf()
        }
    }

    /** Wi-Fi-only setting gate, evaluated at each file boundary. */
    private suspend fun canDownloadNow(): Boolean {
        val app = application as LocalityApp
        if (!app.container.settings.wifiOnly.first()) return true
        return isUnmetered()
    }

    private fun isUnmetered(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun notifyWaitingForWifi() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads paused")
            .setContentText("Waiting for Wi-Fi")
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(PAUSED_ID, n)
    }

    private fun clearWaiting() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSED_ID)
    }

    private suspend fun downloadOne(row: DownloadFileRow) {
        val app = application as LocalityApp
        val dao = app.container.db.downloads()
        val client = app.container.client
        val base = client.config?.baseUrl?.trimEnd('/')
        if (base == null) {
            dao.updateFile(row.copy(status = DownloadFileRow.STATUS_FAILED, error = "No server configured"))
            return
        }
        val dest = File(row.localPath)
        dest.parentFile?.mkdirs()
        var done = if (dest.exists()) dest.length() else 0L
        if (done >= row.size && row.size > 0) {
            dao.updateFile(row.copy(status = DownloadFileRow.STATUS_DONE, downloadedBytes = row.size))
            return
        }
        dao.updateFile(row.copy(status = DownloadFileRow.STATUS_DOWNLOADING, downloadedBytes = done))

        try {
            val req = Request.Builder()
                .url(base + row.streamPath)
                .apply { if (done > 0) header("Range", "bytes=$done-") }
                .build()
            client.okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                if (resp.code != 206 && done > 0) {
                    // Server ignored the Range request — start over.
                    done = 0
                    dest.delete()
                }
                val body = resp.body ?: throw Exception("Empty response")
                RandomAccessFile(dest, "rw").use { out ->
                    out.seek(done)
                    val buf = ByteArray(256 * 1024)
                    var lastTick = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 700) {
                                lastTick = now
                                // Row deleted mid-transfer means the user removed the bundle.
                                val live = dao.file(row.fileId)
                                if (live == null || live.bundleId != row.bundleId) {
                                    dest.delete()
                                    return
                                }
                                dao.updateFile(live.copy(downloadedBytes = done, status = DownloadFileRow.STATUS_DOWNLOADING))
                                startInForeground(row.filename, done, row.size)
                            }
                        }
                    }
                }
            }
            dao.file(row.fileId)?.let {
                dao.updateFile(it.copy(status = DownloadFileRow.STATUS_DONE, downloadedBytes = done))
            }
            refreshSubtitles(row)
        } catch (e: Exception) {
            dao.file(row.fileId)?.let {
                dao.updateFile(it.copy(status = DownloadFileRow.STATUS_FAILED, downloadedBytes = done, error = e.message))
            }
        }
    }

    /**
     * After the video lands, re-query the server's subtitle list and bundle the
     * VTTs. Embedded tracks are extracted server-side between the manifest
     * snapshot and now, so this catches subs the manifest didn't know about.
     */
    private suspend fun refreshSubtitles(row: DownloadFileRow) {
        val app = application as LocalityApp
        val api = app.container.client.api() ?: return
        val dao = app.container.db.downloads()
        val base = app.container.client.config?.baseUrl?.trimEnd('/') ?: return
        try {
            val list = api.fileSubtitles(row.fileId).local
            if (list.isEmpty()) return
            val dir = File(row.localPath).parentFile ?: return
            val subs = ArrayList<LocalSubtitle>()
            for (s in list) {
                val out = File(dir, "sub_${row.fileId}_${s.id}.vtt")
                if (!out.exists() || out.length() == 0L) {
                    try {
                        app.container.client.okHttp
                            .newCall(Request.Builder().url("$base/api/subtitles/${s.id}/vtt").build())
                            .execute()
                            .use { resp ->
                                if (resp.isSuccessful) {
                                    out.outputStream().use { o -> resp.body?.byteStream()?.copyTo(o) }
                                }
                            }
                    } catch (_: Exception) {
                        /* one bad subtitle shouldn't fail the bundle */
                    }
                }
                if (out.exists() && out.length() > 0) {
                    subs.add(LocalSubtitle(s.id, s.lang, s.label.ifEmpty { s.lang ?: "Subtitles" }, out.absolutePath))
                }
            }
            if (subs.isNotEmpty()) {
                dao.setSubtitles(row.fileId, AppJson.encodeToString(ListSerializer(LocalSubtitle.serializer()), subs))
            }
        } catch (_: Exception) {
            /* offline or older server — the manifest-time subs remain */
        }
    }

    private fun startInForeground(text: String, done: Long, total: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Locality download")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (total > 0) setProgress(1000, ((done * 1000) / total).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 41
        private const val PAUSED_ID = 42
    }
}
