package com.locality.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.app.Activity
import android.os.Bundle
import com.locality.mobile.data.ApiClient
import com.locality.mobile.data.CloudAuth
import com.locality.mobile.data.CloudProgressSync
import com.locality.mobile.data.Repo
import com.locality.mobile.data.ServerConfigStore
import com.locality.mobile.data.ServerLocator
import com.locality.mobile.data.SettingsStore
import com.locality.mobile.data.db.LocalityDb
import com.locality.mobile.downloads.DownloadCoordinator
import com.locality.mobile.downloads.SmartDownloadManager
import com.locality.mobile.party.PartyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI — one container for the whole app; no framework needed at this size. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val configStore = ServerConfigStore(app)
    val settings = SettingsStore(app)
    val db = LocalityDb.build(app)
    val client = ApiClient(scope, configStore.config)
    val repo = Repo(client, db)
    val cloud = CloudAuth(app)
    val locator = ServerLocator(configStore, client, cloud)
    val downloads = DownloadCoordinator(app, client, db, scope)
    val smart = SmartDownloadManager(app, client, db, downloads, settings, scope)
    val cloudProgress = CloudProgressSync(cloud, db)
    val party = PartyService(cloud, client, db, scope)

    init {
        // Lets a failed connection re-resolve the PC's address instead of just
        // showing "unreachable" when we're away from the home network.
        repo.relocate = { locator.relocate() }
    }
}

class LocalityApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Reconnect automatically on launch; also un-sticks download rows left
        // "downloading" by a process kill so they resume instead of stalling.
        container.scope.launch {
            container.db.downloads().resetStuck()
            container.repo.checkConnection()
            container.smart.trigger() // top up auto-downloads on launch
            // Signed in? Then progress and party work with or without a server.
            if (container.cloud.load() != null) {
                container.cloudProgress.sync()
                container.party.refreshNow()
            }
        }
        container.party.start()
        registerDownloadResume()
        registerForegroundTracking()
    }

    /**
     * Party polling is only worth its battery while something is on screen —
     * except during a live party, which keeps syncing because the player is
     * what's on screen. Counting started activities covers both the browser and
     * the player without either having to know about it.
     */
    private fun registerForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                container.party.appActive = true
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) container.party.appActive = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Resume paused downloads the moment an unmetered (Wi-Fi) network appears —
     * this is what makes "download over Wi-Fi only" continue on its own instead
     * of waiting for the user to reopen the app. The service re-checks the
     * setting, so firing on any capability change is harmless.
     */
    private fun registerDownloadResume() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!unmetered) return
                container.scope.launch {
                    if (container.db.downloads().nextIncomplete() != null) container.downloads.startService()
                }
                container.smart.trigger() // Wi-Fi is back: refresh the auto-download set
            }
        })
    }

    /** Coil goes through the shared OkHttp client (token header) with its own
     *  disk cache, so posters are fetched from the LAN server once and reused. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.client.okHttp)
        .respectCacheHeaders(false)
        .diskCache(
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        )
        .build()
}
