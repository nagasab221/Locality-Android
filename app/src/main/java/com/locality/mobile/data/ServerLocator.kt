package com.locality.mobile.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Re-finds the PC when its saved address stops working.
 *
 * Without this, remote access would only ever help someone who re-paired by
 * hand: the phone stores whatever address worked at pairing time (usually a LAN
 * IP), so walking out of the house leaves it retrying 192.168.x.y forever while
 * a perfectly good tunnel sits unused. On a failed connection we ask the account
 * where the PC currently is and re-point at the first address that answers.
 *
 * It also repairs a rotated access token: turning on remote access upgrades the
 * PC's token, which makes the phone start getting 401s until it picks the new
 * one out of the published row.
 */
class ServerLocator(
    private val store: ServerConfigStore,
    private val client: ApiClient,
    private val cloud: CloudAuth,
) {
    private val mutex = Mutex()
    private var lastAttempt = 0L

    /** True when the client now points somewhere new and the caller should retry. */
    suspend fun relocate(): Boolean = mutex.withLock {
        if (!cloud.configured || !cloud.isSignedIn()) return false
        // Every failed request would otherwise trigger a full cloud round trip.
        val now = System.currentTimeMillis()
        if (now - lastAttempt < COOLDOWN_MS) return false
        lastAttempt = now

        val servers = cloud.servers().getOrNull().orEmpty()
        val current = store.config.first()
        for (s in servers) {
            val base = cloud.reachableHost(s) ?: continue
            if (base == current?.host && s.token == current.token) continue // already here, and it just failed
            store.save(base, s.port, s.token)
            // ApiClient rebuilds Retrofit from a Flow, so the new base URL isn't
            // live the instant save() returns. Wait for it, or the retry would
            // go straight back to the address that just failed.
            repeat(40) {
                if (client.config?.host == base) return true
                delay(25)
            }
            return true
        }
        return false
    }

    private companion object {
        const val COOLDOWN_MS = 30_000L
    }
}
